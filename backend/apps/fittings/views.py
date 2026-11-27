"""Authenticated asynchronous fitting API; photos and results stay in memory."""
import base64
import binascii
import io
import json
import threading

from django.conf import settings
from django.http import HttpResponse, JsonResponse
from django.views.decorators.http import require_GET, require_http_methods, require_POST
from PIL import Image, UnidentifiedImageError
from apps.users.auth import require_auth
from apps.usage.models import RatingTask
from apps.usage.ratings import should_prompt

from .generation import MODEL, RESOLUTION, NanoBanana
from . import person
from .jobs import Jobs, QueueFull

MAX_IMAGE_BYTES = 4 * 1024 * 1024
MAX_REQUEST_BYTES = 24 * 1024 * 1024
_jobs = None
_jobs_lock = threading.Lock()


def get_jobs():
    global _jobs
    with _jobs_lock:
        if _jobs is None:
            _jobs = Jobs(NanoBanana(settings.OPENROUTER_API_KEY), concurrency=settings.FITTING_CONCURRENCY)
        return _jobs


def response(data, status=200):
    result = JsonResponse(data, status=status)
    result["Cache-Control"] = "no-store"
    return result


def decode_image(value):
    if not isinstance(value, str) or len(value) > MAX_IMAGE_BYTES * 4 // 3 + 8:
        raise ValueError("Invalid image size")
    raw = base64.b64decode(value, validate=True)
    if not raw or len(raw) > MAX_IMAGE_BYTES:
        raise ValueError("Invalid image size")
    with Image.open(io.BytesIO(raw)) as image:
        if image.format not in ("JPEG", "PNG", "WEBP") or image.width * image.height > 16_000_000:
            raise ValueError("Unsupported image")
        image.verify()
    return raw


@require_POST
@require_auth
def submit(request):
    if not settings.OPENROUTER_API_KEY:
        return response({"error": "FITTING_NOT_CONFIGURED"}, 503)
    if request.content_type != "application/json":
        return response({"error": "JSON_REQUIRED"}, 415)
    raw = request.read(MAX_REQUEST_BYTES + 1)
    if len(raw) > MAX_REQUEST_BYTES:
        return response({"error": "BODY_TOO_LARGE"}, 413)
    try:
        data = json.loads(raw)
        if not isinstance(data, dict) or data.get("consent") is not True:
            return response({"error": "PHOTO_CONSENT_REQUIRED"}, 400)
        if set(data) != {"consent", "person", "garments"}:
            raise ValueError("Unsupported fields")
        garments = data["garments"]
        if not isinstance(garments, list) or not 1 <= len(garments) <= 3:
            raise ValueError("Invalid garments")
        categories = [g["category"] for g in garments]
        if categories not in (["tops"], ["bottoms"], ["tops", "bottoms"], ["tops", "bottoms", "outerwear"]):
            raise ValueError("Unsupported outfit")
        clothes = []
        for garment in garments:
            if not isinstance(garment, dict) or set(garment) - {"category", "image", "photo_type", "open_front"}:
                raise ValueError("Invalid garment")
            kind, open_front = garment.get("photo_type", "flat-lay"), garment.get("open_front", True)
            if kind not in ("flat-lay", "model") or not isinstance(open_front, bool):
                raise ValueError("Invalid garment metadata")
            clothes.append((garment["category"], decode_image(garment["image"]), kind, open_front))
        photo = decode_image(data["person"])
        # A photo without a person would come back with a stranger the model made up, and bottoms on a photo
        # cut above the legs with legs it made up.
        refused = person.check(photo, needs_lower_body="bottoms" in categories)
        if refused:
            return response({"error": refused}, 422)
        job = get_jobs().submit(photo, clothes, owner=str(request.user.pk))
    except QueueFull:
        result = response({"error": "FITTING_BUSY"}, 429)
        result["Retry-After"] = "5"
        return result
    except (ValueError, TypeError, KeyError, RecursionError, binascii.Error, OSError, UnidentifiedImageError,
            Image.DecompressionBombError):
        return response({"error": "INVALID_FITTING_REQUEST"}, 400)
    return response({"id": job.id, "token": job.token, "status": job.state}, 202)


def authorized(request, job_id):
    return get_jobs().get(job_id, request.headers.get("X-Fitting-Token"), str(request.user.pk))


@require_http_methods(["GET", "DELETE"])
@require_auth
def job(request, job_id):
    entry = authorized(request, job_id)
    if entry is None:
        return response({"error": "NOT_FOUND"}, 404)
    if request.method == "DELETE":
        get_jobs().delete(entry)
        result = HttpResponse(status=204)
        result["Cache-Control"] = "no-store"
        return result
    status = get_jobs().status(entry)
    # 완료된 job에만 별점 팝업 여부를 붙인다. 앱은 결과 이미지를 처음 보여 줄 때 이 값을 쓴다.
    if status["status"] == "completed":
        status["rating_prompt"] = should_prompt(request.user, RatingTask.VIRTUAL_FITTING)
    return response(status)


@require_GET
@require_auth
def image(request, job_id):
    entry = authorized(request, job_id)
    if entry is None:
        return response({"error": "NOT_FOUND"}, 404)
    if entry.state != "completed" or not entry.output:
        return response({"error": "RESULT_NOT_READY"}, 409)
    result = HttpResponse(entry.output, content_type="image/jpeg")
    result["Cache-Control"] = "no-store"
    return result


@require_GET
@require_auth
def health(request):
    configured = bool(settings.OPENROUTER_API_KEY)
    return response({"status": "ready" if configured else "not_configured", "model": MODEL,
        "provider": "openrouter", "resolution": RESOLUTION, "concurrency": settings.FITTING_CONCURRENCY,
        "capacity": 20, "input_storage": "memory", "result_ttl_seconds": 900}, 200 if configured else 503)

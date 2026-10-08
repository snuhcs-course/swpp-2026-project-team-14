# AI-generated with Codex, 2026-09-29, reviewed by Hyeon U Jeong
import json
import uuid
from functools import wraps
from threading import BoundedSemaphore

from django.conf import settings
from django.core.files.base import ContentFile
from django.core.exceptions import RequestDataTooBig
from django.http import FileResponse, JsonResponse
from django.shortcuts import get_object_or_404
from django.views.decorators.http import require_GET, require_http_methods, require_POST

from .analysis import AnalysisError, MAX_IMAGE_BYTES, MODEL, analyze_image, prepare_image
from .editor import catalog, validate_record
from .models import Garment
from .landmarks import MAX_FRAME_BYTES, detect

# Bound concurrent paid requests and image decode memory.
ANALYSIS_SLOTS = BoundedSemaphore(1)


def require_database(view):
    @wraps(view)
    def wrapped(request, *args, **kwargs):
        engine = settings.DATABASES.get('default', {}).get('ENGINE')
        if not engine or engine == 'django.db.backends.dummy':
            return JsonResponse({'error': 'DATABASE_NOT_CONFIGURED'}, status=503)
        return view(request, *args, **kwargs)
    return wrapped


@require_POST
def landmarks(request):
    if request.content_type not in ('image/jpeg', 'image/png', 'image/webp'):
        return JsonResponse({'error': 'IMAGE_REQUIRED'}, status=415)
    if not ANALYSIS_SLOTS.acquire(blocking=False):
        return JsonResponse({'error': 'AI_BUSY'}, status=429)
    try:
        result = detect(request.read(MAX_FRAME_BYTES + 1), request.GET.get('garment'))
        response = JsonResponse(result)
        response['Cache-Control'] = 'no-store'
        return response
    except AnalysisError as error:
        return JsonResponse({'error': error.code}, status=error.status)
    finally:
        ANALYSIS_SLOTS.release()


@require_POST
@require_database
def analyze(request):
    if request.content_type not in ('image/jpeg', 'image/png', 'image/webp'):
        return JsonResponse({'error': 'IMAGE_REQUIRED'}, status=415)
    if not ANALYSIS_SLOTS.acquire(blocking=False):
        return JsonResponse({'error': 'AI_BUSY'}, status=429)
    try:
        # Bound image reads independently of the JSON edit limit.
        image = request.read(MAX_IMAGE_BYTES + 1)
        attributes = analyze_image(image, settings.GEMINI_API_KEY)
        garment = Garment(attributes=attributes)
        garment.image.save(f'{uuid.uuid4()}.jpg', ContentFile(prepare_image(image)), save=False)
        try:
            garment.save()
        except Exception:
            garment.image.delete(save=False)
            raise
        response = JsonResponse({'id': str(garment.id), 'model': MODEL, 'attributes': attributes})
        response['Cache-Control'] = 'no-store'
        return response
    except AnalysisError as error:
        return JsonResponse({'error': error.code}, status=error.status)
    finally:
        ANALYSIS_SLOTS.release()


def serialize(garment):
    return {'id': str(garment.id), 'attributes': garment.attributes, 'dimensions': garment.dimensions,
            'notes': garment.notes,
            'image_url': f'/api/wardrobe/items/{garment.id}/image/', 'saved': garment.saved}


@require_GET
def editor_options(request):
    return JsonResponse(catalog())


@require_GET
@require_database
def items(request):
    response = JsonResponse({'items': [serialize(item) for item in Garment.objects.filter(saved=True).order_by('-created_at')]})
    response['Cache-Control'] = 'no-store'
    return response


@require_http_methods(['GET', 'PUT'])
@require_database
def item(request, item_id):
    garment = get_object_or_404(Garment, pk=item_id)
    if request.method == 'PUT':
        if request.content_type != 'application/json':
            return JsonResponse({'error': 'JSON_REQUIRED'}, status=415)
        try:
            raw = request.read(16 * 1024 + 1)
            if len(raw) > 16 * 1024:
                raise RequestDataTooBig()
            values = validate_record(json.loads(raw))
        except RequestDataTooBig:
            return JsonResponse({'error': 'BODY_TOO_LARGE'}, status=413)
        except (ValueError, TypeError, UnicodeDecodeError, OverflowError):
            return JsonResponse({'error': 'INVALID_GARMENT'}, status=400)
        for key, value in values.items():
            setattr(garment, key, value)
        # The analysis ID is reused on retry: saving never creates a second garment.
        garment.saved = True
        garment.save(update_fields=[*values, 'saved', 'updated_at'])
    response = JsonResponse(serialize(garment))
    response['Cache-Control'] = 'no-store'
    return response


@require_GET
@require_database
def item_image(request, item_id):
    garment = get_object_or_404(Garment, pk=item_id, saved=True)
    try:
        response = FileResponse(garment.image.open('rb'), content_type='image/jpeg')
    except FileNotFoundError:
        return JsonResponse({'error': 'IMAGE_NOT_FOUND'}, status=404)
    response['Cache-Control'] = 'no-store'
    return response

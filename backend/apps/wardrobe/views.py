# AI-generated with Codex, 2026-09-29, reviewed by Hyeon U Jeong; recommendation labels added with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
import json
import uuid
from threading import BoundedSemaphore

from django.conf import settings
from django.core.files.base import ContentFile
from django.core.exceptions import RequestDataTooBig
from django.http import FileResponse, JsonResponse
from django.shortcuts import get_object_or_404
from django.views.decorators.http import require_GET, require_http_methods, require_POST

from apps.body_profiles.profile import body_from_request
from apps.users.auth import require_auth
from recommendation.labeling import label

from .analysis import AnalysisError, MAX_IMAGE_BYTES, MODEL, analyze_image, prepare_image
from .editor import catalog, validate_record
from .schema import validate_ai_details, validate_attributes
from .models import Garment
from .landmarks import GARMENTS, MAX_FRAME_BYTES, detect

# Bound concurrent paid requests and image decode memory.
ANALYSIS_SLOTS = BoundedSemaphore(1)


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
@require_auth
def analyze(request):
    if request.content_type not in ('image/jpeg', 'image/png', 'image/webp'):
        return JsonResponse({'error': 'IMAGE_REQUIRED'}, status=415)
    if not ANALYSIS_SLOTS.acquire(blocking=False):
        return JsonResponse({'error': 'AI_BUSY'}, status=429)
    try:
        # Bound image reads independently of the JSON edit limit.
        image = request.read(MAX_IMAGE_BYTES + 1)
        garment_type = request.GET.get('garment')
        attributes, details = analyze_image(image, settings.GEMINI_API_KEY,
                                            garment_type if garment_type in GARMENTS else None)
        garment = Garment(user=request.user, attributes=attributes)
        garment.image.save(f'{uuid.uuid4()}.jpg', ContentFile(prepare_image(image)), save=False)
        try:
            garment.save()
        except Exception:
            garment.image.delete(save=False)
            raise
        response = JsonResponse({'id': str(garment.id), 'model': MODEL, 'attributes': attributes, 'details': details})
        response['Cache-Control'] = 'no-store'
        return response
    except AnalysisError as error:
        return JsonResponse({'error': error.code}, status=error.status)
    finally:
        ANALYSIS_SLOTS.release()


def serialize(garment):
    return {'id': str(garment.id), 'attributes': garment.attributes, 'dimensions': garment.dimensions,
            'notes': garment.notes, 'labels': garment.labels,
            'image_url': f'/api/wardrobe/items/{garment.id}/image/', 'saved': garment.saved}


@require_GET
def editor_options(request):
    return JsonResponse(catalog())


@require_POST
def labels(request):
    """Suggested recommendation labels for the garment being edited; nothing is saved. Without a body
    profile only the body-independent labels are filled (the user picks the fit)."""
    if request.content_type != 'application/json':
        return JsonResponse({'error': 'JSON_REQUIRED'}, status=415)
    try:
        raw = request.read(16 * 1024 + 1)
        if len(raw) > 16 * 1024:
            raise RequestDataTooBig()
        data = json.loads(raw)
        if not isinstance(data, dict):
            raise ValueError('Invalid request')
        attributes = validate_attributes(data.get('attributes'))
        dimensions = validate_record({'attributes': attributes, 'dimensions': data.get('dimensions'),
                                      'notes': ''})['dimensions']
        body = body_from_request(request, data) if data.get('body') is not None else None
        garment_type = data.get('garment_type')
        if garment_type is not None and garment_type not in GARMENTS:
            raise ValueError('Invalid garment type')
    except RequestDataTooBig:
        return JsonResponse({'error': 'BODY_TOO_LARGE'}, status=413)
    except (ValueError, TypeError, UnicodeDecodeError, OverflowError):
        return JsonResponse({'error': 'INVALID_LABEL_REQUEST'}, status=400)
    details = validate_ai_details(data.get('details'), attributes['category'])
    response = JsonResponse({'labels': label(attributes, dimensions, body, details, garment_type)},
                            json_dumps_params={'ensure_ascii': False})
    response['Cache-Control'] = 'no-store'
    return response


@require_GET
@require_auth
def items(request):
    response = JsonResponse({'items': [serialize(item) for item in Garment.objects.filter(user=request.user, saved=True).order_by('-created_at')]})
    response['Cache-Control'] = 'no-store'
    return response


@require_http_methods(['GET', 'PUT'])
@require_auth
def item(request, item_id):
    garment = get_object_or_404(Garment, pk=item_id, user=request.user)
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
@require_auth
def item_image(request, item_id):
    garment = get_object_or_404(Garment, pk=item_id, saved=True, user=request.user)
    try:
        response = FileResponse(garment.image.open('rb'), content_type='image/jpeg')
    except FileNotFoundError:
        return JsonResponse({'error': 'IMAGE_NOT_FOUND'}, status=404)
    response['Cache-Control'] = 'no-store'
    return response

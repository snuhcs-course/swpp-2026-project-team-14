# AI-generated with Codex, 2026-09-29, reviewed by Hyeon U Jeong; recommendation labels added with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
import json
import uuid
from threading import BoundedSemaphore

from django.conf import settings
from django.core.files.base import ContentFile
from django.core.exceptions import RequestDataTooBig
from django.db import transaction
from django.db.models import Q
from django.http import FileResponse, JsonResponse
from django.shortcuts import get_object_or_404
from django.views.decorators.http import require_GET, require_http_methods, require_POST

from apps.body_profiles.profile import body_from_request
from apps.users.auth import require_auth
from apps.users.models import User
from apps.users.pagination import account_page
from recommendation.labeling import label

from .analysis import AnalysisError, MAX_IMAGE_BYTES, MODEL, analyze_image, prepare_image
from .editor import catalog, validate_record
from .schema import validate_ai_details, validate_attributes
from .models import Garment, CategoryInUse, WornGeneration
from .worn_estimates import suggestions, estimate_matches
from .landmarks import GARMENTS, MAX_FRAME_BYTES, detect

# Bound concurrent paid requests and image decode memory.
ANALYSIS_SLOTS = BoundedSemaphore(1)


@require_POST
@require_auth
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
    return _analyze(request)


@require_POST
@require_auth
def analyze_worn(request):
    target = request.GET.get('category')
    if target not in ('top', 'bottom', 'outerwear'):
        return JsonResponse({'error': 'INVALID_TARGET'}, status=400)
    return _analyze(request, target)


def _analyze(request, target=None):
    request_key = request.GET.get('request_id') if target else None
    if request_key:
        try: request_key = uuid.UUID(request_key)
        except (ValueError, TypeError): return JsonResponse({'error': 'INVALID_REQUEST_ID'}, status=400)
        with transaction.atomic():
            User.objects.select_for_update().get(pk=request.user.pk)
            existing = Garment.objects.filter(user=request.user, request_key=request_key).first()
            if existing:
                generation = WornGeneration.objects.get(garment=existing)
                result = JsonResponse({**serialize(existing), 'details': generation.details, 'model': MODEL})
                result['Cache-Control'] = 'no-store'
                return result
            return _analyze_once(request, target, request_key)
    return _analyze_once(request, target)


def _analyze_once(request, target=None, request_key=None):
    if request.content_type not in ('image/jpeg', 'image/png', 'image/webp'):
        return JsonResponse({'error': 'IMAGE_REQUIRED'}, status=415)
    if not ANALYSIS_SLOTS.acquire(blocking=False):
        return JsonResponse({'error': 'AI_BUSY'}, status=429)
    try:
        # Bound image reads independently of the JSON edit limit.
        image = request.read(MAX_IMAGE_BYTES + 1)
        garment_type = request.GET.get('garment')
        labels_value = estimates = None
        if target:
            attributes, details, *wearing = analyze_image(image, settings.GEMINI_API_KEY,
                                                        target_category=target, include_wearing=True)
            labels_value, estimates = suggestions(attributes, details, wearing[0] if wearing else None,
                                                  request.user.body_profile)
        else:
            attributes, details = analyze_image(image, settings.GEMINI_API_KEY,
                                                garment_type if garment_type in GARMENTS else None)
        garment = Garment(user=request.user, attributes=attributes,
                          labels=labels_value, estimated_dimensions=estimates,
                          image_source='worn_crop' if target else 'garment_photo', request_key=request_key)
        garment.image.save(f'{uuid.uuid4()}.jpg', ContentFile(prepare_image(image)), save=False)
        try:
            with transaction.atomic():
                garment.save()
                if target:
                    WornGeneration.objects.create(garment=garment, original=garment.image.name, details=details)
        except Exception:
            garment.image.delete(save=False)
            raise
        response = JsonResponse({'id': str(garment.id), 'model': MODEL, 'attributes': attributes, 'details': details,
                                 **(serialize(garment) if target else {})})
        response['Cache-Control'] = 'no-store'
        return response
    except AnalysisError as error:
        return JsonResponse({'error': error.code}, status=error.status)
    finally:
        ANALYSIS_SLOTS.release()


def serialize(garment):
    return {'id': str(garment.id), 'attributes': garment.attributes, 'dimensions': garment.dimensions,
            'estimated_dimensions': garment.estimated_dimensions,
            'image_source': garment.image_source,
            'notes': garment.notes, 'labels': garment.labels,
            'image_url': f'/api/wardrobe/items/{garment.id}/image/', 'saved': garment.saved}


@require_GET
@require_auth
def editor_options(request):
    return JsonResponse(catalog())


@require_POST
@require_auth
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
    response = account_page(request, Garment.objects.filter(user=request.user, saved=True), serialize, 'items')
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
        with transaction.atomic():
            User.objects.select_for_update().get(pk=request.user.pk)
            garment = get_object_or_404(Garment, pk=item_id, user=request.user)
            generation = WornGeneration.objects.filter(garment=garment).first()
            if generation and generation.decision not in ('generated', 'crop'):
                return JsonResponse({'error': 'IMAGE_REVIEW_REQUIRED'}, status=409)
            if garment.image_source in ('worn_crop', 'ai_flatlay'):
                labels = values.get('labels')
                if values['dimensions'] is not None or not labels or not labels.get('confirmed'):
                    return JsonResponse({'error': 'WORN_REVIEW_REQUIRED'}, status=400)
            try:
                garment.check_category_change(values['attributes'])
            except CategoryInUse:
                return JsonResponse({'error': 'GARMENT_CATEGORY_IN_USE'}, status=409)
            for key, value in values.items():
                setattr(garment, key, value)
            if not estimate_matches(garment.estimated_dimensions, garment.attributes, garment.labels):
                garment.estimated_dimensions = None
            # The analysis ID is reused on retry: saving never creates a second garment.
            garment.saved = True
            garment.save(update_fields=[*values, 'estimated_dimensions', 'saved', 'updated_at'])
    response = JsonResponse(serialize(garment))
    response['Cache-Control'] = 'no-store'
    return response


@require_GET
@require_auth
def item_image(request, item_id):
    garment = get_object_or_404(Garment.objects.filter(Q(saved=True) | Q(image_source__in=('worn_crop', 'ai_flatlay'))),
                                pk=item_id, user=request.user)
    try:
        response = FileResponse(garment.image.open('rb'), content_type='image/jpeg')
    except FileNotFoundError:
        return JsonResponse({'error': 'IMAGE_NOT_FOUND'}, status=404)
    response['Cache-Control'] = 'no-store'
    return response

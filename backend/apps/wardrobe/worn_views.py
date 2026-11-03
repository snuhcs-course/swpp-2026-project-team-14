"""Owner-scoped generation, explicit image review and idempotent paid requests."""
from datetime import timedelta
import json
import uuid

from django.conf import settings
from django.core.files.base import ContentFile
from django.db import transaction
from django.http import FileResponse, JsonResponse
from django.shortcuts import get_object_or_404
from django.utils import timezone
from django.views.decorators.http import require_GET, require_http_methods, require_POST

from apps.users.auth import require_auth
from apps.users.models import User
from .analysis import AnalysisError
from .models import WornGeneration
from . import worn


def state(row):
    base = f'/api/wardrobe/items/{row.garment_id}/'
    return {'status': row.status, 'decision': row.decision, 'error': row.error or None,
            'request_id': str(row.request_id) if row.request_id else None,
            'original_url': base + 'worn-image/original/',
            'segmented_url': base + 'worn-image/segmented/' if row.segmented else None,
            'generated_url': base + 'worn-image/candidate/' if row.status == 'ready' and row.candidate else None,
            'model': row.model, 'cost_usd': str(row.cost_usd) if row.cost_usd is not None else None,
            'occlusion_warning': row.occlusion_warning}


def response(row):
    result = JsonResponse(state(row))
    result['Cache-Control'] = 'no-store'
    return result


def owned(request, item_id, lock=False):
    rows = WornGeneration.objects.select_related('garment').filter(garment__user=request.user)
    if lock: rows = rows.select_for_update()
    return get_object_or_404(rows, garment_id=item_id)


def body(request):
    if request.content_type != 'application/json': raise ValueError()
    raw = request.read(4097)
    if len(raw) > 4096: raise ValueError()
    data = json.loads(raw)
    if not isinstance(data, dict): raise ValueError()
    return data


@require_http_methods(['GET', 'POST'])
@require_auth
def generation(request, item_id):
    if request.method == 'GET':
        row = owned(request, item_id)
        # A killed web process cannot leave an unrecoverable 'running' row forever.
        if row.status == 'running' and row.started_at and row.started_at < timezone.now() - timedelta(minutes=3):
            WornGeneration.objects.filter(pk=row.pk, status='running', request_id=row.request_id).update(
                status='failed', error='GENERATION_INTERRUPTED')
            row.refresh_from_db()
        return response(row)
    try:
        data = body(request)
        if set(data) != {'request_id'}: raise ValueError()
        request_id = uuid.UUID(data['request_id'])
    except (ValueError, TypeError, UnicodeError):
        return JsonResponse({'error': 'INVALID_GENERATION_REQUEST'}, status=400)
    with transaction.atomic():
        row = owned(request, item_id, lock=True)
        if row.garment.saved or row.decision in ('generated', 'crop'):
            return JsonResponse({'error': 'GENERATION_ALREADY_REVIEWED'}, status=409)
        if row.request_id == request_id or row.status == 'running':
            return response(row)  # Repeated POST never starts another paid request.
        row.status, row.error, row.decision = 'running', '', 'pending'
        row.request_id, row.started_at = request_id, timezone.now()
        row.model, row.cost_usd = settings.OPENROUTER_IMAGE_MODEL, None
        row.save()
    # Keep expensive work outside DB locks. The running row is the per-draft lock.
    new_files = []
    old_files = [row.segmented.name, row.candidate.name]
    try:
        with row.original.open('rb') as image: raw = image.read()
        segmented, mask, warning = worn.segment(raw, row.garment.attributes)
        generated, cost = worn.generate(raw, segmented, mask, row.garment.attributes)
        for field, suffix, content in [(row.segmented, 'segment.png', segmented), (row.candidate, 'generated.jpg', generated)]:
            field.save(f'{row.pk}-{request_id}-{suffix}', ContentFile(content), save=False)
            new_files.append(field.name)
        row.status, row.occlusion_warning, row.cost_usd = 'ready', warning, cost
        row.save()
        for name in old_files:
            if name and name not in new_files: row.candidate.storage.delete(name)  # superseded, unapproved outputs only
    except AnalysisError as error:
        row.status, row.error = 'failed', error.code
        row.save(update_fields=['status', 'error'])
    except Exception:
        for name in new_files: row.candidate.storage.delete(name)
        row.status, row.error = 'failed', 'GENERATION_STORAGE_FAILED'
        row.save(update_fields=['status', 'error'])
    return response(row)


@require_POST
@require_auth
def review(request, item_id):
    try:
        data = body(request)
        if set(data) != {'choice'} or data['choice'] not in ('generated', 'crop', 'reject'): raise ValueError()
    except (ValueError, TypeError, UnicodeError):
        return JsonResponse({'error': 'INVALID_REVIEW'}, status=400)
    with transaction.atomic():
        User.objects.select_for_update().get(pk=request.user.pk)
        row = owned(request, item_id, lock=True)
        if row.garment.saved or row.status == 'running':
            return JsonResponse({'error': 'REVIEW_NOT_AVAILABLE'}, status=409)
        choice = data['choice']
        if choice == 'generated' and (row.status != 'ready' or not row.candidate):
            return JsonResponse({'error': 'GENERATION_NOT_READY'}, status=409)
        row.decision = choice if choice != 'reject' else 'rejected'
        row.save(update_fields=['decision'])
        if choice != 'reject':
            row.garment.image.name = row.candidate.name if choice == 'generated' else row.original.name
            row.garment.image_source = 'ai_flatlay' if choice == 'generated' else 'worn_crop'
            row.garment.save(update_fields=['image', 'image_source'])
    from .views import serialize
    result = JsonResponse({**serialize(row.garment), 'details': row.details, 'generation': state(row)})
    result['Cache-Control'] = 'no-store'
    return result


@require_GET
@require_auth
def image(request, item_id, kind):
    row = owned(request, item_id)
    field = {'original': row.original, 'segmented': row.segmented, 'candidate': row.candidate}.get(kind)
    if not field: return JsonResponse({'error': 'IMAGE_NOT_FOUND'}, status=404)
    try:
        result = FileResponse(field.open('rb'), content_type='image/png' if kind == 'segmented' else 'image/jpeg')
    except FileNotFoundError:
        return JsonResponse({'error': 'IMAGE_NOT_FOUND'}, status=404)
    result['Cache-Control'] = 'no-store'
    return result

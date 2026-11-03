"""Private automatic proposals, idempotent draft preparation and atomic multi-save."""
from datetime import timedelta
import json
import uuid

from django.conf import settings
from django.core.files.base import ContentFile
from django.db import transaction
from django.http import FileResponse, HttpResponse, JsonResponse
from django.shortcuts import get_object_or_404
from django.utils import timezone
from django.views.decorators.http import require_GET, require_POST

from apps.users.auth import require_auth
from apps.users.models import User
from recommendation.labeling import label
from .analysis import AnalysisError, MAX_IMAGE_BYTES, MODEL, prepare_image
from .editor import validate_record
from .models import Garment, WornGeneration, WornPhoto
from . import worn_detection
from .views import ANALYSIS_SLOTS, serialize
from .worn_views import state as generation_state
from .worn_estimates import suggestions, estimate_matches


def private_json(data, status=200):
    result = JsonResponse(data, status=status)
    result['Cache-Control'] = 'no-store'
    return result


def photo_state(row):
    base = f'/api/wardrobe/worn-photos/{row.pk}/'
    return {'id': str(row.pk), 'status': row.status, 'error': row.error or None, 'model': MODEL,
            'image_url': base + 'image/',
            'candidates': [dict(candidate, crop_url=base + f"candidates/{candidate['id']}/image/")
                           for candidate in row.candidates]}


def owned(request, photo_id, lock=False):
    rows = WornPhoto.objects.filter(user=request.user)
    if lock:
        rows = rows.select_for_update()
    return get_object_or_404(rows, pk=photo_id)


def body(request):
    if request.content_type != 'application/json':
        raise ValueError()
    raw = request.read(48 * 1024 + 1)
    if len(raw) > 48 * 1024:
        raise ValueError()
    data = json.loads(raw)
    if not isinstance(data, dict):
        raise ValueError()
    return data


@require_POST
@require_auth
def upload(request):
    if request.content_type not in ('image/jpeg', 'image/png', 'image/webp'):
        return private_json({'error': 'IMAGE_REQUIRED'}, 415)
    try:
        key = uuid.UUID(request.GET.get('request_id', ''))
    except (ValueError, TypeError):
        return private_json({'error': 'INVALID_REQUEST_ID'}, 400)
    existing = WornPhoto.objects.filter(user=request.user, request_key=key).first()
    if existing:
        return private_json(photo_state(existing))
    try:
        image = prepare_image(request.read(MAX_IMAGE_BYTES + 1))
    except AnalysisError as error:
        return private_json({'error': error.code}, error.status)
    if not ANALYSIS_SLOTS.acquire(blocking=False):
        return private_json({'error': 'AI_BUSY'}, 429)
    row = None
    try:
        with transaction.atomic():
            User.objects.select_for_update().get(pk=request.user.pk)
            existing = WornPhoto.objects.filter(user=request.user, request_key=key).first()
            if existing:
                return private_json(photo_state(existing))
            row = WornPhoto(user=request.user, request_key=key, status='detecting')
            row.image.save(f'{row.pk}.jpg', ContentFile(image), save=False)
            try:
                row.save()
            except Exception:
                row.image.delete(save=False)
                raise
        try:
            row.candidates = worn_detection.detect(image, settings.GEMINI_API_KEY)
            row.status = 'ready'
        except AnalysisError as error:
            row.status, row.error = 'failed', error.code
        except Exception:
            row.status, row.error = 'failed', 'DETECTION_FAILED'
        row.save(update_fields=['candidates', 'status', 'error'])
        return private_json(photo_state(row))
    finally:
        ANALYSIS_SLOTS.release()


@require_GET
@require_auth
def detail(request, photo_id):
    row = owned(request, photo_id)
    if row.status == 'detecting' and row.created_at < timezone.now() - timedelta(minutes=3):
        WornPhoto.objects.filter(pk=row.pk, status='detecting').update(status='failed', error='DETECTION_INTERRUPTED')
        row.refresh_from_db()
    return private_json(photo_state(row))


@require_GET
@require_auth
def image(request, photo_id, candidate_id=None):
    row = owned(request, photo_id)
    try:
        if candidate_id is None:
            result = FileResponse(row.image.open('rb'), content_type='image/jpeg')
        else:
            candidate = next((c for c in row.candidates if c['id'] == str(candidate_id)), None)
            if candidate is None:
                return private_json({'error': 'IMAGE_NOT_FOUND'}, 404)
            with row.image.open('rb') as source:
                result = HttpResponse(worn_detection.crop(source.read(), candidate['bounds']), content_type='image/jpeg')
    except FileNotFoundError:
        return private_json({'error': 'IMAGE_NOT_FOUND'}, 404)
    result['Cache-Control'] = 'no-store'
    return result


def draft_record(generation):
    return {**serialize(generation.garment), 'details': generation.details, 'generation': generation_state(generation)}


@require_POST
@require_auth
def prepare(request, photo_id):
    try:
        data = body(request)
        ids = data['candidate_ids']
        if (set(data) != {'candidate_ids'} or not isinstance(ids, list) or not 1 <= len(ids) <= 3 or
                any(not isinstance(v, str) for v in ids) or len(set(ids)) != len(ids)):
            raise ValueError()
    except (ValueError, KeyError, TypeError, UnicodeError):
        return private_json({'error': 'INVALID_CANDIDATES'}, 400)
    new_files = []
    try:
        with transaction.atomic():
            User.objects.select_for_update().get(pk=request.user.pk)
            photo = owned(request, photo_id, lock=True)
            if photo.status != 'ready':
                return private_json({'error': 'DETECTION_NOT_READY'}, 409)
            candidates = {c['id']: c for c in photo.candidates}
            if any(key not in candidates for key in ids):
                return private_json({'error': 'INVALID_CANDIDATES'}, 400)
            with photo.image.open('rb') as source:
                raw = source.read()
            records = []
            for key in ids:
                existing = photo.generations.select_related('garment').filter(candidate_id=key).first()
                if existing:
                    records.append(draft_record(existing))
                    continue
                candidate = candidates[key]
                attrs = candidate['attributes']
                labels, estimates = suggestions(attrs, candidate['details'], candidate.get('wearing'), request.user.body_profile)
                garment = Garment(user=request.user, attributes=attrs, image_source='worn_crop', labels=labels,
                                  estimated_dimensions=estimates,
                                  request_key=uuid.uuid5(photo.pk, key))
                garment.image.save(f'{garment.pk}.jpg', ContentFile(worn_detection.crop(raw, candidate['bounds'])), save=False)
                new_files.append((garment.image.storage, garment.image.name))
                garment.save()
                generation = WornGeneration.objects.create(garment=garment, photo=photo, candidate_id=key,
                                                           original=garment.image.name, details=candidate['details'])
                records.append(draft_record(generation))
        return private_json({'items': records})
    except Exception:
        for storage, name in new_files:
            storage.delete(name)
        raise


@require_POST
@require_auth
def save(request, photo_id):
    try:
        data = body(request)
        rows = data['items']
        if set(data) != {'items'} or not isinstance(rows, list) or not 1 <= len(rows) <= 3:
            raise ValueError()
        values, ids = [], set()
        for row in rows:
            if not isinstance(row, dict) or set(row) != {'id', 'image_choice', 'attributes', 'dimensions', 'notes', 'labels'}:
                raise ValueError()
            key = uuid.UUID(row['id'])
            if key in ids or row['image_choice'] not in ('generated', 'crop'):
                raise ValueError()
            ids.add(key)
            record = validate_record({k: v for k, v in row.items() if k not in ('id', 'image_choice')})
            labels = record.get('labels')
            if (record['attributes']['category'] not in ('top', 'bottom', 'outerwear') or
                    record['dimensions'] is not None or not labels or not labels.get('confirmed')):
                raise ValueError()
            values.append((key, row['image_choice'], record))
    except (ValueError, KeyError, TypeError, UnicodeError):
        return private_json({'error': 'WORN_REVIEW_REQUIRED'}, 400)
    with transaction.atomic():
        User.objects.select_for_update().get(pk=request.user.pk)
        photo = owned(request, photo_id, lock=True)
        generations = {g.garment_id: g for g in photo.generations.select_related('garment').select_for_update()}
        # Validate ALL rows before any mutation. Partial failures cannot partially save.
        for key, choice, record in values:
            generation = generations.get(key)
            if generation is None:
                return private_json({'error': 'ITEM_NOT_FOUND'}, 404)
            if generation.garment.saved:
                continue  # A lost save response can be safely retried without overwriting later edits.
            if generation.status == 'running' or (choice == 'generated' and
                    (generation.status != 'ready' or not generation.candidate)):
                return private_json({'error': 'IMAGE_REVIEW_REQUIRED'}, 409)
        items = []
        for key, choice, record in values:
            generation = generations[key]
            garment = generation.garment
            if not garment.saved:
                generation.decision = choice
                generation.save(update_fields=['decision'])
                garment.image.name = generation.candidate.name if choice == 'generated' else generation.original.name
                garment.image_source = 'ai_flatlay' if choice == 'generated' else 'worn_crop'
                for field, value in record.items():
                    setattr(garment, field, value)
                if not estimate_matches(garment.estimated_dimensions, garment.attributes, garment.labels):
                    garment.estimated_dimensions = None
                garment.saved = True
                garment.save(update_fields=[*record, 'image', 'image_source', 'estimated_dimensions', 'saved', 'updated_at'])
            items.append(serialize(garment))
    return private_json({'items': items})

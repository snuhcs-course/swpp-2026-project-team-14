# AI-generated with Codex, 2026-10-16
"""Explicit, item-by-item guest imports. Source IDs are scoped to the authenticated account."""
import hashlib
import base64
import json
import math
import uuid
from functools import wraps

from django.core.exceptions import RequestDataTooBig, ValidationError
from django.core.files.base import ContentFile
from django.core.files.storage import default_storage
from django.db import IntegrityError, transaction
from django.http.multipartparser import MultiPartParserError
from django.views.decorators.http import require_POST, require_http_methods

from apps.body_profiles.profile import body_from_request
from apps.outfits.models import Outfit, OutfitItem
from apps.wardrobe.analysis import AnalysisError, MAX_IMAGE_BYTES, prepare_image
from apps.wardrobe.editor import validate_record
from apps.wardrobe.models import Garment
from .auth import require_auth
from .models import User
from .views import _finite_float, _json, _reject_nonfinite, _unique_object

STYLE_FIELDS = ('preferred_fits', 'preferred_styles', 'favorite_colors')
DEFAULT_STYLE = {'preferred_fits': ['regular'], 'preferred_styles': [], 'favorite_colors': []}
MAX_JSON = 32 * 1024
MAX_BATCH = 100


class Changed(Exception):
    pass


def _errors(view):
    @wraps(view)
    def wrapped(*args, **kwargs):
        try:
            return view(*args, **kwargs)
        except (Changed, IntegrityError):
            return _json({'error': 'IMPORT_CHANGED'}, 409)
        except RequestDataTooBig:
            return _json({'error': 'BODY_TOO_LARGE'}, 413)
        except AnalysisError as error:
            return _json({'error': error.code}, error.status)
        except (ValueError, TypeError, KeyError, AttributeError, UnicodeDecodeError, OverflowError, RecursionError, ValidationError, MultiPartParserError):
            return _json({'error': 'INVALID_IMPORT'}, 400)
        except OSError:
            return _json({'error': 'IMPORT_STORAGE_FAILED'}, 503)
    return wrapped


def _parse(raw):
    if len(raw.encode('utf-8') if isinstance(raw, str) else raw) > MAX_JSON:
        raise RequestDataTooBig()
    data = json.loads(raw, parse_constant=_reject_nonfinite, parse_float=_finite_float, object_pairs_hook=_unique_object)
    if not isinstance(data, dict):
        raise ValueError('Object required')
    return data


def _read(request):
    if request.content_type != 'application/json':
        raise ValueError('JSON required')
    return _parse(request.read(MAX_JSON + 1))


def _id(value):
    if not isinstance(value, str) or str(uuid.UUID(value)) != value:
        raise ValueError('Invalid source ID')
    return value


def mapped_id(user_id, kind, source_id):
    return uuid.uuid5(user_id, f'guest-{kind}:{_id(source_id)}')


def _hash(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def _style(user):
    return {field: getattr(user, field) for field in STYLE_FIELDS}


def _profile(user, section):
    if section == 'body':
        return user.body_profile
    if section == 'style':
        return _style(user)
    raise ValueError('Invalid profile section')


def _profile_value(user, section, value):
    if section == 'body' and isinstance(value, dict):
        probe = User(username=user.username, body_profile=value)
        probe.clean()
        body_from_request(None, {'body': value})
        return value
    if section == 'style' and isinstance(value, dict) and set(value) == set(STYLE_FIELDS):
        probe = User(username=user.username, **value)
        probe.clean()
        return _style(probe)
    raise ValueError('Invalid profile')


def _ids(data, key):
    values = data.get(key, [])
    if not isinstance(values, list) or len(values) > MAX_BATCH:
        raise ValueError('Invalid batch')
    values = [_id(value) for value in values]
    if len(set(values)) != len(values):
        raise ValueError('Duplicate source ID')
    return values


def _version(row):
    return row.updated_at.isoformat() if row else None


def _envelope(data):
    if set(data) != {'value', 'replace', 'expected'} or not isinstance(data['replace'], bool):
        raise ValueError('Invalid fields')
    if data['expected'] is not None and (not isinstance(data['expected'], str) or len(data['expected']) > 64):
        raise ValueError('Invalid version')
    return data['value'], data['replace'], data['expected']


def _decision(same, current_version, expected, conflict, replace):
    if same:
        return 'unchanged'
    if current_version != expected:
        raise Changed()
    return 'kept' if conflict and not replace else 'imported'


@require_POST
@require_auth
@_errors
def preview(request):
    data = _read(request)
    if set(data) - {'profile', 'garments', 'outfits'}:
        raise ValueError('Invalid fields')
    profile = data.get('profile', {})
    if not isinstance(profile, dict) or set(profile) - {'body', 'style'}:
        raise ValueError('Invalid profile')
    result = {'profile': {}, 'garments': {}, 'outfits': {}}
    for section, raw in profile.items():
        value = _profile_value(request.user, section, raw)
        current = _profile(request.user, section)
        empty = current is None if section == 'body' else current == DEFAULT_STYLE
        result['profile'][section] = {'version': _hash(current), 'conflict': not empty and current != value}
    for kind, model in [('garments', Garment), ('outfits', Outfit)]:
        ids = _ids(data, kind)
        targets = {str(mapped_id(request.user.pk, kind, value)): value for value in ids}
        existing = {str(row.pk): row for row in model.objects.filter(user=request.user, pk__in=targets)}
        for target, source in targets.items():
            row = existing.get(target)
            result[kind][source] = {'version': _version(row), 'conflict': row is not None}
    return _json(result)


@require_http_methods(['PUT'])
@require_auth
@_errors
def profile(request, section):
    value, replace, expected = _envelope(_read(request))
    value = _profile_value(request.user, section, value)
    with transaction.atomic():
        user = User.objects.select_for_update().get(pk=request.user.pk)
        current = _profile(user, section)
        empty = current is None if section == 'body' else current == DEFAULT_STYLE
        status = _decision(current == value, _hash(current), expected, not empty, replace)
        if status == 'imported':
            if section == 'body':
                user.body_profile = value
                fields = ['body_profile']
            else:
                for field, selected in value.items():
                    setattr(user, field, selected)
                fields = list(STYLE_FIELDS)
            user.full_clean()
            user.save(update_fields=[*fields, 'updated_at'])
    return _json({'status': status})


def _delete_image(name):
    try:
        default_storage.delete(name)
    except OSError:
        pass


@require_POST
@require_auth
@_errors
def garment(request, source_id):
    if request.content_type != 'multipart/form-data' or int(request.META.get('CONTENT_LENGTH') or 0) > MAX_IMAGE_BYTES + MAX_JSON + 4096:
        raise RequestDataTooBig()
    if set(request.POST) != {'metadata'} or len(request.POST.getlist('metadata')) != 1 or set(request.FILES) != {'image'} or len(request.FILES.getlist('image')) != 1:
        raise ValueError('Invalid upload')
    value, replace, expected = _envelope(_parse(request.POST['metadata']))
    values = validate_record(value)
    values.setdefault('labels', None)
    image = prepare_image(request.FILES['image'].read(MAX_IMAGE_BYTES + 1))
    digest = base64.urlsafe_b64encode(hashlib.sha256(image).digest()).decode('ascii').rstrip('=')
    target = mapped_id(request.user.pk, 'garments', str(source_id))
    image_name = f'garments/i/{target.hex}/{digest}.jpg'
    created_image = None
    try:
        with transaction.atomic():
            User.objects.select_for_update().get(pk=request.user.pk)
            row = Garment.objects.select_for_update().filter(pk=target, user=request.user).first()
            same = row is not None and row.saved and row.image.name == image_name and all(getattr(row, key) == val for key, val in values.items())
            status = _decision(same, _version(row), expected, row is not None, replace)
            if status == 'imported':
                if not default_storage.exists(image_name):
                    image_name = default_storage.save(image_name, ContentFile(image))
                    created_image = image_name
                old_image = row.image.name if row else None
                new = row is None
                row = row or Garment(pk=target, user=request.user)
                for key, val in values.items():
                    setattr(row, key, val)
                row.image = image_name
                row.saved = True
                row.save(force_insert=new)
                if old_image and old_image != image_name:
                    transaction.on_commit(lambda: _delete_image(old_image))
    except Exception:
        if created_image:
            _delete_image(created_image)
        raise
    return _json({'status': status, 'id': str(target)})


def _outfit_value(raw, user):
    if not isinstance(raw, dict) or set(raw) != {'name', 'items', 'score', 'components', 'reasons', 'context', 'feedback'}:
        raise ValueError('Invalid outfit')
    if not isinstance(raw['name'], str) or len(raw['name']) > 80 or raw['feedback'] not in (None, 'liked', 'disliked'):
        raise ValueError('Invalid outfit metadata')
    score = raw['score']
    if score is not None and (isinstance(score, bool) or not isinstance(score, int) or not 0 <= score <= 100):
        raise ValueError('Invalid score')
    components = raw['components']
    if components is not None and (not isinstance(components, dict) or set(components) - {'compat', 'body', 'color', 'season', 'taste'} or any(
            isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) or not 0 <= v <= 100 for v in components.values())):
        raise ValueError('Invalid components')
    if not isinstance(raw['context'], dict) or len(json.dumps(raw['context'])) > 2000:
        raise ValueError('Invalid context')
    if not isinstance(raw['reasons'], list) or len(raw['reasons']) > 12:
        raise ValueError('Invalid reasons')
    for reason in raw['reasons']:
        if not isinstance(reason, dict) or set(reason) - {'kind', 'text', 'sub'} or any(not isinstance(v, str) or len(v) > 500 for v in reason.values()):
            raise ValueError('Invalid reason')
    members = raw['items']
    if not isinstance(members, list) or not 2 <= len(members) <= 3:
        raise ValueError('Invalid members')
    found, slots = [], set()
    for member in members:
        if not isinstance(member, dict) or set(member) != {'garment_id', 'slot', 'note'} or member['slot'] not in ('top', 'bottom', 'outerwear'):
            raise ValueError('Invalid member')
        if member['slot'] in slots or not isinstance(member['note'], str) or len(member['note']) > 200:
            raise ValueError('Invalid member')
        slots.add(member['slot'])
        garment_id = mapped_id(user.pk, 'garments', member['garment_id'])
        garment = Garment.objects.filter(pk=garment_id, user=user, saved=True).first()
        if garment is None or garment.attributes['category'] != member['slot']:
            raise ValueError('Import outfit garments first')
        found.append({'garment_id': garment_id, 'slot': member['slot'], 'note': member['note']})
    if not {'top', 'bottom'} <= slots:
        raise ValueError('Missing top or bottom')
    return {key: value for key, value in raw.items() if key != 'items'}, found


@require_http_methods(['PUT'])
@require_auth
@_errors
def outfit(request, source_id):
    value, replace, expected = _envelope(_read(request))
    target = mapped_id(request.user.pk, 'outfits', str(source_id))
    with transaction.atomic():
        User.objects.select_for_update().get(pk=request.user.pk)
        values, members = _outfit_value(value, request.user)
        row = Outfit.objects.select_for_update().filter(pk=target, user=request.user).first()
        current_members = list(row.items.values('garment_id', 'slot', 'note')) if row else []
        same = row is not None and row.saved and all(getattr(row, key) == val for key, val in values.items()) and sorted(current_members, key=lambda i: i['slot']) == sorted(members, key=lambda i: i['slot'])
        status = _decision(same, _version(row), expected, row is not None, replace)
        if status == 'imported':
            new = row is None
            row = row or Outfit(pk=target, user=request.user, source=Outfit.Source.USER)
            for key, val in values.items():
                setattr(row, key, val)
            row.key = '+'.join(sorted(f'w:{item["garment_id"]}' for item in members))
            row.saved = True
            row.save(force_insert=new)
            row.items.all().delete()
            OutfitItem.objects.bulk_create([OutfitItem(outfit=row, **member) for member in members])
    return _json({'status': status, 'id': str(target)})

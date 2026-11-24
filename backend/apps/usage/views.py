import json
import re
import uuid
from datetime import timedelta

from django.db import transaction
from django.http import JsonResponse
from django.utils import timezone
from django.utils.dateparse import parse_datetime
from django.views.decorators.http import require_POST

from apps.users.auth import require_auth

from .models import UsageEvent

MAX_BATCH = 25
MAX_BYTES = 32 * 1024
NAMES = {'garment_registration', 'virtual_fitting', 'chat_request'}
FIELDS = {'id', 'attempt_id', 'session_id', 'name', 'phase', 'outcome', 'occurred_at', 'duration_ms', 'error_code', 'details'}
SOURCES = {'wardrobe', 'onboarding', 'worn', 'gallery', 'virtual', 'chat'}


def _uuid(value):
    if not isinstance(value, str) or str(uuid.UUID(value)) != value:
        raise ValueError('Invalid UUID')
    return value


def validate_event(data):
    if not isinstance(data, dict) or set(data) != FIELDS:
        raise ValueError('Invalid event fields')
    for key in ('id', 'attempt_id', 'session_id'):
        _uuid(data[key])
    if data['name'] not in NAMES or data['phase'] not in ('started', 'generated', 'finished'):
        raise ValueError('Invalid event type')
    phase = data['phase']
    if phase == 'generated' and data['name'] != 'virtual_fitting':
        raise ValueError('Invalid generation event')
    if data['outcome'] not in (('success', 'failure', 'cancelled') if phase == 'finished' else ('',)):
        raise ValueError('Invalid outcome')
    duration = data['duration_ms']
    if duration is not None and (type(duration) is not int or not 0 <= duration <= 30 * 86400 * 1000):
        raise ValueError('Invalid duration')
    if phase == 'started' and duration is not None:
        raise ValueError('Start has no duration')
    code = data['error_code']
    if not isinstance(code, str) or (code and not re.fullmatch(r'[A-Z][A-Z0-9_]{0,63}', code)):
        raise ValueError('Invalid error code')
    if (data['outcome'] == 'failure') != bool(code):
        raise ValueError('Failure requires an error code')
    stamp = parse_datetime(data['occurred_at']) if isinstance(data['occurred_at'], str) else None
    if stamp is None or timezone.is_naive(stamp) or stamp > timezone.now() + timedelta(minutes=10):
        raise ValueError('Invalid event time')
    details = data['details']
    if not isinstance(details, dict) or set(details) - {'source', 'status', 'clock_reset', 'app_version'}:
        raise ValueError('Invalid details')
    if details.get('source') not in SOURCES:
        raise ValueError('Invalid source')
    if 'status' in details and details['status'] not in ('updated', 'clarify', 'unlock', 'undo', 'unchanged'):
        raise ValueError('Invalid chat status')
    if 'clock_reset' in details and type(details['clock_reset']) is not bool:
        raise ValueError('Invalid clock state')
    if 'app_version' in details and (not isinstance(details['app_version'], str) or not re.fullmatch(r'[A-Za-z0-9.+_-]{1,40}', details['app_version'])):
        raise ValueError('Invalid app version')
    if phase != 'started' and duration is None and details.get('clock_reset') is not True:
        raise ValueError('Duration required unless the clock reset')
    return {**data, 'occurred_at': stamp}


@require_POST
@require_auth
def collect(request):
    if request.content_type != 'application/json':
        return JsonResponse({'error': 'JSON_REQUIRED'}, status=415)
    raw = request.read(MAX_BYTES + 1)
    if len(raw) > MAX_BYTES:
        return JsonResponse({'error': 'BODY_TOO_LARGE'}, status=413)
    try:
        data = json.loads(raw)
        if not isinstance(data, dict) or set(data) != {'events'} or not isinstance(data['events'], list) or not 1 <= len(data['events']) <= MAX_BATCH:
            raise ValueError('Invalid batch')
        events = [validate_event(event) for event in data['events']]
    except (ValueError, TypeError, KeyError, OverflowError):
        return JsonResponse({'error': 'INVALID_USAGE_EVENT'}, status=400)
    with transaction.atomic():
        # An event id and an attempt phase are immutable, even if a client retries after losing a response.
        for event in events:
            if UsageEvent.objects.filter(pk=event['id']).exists():
                continue
            UsageEvent.objects.get_or_create(user=request.user, attempt_id=event['attempt_id'], phase=event['phase'], defaults={
                key: value for key, value in event.items() if key not in ('attempt_id', 'phase')
            })
    return JsonResponse({'accepted': [event['id'] for event in events]})

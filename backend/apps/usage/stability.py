import json
import re
from datetime import timedelta

from django.db import transaction
from django.http import JsonResponse
from django.utils import timezone
from django.utils.dateparse import parse_datetime
from django.views.decorators.http import require_POST

from apps.users.auth import require_auth

from .models import StabilityEvent
from .views import _uuid

MAX_BYTES = 64 * 1024
FIELDS = {'id', 'installation_id', 'session_id', 'kind', 'source', 'occurred_at', 'app_version',
          'sdk_int', 'foreground', 'exception_type', 'frames', 'duration_ms', 'exit_reason', 'exit_status'}
FRAME = re.compile(r'[A-Za-z0-9_.$<>/-]+\([A-Za-z0-9_.$:-]*\)')


def validate(data):
    if not isinstance(data, dict) or set(data) != FIELDS:
        raise ValueError('Invalid fields')
    for key in ('id', 'installation_id', 'session_id'):
        _uuid(data[key])
    if not isinstance(data['app_version'], str) or not re.fullmatch(r'[A-Za-z0-9.+_-]{1,40}', data['app_version']):
        raise ValueError('Invalid version')
    if type(data['sdk_int']) is not int or not 26 <= data['sdk_int'] <= 100 or type(data['foreground']) is not bool:
        raise ValueError('Invalid platform')
    stamp = parse_datetime(data['occurred_at']) if isinstance(data['occurred_at'], str) else None
    if stamp is None or timezone.is_naive(stamp) or stamp.year < 2020 or stamp > timezone.now() + timedelta(minutes=10):
        raise ValueError('Invalid timestamp')
    frames = data['frames']
    if not isinstance(frames, list) or len(frames) > 20 or any(
            not isinstance(frame, str) or len(frame) > 180 or not FRAME.fullmatch(frame) for frame in frames):
        raise ValueError('Invalid stack frames')
    exception = data['exception_type']
    if not isinstance(exception, str) or (exception and not re.fullmatch(r'[A-Za-z0-9_.$]{1,160}', exception)):
        raise ValueError('Invalid exception type')
    kind, source = data['kind'], data['source']
    if (kind, source) not in {('active', 'lifecycle'), ('crash', 'java'), ('crash', 'os_exit'),
                              ('anr', 'os_exit'), ('stall', 'watchdog')}:
        raise ValueError('Invalid diagnostic type')
    duration = data['duration_ms']
    if kind == 'stall':
        if type(duration) is not int or not 5000 <= duration <= 30 * 86400 * 1000 or not data['foreground']:
            raise ValueError('Invalid foreground stall')
    elif duration is not None:
        raise ValueError('Unexpected duration')
    if source == 'os_exit':
        expected = (6,) if kind == 'anr' else (4, 5)
        if data['sdk_int'] < 30 or type(data['exit_reason']) is not int or data['exit_reason'] not in expected:
            raise ValueError('Invalid OS exit')
        if type(data['exit_status']) is not int or not -2147483648 <= data['exit_status'] <= 2147483647:
            raise ValueError('Invalid exit status')
    elif data['exit_reason'] is not None or data['exit_status'] is not None:
        raise ValueError('Unexpected exit details')
    if bool(exception) != (source == 'java'):
        raise ValueError('Invalid exception')
    if kind == 'active' and (frames or not data['foreground']):
        raise ValueError('Invalid activity')
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
        if not isinstance(data, dict) or set(data) != {'events'} or not isinstance(data['events'], list) or not 1 <= len(data['events']) <= 5:
            raise ValueError('Invalid batch')
        events = [validate(event) for event in data['events']]
    except (ValueError, TypeError, KeyError, OverflowError):
        return JsonResponse({'error': 'INVALID_STABILITY_EVENT'}, status=400)
    with transaction.atomic():
        for event in events:
            StabilityEvent.objects.get_or_create(id=event['id'], defaults={k: v for k, v in event.items() if k != 'id'})
    return JsonResponse({'accepted': [event['id'] for event in events]})

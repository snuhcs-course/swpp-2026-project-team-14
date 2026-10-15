# AI-generated with Codex, 2026-10-16
"""Bound account lists below the Android per-response limit, with an owner-scoped cursor."""
import json
import uuid
import base64
import binascii
from datetime import datetime, timezone

from django.db.models import Q
from django.http import JsonResponse
from django.utils.timezone import is_aware

PAGE_SIZE = 20
PAGE_BYTES = 128 * 1024


def account_page(request, rows, serialize, key, order_field='created_at'):
    cursor = request.GET.get('after')
    if cursor is not None:
        try:
            if not 1 <= len(cursor) <= 512:
                raise ValueError('Invalid cursor length')
            position = json.loads(base64.b64decode(cursor + '=' * (-len(cursor) % 4), altchars=b'-_', validate=True))
            if not isinstance(position, list) or len(position) != 4:
                raise ValueError('Invalid cursor')
            owner, kind, at, row_id = position
            if owner != str(request.user.pk) or kind != key:
                raise ValueError('Invalid cursor scope')
            timestamp, cursor_id = datetime.fromisoformat(at), uuid.UUID(row_id)
            if not is_aware(timestamp):
                raise ValueError('Invalid cursor time')
            timestamp = timestamp.astimezone(timezone.utc)
        except (ValueError, TypeError, AttributeError, OverflowError, UnicodeDecodeError, binascii.Error):
            return JsonResponse({'error': 'INVALID_CURSOR'}, status=400)
        # The position stays fixed even when the previous page's last row is edited or unsaved.
        # Authorization always comes from the owner-filtered queryset, never from this cursor.
        rows = rows.filter(Q(**{f'{order_field}__lt': timestamp}) |
                           Q(**{order_field: timestamp, 'pk__lt': cursor_id}))
    candidates = list(rows.order_by(f'-{order_field}', '-pk')[:PAGE_SIZE + 1])
    result, size = [], 0
    for row in candidates[:PAGE_SIZE]:
        value = serialize(row)
        encoded_size = len(json.dumps(value, ensure_ascii=False).encode('utf-8'))
        if size + encoded_size > PAGE_BYTES:
            if not result:
                return JsonResponse({'error': 'RECORD_TOO_LARGE'}, status=413)
            break
        result.append(value)
        size += encoded_size
    next_cursor = None
    if len(candidates) > len(result):
        last = candidates[len(result) - 1]
        position = [str(request.user.pk), key, getattr(last, order_field).isoformat(), str(last.pk)]
        next_cursor = base64.urlsafe_b64encode(json.dumps(position).encode('utf-8')).decode('ascii').rstrip('=')
    return JsonResponse({key: result, 'next': next_cursor}, json_dumps_params={'ensure_ascii': False})

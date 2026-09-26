import json

from django.db import DatabaseError, connection
from django.http import JsonResponse
from django.views.decorators.http import require_GET, require_http_methods

from .models import ProbeItem


@require_GET
def health(request):
    try:
        with connection.cursor() as cursor:
            cursor.execute('SELECT 1')
            cursor.fetchone()
    except DatabaseError:
        return JsonResponse({'status': 'error', 'database': 'unavailable'}, status=503)
    return JsonResponse({'status': 'ok', 'database': 'mysql', 'environment': 'local-dev'})


@require_http_methods(['GET', 'POST'])
def items(request):
    if request.method == 'GET':
        rows = list(ProbeItem.objects.values('id', 'name', 'created_at')[:100])
        return JsonResponse({'results': rows})
    if request.content_type != 'application/json':
        return JsonResponse({'error': 'JSON_REQUIRED'}, status=415)
    try:
        payload = json.loads(request.body)
    except (ValueError, UnicodeDecodeError):
        return JsonResponse({'error': 'INVALID_JSON'}, status=400)
    if not isinstance(payload, dict) or set(payload) != {'name'}:
        return JsonResponse({'error': 'INVALID_FIELDS'}, status=400)
    name = payload.get('name')
    if not isinstance(name, str) or not 1 <= len(name.strip()) <= 50:
        return JsonResponse({'error': 'INVALID_NAME'}, status=400)
    item = ProbeItem.objects.create(name=name.strip())
    return JsonResponse({'id': item.id, 'name': item.name, 'created_at': item.created_at}, status=201)

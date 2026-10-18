# AI-generated with Codex, 2026-10-16
import hashlib
import re
import secrets
from datetime import timedelta
from functools import wraps

from django.conf import settings
from django.http import JsonResponse
from django.utils import timezone

from .models import AuthToken, User


def require_database(view):
    @wraps(view)
    def wrapped(request, *args, **kwargs):
        engine = settings.DATABASES.get('default', {}).get('ENGINE')
        if not engine or engine == 'django.db.backends.dummy':
            response = JsonResponse({'error': 'DATABASE_NOT_CONFIGURED'}, status=503)
            response['Cache-Control'] = 'no-store'
            return response
        return view(request, *args, **kwargs)
    return wrapped


def issue_token(user):
    raw = secrets.token_urlsafe(32)
    token = AuthToken.objects.create(
        key_hash=hashlib.sha256(raw.encode('ascii')).hexdigest(), user=user,
        expires_at=timezone.now() + timedelta(days=settings.AUTH_TOKEN_TTL_DAYS),
    )
    User.objects.filter(pk=user.pk).update(last_login=timezone.now())
    return token, raw


def require_auth(view):
    @wraps(view)
    @require_database
    def wrapped(request, *args, **kwargs):
        parts = request.headers.get('Authorization', '').split()
        token = None
        if len(parts) == 2 and parts[0].lower() == 'bearer' and re.fullmatch(r'[A-Za-z0-9_-]{43}', parts[1]):
            key_hash = hashlib.sha256(parts[1].encode('ascii')).hexdigest()
            token = AuthToken.objects.select_related('user').filter(
                key_hash=key_hash, expires_at__gt=timezone.now(), user__is_active=True,
            ).first()
        if token is None:
            response = JsonResponse({'error': 'AUTHENTICATION_REQUIRED'}, status=401)
            response['WWW-Authenticate'] = 'Bearer'
            response['Cache-Control'] = 'no-store'
            return response
        request.user = token.user
        request.auth_token = token
        response = view(request, *args, **kwargs)
        response['Cache-Control'] = 'no-store'
        return response
    return wrapped

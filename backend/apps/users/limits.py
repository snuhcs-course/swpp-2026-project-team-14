# AI-generated with Codex, 2026-10-16
import hashlib
import math
from datetime import timedelta

from django.conf import settings
from django.db import transaction
from django.http import JsonResponse
from django.utils import timezone

from .models import AuthRateLimit


def locked_counter(scope, identifier):
    """Caller must hold transaction.atomic() until the counter update is complete."""
    key = hashlib.sha256(f'{scope}:{identifier}'.encode('utf-8')).hexdigest()
    counter, _ = AuthRateLimit.objects.select_for_update().get_or_create(
        key=key, defaults={'window_started': timezone.now()},
    )
    return counter


def limited_response(seconds, code='TOO_MANY_REQUESTS'):
    retry_after = max(1, math.ceil(seconds))
    response = JsonResponse({'error': code, 'retry_after': retry_after}, status=429)
    response['Retry-After'] = str(retry_after)
    response['Cache-Control'] = 'no-store'
    return response


def check_auth_request_rate(request):
    # Forwarded headers are client-controlled unless a trusted proxy explicitly sanitizes them.
    address = request.META.get('REMOTE_ADDR') or 'unknown'
    with transaction.atomic():
        counter = locked_counter('auth-ip', address)
        now = timezone.now()
        remaining = settings.AUTH_REQUEST_WINDOW_SECONDS - (now - counter.window_started).total_seconds()
        if remaining <= 0:
            counter.count = 0
            counter.window_started = now
        elif counter.count >= settings.AUTH_REQUEST_LIMIT:
            return limited_response(remaining)
        counter.count += 1
        counter.save(update_fields=['count', 'window_started'])
    return None


def check_login_lock(counter):
    now = timezone.now()
    if counter.blocked_until is not None:
        remaining = (counter.blocked_until - now).total_seconds()
        if remaining > 0:
            return limited_response(remaining, 'LOGIN_LOCKED')
        reset_login_counter(counter, now)
    elif (now - counter.window_started).total_seconds() >= settings.LOGIN_FAILURE_WINDOW_SECONDS:
        reset_login_counter(counter, now)
    return None


def reset_login_counter(counter, now=None):
    counter.count = 0
    counter.window_started = now or timezone.now()
    counter.blocked_until = None
    counter.save(update_fields=['count', 'window_started', 'blocked_until'])


def record_login_failure(counter):
    counter.count += 1
    if counter.count >= settings.LOGIN_FAILURE_LIMIT:
        counter.blocked_until = timezone.now() + timedelta(seconds=settings.LOGIN_LOCK_SECONDS)
    counter.save(update_fields=['count', 'blocked_until'])
    if counter.blocked_until is not None:
        return limited_response(settings.LOGIN_LOCK_SECONDS, 'LOGIN_LOCKED')
    return None

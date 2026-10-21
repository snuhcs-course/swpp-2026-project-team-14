# AI-generated with Codex, 2026-10-16
import json
import math
import re

from django.contrib.auth import authenticate
from django.core.exceptions import RequestDataTooBig, ValidationError
from django.db import IntegrityError, transaction
from django.http import HttpResponse, JsonResponse
from django.views.decorators.http import require_http_methods, require_POST

from apps.body_profiles.profile import body_from_request

from .auth import issue_token, require_auth, require_database
from .models import MAX_PASSWORD_LENGTH, MIN_PASSWORD_LENGTH, USERNAME_PATTERN, User
from .limits import check_auth_request_rate, check_login_lock, locked_counter, record_login_failure, reset_login_counter


MAX_REQUEST_BYTES = 8 * 1024
PROFILE_FIELDS = {'body_profile', 'preferred_fits', 'preferred_styles', 'favorite_colors'}


def _reject_nonfinite(value):
    raise ValueError('Non-finite JSON number')


def _finite_float(value):
    result = float(value)
    if not math.isfinite(result):
        raise ValueError('Non-finite JSON number')
    return result


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON field')
        result[key] = value
    return result


def _json(data, status=200):
    response = JsonResponse(data, status=status, json_dumps_params={'ensure_ascii': False})
    response['Cache-Control'] = 'no-store'
    return response


def _read(request):
    if request.content_type != 'application/json':
        raise TypeError('JSON_REQUIRED')
    raw = request.read(MAX_REQUEST_BYTES + 1)
    if len(raw) > MAX_REQUEST_BYTES:
        raise RequestDataTooBig()
    data = json.loads(raw, parse_constant=_reject_nonfinite, parse_float=_finite_float, object_pairs_hook=_unique_object)
    if not isinstance(data, dict):
        raise ValueError('Object required')
    return data


def _read_or_error(request):
    try:
        return _read(request), None
    except TypeError:
        return None, _json({'error': 'JSON_REQUIRED'}, 415)
    except RequestDataTooBig:
        return None, _json({'error': 'BODY_TOO_LARGE'}, 413)
    except (ValueError, UnicodeDecodeError, RecursionError):
        return None, _json({'error': 'INVALID_REQUEST'}, 400)


def serialize(user):
    return {
        'id': str(user.pk), 'username': user.username, 'body_profile': user.body_profile,
        'preferred_fits': user.preferred_fits, 'preferred_styles': user.preferred_styles,
        'favorite_colors': user.favorite_colors,
    }


def _authenticated_response(user, status=200):
    token, raw = issue_token(user)
    return _json({'token': raw, 'expires_at': token.expires_at.isoformat(), 'user': serialize(user)}, status)


@require_POST
@require_database
def register(request):
    limited = check_auth_request_rate(request)
    if limited is not None:
        return limited
    data, error = _read_or_error(request)
    if error is not None:
        return error
    if set(data) != {'username', 'password', 'password_confirmation'}:
        return _json({'error': 'INVALID_REQUEST'}, 400)
    if not isinstance(data['username'], str) or not re.fullmatch(USERNAME_PATTERN, data['username']):
        return _json({'error': 'INVALID_USERNAME'}, 400)
    password = data['password']
    if not isinstance(password, str) or not MIN_PASSWORD_LENGTH <= len(password) <= MAX_PASSWORD_LENGTH:
        return _json({'error': 'INVALID_PASSWORD'}, 400)
    if not isinstance(data['password_confirmation'], str) or password != data['password_confirmation']:
        return _json({'error': 'PASSWORD_MISMATCH'}, 400)
    try:
        with transaction.atomic():
            user = User.objects.create_user(data['username'], password)
            return _authenticated_response(user, 201)
    except ValidationError:
        return _json({'error': 'USERNAME_TAKEN'}, 409)
    except IntegrityError:
        # The unique index also covers concurrent registrations of the same ID.
        return _json({'error': 'USERNAME_TAKEN'}, 409)


@require_POST
@require_database
def login(request):
    limited = check_auth_request_rate(request)
    if limited is not None:
        return limited
    data, error = _read_or_error(request)
    if error is not None:
        return error
    if set(data) != {'username', 'password'}:
        return _json({'error': 'INVALID_REQUEST'}, 400)
    username, password = data['username'], data['password']
    if not isinstance(username, str) or not re.fullmatch(USERNAME_PATTERN, username) \
            or not isinstance(password, str) or len(password) > MAX_PASSWORD_LENGTH:
        return _json({'error': 'INVALID_CREDENTIALS'}, 401)
    with transaction.atomic():
        counter = locked_counter('login-id', username)
        limited = check_login_lock(counter)
        if limited is not None:
            return limited
        user = authenticate(request, username=username, password=password)
        if user is None:
            limited = record_login_failure(counter)
            if limited is not None:
                return limited
            return _json({'error': 'INVALID_CREDENTIALS'}, 401)
        reset_login_counter(counter)
        return _authenticated_response(user)


@require_POST
@require_auth
def logout(request):
    request.auth_token.delete()
    return HttpResponse(status=204)


@require_http_methods(['GET', 'PATCH'])
@require_auth
def me(request):
    if request.method == 'GET':
        return _json({'user': serialize(request.user)})
    data, error = _read_or_error(request)
    if error is not None:
        return error
    if not data or set(data) - PROFILE_FIELDS:
        return _json({'error': 'INVALID_PROFILE'}, 400)
    try:
        # Serialize concurrent edits so changing colors cannot overwrite a newer fit selection.
        with transaction.atomic():
            user = User.objects.select_for_update().get(pk=request.user.pk)
            for field, value in data.items():
                setattr(user, field, value)
            user.full_clean()
            if 'body_profile' in data and user.body_profile is not None:
                body_from_request(request, {'body': user.body_profile})
            user.save(update_fields=[*data, 'updated_at'])
    except (ValidationError, ValueError, TypeError, AttributeError, OverflowError):
        return _json({'error': 'INVALID_PROFILE'}, 400)
    return _json({'user': serialize(user)})

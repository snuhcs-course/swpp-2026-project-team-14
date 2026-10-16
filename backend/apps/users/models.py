# AI-generated with Codex, 2026-10-16
import re
import uuid

from django.contrib.auth.base_user import AbstractBaseUser, BaseUserManager
from django.core.exceptions import ValidationError
from django.core.validators import RegexValidator
from django.db import models
from django.utils import timezone

from .profile import MAX_FAVORITE_COLORS, validate_body_profile


USERNAME_PATTERN = r'\A[a-z0-9_]{4,20}\Z'
MIN_PASSWORD_LENGTH = 8
MAX_PASSWORD_LENGTH = 64


def default_preferred_fits():
    return ['regular']


def _validate_selection(field, value, choices):
    if not isinstance(value, list) or len(value) > len(choices) or any(not isinstance(item, str) or item not in choices for item in value):
        raise ValidationError({field: '허용된 값의 목록이어야 합니다.'})
    if len(value) != len(set(value)):
        raise ValidationError({field: '같은 값을 중복 선택할 수 없습니다.'})


class UserManager(BaseUserManager):
    def create_user(self, username, password, **extra_fields):
        if not isinstance(password, str) or not MIN_PASSWORD_LENGTH <= len(password) <= MAX_PASSWORD_LENGTH:
            raise ValidationError({'password': '비밀번호는 8~64자여야 합니다.'})
        user = self.model(username=username, **extra_fields)
        user.set_password(password)
        user.full_clean()
        user.save(using=self._db)
        return user


class User(AbstractBaseUser):
    class Fit(models.TextChoices):
        OVERSIZED = 'oversized', '오버핏'
        WIDE = 'wide', '세미 와이드 / 와이드'
        REGULAR = 'regular', '레귤러'
        TAPERED = 'tapered', '테이퍼드'
        SLIM = 'slim', '슬림'

    class Style(models.TextChoices):
        MINIMAL = 'minimal', '미니멀'
        CITYBOY = 'cityboy', '시티보이'
        CASUAL = 'casual', '캐주얼'
        STREET = 'street', '스트리트'
        CLASSIC = 'classic', '클래식 / 프레피'
        SPORTY = 'sporty', '스포티'

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    username = models.CharField(
        max_length=20, unique=True,
        validators=[RegexValidator(USERNAME_PATTERN, '아이디는 영문 소문자·숫자·밑줄 4~20자여야 합니다.')],
    )
    body_profile = models.JSONField(null=True, blank=True, default=None)
    preferred_fits = models.JSONField(default=default_preferred_fits, blank=True)
    preferred_styles = models.JSONField(default=list, blank=True)
    favorite_colors = models.JSONField(default=list, blank=True)
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    objects = UserManager()

    USERNAME_FIELD = 'username'
    REQUIRED_FIELDS = []

    def clean(self):
        # Keep the exact ASCII login ID: do not normalize other characters into valid IDs.
        if self.preferred_fits == []:
            self.preferred_fits = default_preferred_fits()
        _validate_selection('preferred_fits', self.preferred_fits, self.Fit.values)
        _validate_selection('preferred_styles', self.preferred_styles, self.Style.values)
        validate_body_profile(self.body_profile)
        if not isinstance(self.favorite_colors, list) or len(self.favorite_colors) > MAX_FAVORITE_COLORS or any(
            not isinstance(color, str) or not re.fullmatch(r'#[0-9A-Fa-f]{6}', color)
            for color in self.favorite_colors
        ):
            raise ValidationError({'favorite_colors': '컬러는 6자리 HEX 색상 목록이어야 합니다.'})
        self.favorite_colors = [color.upper() for color in self.favorite_colors]
        if len(self.favorite_colors) != len(set(self.favorite_colors)):
            raise ValidationError({'favorite_colors': '같은 컬러를 중복 선택할 수 없습니다.'})


class AuthToken(models.Model):
    key_hash = models.CharField(max_length=64, primary_key=True)
    user = models.ForeignKey(User, related_name='auth_tokens', on_delete=models.CASCADE)
    created_at = models.DateTimeField(auto_now_add=True)
    expires_at = models.DateTimeField(db_index=True)


class AuthRateLimit(models.Model):
    key = models.CharField(max_length=64, primary_key=True)
    count = models.PositiveIntegerField(default=0)
    window_started = models.DateTimeField(default=timezone.now)
    blocked_until = models.DateTimeField(null=True, default=None)

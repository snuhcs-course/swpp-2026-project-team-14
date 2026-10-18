# AI-generated with Claude Code (Dongkun Moon) and Codex (Hyeon U Jeong), 2026-09-24, reviewed by Dongkun Moon and Hyeon U Jeong
"""Django settings for the StyleMate backend.

Configured through environment variables so the same code runs locally and in the cluster:
- DJANGO_DEBUG: "1" for local debugging (default off, as in the deployed image)
- DJANGO_SECRET_KEY: required once sessions/auth are added; without it a random key is used
  (fine while no endpoint relies on signed data)
- DJANGO_ALLOWED_HOSTS: comma-separated host names (the Docker image sets the in-cluster names)
"""

import os
import secrets
from pathlib import Path

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent.parent
load_dotenv(BASE_DIR / ".env", override=False)

DEBUG = os.environ.get("DJANGO_DEBUG", "0") == "1"
SECRET_KEY = os.environ.get("DJANGO_SECRET_KEY") or secrets.token_urlsafe(64)
ALLOWED_HOSTS = os.environ.get("DJANGO_ALLOWED_HOSTS", "*").split(",")

INSTALLED_APPS = [
    "django.contrib.contenttypes", "django.contrib.auth",
    "apps.users", "apps.body_profiles", "apps.wardrobe", "apps.outfits",
]
AUTH_USER_MODEL = "users.User"
AUTH_TOKEN_TTL_DAYS = 30
AUTH_REQUEST_LIMIT = 20
AUTH_REQUEST_WINDOW_SECONDS = 60
LOGIN_FAILURE_LIMIT = 5
LOGIN_FAILURE_WINDOW_SECONDS = 300
LOGIN_LOCK_SECONDS = 300
MIDDLEWARE = ["django.middleware.common.CommonMiddleware"]
ROOT_URLCONF = "config.urls"
WSGI_APPLICATION = "config.wsgi.application"
DATABASES = {}  # Body analysis and landmarks can run before MySQL is configured.
if os.environ.get("MYSQL_DATABASE"):
    DATABASES = {"default": {
        "ENGINE": "django.db.backends.mysql",
        "NAME": os.environ["MYSQL_DATABASE"],
        "USER": os.environ.get("MYSQL_USER", ""),
        "PASSWORD": os.environ.get("MYSQL_PASSWORD", ""),
        "HOST": os.environ.get("MYSQL_HOST", "127.0.0.1"),
        "PORT": os.environ.get("MYSQL_PORT", "3306"),
        "OPTIONS": {"charset": "utf8mb4", "init_command": "SET sql_mode='STRICT_TRANS_TABLES'"},
    }}
DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"
USE_TZ = True

# Log unhandled errors (500s) with tracebacks to stderr, so they show up in `kubectl logs`.
LOGGING = {
    "version": 1,
    "disable_existing_loggers": False,
    "handlers": {"console": {"class": "logging.StreamHandler"}},
    "loggers": {"django.request": {"handlers": ["console"], "level": "ERROR", "propagate": False}},
}

# Body photos must never touch the disk: keep uploads in memory only (no temp files) and cap them.
FILE_UPLOAD_HANDLERS = ["django.core.files.uploadhandler.MemoryFileUploadHandler"]
# The memory handler takes a request only if the whole body fits; a larger body would silently lose
# its files. Two 8 MB body photos plus form fields must fit (larger requests get a 413 in the view).
FILE_UPLOAD_MAX_MEMORY_SIZE = 20 * 1024 * 1024
DATA_UPLOAD_MAX_MEMORY_SIZE = 25 * 1024 * 1024


GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY", "").strip()
WARDROBE_MODEL_DIR = Path(os.environ.get("WARDROBE_MODEL_DIR", str(BASE_DIR / "wardrobe/garment-landmarks")))
MEDIA_ROOT = Path(os.environ.get("WARDROBE_MEDIA_ROOT", str(BASE_DIR / "private/wardrobe-media")))

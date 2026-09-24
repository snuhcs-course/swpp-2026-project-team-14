"""Django settings for the StyleMate backend.

Configured through environment variables so the same code runs locally and in the cluster:
- DJANGO_DEBUG: "1" for local debugging (default off, as in the deployed image)
- DJANGO_SECRET_KEY: required once sessions/auth are added; without it a random key is used
  (fine while no endpoint relies on signed data)
- DJANGO_ALLOWED_HOSTS: comma-separated host names (the Docker image sets the in-cluster names)
"""

import os
import secrets

DEBUG = os.environ.get("DJANGO_DEBUG", "0") == "1"
SECRET_KEY = os.environ.get("DJANGO_SECRET_KEY") or secrets.token_urlsafe(64)
ALLOWED_HOSTS = os.environ.get("DJANGO_ALLOWED_HOSTS", "*").split(",")

INSTALLED_APPS = ["apps.body_profiles"]
MIDDLEWARE = ["django.middleware.common.CommonMiddleware"]
ROOT_URLCONF = "config.urls"
WSGI_APPLICATION = "config.wsgi.application"
DATABASES = {}  # the analyze endpoint is stateless; profile storage comes with the database setup
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
FILE_UPLOAD_MAX_MEMORY_SIZE = 10 * 1024 * 1024
DATA_UPLOAD_MAX_MEMORY_SIZE = 25 * 1024 * 1024

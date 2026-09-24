"""Minimal Django settings for the body-analysis API.

This is a stand-alone dev server for P9/P10. When the team's Django project (P7) exists,
`body_profiles` is added to its INSTALLED_APPS/urls and these upload settings are copied over.
"""

import os

SECRET_KEY = os.environ.get("DJANGO_SECRET_KEY", "dev-only-not-secret")
DEBUG = os.environ.get("DJANGO_DEBUG", "1") == "1"
ALLOWED_HOSTS = os.environ.get("DJANGO_ALLOWED_HOSTS", "*").split(",")

INSTALLED_APPS = ["body_profiles"]
MIDDLEWARE = ["django.middleware.common.CommonMiddleware"]
ROOT_URLCONF = "stylemate_server.urls"
WSGI_APPLICATION = "stylemate_server.wsgi.application"
DATABASES = {}  # the analyze endpoint is stateless; profile storage comes with P7's database
USE_TZ = True

# Body photos must never touch the disk: keep uploads in memory only (no temp files) and cap them.
FILE_UPLOAD_HANDLERS = ["django.core.files.uploadhandler.MemoryFileUploadHandler"]
FILE_UPLOAD_MAX_MEMORY_SIZE = 10 * 1024 * 1024
DATA_UPLOAD_MAX_MEMORY_SIZE = 25 * 1024 * 1024

# AI-generated with Codex, 2026-10-03, reviewed by Hyeon U Jeong
"""Isolated wardrobe API tests; no production database, photos, or AI credentials."""
from .settings import *

DATABASES = {'default': {'ENGINE': 'django.db.backends.sqlite3', 'NAME': ':memory:'}}
GEMINI_API_KEY = ''
ALLOWED_HOSTS = ['testserver', 'localhost', '127.0.0.1']
DATA_UPLOAD_MAX_MEMORY_SIZE = 16 * 1024

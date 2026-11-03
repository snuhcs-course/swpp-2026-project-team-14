# AI-generated with Claude Code (Claude Opus 5.5), 2026-11-03, reviewed by Dongkun Moon
"""Temporary HE (11/4) dummy wardrobe: every account gets the same 45 AI-generated garments.

Enabled only when HE_DUMMY_WARDROBE is set. Remove this module, its data folder (he_dummy/) and the
three call sites (login/register, wardrobe list, recommend) after HE.

Each image is stored once in media and shared by every account's garment rows; no code path deletes
a saved garment's image, so sharing is safe. Garment IDs are stable per account, so repeated calls
only add what is missing and never duplicate or overwrite the user's own garments.
"""

import json
from functools import lru_cache
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5

from django.conf import settings
from django.core.files.base import ContentFile
from django.core.files.storage import default_storage

from recommendation.labeling import label

from .models import Garment

DATA_DIR = Path(__file__).resolve().parent / 'he_dummy'
STORAGE_DIR = 'he-dummy'


@lru_cache(maxsize=1)
def _items():
    return json.loads((DATA_DIR / 'items.json').read_text(encoding='utf-8'))['items']


def _stored_name(file):
    name = f'{STORAGE_DIR}/{file}'
    if not default_storage.exists(name):
        default_storage.save(name, ContentFile((DATA_DIR / file).read_bytes()))
    return name


def ensure_dummy_wardrobe(user):
    """Add any missing dummy garments to this account (no-op unless HE_DUMMY_WARDROBE is on)."""
    if not settings.HE_DUMMY_WARDROBE:
        return
    ids = {uuid5(NAMESPACE_URL, f'stylemate-he-dummy/{user.pk}/{item["file"]}'): item for item in _items()}
    existing = set(Garment.objects.filter(user=user, pk__in=ids).values_list('pk', flat=True))
    missing = [(pk, item) for pk, item in ids.items() if pk not in existing]
    if not missing:
        return
    rows = []
    for pk, item in missing:
        labels = label(item['attributes'], None)
        if labels is not None:
            labels['confirmed'] = True
        garment = Garment(id=pk, user=user, attributes=item['attributes'], labels=labels, saved=True)
        garment.image.name = _stored_name(item['file'])
        rows.append(garment)
    Garment.objects.bulk_create(rows, ignore_conflicts=True)

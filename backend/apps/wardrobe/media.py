# AI-generated with Codex, 2026-11-05
"""Delete garment media after commit, preserving references across all accounts."""
import logging

from django.core.files.storage import default_storage
from django.db import transaction
from django.db.models import Q

from .models import Garment, WornGeneration, WornPhoto


logger = logging.getLogger(__name__)


def delete_garment_with_media(garment):
    """Run inside the caller's transaction and owner lock; defer storage changes."""
    names = {garment.image.name}
    generation = WornGeneration.objects.filter(garment=garment).first()
    if generation:
        names.update((generation.original.name, generation.segmented.name, generation.candidate.name))
    photo_id = generation.photo_id if generation else None

    garment.delete()
    if photo_id:
        photo = WornPhoto.objects.filter(pk=photo_id).first()
        if photo and not photo.generations.exists():
            names.add(photo.image.name)
            photo.delete()

    names.discard('')
    names.discard(None)
    transaction.on_commit(lambda: _delete_unreferenced(names), robust=True)


def _delete_unreferenced(names):
    for name in sorted(names):
        try:
            # Include unsaved drafts and other accounts, including legacy shared demo photos.
            if (Garment.objects.filter(image=name).exists()
                    or WornPhoto.objects.filter(image=name).exists()
                    or WornGeneration.objects.filter(
                        Q(original=name) | Q(segmented=name) | Q(candidate=name)).exists()):
                continue
            default_storage.delete(name)
        except FileNotFoundError:
            pass
        except Exception:
            # Database deletion has committed; a storage error must not make the client retry it.
            logger.exception('Could not clean up unreferenced garment media: %s', name)

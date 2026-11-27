# AI-generated with Codex and Claude Code (Dongkun Moon), 2026-11-25; stored cutouts with Claude Code (Claude Opus 5.5), 2026-11-26, reviewed by Dongkun Moon
"""Transparent garment PNGs for the wardrobe rail.

A saved garment's photo is cut out once, in the background right after saving (schedule_cutout), with the
same SegFormer model the photo registration uses, and stored next to the original in Garment.cutout. The
original photo is never changed. `cutout_key` records the source photo and model version, so a cutout made
from an older photo or model is made again on its next request (ensure_cutout). Nothing here calls a paid API.
"""
from concurrent.futures import ThreadPoolExecutor
from hashlib import sha256
from io import BytesIO
import logging
from threading import Lock, Semaphore
import uuid

import cv2
import numpy as np
from django.conf import settings
from django.core.files.base import ContentFile
from django.db import close_old_connections, transaction
from PIL import Image, ImageOps

from . import worn
from .analysis import AnalysisError, MAX_IMAGE_BYTES
from .media import delete_unreferenced
from .models import Garment

logger = logging.getLogger(__name__)
HANGABLE = ('top', 'bottom', 'outerwear')
DISPLAY_VERSION = 'flatlay-union-v5-448'  # v3: real photos use SegFormer; v4: enclosed holes filled; v5: lone worn crops
_inference = Semaphore(1)  # one CPU inference at a time, shared by background work and requests
_jobs = [Lock() for _ in range(16)]  # one cutout job per garment at a time
_pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix='garment-cutout')


def clothing_mask(image, attributes):
    """A display-only mask: class confusion must not punch holes in a single flatlay."""
    tensor = np.asarray(image.convert('RGB').resize((512, 512), Image.Resampling.BILINEAR), dtype=np.float32) / 255
    tensor = (tensor - np.array([.485, .456, .406], np.float32)) / np.array([.229, .224, .225], np.float32)
    session = worn.segmenter(str(settings.WORN_SEGMENTATION_MODEL))
    with _inference:
        logits = session.run(None, {session.get_inputs()[0].name: tensor.transpose(2, 0, 1)[None]})[0][0]
    # Work at display resolution instead of allocating 19 full-resolution photo planes.
    labels = np.stack([cv2.resize(channel, image.size, interpolation=cv2.INTER_LINEAR) for channel in logits]).argmax(axis=0)
    return apparel_mask(labels, attributes)


def apparel_mask(labels, attributes):
    if attributes.get('_display_source') == 'worn_crop':
        keep = worn.garment_mask(labels, attributes)  # one garment on a person; all clothing if no person
    else:
        keep = np.isin(labels, worn.APPAREL_LABELS)
    return Image.fromarray(keep.astype(np.uint8) * 255)


def contrast_mask(image):
    """Cheap path for strongly separated studio images; pale clothing uses semantic labels."""
    rgb = np.asarray(image.convert('RGB'))
    border = np.concatenate((rgb[0], rgb[-1], rgb[:, 0], rgb[:, -1]))
    background = np.median(border, axis=0)
    if background.min() < 175 or np.ptp(background) > 25:
        return None
    difference = np.abs(rgb.astype(np.int16) - background).max(axis=2)
    if (np.abs(border.astype(np.int16) - background).max(axis=1) <= 26).mean() < .9:
        return None
    backdrop = (difference <= 22).astype(np.uint8)
    count, labels = cv2.connectedComponents(backdrop, connectivity=4)
    edge_labels = np.unique(np.concatenate((labels[0], labels[-1], labels[:, 0], labels[:, -1])))
    removed = np.isin(labels, edge_labels[edge_labels != 0])
    foreground = ~removed
    area = int(foreground.sum())
    if not .045 < area / foreground.size < .85:
        return None
    # Similar-to-backdrop fabric is never accepted by this colour heuristic.
    if np.median(difference[foreground]) < 65:
        return None
    count, components, stats, _ = cv2.connectedComponentsWithStats(foreground.astype(np.uint8), connectivity=8)
    if count < 2:
        return None
    largest = 1 + stats[1:, cv2.CC_STAT_AREA].argmax()
    if stats[largest, cv2.CC_STAT_AREA] < area * .95:
        return None
    mask = (components == largest).astype(np.uint8) * 255
    return Image.fromarray(mask)


def fill_enclosed_holes(mask):
    """A flat garment has no see-through gaps: light prints or logos that the model reads as background
    would otherwise become holes. Only gaps fully enclosed by the garment are filled; worn crops keep
    theirs, since a gap between an arm and the body there is real background."""
    solid = np.asarray(mask) > 0
    count, labels = cv2.connectedComponents((~solid).astype(np.uint8), connectivity=4)
    border = np.unique(np.concatenate((labels[0], labels[-1], labels[:, 0], labels[:, -1])))
    holes = ~solid & ~np.isin(labels, border)
    return Image.fromarray(((solid | holes) * 255).astype(np.uint8))


def render(raw, attributes):
    with Image.open(BytesIO(raw)) as original:
        image = ImageOps.exif_transpose(original).convert('RGBA')
        image.thumbnail((600, 600))
    # The colour shortcut only for AI flat-lays, which are always on clean white. Real photos (직접 촬영하기,
    # worn crops) have floors, seams and shadows that it would keep as part of the garment.
    mask = contrast_mask(image) if attributes.get('_display_source') == 'ai_flatlay' else None
    if mask is None:
        mask = clothing_mask(image, attributes)
    if attributes.get('_display_source') != 'worn_crop':
        mask = fill_enclosed_holes(mask)
    if mask.size != image.size or mask.getbbox() is None:
        raise AnalysisError('IMAGE_TARGET_UNCLEAR', 422)
    bounds = mask.getbbox()
    occupancy = sum(mask.histogram()[1:]) / ((bounds[2] - bounds[0]) * (bounds[3] - bounds[1]))
    if occupancy < .35:
        raise AnalysisError('IMAGE_TARGET_UNCLEAR', 422)
    image.putalpha(mask)
    image = image.crop(bounds)
    image.thumbnail((448, 448), Image.Resampling.LANCZOS)
    output = BytesIO()
    image.save(output, 'PNG')
    return output.getvalue()


def hangable(garment):
    return garment.saved and garment.attributes.get('category') in HANGABLE


def source_key(garment, raw):
    """Changes whenever the photo, how it is masked, or the model or display version changes."""
    parts = [sha256(raw).hexdigest(), garment.image_source, str(garment.attributes.get('category')),
             str(garment.attributes.get('subcategory')), worn.MODEL_REVISION, DISPLAY_VERSION]
    return sha256('|'.join(parts).encode()).hexdigest()


def _stored(garment, key):
    if not garment.cutout or garment.cutout_key != key:
        return None
    try:
        with garment.cutout.open('rb') as stored:
            return stored.read()
    except FileNotFoundError:
        return None


def ensure_cutout(garment, wait=None):
    """The stored cutout of a saved hangable garment, made now if missing or stale. Raises AnalysisError
    when the photo cannot be cut out (the field then stays empty) and FileNotFoundError without a photo.
    `wait` bounds how long a request waits for another job on the same garment."""
    if not hangable(garment):
        raise AnalysisError('IMAGE_TARGET_UNCLEAR', 422)
    with garment.image.open('rb') as source:
        raw = source.read(MAX_IMAGE_BYTES + 1)
    if len(raw) > MAX_IMAGE_BYTES:
        raise AnalysisError('IMAGE_TOO_LARGE', 413)
    key = source_key(garment, raw)
    if (result := _stored(garment, key)) is not None:
        return result
    job = _jobs[uuid.UUID(str(garment.pk)).int % len(_jobs)]
    if not job.acquire(timeout=-1 if wait is None else wait):
        raise AnalysisError('SEGMENTATION_BUSY', 503)
    try:
        garment.refresh_from_db(fields=['cutout', 'cutout_key'])
        if (result := _stored(garment, key)) is not None:  # made by another job meanwhile
            return result
        result = render(raw, dict(garment.attributes, _display_source=garment.image_source))
        previous = garment.cutout.name
        garment.cutout.save(f'{garment.pk}-{key[:12]}.png', ContentFile(result), save=False)
        # update() leaves updated_at alone: making a cutout is not an edit of the garment.
        if not Garment.objects.filter(pk=garment.pk).update(cutout=garment.cutout.name, cutout_key=key):
            delete_unreferenced({garment.cutout.name})  # deleted while the cutout was being made
        else:
            garment.cutout_key = key
            if previous and previous != garment.cutout.name:
                delete_unreferenced({previous})
        return result
    finally:
        job.release()


def schedule_cutout(garment):
    """Make the cutout in the background once the save has committed, so saving does not wait for it."""
    if hangable(garment):
        garment_id = garment.pk
        transaction.on_commit(lambda: _pool.submit(_run, garment_id))


def _run(garment_id):
    close_old_connections()
    try:
        garment = Garment.objects.filter(pk=garment_id, saved=True).first()
        if garment is not None:
            ensure_cutout(garment)
    except AnalysisError as error:
        logger.info('No cutout for garment %s: %s', garment_id, error.code)
    except Exception:
        logger.exception('Could not make the cutout of garment %s', garment_id)
    finally:
        close_old_connections()

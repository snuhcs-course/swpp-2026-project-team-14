# AI-generated with Codex and Claude Code (Dongkun Moon), 2026-11-25, reviewed by Dongkun Moon
"""Owner-only derived PNGs. Reuses the installed segmenter; never generates or saves clothes."""
from collections import OrderedDict
from hashlib import sha256
from io import BytesIO
from threading import Lock, Semaphore

import cv2
import numpy as np
from django.conf import settings
from django.http import HttpResponse, JsonResponse
from django.shortcuts import get_object_or_404
from django.views.decorators.http import require_GET
from PIL import Image, ImageOps

from apps.users.auth import require_auth
from . import worn
from .analysis import AnalysisError, MAX_IMAGE_BYTES
from .models import Garment

_cache = OrderedDict()
_cache_bytes = 0
_cache_limit = 24 * 1024 * 1024
_lock = Lock()  # Bound CPU inference and coalesce simultaneous requests for the same source.
_jobs = [Lock() for _ in range(16)]
_inference = Semaphore(1)
DISPLAY_VERSION = 'flatlay-union-v2-448'


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
        target = (5 if attributes.get('subcategory') == 'skirt' else 6) if attributes['category'] == 'bottom' else 4
        keep = labels == target
    else:
        keep = np.isin(labels, (4, 5, 6, 7, 8, 17))
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


def render(raw, attributes):
    with Image.open(BytesIO(raw)) as original:
        image = ImageOps.exif_transpose(original).convert('RGBA')
        image.thumbnail((600, 600))
    mask = contrast_mask(image)
    if mask is None:
        mask = clothing_mask(image, attributes)
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


@require_GET
@require_auth
def image(request, item_id):
    global _cache_bytes
    garment = get_object_or_404(Garment, pk=item_id, user=request.user, saved=True)
    if garment.attributes.get('category') not in ('top', 'bottom', 'outerwear'):
        return JsonResponse({'error': 'IMAGE_TARGET_UNCLEAR'}, status=422)
    try:
        with garment.image.open('rb') as source:
            raw = source.read(MAX_IMAGE_BYTES + 1)
        if len(raw) > MAX_IMAGE_BYTES:
            raise AnalysisError('IMAGE_TOO_LARGE', 413)
        key = (str(request.user.pk), str(garment.pk), sha256(raw).digest(),
               garment.attributes.get('category'), garment.attributes.get('subcategory'),
               garment.image_source, worn.MODEL_REVISION, DISPLAY_VERSION)
        with _lock:
            result = _cache.get(key)
            if result is not None:
                _cache.move_to_end(key)
        job = _jobs[hash(key) % len(_jobs)]
        if result is None and not job.acquire(timeout=12):
            raise AnalysisError('SEGMENTATION_BUSY', 503)
        if result is None:
            try:
                with _lock:
                    result = _cache.get(key)
                if result is None:
                    result = render(raw, dict(garment.attributes, _display_source=garment.image_source))
                    with _lock:
                        while _cache and _cache_bytes + len(result) > _cache_limit:
                            _, removed = _cache.popitem(last=False)
                            _cache_bytes -= len(removed)
                        if len(result) <= _cache_limit:
                            _cache[key] = result
                            _cache_bytes += len(result)
            finally:
                job.release()
    except FileNotFoundError:
        return JsonResponse({'error': 'IMAGE_NOT_FOUND'}, status=404)
    except AnalysisError as error:
        return JsonResponse({'error': error.code}, status=error.status)
    response = HttpResponse(result, content_type='image/png')
    response['Cache-Control'] = 'private, no-store'
    response['Vary'] = 'Authorization'
    return response

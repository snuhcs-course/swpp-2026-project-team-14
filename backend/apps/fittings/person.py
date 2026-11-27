# AI-generated with Claude Code (Claude Opus 5.5), 2026-11-27, reviewed by Dongkun Moon
"""Is there a person in a fitting photo?

The image model dresses whoever it is given; with a garment photo or a screenshot it invents a stranger. So
before a paid generation the photo is checked with the SegFormer clothes model the wardrobe cutouts already
use. A person shows a head (hair or face) and some body: on 31 photos of people (front, side, gallery, the
bundled virtual models) the head covered at least 0.7 % of the picture and head plus arms and legs at least
2 %, while 40 garment photos, flat-lays and app screenshots showed at most 0.2 % head.
"""
import io
import logging

import numpy as np
from PIL import Image

from apps.wardrobe import cutouts, worn
from apps.wardrobe.analysis import AnalysisError

logger = logging.getLogger(__name__)

HEAD = (2, 11)            # hair, face
LIMBS = (12, 13, 14, 15)  # legs, arms
MIN_HEAD = .004
MIN_PERSON = .01


def labels(raw):
    from django.conf import settings
    image = Image.open(io.BytesIO(raw)).convert('RGB')
    tensor = np.asarray(image.resize((512, 512), Image.Resampling.BILINEAR), dtype=np.float32) / 255
    tensor = (tensor - np.array([.485, .456, .406], np.float32)) / np.array([.229, .224, .225], np.float32)
    session = worn.segmenter(str(settings.WORN_SEGMENTATION_MODEL))
    with cutouts._inference:
        logits = session.run(None, {session.get_inputs()[0].name: tensor.transpose(2, 0, 1)[None]})[0][0]
    return logits.argmax(axis=0)


def shows_person(found):
    head = np.isin(found, HEAD).mean()
    return bool(head >= MIN_HEAD and head + np.isin(found, LIMBS).mean() >= MIN_PERSON)


LEGS_AND_SHOES = (12, 13, 9, 10)
UPPER_CLOTHES, LOWER_CLOTHES = (4, 7), (5, 6, 7)
MIN_LEGS = .003


def _height(found, values):
    rows = np.where(np.isin(found, values).any(axis=1))[0]
    return (rows.max() - rows.min() + 1) / found.shape[0] if len(rows) else 0.


def shows_lower_body(found):
    """Legs or shoes in view, or bottoms clearly longer than the top (a photo cut just above the ankles).

    Full-body photos showed at least 0.5 % legs and shoes; the same photos cut at the chest, waist or knees
    showed none, and their bottoms were no longer than the top."""
    if np.isin(found, LEGS_AND_SHOES).mean() >= MIN_LEGS:
        return True
    upper = _height(found, UPPER_CLOTHES)
    return bool(upper and _height(found, LOWER_CLOTHES) >= upper * 1.1)


def check(raw, needs_lower_body):
    """None when the photo will do, otherwise the reason to refuse it ('PERSON_REQUIRED' or
    'FULL_BODY_REQUIRED' when bottoms are to be fitted on a photo cut above the legs). The photo is never
    refused when the check cannot run here (no model), so fitting is not blocked by it."""
    try:
        found = labels(raw)
    except AnalysisError:
        return None
    except Exception:
        logger.exception('Person check failed')
        return None
    if not shows_person(found):
        return 'PERSON_REQUIRED'
    if needs_lower_body and not shows_lower_body(found):
        return 'FULL_BODY_REQUIRED'
    return None

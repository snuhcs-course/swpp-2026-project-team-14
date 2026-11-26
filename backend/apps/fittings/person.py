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


def has_person(raw):
    """True or False; None when the check cannot run (no model here), so fitting is not blocked by it."""
    try:
        return shows_person(labels(raw))
    except AnalysisError:
        return None
    except Exception:
        logger.exception('Person check failed')
        return None

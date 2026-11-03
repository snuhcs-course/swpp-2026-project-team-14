"""Rough owner-photo/body estimates, deliberately separate from measured dimensions.

These intervals are tunable heuristic ranges, NOT validated measurement error or
statistical confidence intervals. Generated pixels are never an input. Only the
existing recommendation ease bands and actual owner profile measurements are used.
"""
import math
import json

from recommendation.fit import TOP_CHEST_EASE, OUTER_CHEST_EASE, BOTTOM_HIP_EASE, WAIST_EASE
from recommendation.labeling import label, options


SOURCE = 'body_photo_estimate'
METHOD = 'body_photo_v1'
LABEL_OPTIONS = options()


def nullable_y_pair():
    return {'type': ['array', 'null'], 'items': {'type': 'integer'}}


WEARING_SCHEMA = {
    # Keep provider constraints small: category-specific labels are supplied in
    # the prompt and checked by observation(), independently of garment detection.
    'type': 'object', 'additionalProperties': False,
    'properties': {
        'fit': {'type': ['string', 'null']}, 'length': {'type': ['string', 'null']},
        'visibility': {'type': 'string', 'enum': ['clear', 'partial', 'unclear']},
        'waist_visible': {'type': 'boolean'}, 'upright_front': {'type': 'boolean'},
        'person_y': nullable_y_pair(), 'garment_length_y': nullable_y_pair(),
    },
    'required': ['fit', 'length', 'visibility', 'waist_visible', 'upright_front', 'person_y', 'garment_length_y'],
}
WEARING_PROMPT = '''
This service assumes that the wearer is the account owner. Separately return wearing
observations of this ONE target from the ORIGINAL worn photo, never from generated
pixels. Do NOT return cm values. fit and length are rough visible worn labels, NOT
measurements; use null when unclear. The allowed labels differ by target category:
'''
WEARING_PROMPT += json.dumps({category: {field: LABEL_OPTIONS[category][field] for field in ('fit', 'length')}
                             for category in ('top', 'bottom', 'outerwear')}, ensure_ascii=False) + '''
visibility describes the visible target: clear, partial, or unclear. Do not use body
or silhouette details from other garments. waist_visible is true only when the target
bottom's waistband is identifiable. upright_front is true only for an upright, nearly
frontal person with little perspective foreshortening. person_y=[TOP_OF_HEAD_Y,
SOLE_Y] is normalized 0..1000 y coordinates in the original image. Return it ONLY
when the complete standing person including head and feet is visible; otherwise null.
garment_length_y=[START_Y,HEM_Y] uses the visible neck/shoulder-base to hem for tops/
outerwear (EXCLUDE hood height and sleeve length), or visible waistband to leg/skirt
hem for bottoms. Return null when either endpoint is hidden, legs are bent, the coat
is lifted, or projection makes that vertical span unreliable. Never reconstruct
hidden endpoints. Missing wearing observations must not prevent garment registration.
'''


def observation(raw, category):
    """Sanitize optional observations independently; bad suggestions do not block registration."""
    if not isinstance(raw, dict) or category not in LABEL_OPTIONS:
        return None
    allowed = LABEL_OPTIONS[category]
    visibility = raw.get('visibility')
    if visibility not in ('clear', 'partial'):
        return None
    result = {'visibility': visibility}
    for field in ('fit', 'length'):
        result[field] = raw.get(field) if raw.get(field) in allowed[field] else None
    for field in ('waist_visible', 'upright_front'):
        result[field] = raw.get(field) is True
    for field in ('person_y', 'garment_length_y'):
        pair = raw.get(field)
        result[field] = pair if (isinstance(pair, list) and len(pair) == 2 and
            all(type(value) is int and 0 <= value <= 1000 for value in pair) and pair[1] - pair[0] >= 20) else None
    return result


def profile_values(profile):
    """No default height, population averages, preferred fit or other-account data."""
    if not isinstance(profile, dict):
        return {}, set()
    values, low_quality = {}, set()
    height = profile.get('height_cm')
    if type(height) in (int, float) and math.isfinite(height) and 100 <= height <= 230:
        values['height'] = height
    measurements = profile.get('measurements')
    if isinstance(measurements, list):
        limits = {'chest': (50, 180), 'waist': (40, 180), 'hip': (50, 190)}
        for item in measurements:
            if not isinstance(item, dict):
                continue
            field, value = item.get('type'), item.get('value_cm')
            if isinstance(field, str) and field in limits and type(value) in (int, float) and math.isfinite(value):
                lo, hi = limits[field]
                if lo <= value <= hi:
                    values[field] = value
                    if item.get('confidence') == 'low':
                        low_quality.add(field)
    return values, low_quality


def suggestions(attributes, details, wearing, profile):
    category = attributes['category']
    labels = label(attributes, None, None, details)
    if labels is None:
        return None, None
    for field in ('fit', 'length', 'fit_basis', 'length_basis'):
        labels[field] = None
    seen = observation(wearing, category)
    if seen is None:
        return labels, None
    for field in ('fit', 'length'):
        labels[field] = seen[field]
        labels[field + '_basis'] = '본인 착용 사진에서 추정' if seen[field] else None
    body, low_quality = profile_values(profile)
    measurements, basis = {}, {}

    def add(field, lo, hi, used):
        lo, hi = max(1, math.floor(lo)), math.ceil(hi)
        measurements[field] = {'value': round((lo + hi) / 2), 'min': lo, 'max': hi}
        for key in used:
            basis[key] = body[key]

    fit = seen['fit']
    if category in ('top', 'outerwear'):
        band = (OUTER_CHEST_EASE if category == 'outerwear' else TOP_CHEST_EASE).get(fit)
        if band and 'chest' in body:
            padding = 4 if seen['visibility'] == 'partial' or 'chest' in low_quality else 2
            add('chest_width_half', (body['chest'] + band[0]) / 2 - padding,
                (body['chest'] + band[1]) / 2 + padding, ['chest'])
    elif category == 'bottom':
        band = BOTTOM_HIP_EASE.get(fit)
        if band and 'hip' in body:
            padding = 4 if seen['visibility'] == 'partial' or 'hip' in low_quality else 2
            add('hip_width_half', (body['hip'] + band[0]) / 2 - padding,
                (body['hip'] + band[1]) / 2 + padding, ['hip'])
        if fit and seen['waist_visible'] and 'waist' in body:
            # Waist position, fabric and profile uncertainty remain substantial.
            padding = 5 if 'waist' in low_quality else 3
            add('waist_width_half', (body['waist'] + WAIST_EASE[0]) / 2 - padding,
                (body['waist'] + WAIST_EASE[1]) / 2 + padding, ['waist'])
    person, garment = seen['person_y'], seen['garment_length_y']
    if seen['upright_front'] and person and garment and 'height' in body:
        if person[0] <= garment[0] < garment[1] <= person[1]:
            ratio = (garment[1] - garment[0]) / (person[1] - person[0])
            if .08 <= ratio <= .85:
                value = body['height'] * ratio
                margin = value * (.25 if seen['visibility'] == 'partial' else .15) + 3
                add('total_length', value - margin, value + margin, ['height'])
    if not measurements:
        return labels, None
    return labels, {'unit': 'cm', 'source': SOURCE, 'method': METHOD, 'quality': 'rough',
                    'assumption': 'photo_of_owner', 'category': category,
                    'subcategory': attributes.get('subcategory'), 'fit': seen['fit'], 'length': seen['length'],
                    'body_basis': basis, 'measurements': measurements}


def estimate_matches(estimate, attributes, labels):
    if not estimate:
        return True
    return (estimate.get('category') == attributes['category'] and
            estimate.get('subcategory') == attributes.get('subcategory') and
            all(estimate.get(field) == (labels or {}).get(field) for field in ('fit', 'length')))

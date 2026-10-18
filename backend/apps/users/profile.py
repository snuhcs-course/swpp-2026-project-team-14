# AI-generated with Codex, 2026-10-16
import math

from django.core.exceptions import ValidationError


MAX_FAVORITE_COLORS = 8
MEASUREMENT_TYPES = {
    'shoulder_width', 'sleeve_length', 'torso_length', 'rise', 'inseam', 'outseam',
    'neck', 'chest', 'underbust', 'waist', 'hip', 'armhole', 'bicep', 'wrist', 'thigh', 'calf',
}
BODY_FIELDS = {'height_cm', 'weight_kg', 'gender', 'measurements', 'clothing', 'reference'}
MEASUREMENT_FIELDS = {'type', 'value_cm', 'confidence', 'edited_by_user'}
MAX_MEASUREMENT_CM = 250


def _invalid():
    raise ValidationError({'body_profile': '체형 정보의 항목·형식·범위를 확인해주세요.'})


def _number(value, low, high):
    # bool is a subclass of int, but is not a measurement. Bound before converting huge ints.
    if type(value) not in (int, float) or not low <= value <= high or not math.isfinite(value):
        _invalid()


def validate_body_profile(profile):
    if profile is None:
        return
    if not isinstance(profile, dict) or set(profile) - BODY_FIELDS:
        _invalid()
    _number(profile.get('height_cm'), 100, 230)
    if profile.get('weight_kg') is not None:
        _number(profile['weight_kg'], 30, 200)
    if profile.get('gender', 'unspecified') not in ('female', 'male', 'unspecified'):
        _invalid()
    measurements = profile.get('measurements')
    if not isinstance(measurements, list) or len(measurements) > len(MEASUREMENT_TYPES):
        _invalid()
    seen = set()
    for measurement in measurements:
        if not isinstance(measurement, dict) or set(measurement) - MEASUREMENT_FIELDS:
            _invalid()
        kind = measurement.get('type')
        if not isinstance(kind, str) or kind not in MEASUREMENT_TYPES or kind in seen:
            _invalid()
        seen.add(kind)
        _number(measurement.get('value_cm'), 0.1, MAX_MEASUREMENT_CM)
        if 'confidence' in measurement and measurement['confidence'] not in ('high', 'medium', 'low'):
            _invalid()
        if 'edited_by_user' in measurement and type(measurement['edited_by_user']) is not bool:
            _invalid()
    if 'clothing' in profile:
        clothing = profile['clothing']
        if not isinstance(clothing, dict) or set(clothing) - {'top_loose', 'bottom_loose'} \
                or any(type(value) is not bool for value in clothing.values()):
            _invalid()
    reference = profile.get('reference')
    if reference is not None:
        if not isinstance(reference, dict) or set(reference) != {'body', 'height_cm', 'measurements'}:
            _invalid()
        if not isinstance(reference['body'], str) or not 1 <= len(reference['body']) <= 40:
            _invalid()
        _number(reference['height_cm'], 100, 230)
        values = reference['measurements']
        if not isinstance(values, dict) or set(values) - MEASUREMENT_TYPES:
            _invalid()
        for value in values.values():
            _number(value, 0.1, MAX_MEASUREMENT_CM)

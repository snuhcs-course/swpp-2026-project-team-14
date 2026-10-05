from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from typing import NamedTuple

from .schema import (
    ARRAY_LIMITS,
    COLOR_PALETTE,
    ENUMS,
    FIELD_LABELS,
    SUBCATEGORIES,
    validate_attributes,
)


class DimensionDefinition(NamedTuple):
    label: str
    method: str


TOP_DIMENSIONS = {
    'shoulder_width': DimensionDefinition(label='어깨너비', method='flat_shoulder_seam_to_seam'),
    'chest_width_half': DimensionDefinition(label='가슴 단면', method='flat_underarm_to_underarm'),
    'total_length': DimensionDefinition(label='총장', method='back_neck_to_hem'),
    'sleeve_length': DimensionDefinition(label='소매길이', method='shoulder_seam_to_cuff'),
    'hem_width_half': DimensionDefinition(label='밑단 단면', method='flat_body_hem'),
    'cuff_width_half': DimensionDefinition(label='소매끝', method='flat_sleeve_opening'),
    'armhole_straight': DimensionDefinition(label='암홀', method='armhole_top_to_underarm_straight'),
}
BOTTOM_DIMENSIONS = {
    'waist_width_half': DimensionDefinition(label='허리 단면', method='flat_waistband_relaxed'),
    'hip_width_half': DimensionDefinition(label='엉덩이 단면', method='flat_hip_max_width'),
    'thigh_width_half': DimensionDefinition(label='허벅지 단면', method='flat_thigh_at_crotch'),
    'rise_front': DimensionDefinition(label='앞밑위', method='front_waistband_along_rise_to_crotch'),
    'inseam': DimensionDefinition(label='인심', method='crotch_along_inner_seam_to_hem'),
    'total_length': DimensionDefinition(label='총장', method='waistband_along_side_to_hem'),
    'hem_opening': DimensionDefinition(label='한쪽 밑단', method='flat_single_leg_hem'),
}

SKIRT_DIMENSION_FIELDS = ('waist_width_half', 'hip_width_half', 'total_length')
MEASUREMENT_SOURCES = ('arcore_manual', 'arcore_assisted', 'user_measured', 'product_chart')
NOTES_MAX_LENGTH = 2000
REFERENCE_MAX_LENGTH = 500
MEASUREMENT_PRECISION = Decimal('0.01')


def dimension_fields(attributes):
    category = attributes['category']
    if category in ('top', 'outerwear'):
        return TOP_DIMENSIONS
    if category == 'bottom':
        if attributes['subcategory'] == 'skirt':
            return {
                key: definition
                for key, definition in BOTTOM_DIMENSIONS.items()
                if key in SKIRT_DIMENSION_FIELDS
            }
        return dict(BOTTOM_DIMENSIONS)
    return {}


def catalog():
    dimensions = {}
    for group, fields in (('top', TOP_DIMENSIONS), ('bottom', BOTTOM_DIMENSIONS)):
        dimensions[group] = {
            key: {'label': definition.label, 'method': definition.method}
            for key, definition in fields.items()
        }

    return {
        'enums': ENUMS,
        'labels': FIELD_LABELS,
        'subcategories': SUBCATEGORIES,
        'array_limits': ARRAY_LIMITS,
        'color_palette': COLOR_PALETTE,
        'dimensions': dimensions,
    }


def validate_record(data):
    if not isinstance(data, dict) or set(data) != {'attributes', 'dimensions', 'notes'}:
        raise ValueError('Invalid fields')

    attributes = validate_attributes(data['attributes'])
    notes = data['notes']
    if not isinstance(notes, str) or len(notes) > NOTES_MAX_LENGTH:
        raise ValueError('Invalid notes')
    dimensions = _validate_dimensions(data['dimensions'], attributes)

    return {'attributes': attributes, 'dimensions': dimensions, 'notes': notes}


def _validate_dimensions(raw, attributes):
    if raw is None:
        return None

    fields = dimension_fields(attributes)
    if not isinstance(raw, dict) or raw.get('unit') != 'cm' or set(raw) - {'unit', *fields}:
        raise ValueError('Invalid dimensions')

    dimensions = {'unit': 'cm'}
    for key, measurement in raw.items():
        if key == 'unit' or measurement is None:
            continue
        dimensions[key] = _validate_measurement(measurement, key, fields[key])

    return dimensions if len(dimensions) > 1 else None


def _validate_measurement(measurement, key, definition):
    if not isinstance(measurement, dict) or set(measurement) != {'value', 'source', 'method', 'reference'}:
        raise ValueError('Invalid measurement')

    value = _validate_measurement_value(measurement['value'])
    methods = {definition.method, 'unspecified'}
    if key == 'sleeve_length':
        methods.add('center_back_via_shoulder_to_cuff')
    if measurement['source'] not in MEASUREMENT_SOURCES or measurement['method'] not in methods:
        raise ValueError('Invalid source or method')

    reference = measurement['reference']
    if reference is not None:
        if not isinstance(reference, str) or len(reference) > REFERENCE_MAX_LENGTH:
            raise ValueError('Invalid reference')

    return dict(measurement, value=value)


def _validate_measurement_value(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError('Invalid number')
    try:
        number = Decimal(str(value)).quantize(MEASUREMENT_PRECISION, rounding=ROUND_HALF_UP)
    except InvalidOperation:
        raise ValueError('Invalid number') from None
    if not number.is_finite() or number <= 0:
        raise ValueError('Invalid number')
    return float(number)

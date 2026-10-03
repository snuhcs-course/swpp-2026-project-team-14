"""User-editable fields; these are never sent to Gemini."""
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP

from .schema import ARRAY_LIMITS, ENUMS, FIELD_LABELS, SUBCATEGORIES, validate_attributes


TOP_DIMENSIONS = {
    'shoulder_width': ('어깨너비', 'flat_shoulder_seam_to_seam'),
    'chest_width_half': ('가슴 단면', 'flat_underarm_to_underarm'),
    'total_length': ('총장', 'back_neck_to_hem'),
    'sleeve_length': ('소매길이', 'shoulder_seam_to_cuff'),
    'hem_width_half': ('밑단 단면', 'flat_body_hem'),
    'cuff_width_half': ('소매끝', 'flat_sleeve_opening'),
    'armhole_straight': ('암홀', 'armhole_top_to_underarm_straight'),
}
BOTTOM_DIMENSIONS = {
    'waist_width_half': ('허리 단면', 'flat_waistband_relaxed'),
    'hip_width_half': ('엉덩이 단면', 'flat_hip_max_width'),
    'thigh_width_half': ('허벅지 단면', 'flat_thigh_at_crotch'),
    'rise_front': ('앞밑위', 'front_waistband_along_rise_to_crotch'),
    'inseam': ('인심', 'crotch_along_inner_seam_to_hem'),
    'total_length': ('총장', 'waistband_along_side_to_hem'),
    'hem_opening': ('한쪽 밑단', 'flat_single_leg_hem'),
}


def dimension_fields(attributes):
    category = attributes['category']
    if category in ('top', 'outerwear'):
        return {k: v for k, v in TOP_DIMENSIONS.items()
                if attributes['sleeve_length'] != 'sleeveless' or k not in ('sleeve_length', 'cuff_width_half')}
    if category == 'bottom':
        return {k: v for k, v in BOTTOM_DIMENSIONS.items()
                if attributes['subcategory'] != 'skirt' or k in ('waist_width_half', 'hip_width_half', 'total_length')}
    return {}


def catalog():
    return {'enums': ENUMS, 'labels': FIELD_LABELS, 'subcategories': SUBCATEGORIES,
            'array_limits': ARRAY_LIMITS,
            'dimensions': {group: {key: {'label': value[0], 'method': value[1]} for key, value in fields.items()}
                           for group, fields in (('top', TOP_DIMENSIONS), ('bottom', BOTTOM_DIMENSIONS))}}


def validate_record(data):
    if not isinstance(data, dict) or set(data) != {'attributes', 'dimensions', 'notes'}:
        raise ValueError('Invalid fields')
    attributes = validate_attributes(data['attributes'])
    notes = data['notes']
    if not isinstance(notes, str) or len(notes) > 2000:
        raise ValueError('Invalid notes')
    raw = data['dimensions']
    dimensions = None
    if raw is not None:
        fields = dimension_fields(attributes)
        if not isinstance(raw, dict) or raw.get('unit') != 'cm' or set(raw) - {'unit', *fields}:
            raise ValueError('Invalid dimensions')
        dimensions = {'unit': 'cm'}
        for key, item in raw.items():
            if key == 'unit':
                continue
            if item is None:
                continue
            if not isinstance(item, dict) or set(item) != {'value', 'source', 'method', 'reference'}:
                raise ValueError('Invalid measurement')
            value = item['value']
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                raise ValueError('Invalid number')
            try:
                number = Decimal(str(value)).quantize(Decimal('0.01'), rounding=ROUND_HALF_UP)
            except InvalidOperation:
                raise ValueError('Invalid number') from None
            if not number.is_finite() or number <= 0:
                raise ValueError('Invalid number')
            methods = {fields[key][1], 'unspecified'}
            if key == 'sleeve_length':
                methods.add('center_back_via_shoulder_to_cuff')
            if item['source'] not in ('arcore_manual', 'arcore_assisted', 'user_measured', 'product_chart') or item['method'] not in methods:
                raise ValueError('Invalid source or method')
            if item['reference'] is not None and (not isinstance(item['reference'], str) or len(item['reference']) > 500):
                raise ValueError('Invalid reference')
            dimensions[key] = dict(item, value=float(number))
        if len(dimensions) == 1:
            dimensions = None
    return {'attributes': attributes, 'dimensions': dimensions, 'notes': notes}

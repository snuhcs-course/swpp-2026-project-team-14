# AI-generated with Codex, 2026-09-29, reviewed by Hyeon U Jeong; recommendation labels added with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
import re
from typing import TypedDict, cast

from recommendation.vocab import COLLARS, DETAILS, MATERIALS, NECKLINES, PRINTS


class WardrobeAttributes(TypedDict):
    name: str
    category: str
    subcategory: str | None
    colors: list[str]
    styles: list[str]
    fit_type: str | None
    leg_shape: str | None
    rise_type: str | None
    skirt_shape: str | None


class AiAttributes(TypedDict):
    name: str
    category: str
    colors: list[str]
    subcategory: str | None  # suggestion the user confirms
    styles: list[str]  # suggestion the user confirms


class AiDetails(TypedDict):
    """Visible design details in the K-Fashion vocabulary, used only for the recommendation labels."""
    print: str | None
    neckline: str | None
    collar: str | None
    design_details: list[str]
    materials: list[str]  # only when clearly visible (denim, knit, leather, padding, ...)


SUBCATEGORIES = {
    'top': {
        'tshirt': '티셔츠',
        'shirt': '셔츠',
        'blouse': '블라우스',
        'sweatshirt': '맨투맨',
        'hoodie': '후드티',
        'knitwear': '니트웨어',
        'other': '기타',
    },
    'bottom': {
        'jeans': '청바지',
        'slacks': '슬랙스',
        'pants': '캐주얼바지',
        'active_pants': '트레이닝바지',
        'shorts': '반바지',
        'skirt': '스커트',
        'other': '기타',
    },
    'outerwear': {
        'cardigan': '카디건',
        'hooded_jacket': '후드집업',
        'fleece': '플리스',
        'jacket': '재킷',
        'coat': '코트',
        'puffer': '패딩',
        'vest': '조끼',
        'other': '기타',
    },
    'shoes': {
        'sneakers': '스니커즈',
        'loafers': '로퍼',
        'boots': '부츠',
        'sandals': '샌들',
        'dress_shoes': '구두',
        'flats': '플랫',
        'heels': '힐',
        'other': '기타',
    },
}
COLOR_PALETTE = {
    '#202020': '검정',
    '#FFFFFF': '흰색',
    '#808080': '회색',
    '#FFFFF0': '아이보리',
    '#D6BE9A': '베이지',
    '#795548': '갈색',
    '#24344B': '네이비',
    '#3975C6': '파랑',
    '#4F7952': '초록',
    '#7B8052': '카키',
    '#C83C3C': '빨강',
    '#E88A3D': '주황',
    '#E8C547': '노랑',
    '#E8A0B0': '분홍',
    '#9165AD': '보라',
    '#C0C0C0': '은색',
    '#C9A447': '금색',
}
HEX_COLOR = re.compile(r'#[0-9A-Fa-f]{6}')
ENUMS = {
    'category': {
        'top': '상의',
        'bottom': '하의',
        'outerwear': '아우터',
        'shoes': '신발',
    },
    'subcategory': {
        key: label
        for group in SUBCATEGORIES.values()
        for key, label in group.items()
    },
    'styles': {
        'minimal': '미니멀',
        'casual': '캐주얼',
        'street': '스트릿',
        'classic': '클래식',
        'sporty': '스포티',
        'formal': '포멀',
        'workwear': '워크웨어',
    },
    'fit_type': {
        'skinny': '스키니',
        'slim': '슬림',
        'regular': '레귤러',
        'loose': '루즈',
        'oversized': '오버사이즈',
    },
    'leg_shape': {
        'skinny': '밀착',
        'slim': '슬림',
        'straight': '일자',
        'tapered': '테이퍼드',
        'semi_wide': '세미와이드',
        'wide': '와이드',
        'bootcut': '부츠컷',
        'flared': '플레어',
        'balloon': '벌룬',
    },
    'rise_type': {
        'low': '로우라이즈',
        'mid': '미드라이즈',
        'high': '하이라이즈',
    },
    'skirt_shape': {
        'straight': '일자',
        'a_line': 'A라인',
        'flared': '플레어',
        'pleated': '플리츠',
        'pencil': '펜슬',
        'other': '기타',
    },
}
FIELD_LABELS = {
    'name': '이름',
    'category': '분류',
    'subcategory': '종류',
    'colors': '색상',
    'styles': '스타일',
    'fit_type': '핏',
    'leg_shape': '바지형태',
    'rise_type': '허리선',
    'skirt_shape': '스커트형태',
}
ARRAY_LIMITS = {'colors': 5, 'styles': 3}

NAME_MAX_LENGTH = 80
AI_COLOR_MIN_COUNT = 1
AI_COLOR_MAX_COUNT = 2
AI_FIELDS = tuple(AiAttributes.__annotations__)
AI_REQUIRED_FIELDS = frozenset({'name', 'category', 'colors'})
AI_STYLE_MAX_COUNT = 2
DETAIL_CHOICES = {'print': PRINTS, 'neckline': NECKLINES, 'collar': COLLARS}
DETAIL_LISTS = {'design_details': (DETAILS, 3), 'materials': (MATERIALS, 2)}
ATTRIBUTE_FIELDS = frozenset(WardrobeAttributes.__annotations__)
BOTTOM_FIELDS = ('leg_shape', 'rise_type', 'skirt_shape')
PANTS_SUBCATEGORIES = ('jeans', 'slacks', 'pants', 'active_pants', 'shorts')

SCHEMA = {
    'type': 'object',
    'additionalProperties': False,
    'properties': {
        'image_status': {
            'type': 'string',
            'enum': ['single', 'no_garment', 'multiple', 'unsupported', 'unclear'],
        },
        'attributes': {
            'type': ['object', 'null'],
            'additionalProperties': False,
            'properties': {
                'name': {
                    'type': 'string',
                    'minLength': 1,
                    'maxLength': NAME_MAX_LENGTH,
                },
                'category': {
                    'type': 'string',
                    'enum': list(SUBCATEGORIES),
                },
                'colors': {
                    'type': 'array',
                    'items': {'type': 'string', 'enum': list(COLOR_PALETTE)},
                    'minItems': AI_COLOR_MIN_COUNT,
                    'maxItems': AI_COLOR_MAX_COUNT,
                },
                'subcategory': {'anyOf': [
                    {'type': 'string', 'enum': list(dict.fromkeys(code for group in SUBCATEGORIES.values() for code in group))},
                    {'type': 'null'},
                ]},
                'styles': {
                    'type': 'array',
                    'items': {'type': 'string', 'enum': list(ENUMS['styles'])},
                    'maxItems': AI_STYLE_MAX_COUNT,
                },
            },
            'required': list(AI_FIELDS),
        },
        'details': {
            'type': ['object', 'null'],
            'additionalProperties': False,
            'properties': {
                **{field: {'anyOf': [{'type': 'string', 'enum': list(choices)}, {'type': 'null'}]}
                   for field, choices in DETAIL_CHOICES.items()},
                **{field: {'type': 'array', 'items': {'type': 'string', 'enum': list(choices)}, 'maxItems': limit}
                   for field, (choices, limit) in DETAIL_LISTS.items()},
            },
            'required': [*DETAIL_CHOICES, *DETAIL_LISTS],
        },
    },
    'required': ['image_status', 'attributes', 'details'],
}


def validate_ai_details(data: object, category: str) -> AiDetails | None:
    """Details are optional hints: anything malformed is dropped instead of failing the paid analysis."""
    if category == 'shoes' or not isinstance(data, dict):
        return None
    details = {field: data.get(field) if data.get(field) in choices else None
               for field, choices in DETAIL_CHOICES.items()}
    for field, (choices, limit) in DETAIL_LISTS.items():
        values = data.get(field) if isinstance(data.get(field), list) else []
        details[field] = list(dict.fromkeys(v for v in values if v in choices))[:limit]
    if category == 'bottom':
        details.update(neckline=None, collar=None)
    return cast(AiDetails, details) if any(details.values()) else None


def validate_ai_attributes(data: object) -> WardrobeAttributes:
    # name, category and colors are required; the subcategory and style suggestions are dropped when invalid
    if not isinstance(data, dict) or not AI_REQUIRED_FIELDS <= set(data) or set(data) - set(AI_FIELDS):
        raise ValueError('Unexpected AI fields')

    colors = data['colors']
    if not isinstance(colors, list) or not AI_COLOR_MIN_COUNT <= len(colors) <= AI_COLOR_MAX_COUNT:
        raise ValueError('Invalid AI colors')
    if any(not isinstance(color, str) or color not in COLOR_PALETTE for color in colors):
        raise ValueError('Invalid AI colors')

    category = data['category'] if isinstance(data['category'], str) else ''
    subcategory = data.get('subcategory')
    styles = data.get('styles') if isinstance(data.get('styles'), list) else []
    attributes = {
        **data,
        'subcategory': subcategory if isinstance(subcategory, str) and subcategory in SUBCATEGORIES.get(category, {}) else None,
        'styles': list(dict.fromkeys(s for s in styles if isinstance(s, str) and s in ENUMS['styles']))[:AI_STYLE_MAX_COUNT],
        'fit_type': None,
        'leg_shape': None,
        'rise_type': None,
        'skirt_shape': None,
    }
    return validate_attributes(attributes)


def validate_attributes(data: object) -> WardrobeAttributes:
    if not isinstance(data, dict) or set(data) != ATTRIBUTE_FIELDS:
        raise ValueError('Unexpected fields')

    name = _validate_name(data['name'])
    colors = _validate_colors(data['colors'])
    _validate_choices(data)
    _validate_category_fields(data)

    return cast(WardrobeAttributes, dict(data, name=name, colors=colors))


def _validate_name(value: object) -> str:
    if not isinstance(value, str) or not 1 <= len(value.strip()) <= NAME_MAX_LENGTH:
        raise ValueError('Invalid name')
    return value.strip()


def _validate_colors(value: object) -> list[str]:
    if not isinstance(value, list) or len(value) > ARRAY_LIMITS['colors']:
        raise ValueError('Invalid colors')
    if any(not isinstance(color, str) or not HEX_COLOR.fullmatch(color) for color in value):
        raise ValueError('Invalid colors')

    colors = [color.upper() for color in value]
    if len(set(colors)) != len(colors):
        raise ValueError('Duplicate colors')
    return colors


def _validate_choices(data: dict) -> None:
    for field, choices in ENUMS.items():
        value = data[field]
        if field in ARRAY_LIMITS:
            _validate_choice_list(value, choices, ARRAY_LIMITS[field])
        elif value is not None and (not isinstance(value, str) or value not in choices):
            raise ValueError('Invalid enum')


def _validate_choice_list(value: object, choices: dict[str, str], limit: int) -> None:
    if not isinstance(value, list) or len(value) > limit:
        raise ValueError('Invalid list')
    if any(not isinstance(item, str) or item not in choices for item in value):
        raise ValueError('Invalid list')
    if len(set(value)) != len(value):
        raise ValueError('Invalid list')


def _validate_category_fields(data: dict) -> None:
    category = data['category']
    subcategory = data['subcategory']
    if category not in SUBCATEGORIES:
        raise ValueError('Invalid category combination')
    if subcategory is not None and subcategory not in SUBCATEGORIES[category]:
        raise ValueError('Invalid category combination')

    excluded = _excluded_fields(category, subcategory)
    if any(data[field] is not None for field in excluded):
        raise ValueError('Inapplicable attribute')


def _excluded_fields(category: str, subcategory: str | None) -> tuple[str, ...]:
    if category in ('top', 'outerwear'):
        return BOTTOM_FIELDS
    if category == 'shoes':
        return (*BOTTOM_FIELDS, 'fit_type')
    if subcategory == 'skirt':
        return ('leg_shape',)
    if subcategory in PANTS_SUBCATEGORIES:
        return ('skirt_shape',)
    return ('leg_shape', 'skirt_shape')

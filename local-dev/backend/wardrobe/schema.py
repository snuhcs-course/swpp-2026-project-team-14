"""Visual-only output contract corresponding to wardrobe-spec.md v0.3."""


def options(pairs):
    return dict(pair.split(':', 1) for pair in pairs.split())


SUBCATEGORIES = {
    'top': options('tshirt:티셔츠 shirt:셔츠 blouse:블라우스 sweatshirt:맨투맨 hoodie:후드티 knitwear:니트웨어 other:기타'),
    'bottom': options('jeans:청바지 slacks:슬랙스 pants:캐주얼바지 active_pants:트레이닝바지 shorts:반바지 skirt:스커트 other:기타'),
    'outerwear': options('cardigan:카디건 hooded_jacket:후드집업 fleece:플리스 jacket:재킷 coat:코트 puffer:패딩 vest:조끼 other:기타'),
    'shoes': options('sneakers:스니커즈 loafers:로퍼 boots:부츠 sandals:샌들 dress_shoes:구두 flats:플랫 heels:힐 other:기타'),
}
ENUMS = {
    'category': options('top:상의 bottom:하의 outerwear:아우터 shoes:신발'),
    'subcategory': {key: label for group in SUBCATEGORIES.values() for key, label in group.items()},
    'colors': options('black:검정 white:흰색 gray:회색 ivory:아이보리 beige:베이지 brown:갈색 navy:네이비 blue:파랑 green:초록 khaki:카키 red:빨강 orange:주황 yellow:노랑 pink:분홍 purple:보라 silver:은색 gold:금색 other:기타'),
    'pattern': options('solid:무지 stripe:줄무늬 check:체크 dot:도트 graphic:그래픽 floral:꽃무늬 animal:동물무늬 camouflage:위장무늬 color_block:배색 other:기타'),
    'styles': options('minimal:미니멀 casual:캐주얼 street:스트릿 lovely:러블리 classic:클래식 sporty:스포티 formal:포멀 workwear:워크웨어'),
    'formality': options('relaxed:편한차림 casual:일상캐주얼 smart_casual:단정한캐주얼 formal:격식있는차림'),
    'fit_type': options('skinny:스키니 slim:슬림 regular:레귤러 loose:루즈 oversized:오버사이즈'),
    'length': options('cropped:크롭 semi_cropped:세미크롭 regular:기본 long:긴기장 short:짧음 knee:무릎길이 full:긴기장 mini:미니 midi:미디 maxi:맥시'),
    'sleeve_length': options('sleeveless:민소매 short:반팔 elbow:팔꿈치길이 three_quarter:7부 long:긴팔'),
    'neckline': options('crew:라운드넥 v_neck:브이넥 scoop:깊은둥근넥 square:스퀘어넥 boat:보트넥 henley:헨리넥 polo:폴로 shirt_collar:셔츠칼라 open_collar:오픈칼라 band_collar:밴드칼라 turtleneck:터틀넥 mock_neck:반목 hooded:후드 other:기타'),
    'shoulder_construction': options('set_in:일반어깨선 drop_shoulder:드롭숄더 raglan:래글런 dolman:가오리형 other:기타'),
    'leg_shape': options('skinny:밀착 slim:슬림 straight:일자 tapered:테이퍼드 semi_wide:세미와이드 wide:와이드 bootcut:부츠컷 flared:플레어 balloon:벌룬'),
    'rise_type': options('low:로우라이즈 mid:미드라이즈 high:하이라이즈'),
    'skirt_shape': options('straight:일자 a_line:A라인 flared:플레어 pleated:플리츠 pencil:펜슬 other:기타'),
    'closure': options('pullover:풀오버 button:단추 zip:전체지퍼 half_zip:부분지퍼 wrap:랩 open_front:앞트임 other:기타'),
    'details': options('cargo_pockets:카고포켓 ruffle:러플 pleats:주름 cable_knit:꽈배기 side_stripe:옆선배색 cuffed_hem:조인밑단 drawstring:조임끈 elastic_waist:허리밴딩'),
}
FIELD_LABELS = options('name:이름 category:분류 subcategory:종류 colors:색상 pattern:패턴 styles:스타일 formality:격식 fit_type:핏 length:기장 sleeve_length:소매 neckline:넥라인 shoulder_construction:어깨 leg_shape:바지형태 rise_type:허리선 skirt_shape:스커트형태 closure:여밈 details:디테일')
ARRAY_LIMITS = {'colors': 3, 'styles': 3, 'details': 8}
PROPERTIES = {'name': {'type': 'string', 'minLength': 1, 'maxLength': 80}}
for field, values in ENUMS.items():
    if field in ARRAY_LIMITS:
        PROPERTIES[field] = {'type': 'array', 'items': {'type': 'string', 'enum': list(values)},
                             'maxItems': ARRAY_LIMITS[field]}
    else:
        PROPERTIES[field] = {'type': ['string', 'null'], 'enum': [*values, None]}
PROPERTIES['category'] = {'type': 'string', 'enum': list(SUBCATEGORIES)}
SCHEMA = {
    'type': 'object', 'additionalProperties': False,
    'properties': {
        'image_status': {'type': 'string', 'enum': ['single', 'no_garment', 'multiple', 'unsupported', 'unclear']},
        'attributes': {'type': ['object', 'null'], 'additionalProperties': False,
                       'properties': PROPERTIES, 'required': list(PROPERTIES)},
    }, 'required': ['image_status', 'attributes'],
}


def validate_attributes(data):
    if not isinstance(data, dict) or set(data) != set(PROPERTIES):
        raise ValueError('Unexpected fields')
    if not isinstance(data['name'], str) or not 1 <= len(data['name'].strip()) <= 80:
        raise ValueError('Invalid name')
    for field, choices in ENUMS.items():
        value = data[field]
        if field in ARRAY_LIMITS:
            if (not isinstance(value, list) or len(value) > ARRAY_LIMITS[field]
                    or any(not isinstance(v, str) or v not in choices for v in value)
                    or len(set(value)) != len(value)):
                raise ValueError('Invalid list')
        elif value is not None and (not isinstance(value, str) or value not in choices):
            raise ValueError('Invalid enum')
    category, subcategory = data['category'], data['subcategory']
    if category not in SUBCATEGORIES or subcategory is not None and subcategory not in SUBCATEGORIES[category]:
        raise ValueError('Invalid category combination')
    top_fields = ['sleeve_length', 'neckline', 'shoulder_construction']
    bottom_fields = ['leg_shape', 'rise_type', 'skirt_shape']
    excluded = []
    allowed_lengths = []
    if category in ('top', 'outerwear'):
        excluded = bottom_fields
        allowed_lengths = ['cropped', 'semi_cropped', 'regular', 'long']
    elif category == 'bottom':
        excluded = top_fields
        if subcategory == 'skirt':
            excluded += ['leg_shape']
            allowed_lengths = ['mini', 'knee', 'midi', 'maxi']
        elif subcategory in ('jeans', 'slacks', 'pants', 'active_pants', 'shorts'):
            excluded += ['skirt_shape']
            allowed_lengths = ['short', 'knee', 'cropped', 'full']
        else:
            excluded += ['leg_shape', 'skirt_shape']
    else:
        excluded = top_fields + bottom_fields + ['fit_type', 'closure']
        if data['details']:
            raise ValueError('Shoe details not supported')
    if any(data[field] is not None for field in excluded):
        raise ValueError('Inapplicable attribute')
    if data['length'] is not None and data['length'] not in allowed_lengths:
        raise ValueError('Inapplicable length')
    return dict(data, name=data['name'].strip())


def display_attributes(attributes):
    rows = []
    for field, label in FIELD_LABELS.items():
        value = attributes[field]
        if value is None or value == []:
            continue
        text = value if field == 'name' else ', '.join(ENUMS[field][v] for v in value) if isinstance(value, list) else ENUMS[field][value]
        rows.append({'label': label, 'value': text})
    return rows

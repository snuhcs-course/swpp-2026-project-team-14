"""Prepare files for Django testserver and PinnedOutfitLiveTest; never connect to a database."""
import argparse
from datetime import timedelta
import json
import os
from pathlib import Path
import shutil
import sys
import uuid


GARMENTS = (
    ('white-shirt', '화이트 옥스퍼드 셔츠', 'top', 'shirt', '#F5F5F5', '화이트', '셔츠', []),
    ('beige-knit', '베이지 케이블 니트', 'top', 'knitwear', '#D6BE9A', '베이지', '니트웨어', ['니트']),
    ('black-slacks', '블랙 슬랙스', 'bottom', 'slacks', '#202020', '블랙', '팬츠', []),
    ('blue-jeans', '블루 데님', 'bottom', 'jeans', '#3975C6', '블루', '청바지', ['데님']),
    ('khaki-chinos', '카키 치노', 'bottom', 'pants', '#7A8061', '카키', '팬츠', []),
    ('brown-coat', '카멜 울 코트', 'outerwear', 'coat', '#A87546', '브라운', '코트', ['울/캐시미어']),
    ('navy-blazer', '네이비 블레이저', 'outerwear', 'jacket', '#1A2843', '네이비', '재킷', ['울/캐시미어']),
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--images', required=True, type=Path, help='Directory with the seven named transparent PNGs')
    parser.add_argument('--output', required=True, type=Path, help='Directory for assets, fixture.json and test-account.json')
    args = parser.parse_args()
    images, output = args.images.resolve(strict=True), args.output.resolve()
    assets = output / 'assets'
    assets.mkdir(parents=True, exist_ok=True)
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    os.environ['DJANGO_SETTINGS_MODULE'] = 'config.test_settings'
    os.environ['WARDROBE_MEDIA_ROOT'] = str(assets)
    import django
    django.setup()
    from django.core import serializers
    from django.utils import timezone
    from PIL import Image
    from apps.users.models import User
    from apps.wardrobe.analysis import MAX_IMAGE_BYTES
    from apps.wardrobe.cutouts import source_key
    from apps.wardrobe.editor import validate_record
    from apps.wardrobe.models import Garment
    from apps.usage.models import NoSeen, RatingTask

    user = User(id=uuid.uuid5(uuid.NAMESPACE_URL, 'stylemate-pinned-live-20261127'),
                username='pin_live_test', preferred_fits=['regular'], preferred_styles=['minimal'])
    user.set_password('Stylemate-Live-2026!')
    user.created_at = user.updated_at = timezone.now()
    objects, details = [user], []
    for index, (asset, name, category, subcategory, color, color_name, kf_category, materials) in enumerate(GARMENTS):
        filename = asset + '.png'
        source, target = images / filename, assets / filename
        if source.stat().st_size > MAX_IMAGE_BYTES:
            raise ValueError(f'{filename} exceeds the photo API size limit')
        with Image.open(source) as image:
            if image.mode != 'RGBA' or image.getextrema()[3] != (0, 255):
                raise ValueError(f'{filename} must be a transparent PNG')
        if source != target:
            shutil.copy2(source, target)
        length = '발목' if category == 'bottom' else '노멀' if category == 'top' else '하프' if subcategory == 'coat' else '노말'
        record = validate_record({
            'attributes': {'name': name, 'category': category, 'subcategory': subcategory, 'colors': [color],
                           'styles': ['minimal'], 'fit_type': None, 'leg_shape': None, 'rise_type': None, 'skirt_shape': None},
            'dimensions': None, 'notes': 'Synthetic photograph for an isolated integration-test fixture.',
            'labels': {'fit': '와이드' if category == 'bottom' else '루즈', 'length': length,
                       'sleeve': None if category == 'bottom' else '긴팔', 'materials': materials, 'prints': ['무지'],
                       'kf_category': kf_category, 'kf_colors': [color_name], 'season': '간절기',
                       'too_small': False, 'too_small_reason': None, 'confirmed': True},
        })
        garment = Garment(id=uuid.uuid5(uuid.NAMESPACE_URL, 'stylemate-pinned-live-' + asset), user=user,
                          image=filename, cutout=filename, image_source='garment_photo', saved=True, **record)
        garment.created_at = garment.updated_at = timezone.now() + timedelta(seconds=index)
        garment.cutout_key = source_key(garment, target.read_bytes())
        objects.append(garment)
        details.append({'id': str(garment.pk), 'name': name, 'category': category, 'asset': filename})
    objects.extend(NoSeen(user=user, task=task) for task in RatingTask.values)
    (output / 'fixture.json').write_text(serializers.serialize('json', objects, indent=2), encoding='utf-8')
    (output / 'test-account.json').write_text(json.dumps({'id': str(user.pk), 'username': user.username,
        'password': 'Stylemate-Live-2026!', 'garments': details}, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'Prepared {len(details)} schema-validated garment fixtures in {output}')


if __name__ == '__main__':
    main()

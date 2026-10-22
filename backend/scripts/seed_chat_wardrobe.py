"""Add fictional clothes to the local wardrobe: python scripts/seed_chat_wardrobe.py.

Stable IDs make repeat runs safe: existing records are left alone. Thumbnails are
simple drawn placeholders; dimensions are fictional and are not body measurements.
"""

import os
import sys
from io import BytesIO
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'config.settings')

import django

django.setup()

from django.conf import settings
from django.core.files.base import ContentFile
from django.db import transaction
from PIL import Image, ImageDraw

from apps.wardrobe.editor import dimension_fields, validate_record
from apps.wardrobe.models import Garment
from apps.outfits.models import Outfit
from apps.users.models import User


# slug, name, category, subcategory, colour, styles, fit, sleeve/leg length
SAMPLES = [
    ('white-tee', '샘플 · 화이트 반팔 티셔츠', 'top', 'tshirt', '#FFFFFF', ['minimal', 'casual'], 'regular', 23),
    ('black-tee', '샘플 · 블랙 반팔 티셔츠', 'top', 'tshirt', '#202020', ['minimal', 'casual'], 'loose', 25),
    ('blue-shirt', '샘플 · 블루 셔츠', 'top', 'shirt', '#3975C6', ['classic', 'formal'], 'regular', 61),
    ('ivory-shirt', '샘플 · 아이보리 셔츠', 'top', 'shirt', '#FFFFF0', ['minimal', 'formal'], 'regular', 60),
    ('beige-knit', '샘플 · 베이지 니트', 'top', 'knitwear', '#D6BE9A', ['minimal', 'classic'], 'loose', 60),
    ('pink-knit', '샘플 · 핑크 니트', 'top', 'knitwear', '#E8A0B0', ['casual'], 'regular', 59),
    ('gray-hoodie', '샘플 · 그레이 후드티', 'top', 'hoodie', '#808080', ['street', 'casual'], 'oversized', 62),
    ('black-slacks', '샘플 · 블랙 슬랙스', 'bottom', 'slacks', '#202020', ['minimal', 'formal'], 'regular', 76),
    ('blue-jeans', '샘플 · 블루 와이드 청바지', 'bottom', 'jeans', '#3975C6', ['casual', 'street'], 'loose', 77),
    ('beige-pants', '샘플 · 베이지 면바지', 'bottom', 'pants', '#D6BE9A', ['minimal', 'casual'], 'regular', 75),
    ('navy-shorts', '샘플 · 네이비 반바지', 'bottom', 'shorts', '#24344B', ['casual', 'sporty'], 'loose', 24),
    ('navy-jacket', '샘플 · 네이비 재킷', 'outerwear', 'jacket', '#24344B', ['classic', 'formal'], 'regular', 62),
    ('brown-coat', '샘플 · 브라운 코트', 'outerwear', 'coat', '#795548', ['classic', 'minimal'], 'loose', 63),
    ('black-puffer', '샘플 · 블랙 패딩', 'outerwear', 'puffer', '#202020', ['casual', 'sporty'], 'oversized', 63),
]


def thumbnail(category, subcategory, color):
    image = Image.new('RGB', (480, 480), '#F4F2EF')
    draw = ImageDraw.Draw(image)
    if category == 'bottom':
        hem = 300 if subcategory == 'shorts' else 410
        points = [(155, 85), (325, 85), (350, hem), (260, hem),
                  (240, 200), (220, hem), (130, hem)]
    else:
        hem = 420 if subcategory == 'coat' else 345
        sleeve = 210 if subcategory == 'tshirt' else 315
        points = [(185, 95), (150, 110), (65, sleeve - 25), (120, sleeve),
                  (160, 205), (160, hem), (320, hem), (320, 205),
                  (360, sleeve), (415, sleeve - 25), (330, 110), (295, 95)]
    draw.polygon(points, fill=color, outline='#777777', width=3)
    if category == 'bottom':
        draw.line([(157, 105), (323, 105)], fill='#777777', width=3)
    else:
        draw.arc((185, 65, 295, 135), 0, 180, fill='#777777', width=4)
        if category == 'outerwear' or subcategory == 'shirt':
            draw.line([(240, 135), (240, hem)], fill='#777777', width=3)
    draw.text((20, 450), 'SAMPLE / ' + subcategory.upper(), fill='#555555')
    output = BytesIO()
    image.save(output, format='PNG')
    return output.getvalue()


def main():
    database = settings.DATABASES.get('default', {})
    if database.get('HOST') not in ('127.0.0.1', 'localhost'):
        raise SystemExit('This script is for a local database only.')
    username = 'samplechat'
    password = 'samplechat2026'
    user = User.objects.filter(username=username).first()
    if user is None:
        user = User.objects.create_user(username, password)
    if user.body_profile is None:
        user.body_profile = {
            'height_cm': 170, 'weight_kg': 65, 'gender': 'unspecified',
            'measurements': [
                {'type': 'chest', 'value_cm': 92, 'confidence': 'medium', 'edited_by_user': True},
                {'type': 'waist', 'value_cm': 76, 'confidence': 'medium', 'edited_by_user': True},
                {'type': 'hip', 'value_cm': 94, 'confidence': 'medium', 'edited_by_user': True},
                {'type': 'shoulder_width', 'value_cm': 43, 'confidence': 'medium', 'edited_by_user': True},
                {'type': 'inseam', 'value_cm': 77, 'confidence': 'medium', 'edited_by_user': True},
            ],
            'clothing': {'top_loose': False, 'bottom_loose': False},
        }
        user.preferred_fits = ['regular']
        user.preferred_styles = ['minimal', 'classic']
        user.full_clean()
        user.save(update_fields=['body_profile', 'preferred_fits', 'preferred_styles', 'updated_at'])
    created = 0
    adopted = 0
    adopted_outfits = 0
    with transaction.atomic():
        sample_ids = {uuid5(NAMESPACE_URL, 'stylemate/local-chat-samples/' + slug)
                      for slug, *_ in SAMPLES}
        adopted = Garment.objects.filter(pk__in=sample_ids, user__isnull=True).update(user=user)
        for outfit in Outfit.objects.filter(user__isnull=True).prefetch_related('items'):
            if all(item.garment_id in sample_ids for item in outfit.items.all()):
                outfit.user = user
                outfit.save(update_fields=['user'])
                adopted_outfits += 1
        for slug, name, category, subcategory, color, styles, fit, length in SAMPLES:
            gid = uuid5(NAMESPACE_URL, 'stylemate/local-chat-samples/' + slug)
            if Garment.objects.filter(pk=gid).exists():
                continue
            attributes = dict(name=name, category=category, subcategory=subcategory,
                              colors=[color], styles=styles,
                              fit_type=fit if category != 'bottom' else None,
                              leg_shape=('wide' if fit == 'loose' else 'straight') if category == 'bottom' else None,
                              rise_type='mid' if category == 'bottom' else None, skirt_shape=None)
            if category == 'bottom':
                values = dict(waist_width_half=44, hip_width_half=57, thigh_width_half=34,
                              hem_opening=30 if fit == 'loose' else 21,
                              rise_front=30, inseam=length, total_length=length + 30)
            else:
                chest = {'regular': 56, 'loose': 60, 'oversized': 65}[fit]
                values = dict(shoulder_width=48, chest_width_half=chest,
                              total_length=105 if subcategory == 'coat' else 70,
                              sleeve_length=length)
            fields = dimension_fields(attributes)
            dimensions = {'unit': 'cm', **{
                key: dict(value=value, source='product_chart', method=fields[key].method,
                          reference='Fictional local development sample')
                for key, value in values.items()}}
            record = validate_record(dict(attributes=attributes, dimensions=dimensions,
                                          notes='채팅·추천 개발용 가상 데이터. 이미지와 치수는 샘플입니다.'))
            garment = Garment(id=gid, user=user, saved=True, **record)
            # Stable image names also avoid duplicates if a DB transaction is retried.
            filename = f'garments/chat-samples/{slug}.png'
            storage = garment.image.storage
            if not storage.exists(filename):
                filename = storage.save(filename, ContentFile(thumbnail(category, subcategory, color)))
            garment.image.name = filename
            garment.save(force_insert=True)
            created += 1
            print(f'Added: {name}')
    print(f'Account: {username}; created clothes: {created}; adopted clothes: {adopted}; '
          f'adopted outfits: {adopted_outfits}; account clothes: {Garment.objects.filter(user=user, saved=True).count()}.')


if __name__ == '__main__':
    main()

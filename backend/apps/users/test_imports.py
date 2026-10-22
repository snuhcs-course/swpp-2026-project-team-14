# AI-generated with Codex, 2026-10-16
import json
import tempfile
import uuid
from io import BytesIO
from pathlib import Path
from unittest.mock import patch

from PIL import Image
from django.core.files.uploadedfile import SimpleUploadedFile
from django.db import IntegrityError
from django.test import TestCase, override_settings

from apps.outfits.models import Outfit, OutfitItem
from apps.wardrobe.models import Garment
from .auth import issue_token
from .imports import mapped_id
from .models import User
from datetime import timedelta


class GuestImportTests(TestCase):
    def test_replacing_linked_garment_cannot_change_category_or_image(self):
        self.upload()
        self.upload(self.bottom, category='bottom')
        self.assertEqual(self.import_outfit().status_code, 200)
        row = Garment.objects.get(pk=mapped_id(self.user.pk, 'garments', self.top))
        original_image = row.image.name
        before_files = set(self.media.rglob('*.jpg'))
        version = self.preview(garments=[self.top])['garments'][self.top]['version']
        rejected = self.upload(category='bottom', expected=version, replace=True)
        self.assertEqual(rejected.status_code, 409, rejected.content)
        self.assertEqual(rejected.json()['error'], 'GARMENT_CATEGORY_IN_USE')
        row.refresh_from_db()
        self.assertEqual(row.attributes['category'], 'top')
        self.assertEqual(row.image.name, original_image)
        self.assertEqual(set(self.media.rglob('*.jpg')), before_files)
        accepted = self.upload(value=self.garment_value(name='새 이름'), expected=version, replace=True)
        self.assertEqual(accepted.status_code, 200, accepted.content)

    def setUp(self):
        self.user = User.objects.create_user('import_owner', 'test-password')
        self.other = User.objects.create_user('other_owner', 'test-password')
        _, token = issue_token(self.user)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.media = Path(directory.name)
        settings = override_settings(MEDIA_ROOT=directory.name)
        settings.enable()
        self.addCleanup(settings.disable)
        self.top, self.bottom, self.outfit = (str(uuid.uuid4()) for _ in range(3))
        output = BytesIO()
        Image.new('RGB', (24, 24), 'beige').save(output, format='JPEG')
        self.image = output.getvalue()

    def json(self, path, data, method='post'):
        return getattr(self.client, method)('/api/users/me/import/' + path, json.dumps(data), content_type='application/json')

    def preview(self, **fields):
        response = self.json('preview/', fields)
        self.assertEqual(response.status_code, 200, response.content)
        return response.json()

    def garment_value(self, category='top', name='로컬 옷'):
        return {'attributes': {'name': name, 'category': category, 'subcategory': 'tshirt' if category == 'top' else 'jeans',
                'colors': ['#FFFFFF'], 'styles': [], 'fit_type': None, 'leg_shape': None, 'rise_type': None, 'skirt_shape': None},
                'dimensions': None, 'notes': '로컬 메모', 'labels': None}

    def upload(self, source=None, category='top', value=None, expected=None, replace=False, image=None):
        metadata = {'value': value or self.garment_value(category), 'expected': expected, 'replace': replace}
        return self.client.post(f'/api/users/me/import/garments/{source or self.top}/', {
            'metadata': json.dumps(metadata), 'image': SimpleUploadedFile('local.jpg', image or self.image, 'image/jpeg')})

    def outfit_value(self):
        return {'name': '로컬 코디', 'items': [{'garment_id': self.top, 'slot': 'top', 'note': '상의'},
                 {'garment_id': self.bottom, 'slot': 'bottom', 'note': '하의'}], 'score': 75,
                'components': {'body': 70}, 'reasons': [{'kind': 'body', 'text': '좋은 핏', 'sub': ''}],
                'context': {}, 'feedback': 'liked'}

    def import_outfit(self, **extra):
        return self.json(f'outfits/{self.outfit}/', {'value': self.outfit_value(), 'expected': None, 'replace': False, **extra}, 'put')

    def test_upload_and_lost_response_retry_reuse_id_and_image(self):
        first = self.upload()
        self.assertEqual(first.status_code, 200, first.content)
        self.assertEqual(first.json()['status'], 'imported')
        second = self.upload()
        self.assertEqual(second.json()['status'], 'unchanged')
        self.assertEqual(first.json()['id'], second.json()['id'])
        self.assertEqual(Garment.objects.count(), 1)
        self.assertLessEqual(len(Garment.objects.get().image.name), Garment._meta.get_field('image').max_length)
        self.assertEqual(len(list(self.media.rglob('*.jpg'))), 1)

    def test_conflict_can_keep_or_replace_and_changed_version_cannot_overwrite(self):
        self.upload()
        version = self.preview(garments=[self.top])['garments'][self.top]['version']
        value = self.garment_value(name='바꿀 이름')
        kept = self.upload(value=value, expected=version)
        self.assertEqual(kept.json()['status'], 'kept')
        row = Garment.objects.get()
        self.assertEqual(row.attributes['name'], '로컬 옷')
        Garment.objects.filter(pk=row.pk).update(
        notes='다른 화면에서 수정',
        updated_at=row.updated_at + timedelta(seconds=1),
    )
        changed = self.upload(value=value, expected=version, replace=True)
        self.assertEqual(changed.status_code, 409)
        version = self.preview(garments=[self.top])['garments'][self.top]['version']
        replaced = self.upload(value=value, expected=version, replace=True)
        self.assertEqual(replaced.json()['status'], 'imported')
        row.refresh_from_db()
        self.assertEqual(row.attributes['name'], '바꿀 이름')

    def test_same_guest_source_can_import_into_two_accounts_without_sharing_rows(self):
        self.upload()
        _, token = issue_token(self.other)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'
        other = self.upload()
        self.assertEqual(other.status_code, 200)
        self.assertEqual(Garment.objects.count(), 2)
        self.assertEqual(Garment.objects.filter(user=self.other).count(), 1)

    def test_outfit_preserves_members_and_metadata_without_duplicate_on_retry(self):
        self.upload()
        self.upload(self.bottom, category='bottom')
        first = self.import_outfit()
        self.assertEqual(first.status_code, 200, first.content)
        second = self.import_outfit()
        self.assertEqual(second.json()['status'], 'unchanged')
        self.assertEqual(Outfit.objects.count(), 1)
        row = Outfit.objects.get()
        self.assertEqual(row.feedback, 'liked')
        self.assertEqual(row.score, 75)
        self.assertEqual(set(row.items.values_list('garment_id', flat=True)),
                         {mapped_id(self.user.pk, 'garments', self.top), mapped_id(self.user.pk, 'garments', self.bottom)})
        self.assertEqual(OutfitItem.objects.count(), 2)

    def test_outfit_cannot_link_missing_or_other_users_garments(self):
        self.upload()
        self.assertEqual(self.import_outfit().status_code, 400)
        self.upload(self.bottom, category='bottom')
        _, token = issue_token(self.other)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'
        self.assertEqual(self.import_outfit().status_code, 400)
        self.assertEqual(Outfit.objects.count(), 0)

    def test_profile_import_and_conflict_keep_or_replace_are_explicit(self):
        body = {'height_cm': 175, 'gender': 'male', 'measurements': []}
        style = {'preferred_fits': ['wide'], 'preferred_styles': ['minimal'], 'favorite_colors': []}
        plan = self.preview(profile={'body': body, 'style': style})
        for section, value in [('body', body), ('style', style)]:
            response = self.json(f'profile/{section}/', {'value': value, 'replace': False, 'expected': plan['profile'][section]['version']}, 'put')
            self.assertEqual(response.json()['status'], 'imported')
        self.user.refresh_from_db()
        self.assertEqual(self.user.body_profile, body)
        newer = {**style, 'favorite_colors': ['#1F2A44']}
        plan = self.preview(profile={'style': newer})
        self.assertTrue(plan['profile']['style']['conflict'])
        data = {'value': newer, 'replace': False, 'expected': plan['profile']['style']['version']}
        self.assertEqual(self.json('profile/style/', data, 'put').json()['status'], 'kept')
        self.assertEqual(self.json('profile/style/', {**data, 'replace': True}, 'put').json()['status'], 'imported')

    def test_bad_ids_duplicate_batches_and_unknown_profile_fields_are_rejected(self):
        for payload in [{'garments': [self.top, self.top]}, {'garments': ['../bad']}, {'garments': [str(uuid.uuid4()) for _ in range(101)]},
                        {'profile': {'username': 'other_owner'}}, {'profile': {'style': {'favorite_colors': ['bad']}}}]:
            with self.subTest(payload=payload):
                self.assertEqual(self.json('preview/', payload).status_code, 400)

    def test_unauthenticated_import_is_rejected_and_invalid_image_creates_nothing(self):
        self.assertEqual(self.upload(image=b'invalid-image').status_code, 400)
        self.assertEqual(Garment.objects.count(), 0)
        self.client.defaults.clear()
        self.assertEqual(self.json('preview/', {}).status_code, 401)

    def test_malformed_multipart_is_a_json_validation_error(self):
        response = self.client.post(f'/api/users/me/import/garments/{self.top}/', b'bad multipart', content_type='multipart/form-data; boundary=')
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()['error'], 'INVALID_IMPORT')

    def test_failed_database_save_removes_new_image_and_retries_cleanly(self):
        with patch.object(Garment, 'save', side_effect=IntegrityError('injected failure')):
            self.assertEqual(self.upload().status_code, 409)
        self.assertFalse(list(self.media.rglob('*.jpg')))
        self.assertEqual(self.upload().json()['status'], 'imported')

    def test_pk_collision_never_reassigns_other_accounts_row(self):
        row = Garment.objects.create(user=self.other, image='existing.jpg', **self.garment_value())
        with patch('apps.users.imports.mapped_id', return_value=row.pk):
            self.assertEqual(self.upload().status_code, 409)
        row.refresh_from_db()
        self.assertEqual(row.user_id, self.other.pk)

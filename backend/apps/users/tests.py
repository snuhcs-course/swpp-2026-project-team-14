# AI-generated with Codex, 2026-10-16
import hashlib
import json
import uuid
from datetime import timedelta
from unittest.mock import patch

from django.contrib.auth import authenticate, get_user_model
from django.core.exceptions import ValidationError
from django.db import IntegrityError, transaction
from django.test import Client, TestCase, override_settings
from django.utils import timezone

from apps.outfits.models import Outfit, OutfitItem
from apps.wardrobe.models import Garment
from .auth import issue_token
from .models import AuthToken


User = get_user_model()
PASSWORD = 'test-password'


class UserTests(TestCase):
    def test_account_works_with_django_auth_without_body_or_contact_info(self):
        user = User.objects.create_user('style_01', PASSWORD)
        user.refresh_from_db()
        self.assertIsInstance(user.id, uuid.UUID)
        self.assertNotEqual(user.password, PASSWORD)
        self.assertTrue(user.check_password(PASSWORD))
        self.assertFalse(user.check_password('wrong-password'))
        self.assertEqual(authenticate(username=user.username, password=PASSWORD), user)
        self.assertIsNone(user.body_profile)
        self.assertEqual(user.preferred_fits, ['regular'])
        self.assertEqual(user.preferred_styles, [])
        self.assertEqual(user.favorite_colors, [])
        field_names = {field.name for field in User._meta.fields}
        self.assertTrue({'email', 'first_name', 'last_name', 'phone'}.isdisjoint(field_names))

    def test_inactive_user_cannot_authenticate(self):
        user = User.objects.create_user('inactive_01', PASSWORD, is_active=False)
        self.assertIsNone(authenticate(username=user.username, password=PASSWORD))

    def test_username_boundaries_and_rejected_characters(self):
        for username in ('abcd', 'a' * 20, '0123', 'test_01'):
            with self.subTest(username=username):
                user = User(username=username)
                user.set_unusable_password()
                user.full_clean()
        for username in ('abc', 'a' * 21, 'ABCD', '한글아이디', 'abcd ', 'ab-cd', 'abcd\n', 'ａｂｃｄ'):
            with self.subTest(username=username):
                user = User(username=username)
                user.set_unusable_password()
                with self.assertRaises(ValidationError) as error:
                    user.full_clean()
                self.assertIn('username', error.exception.message_dict)

    def test_duplicate_username_is_rejected_by_database(self):
        User.objects.create_user('duplicate_01', PASSWORD)
        with self.assertRaises(IntegrityError), transaction.atomic():
            User.objects.create(username='duplicate_01', password='!')

    def test_password_length_boundaries(self):
        for length in (8, 64):
            with self.subTest(length=length):
                user = User.objects.create_user(f'length_{length}', 'a' * length)
                self.assertTrue(user.check_password('a' * length))
        for password in ('', 'a' * 7, 'a' * 65, None, 12345678):
            with self.subTest(password=password), self.assertRaises(ValidationError) as error:
                User.objects.create_user('invalid_password', password)
            self.assertIn('password', error.exception.message_dict)
        self.assertFalse(User.objects.filter(username='invalid_password').exists())

    def test_preferences_persist_without_body_analysis(self):
        user = User.objects.create_user(
            'preferences_01', PASSWORD, preferred_fits=['oversized', 'wide'],
            preferred_styles=['minimal', 'cityboy'], favorite_colors=['#1f2a44', '#FFFFF0'],
        )
        user.refresh_from_db()
        self.assertIsNone(user.body_profile)
        self.assertEqual(user.preferred_fits, ['oversized', 'wide'])
        self.assertEqual(user.preferred_styles, ['minimal', 'cityboy'])
        self.assertEqual(user.favorite_colors, ['#1F2A44', '#FFFFF0'])

    def test_body_profile_preserves_measurement_metadata(self):
        profile = {
            'height_cm': 175, 'weight_kg': None, 'gender': 'unspecified',
            'measurements': [{'type': 'waist', 'value_cm': 78, 'confidence': 'medium', 'edited_by_user': True}],
        }
        user = User.objects.create_user('body_01', PASSWORD, body_profile=profile)
        user.refresh_from_db()
        self.assertEqual(user.body_profile, profile)

    def test_empty_fit_selection_uses_regular_and_defaults_are_independent(self):
        first = User(username='first_01', preferred_fits=[])
        first.clean()
        second = User(username='second_01')
        self.assertEqual(first.preferred_fits, ['regular'])
        first.preferred_fits.append('wide')
        first.preferred_styles.append('minimal')
        first.favorite_colors.append('#FFFFFF')
        self.assertEqual(second.preferred_fits, ['regular'])
        self.assertEqual(second.preferred_styles, [])
        self.assertEqual(second.favorite_colors, [])

    def test_invalid_profile_shapes_choices_and_duplicate_colors(self):
        cases = [
            ('preferred_fits', ['unknown']), ('preferred_fits', ['regular', 'regular']),
            ('preferred_fits', 'regular'), ('preferred_fits', {}), ('preferred_fits', None),
            ('preferred_styles', ['unknown']), ('preferred_styles', ['minimal', 'minimal']),
            ('preferred_styles', [[]]), ('preferred_styles', {}), ('preferred_styles', None),
            ('body_profile', []), ('body_profile', 'profile'),
            ('favorite_colors', ['blue']), ('favorite_colors', ['#FFF']),
            ('favorite_colors', ['#GGGGGG']), ('favorite_colors', ['#FFFFFF', '#ffffff']),
            ('favorite_colors', [None]), ('favorite_colors', {}), ('favorite_colors', None),
        ]
        for field, value in cases:
            with self.subTest(field=field, value=value):
                user = User(username='invalid_01', **{field: value})
                user.set_unusable_password()
                with self.assertRaises(ValidationError) as error:
                    user.full_clean()
                self.assertIn(field, error.exception.message_dict)


class OwnershipTests(TestCase):
    def setUp(self):
        self.owner = User.objects.create_user('owner_01', PASSWORD)
        self.other = User.objects.create_user('other_01', PASSWORD)

    def test_reverse_relations_and_delete_only_remove_owners_records(self):
        garment = Garment.objects.create(user=self.owner, image='garments/test.jpg', attributes={})
        outfit = Outfit.objects.create(user=self.owner, source='user', key=str(garment.id))
        item = OutfitItem.objects.create(outfit=outfit, garment=garment, slot='top')
        other_garment = Garment.objects.create(user=self.other, image='garments/other.jpg', attributes={})
        other_outfit = Outfit.objects.create(user=self.other, source='user', key=str(other_garment.id))
        self.assertEqual(list(self.owner.garments.all()), [garment])
        self.assertEqual(list(self.owner.outfits.all()), [outfit])
        self.owner.delete()
        self.assertFalse(Garment.objects.filter(pk=garment.pk).exists())
        self.assertFalse(Outfit.objects.filter(pk=outfit.pk).exists())
        self.assertFalse(OutfitItem.objects.filter(pk=item.pk).exists())
        self.assertTrue(Garment.objects.filter(pk=other_garment.pk).exists())
        self.assertTrue(Outfit.objects.filter(pk=other_outfit.pk).exists())

    def test_slot_constraint_remains_in_force(self):
        garment = Garment.objects.create(user=self.owner, image='garments/test.jpg', attributes={})
        outfit = Outfit.objects.create(user=self.owner, source='user', key=str(garment.id))
        OutfitItem.objects.create(outfit=outfit, garment=garment, slot='top')
        with self.assertRaises(IntegrityError), transaction.atomic():
            OutfitItem.objects.create(outfit=outfit, garment=garment, slot='top')


@override_settings(PASSWORD_HASHERS=['django.contrib.auth.hashers.MD5PasswordHasher'])
class AuthApiTests(TestCase):
    def post(self, url, data):
        return self.client.post(url, json.dumps(data), content_type='application/json')

    def register(self, **changes):
        return self.post('/api/auth/register/', {
            'username': 'stylemate_01', 'password': PASSWORD, 'password_confirmation': PASSWORD, **changes,
        })

    def authorize(self, response):
        self.client.defaults['HTTP_AUTHORIZATION'] = f"Bearer {response.json()['token']}"

    def test_register_auto_login_and_token_only_stored_as_hash(self):
        response = self.register()
        self.assertEqual(response.status_code, 201)
        data = response.json()
        self.assertEqual(set(data), {'user', 'token', 'expires_at'})
        self.assertNotIn('password', data['user'])
        token = AuthToken.objects.get()
        self.assertEqual(token.key_hash, hashlib.sha256(data['token'].encode('ascii')).hexdigest())
        self.assertNotEqual(token.key_hash, data['token'])
        self.assertAlmostEqual((token.expires_at - timezone.now()).total_seconds(), 30 * 86400, delta=10)
        self.authorize(response)
        # A fresh client represents re-opening the app with its saved token.
        reopened = Client(HTTP_AUTHORIZATION=f"Bearer {data['token']}")
        me = reopened.get('/api/users/me/')
        self.assertEqual(me.json()['user'], data['user'])
        self.assertEqual(me['Cache-Control'], 'no-store')
        user = User.objects.get()
        self.assertIsNotNone(user.last_login)
        self.assertTrue(user.check_password(PASSWORD))

    def test_registration_validation_and_duplicate_id(self):
        cases = [
            ({'username': 'ABCD'}, 'INVALID_USERNAME'), ({'username': 'a' * 21}, 'INVALID_USERNAME'),
            ({'username': []}, 'INVALID_USERNAME'), ({'password': 'short'}, 'INVALID_PASSWORD'),
            ({'password': None}, 'INVALID_PASSWORD'), ({'password': 'a' * 65}, 'INVALID_PASSWORD'),
            ({'password_confirmation': 'different'}, 'PASSWORD_MISMATCH'),
            ({'user_id': str(uuid.uuid4())}, 'INVALID_REQUEST'),
        ]
        for changes, code in cases:
            with self.subTest(changes=changes):
                response = self.register(**changes)
                self.assertEqual((response.status_code, response.json()['error']), (400, code))
        self.assertEqual(User.objects.count(), 0)
        self.assertEqual(self.register().status_code, 201)
        duplicate = self.register()
        self.assertEqual((duplicate.status_code, duplicate.json()['error']), (409, 'USERNAME_TAKEN'))
        self.assertEqual((User.objects.count(), AuthToken.objects.count()), (1, 1))

    def test_token_failure_rolls_back_registration(self):
        with patch('apps.users.views.issue_token', side_effect=RuntimeError('token failure')):
            with self.assertRaises(RuntimeError):
                self.register()
        self.assertEqual(User.objects.count(), 0)

    def test_login_failure_does_not_reveal_whether_account_exists(self):
        self.register()
        responses = [self.post('/api/auth/login/', credentials) for credentials in (
            {'username': 'stylemate_01', 'password': 'wrong'},
            {'username': 'unknown_01', 'password': PASSWORD},
        )]
        self.assertTrue(all(response.status_code == 401 for response in responses))
        self.assertEqual(responses[0].json(), responses[1].json())
        User.objects.update(is_active=False)
        self.assertEqual(self.post('/api/auth/login/', {'username': 'stylemate_01', 'password': PASSWORD}).status_code, 401)

    def test_logout_revokes_current_token_without_revoking_other_logins(self):
        registration = self.register()
        login = self.post('/api/auth/login/', {'username': 'stylemate_01', 'password': PASSWORD})
        self.assertEqual(login.status_code, 200)
        self.assertNotEqual(registration.json()['token'], login.json()['token'])
        self.authorize(login)
        self.assertEqual(self.client.post('/api/auth/logout/').status_code, 204)
        self.assertEqual(self.client.get('/api/users/me/').status_code, 401)
        self.authorize(registration)
        self.assertEqual(self.client.get('/api/users/me/').status_code, 200)

    def test_missing_malformed_expired_and_inactive_tokens_are_rejected(self):
        response = self.register()
        for authorization in ('', 'Basic abc', 'Bearer bad', 'Bearer ' + 'a' * 43, 'Bearer a b'):
            with self.subTest(authorization=authorization):
                self.client.defaults['HTTP_AUTHORIZATION'] = authorization
                denied = self.client.get('/api/users/me/')
                self.assertEqual(denied.status_code, 401)
                self.assertEqual(denied['WWW-Authenticate'], 'Bearer')
        self.authorize(response)
        AuthToken.objects.update(expires_at=timezone.now() - timedelta(seconds=1))
        self.assertEqual(self.client.get('/api/users/me/').status_code, 401)
        AuthToken.objects.update(expires_at=timezone.now() + timedelta(days=1))
        User.objects.update(is_active=False)
        self.assertEqual(self.client.get('/api/users/me/').status_code, 401)

    def test_profile_edit_preserves_other_fields_and_cannot_change_identity(self):
        response = self.register()
        self.authorize(response)
        profile = {'height_cm': 175, 'gender': 'unspecified', 'measurements': [
            {'type': 'waist', 'value_cm': 78, 'confidence': 'medium', 'edited_by_user': True},
        ]}
        changes = {'body_profile': profile, 'preferred_fits': ['oversized', 'wide'], 'preferred_styles': ['cityboy']}
        saved = self.client.patch('/api/users/me/', json.dumps(changes), content_type='application/json')
        self.assertEqual(saved.status_code, 200)
        self.assertEqual(saved.json()['user']['body_profile'], profile)
        color = self.client.patch('/api/users/me/', json.dumps({'favorite_colors': ['#1f2a44']}), content_type='application/json')
        self.assertEqual(color.json()['user']['preferred_fits'], ['oversized', 'wide'])
        self.assertEqual(color.json()['user']['favorite_colors'], ['#1F2A44'])
        for data in ({'username': 'changed_01'}, {'is_active': False}, {'password': 'changed-password'},
                     {'preferred_fits': ['invalid']}, {'favorite_colors': ['blue']}, {'body_profile': {}}, {}):
            with self.subTest(data=data):
                invalid = self.client.patch('/api/users/me/', json.dumps(data), content_type='application/json')
                self.assertEqual(invalid.status_code, 400)
        user = User.objects.get()
        self.assertEqual(user.username, 'stylemate_01')
        self.assertEqual(user.body_profile, profile)
        self.assertTrue(user.is_active)
        self.assertTrue(user.check_password(PASSWORD))

    def test_request_format_limits_and_methods(self):
        self.assertEqual(self.client.get('/api/auth/register/').status_code, 405)
        self.assertEqual(self.client.post('/api/auth/login/', '{}', content_type='text/plain').status_code, 415)
        for body in ('[]', '{', 'null', '\u0000', '{"username": NaN, "password": "password"}'):
            with self.subTest(body=body):
                self.assertEqual(self.client.post('/api/auth/login/', body, content_type='application/json').status_code, 400)
        self.assertEqual(self.client.post('/api/auth/register/', b' ' * (32 * 1024 + 1), content_type='application/json').status_code, 413)


@override_settings(PASSWORD_HASHERS=['django.contrib.auth.hashers.MD5PasswordHasher'])
class OwnershipApiTests(TestCase):
    def setUp(self):
        self.owner = User.objects.create_user('owner_01', PASSWORD)
        self.other = User.objects.create_user('other_01', PASSWORD)
        _, token = issue_token(self.owner)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'
        self.top = Garment.objects.create(user=self.owner, saved=True, image='garments/top.jpg', attributes={'category': 'top'})
        self.bottom = Garment.objects.create(user=self.owner, saved=True, image='garments/bottom.jpg', attributes={'category': 'bottom'})
        self.foreign = Garment.objects.create(user=self.other, saved=True, image='garments/foreign.jpg', attributes={'category': 'top'})
        self.outfit = Outfit.objects.create(user=self.owner, source='user', key='own', saved=True)
        self.foreign_outfit = Outfit.objects.create(user=self.other, source='user', key='other', saved=True)

    def test_lists_only_return_current_users_records(self):
        garments = self.client.get('/api/wardrobe/items/').json()['items']
        self.assertEqual({item['id'] for item in garments}, {str(self.top.pk), str(self.bottom.pk)})
        outfits = self.client.get('/api/outfits/').json()['outfits']
        self.assertEqual([outfit['id'] for outfit in outfits], [str(self.outfit.pk)])

    def test_foreign_details_edits_images_and_feedback_are_hidden(self):
        garment_url = f'/api/wardrobe/items/{self.foreign.pk}/'
        outfit_url = f'/api/outfits/{self.foreign_outfit.pk}/'
        for method, url in (('get', garment_url), ('put', garment_url), ('get', garment_url + 'image/'),
                            ('get', outfit_url), ('put', outfit_url), ('post', outfit_url + 'feedback/')):
            with self.subTest(method=method, url=url):
                response = getattr(self.client, method)(url)
                self.assertEqual(response.status_code, 404)

    def test_outfit_creation_rejects_foreign_garments_and_uses_authenticated_owner(self):
        url = '/api/outfits/'
        response = self.client.post(url, json.dumps({'garment_ids': [str(self.foreign.pk), str(self.bottom.pk)]}), content_type='application/json')
        self.assertEqual(response.status_code, 400)
        self.assertEqual(Outfit.objects.count(), 2)
        created = self.client.post(url, json.dumps({'garment_ids': [str(self.top.pk), str(self.bottom.pk)],
                                                   'user_id': str(self.other.pk)}), content_type='application/json')
        self.assertEqual(created.status_code, 201)
        self.assertEqual(Outfit.objects.get(pk=created.json()['id']).user, self.owner)

    @patch('apps.wardrobe.views.analyze_image')
    def test_unauthenticated_requests_cannot_read_write_or_trigger_paid_analysis(self, analyze):
        self.client.defaults.clear()
        endpoints = [('get', '/api/wardrobe/items/'), ('get', f'/api/wardrobe/items/{self.top.pk}/'),
                     ('put', f'/api/wardrobe/items/{self.top.pk}/'), ('get', f'/api/wardrobe/items/{self.top.pk}/image/'),
                     ('post', '/api/wardrobe/analyze/'), ('get', '/api/outfits/'), ('post', '/api/outfits/'),
                     ('post', '/api/outfits/recommend/'), ('get', f'/api/outfits/{self.outfit.pk}/'),
                     ('put', f'/api/outfits/{self.outfit.pk}/'), ('post', f'/api/outfits/{self.outfit.pk}/feedback/')]
        for method, url in endpoints:
            with self.subTest(method=method, url=url):
                self.assertEqual(getattr(self.client, method)(url).status_code, 401)
        analyze.assert_not_called()

    def test_database_rejects_records_without_owner(self):
        with self.assertRaises(IntegrityError), transaction.atomic():
            Garment.objects.create(image='garments/unowned.jpg', attributes={})
        with self.assertRaises(IntegrityError), transaction.atomic():
            Outfit.objects.create(source='user', key='unowned')

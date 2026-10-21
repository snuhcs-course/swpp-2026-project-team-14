# AI-generated with Codex, 2026-10-16
import json
from datetime import timedelta
from unittest.mock import patch

from django.test import Client, TestCase, override_settings
from django.utils import timezone

from .auth import issue_token
from .models import AuthToken, User


@override_settings(PASSWORD_HASHERS=['django.contrib.auth.hashers.MD5PasswordHasher'])
class LoginLimitTests(TestCase):
    def setUp(self):
        self.user = User.objects.create_user('security_01', 'test-password')

    def login(self, password='wrong-password', username='security_01', address='192.0.2.1', **extra):
        return self.client.post('/api/auth/login/', json.dumps({'username': username, 'password': password}),
                                content_type='application/json', REMOTE_ADDR=address, **extra)

    def test_fifth_failure_locks_even_correct_password_until_cooldown_expires(self):
        now = timezone.now()
        with patch('apps.users.limits.timezone.now', return_value=now):
            for _ in range(4):
                self.assertEqual(self.login().status_code, 401)
            locked = self.login()
            self.assertEqual((locked.status_code, locked.json()['error']), (429, 'LOGIN_LOCKED'))
            self.assertEqual(locked['Retry-After'], '300')
            self.assertEqual(self.login('test-password', address='192.0.2.2').status_code, 429)
            self.assertEqual(AuthToken.objects.count(), 0)
        with patch('apps.users.limits.timezone.now', return_value=now + timedelta(seconds=300)):
            self.assertEqual(self.login('test-password').status_code, 200)

    def test_success_resets_failures_and_accounts_do_not_share_failure_counter(self):
        for _ in range(4):
            self.assertEqual(self.login().status_code, 401)
        self.assertEqual(self.login('test-password').status_code, 200)
        self.assertEqual(self.login().status_code, 401)
        for _ in range(5):
            self.login(username='not_registered')
        self.assertEqual(self.login('test-password').status_code, 200)

    def test_unknown_and_existing_accounts_have_same_lock_behavior(self):
        for username in ('security_01', 'not_registered'):
            for _ in range(4):
                self.assertEqual(self.login(username=username).status_code, 401)
            response = self.login(username=username)
            self.assertEqual((response.status_code, response.json()['error']), (429, 'LOGIN_LOCKED'))

    def test_old_failures_expire_without_requiring_success(self):
        now = timezone.now()
        with patch('apps.users.limits.timezone.now', return_value=now):
            for _ in range(4):
                self.login()
        with patch('apps.users.limits.timezone.now', return_value=now + timedelta(seconds=300)):
            self.assertEqual(self.login().status_code, 401)

    def test_twenty_auth_requests_per_ip_per_minute_and_forwarded_ip_cannot_bypass(self):
        now = timezone.now()
        with patch('apps.users.limits.timezone.now', return_value=now):
            for _ in range(20):
                self.assertEqual(self.login('test-password').status_code, 200)
            # The shared counter also protects registration and rejects before hashing.
            response = self.client.post('/api/auth/register/', '{}', content_type='application/json',
                                        REMOTE_ADDR='192.0.2.1', HTTP_X_FORWARDED_FOR='192.0.2.100')
            self.assertEqual((response.status_code, response.json()['error']), (429, 'TOO_MANY_REQUESTS'))
            self.assertEqual(response['Retry-After'], '60')
            self.assertEqual(self.login('test-password', address='192.0.2.2').status_code, 200)
        with patch('apps.users.limits.timezone.now', return_value=now + timedelta(seconds=60)):
            self.assertEqual(self.login('test-password').status_code, 200)

    def test_limits_are_shared_by_separate_clients(self):
        for _ in range(4):
            self.login()
        fresh = Client()
        response = fresh.post('/api/auth/login/', json.dumps({'username': 'security_01', 'password': 'wrong'}),
                              content_type='application/json', REMOTE_ADDR='192.0.2.99')
        self.assertEqual(response.status_code, 429)


@override_settings(PASSWORD_HASHERS=['django.contrib.auth.hashers.MD5PasswordHasher'])
class ProfileValidationTests(TestCase):
    def setUp(self):
        self.user = User.objects.create_user('profile_01', 'test-password')
        _, token = issue_token(self.user)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'

    def save(self, data):
        return self.client.patch('/api/users/me/', json.dumps(data), content_type='application/json')

    def test_all_sixteen_measurements_and_bounded_metadata_can_be_saved(self):
        from .profile import MEASUREMENT_TYPES
        measurements = [{'type': kind, 'value_cm': 65, 'confidence': 'medium', 'edited_by_user': True}
                        for kind in MEASUREMENT_TYPES]
        # Keep the already-established recommendation input bounds as well.
        for measurement in measurements:
            if measurement['type'] == 'hip':
                measurement['value_cm'] = 95
            if measurement['type'] == 'torso_length':
                measurement['value_cm'] = 50
        profile = {'height_cm': 175, 'weight_kg': None, 'gender': 'unspecified', 'measurements': measurements,
                   'clothing': {'top_loose': False, 'bottom_loose': True},
                   'reference': {'body': 'm_avg', 'height_cm': 175, 'measurements': {'neck': 35}}}
        response = self.save({'body_profile': profile, 'favorite_colors': ['#FFFFFF', '#000000']})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['user']['body_profile'], profile)

    def test_negative_boolean_unknown_duplicate_and_oversized_measurements_are_rejected(self):
        normal = {'height_cm': 175, 'measurements': []}
        bad = [
            {'photo': 'not allowed'}, {'height_cm': True}, {'height_cm': '175'}, {'weight_kg': -1},
            {'measurements': [{'type': 'neck', 'value_cm': -5}]},
            {'measurements': [{'type': 'neck', 'value_cm': False}]},
            {'measurements': [{'type': 'unknown', 'value_cm': 30}]},
            {'measurements': [{'type': 'neck', 'value_cm': 30, 'confidence': 'certain'}]},
            {'measurements': [{'type': 'neck', 'value_cm': 30, 'edited_by_user': 'yes'}]},
            {'measurements': [{'type': 'neck', 'value_cm': 30, 'photo': 'not allowed'}]},
            {'measurements': [{'type': 'neck', 'value_cm': 30}] * 2},
            {'measurements': [{'type': 'neck', 'value_cm': 30}] * 17},
            {'clothing': {'top_loose': 'false'}}, {'reference': {'photo': 'not allowed'}},
        ]
        for change in bad:
            with self.subTest(change=change):
                self.assertEqual(self.save({'body_profile': {**normal, **change}}).status_code, 400)
        self.user.refresh_from_db()
        self.assertIsNone(self.user.body_profile)

    def test_only_known_profile_fields_and_bounded_selections_can_be_saved(self):
        for changes in ({'extra': 1}, {'preferred_fits': ['regular'] * 6}, {'preferred_styles': ['minimal'] * 7},
                        {'favorite_colors': [f'#{index:06X}' for index in range(9)]}):
            with self.subTest(changes=changes):
                self.assertEqual(self.save(changes).status_code, 400)
        colors = [f'#{index:06X}' for index in range(8)]
        self.assertEqual(self.save({'favorite_colors': colors}).status_code, 200)

    def test_overflow_nan_duplicate_keys_and_deeply_nested_json_return_400(self):
        bodies = [
            '{"body_profile":{"height_cm":175,"measurements":[],"extra":1e400}}',
            '{"body_profile":{"height_cm":1e400,"measurements":[]}}',
            '{"body_profile":{"height_cm":NaN,"measurements":[]}}',
            '{"preferred_fits":["slim"],"preferred_fits":["regular"]}',
            '{"body_profile":' + '[' * 1100 + '0' + ']' * 1100 + '}',
        ]
        for body in bodies:
            with self.subTest(body=body[:80]):
                response = self.client.patch('/api/users/me/', body, content_type='application/json')
                self.assertEqual(response.status_code, 400)
        self.assertEqual(self.client.patch('/api/users/me/', b' ' * 8193, content_type='application/json').status_code, 413)

# AI-generated with Claude Code (Claude Opus 5.5), 2026-11-03, reviewed by Dongkun Moon
import tempfile
from pathlib import Path

from django.test import TestCase, override_settings

from apps.users.auth import issue_token
from apps.users.models import User
from .dummy_wardrobe import _items, ensure_dummy_wardrobe
from .models import Garment

PASSWORD = 'dummy-password-1'


class DummyWardrobeTests(TestCase):
    """Temporary HE (11/4) dummy wardrobe; delete together with dummy_wardrobe.py after HE."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.media = Path(directory.name)
        setting = override_settings(MEDIA_ROOT=directory.name, HE_DUMMY_WARDROBE=True)
        setting.enable()
        self.addCleanup(setting.disable)
        self.user = User.objects.create_user('dummy_01', PASSWORD)

    def login(self, username='dummy_01'):
        return self.client.post('/api/auth/login/', {'username': username, 'password': PASSWORD},
                                content_type='application/json')

    def test_off_by_default(self):
        with override_settings(HE_DUMMY_WARDROBE=False):
            self.assertEqual(self.login().status_code, 200)
        self.assertFalse(Garment.objects.filter(user=self.user).exists())

    def test_login_and_register_fill_the_wardrobe(self):
        self.assertEqual(self.login().status_code, 200)
        self.assertEqual(Garment.objects.filter(user=self.user, saved=True).count(), len(_items()))
        response = self.client.post('/api/auth/register/', {
            'username': 'dummy_02', 'password': PASSWORD, 'password_confirmation': PASSWORD,
        }, content_type='application/json')
        self.assertEqual(response.status_code, 201)
        self.assertEqual(Garment.objects.filter(user__username='dummy_02').count(), len(_items()))

    def test_repeat_calls_add_nothing_and_keep_own_garments(self):
        own = Garment.objects.create(user=self.user, attributes=_items()[0]['attributes'], saved=True)
        ensure_dummy_wardrobe(self.user)
        ensure_dummy_wardrobe(self.user)
        self.login()
        self.assertEqual(Garment.objects.filter(user=self.user).count(), len(_items()) + 1)
        self.assertTrue(Garment.objects.filter(pk=own.pk).exists())

    def test_existing_session_gets_it_from_the_wardrobe_list(self):
        _, token = issue_token(self.user)
        response = self.client.get('/api/wardrobe/items/', HTTP_AUTHORIZATION=f'Bearer {token}')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(Garment.objects.filter(user=self.user).count(), len(_items()))

    def test_images_are_stored_once_and_served_to_each_owner(self):
        other = User.objects.create_user('dummy_03', PASSWORD)
        ensure_dummy_wardrobe(self.user)
        ensure_dummy_wardrobe(other)
        self.assertEqual(len(list((self.media / 'he-dummy').iterdir())), len(_items()))
        garment = Garment.objects.filter(user=other).first()
        _, token = issue_token(other)
        response = self.client.get(f'/api/wardrobe/items/{garment.pk}/image/', HTTP_AUTHORIZATION=f'Bearer {token}')
        self.assertEqual(response.status_code, 200)
        self.assertGreater(len(b''.join(response.streaming_content)), 1000)
        _, token = issue_token(self.user)
        response = self.client.get(f'/api/wardrobe/items/{garment.pk}/image/', HTTP_AUTHORIZATION=f'Bearer {token}')
        self.assertEqual(response.status_code, 404)  # another account's row stays private

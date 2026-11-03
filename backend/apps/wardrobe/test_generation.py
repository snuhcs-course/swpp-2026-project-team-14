import base64
from datetime import timedelta
from decimal import Decimal
import json
import tempfile
import uuid
from unittest.mock import patch, MagicMock
from urllib.error import HTTPError
from io import BytesIO

from django.test import TestCase, SimpleTestCase, override_settings
from django.utils import timezone
from apps.users.auth import issue_token
from apps.users.models import User
from .analysis import AnalysisError, prepare_image
from .models import Garment, WornGeneration
from .tests import attributes, photo
from .worn import generate
from recommendation.labeling import options


@override_settings(GEMINI_API_KEY='test-key')
class GenerationFlowTests(TestCase):
    def setUp(self):
        user = User.objects.create_user('generation_owner', 'test-password')
        _, token = issue_token(user)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        setting = override_settings(MEDIA_ROOT=directory.name)
        setting.enable()
        self.addCleanup(setting.disable)

    def draft(self, category='outerwear', request_id=None):
        attrs = dict(attributes(), category=category, subcategory='jeans' if category == 'bottom' else 'jacket')
        if category == 'top': attrs['subcategory'] = 'tshirt'
        with patch('apps.wardrobe.views.analyze_image', return_value=(attrs, {'design_details': ['단추']})):
            url = '/api/wardrobe/analyze-worn/?category=' + category
            if request_id: url += '&request_id=' + request_id
            result = self.client.post(url, photo(), content_type='image/jpeg')
        self.assertEqual(result.status_code, 200)
        return result.json(), '/api/wardrobe/items/' + result.json()['id'] + '/'

    def post(self, path, data):
        return self.client.post(path, json.dumps(data), content_type='application/json')

    def payload(self, attrs):
        choices = options()[attrs['category']]
        return {'attributes': attrs, 'dimensions': None, 'notes': '', 'labels': {
            'fit': choices['fit'][0], 'length': choices['length'][0], 'confirmed': True}}

    @patch('apps.wardrobe.worn.generate', return_value=(photo(), Decimal('.039')))
    @patch('apps.wardrobe.worn.segment', return_value=(b'segment', b'mask', True))
    def test_outerwear_and_pants_approval_saves_generated_image_with_original_attributes(self, segment, generate_image):
        for category in ('outerwear', 'bottom'):
            record, path = self.draft(category)
            data = {'request_id': str(uuid.uuid4())}
            before = Garment.objects.get(pk=record['id'])
            original_name = before.image.name
            result = self.post(path + 'generation/', data)
            self.assertEqual(result.json()['status'], 'ready')
            self.assertFalse(Garment.objects.get(pk=before.pk).saved)
            self.assertEqual(self.client.put(path, json.dumps(self.payload(record['attributes'])), content_type='application/json').status_code, 409)
            row = WornGeneration.objects.get(pk=before.pk)
            self.assertEqual(row.original.name, original_name)
            original_bytes = row.original.read()
            self.assertEqual(generate_image.call_args.args[0], original_bytes)
            # Same operation replays its stored result without another provider request or file.
            candidate_name = row.candidate.name
            count = generate_image.call_count
            self.assertEqual(self.post(path + 'generation/', data).json()['status'], 'ready')
            self.assertEqual(generate_image.call_count, count)
            row.refresh_from_db()
            self.assertEqual(row.candidate.name, candidate_name)
            accepted = self.post(path + 'review/', {'choice': 'generated'})
            self.assertEqual(accepted.json()['image_source'], 'ai_flatlay')
            self.assertEqual(accepted.json()['details'], {'design_details': ['단추']})
            self.assertEqual(accepted.json()['attributes'], record['attributes'])
            payload = self.payload(record['attributes'])
            self.assertEqual(self.client.put(path, json.dumps(payload), content_type='application/json').status_code, 200)
            saved = Garment.objects.get(pk=before.pk)
            self.assertEqual(saved.image.name, candidate_name)
            self.assertIsNone(saved.dimensions)
            image = self.client.get(path + 'image/')
            self.assertEqual(b''.join(image.streaming_content), photo())
            image.close()
            self.assertEqual(self.post(path + 'generation/', {'request_id': str(uuid.uuid4())}).status_code, 409)

    @patch('apps.wardrobe.worn.generate')
    @patch('apps.wardrobe.worn.segment', return_value=(b'segment', b'mask', True))
    def test_rejection_explicit_crop_and_manual_regeneration(self, segment, generate_image):
        generate_image.return_value = photo(), Decimal('.039')
        record, path = self.draft()
        self.post(path + 'generation/', {'request_id': str(uuid.uuid4())})
        self.post(path + 'review/', {'choice': 'reject'})
        self.assertEqual(self.client.put(path, json.dumps(self.payload(record['attributes'])), content_type='application/json').status_code, 409)
        self.post(path + 'generation/', {'request_id': str(uuid.uuid4())})
        self.assertEqual(generate_image.call_count, 2)
        result = self.post(path + 'review/', {'choice': 'crop'})
        self.assertEqual(result.json()['image_source'], 'worn_crop')
        row = WornGeneration.objects.get(pk=record['id'])
        self.assertEqual(row.garment.image.name, row.original.name)

    @patch('apps.wardrobe.worn.generate')
    @patch('apps.wardrobe.worn.segment', return_value=(b'segment', b'mask', True))
    def test_failures_are_recorded_once_and_can_only_fallback_explicitly(self, segment, generate_image):
        for code in ('GENERATION_TIMEOUT', 'GENERATION_RATE_LIMITED', 'GENERATION_CREDITS_REQUIRED'):
            record, path = self.draft()
            generate_image.side_effect = AnalysisError(code, 503)
            key = {'request_id': str(uuid.uuid4())}
            result = self.post(path + 'generation/', key)
            self.assertEqual(result.json()['error'], code)
            count = generate_image.call_count
            self.post(path + 'generation/', key)
            self.assertEqual(generate_image.call_count, count)
            row = WornGeneration.objects.get(pk=record['id'])
            self.assertFalse(row.candidate)
            self.assertFalse(row.garment.saved)
            self.assertEqual(self.post(path + 'review/', {'choice': 'generated'}).status_code, 409)
            self.assertEqual(self.post(path + 'review/', {'choice': 'crop'}).status_code, 200)

    @patch('apps.wardrobe.worn.generate')
    def test_other_account_cannot_read_original_generated_status_or_mutate(self, generate_image):
        record, path = self.draft()
        other = User.objects.create_user('generation_other', 'test-password')
        _, token = issue_token(other)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'
        for suffix in ('generation/', 'worn-image/original/', 'worn-image/candidate/', 'worn-image/segmented/'):
            self.assertEqual(self.client.get(path + suffix).status_code, 404)
        self.assertEqual(self.post(path + 'generation/', {'request_id': str(uuid.uuid4())}).status_code, 404)
        self.assertEqual(self.post(path + 'review/', {'choice': 'crop'}).status_code, 404)
        self.client.defaults.clear()
        self.assertEqual(self.client.get(path + 'generation/').status_code, 401)
        generate_image.assert_not_called()

    def test_upload_retry_reuses_draft_and_does_not_reanalyze(self):
        key = str(uuid.uuid4())
        first, path = self.draft(request_id=key)
        with patch('apps.wardrobe.views.analyze_image') as analyze:
            second = self.client.post('/api/wardrobe/analyze-worn/?category=outerwear&request_id=' + key, photo(), content_type='image/jpeg')
        self.assertEqual(first['id'], second.json()['id'])
        analyze.assert_not_called()
        self.assertEqual(Garment.objects.count(), 1)

    @patch('apps.wardrobe.worn.generate')
    def test_running_request_is_not_duplicated_and_interrupted_status_is_recoverable(self, generate_image):
        record, path = self.draft()
        row = WornGeneration.objects.get(pk=record['id'])
        row.status, row.request_id, row.started_at = 'running', uuid.uuid4(), timezone.now()
        row.save()
        self.assertEqual(self.post(path + 'generation/', {'request_id': str(uuid.uuid4())}).json()['status'], 'running')
        row.started_at = timezone.now() - timedelta(minutes=4)
        row.save()
        self.assertEqual(self.client.get(path + 'generation/').json()['error'], 'GENERATION_INTERRUPTED')
        generate_image.assert_not_called()


@override_settings(OPENROUTER_API_KEY='test-only', OPENROUTER_IMAGE_MODEL='test-image-model')
class GenerationProviderTests(SimpleTestCase):
    @patch('apps.wardrobe.worn.build_opener')
    def test_image_request_has_three_references_and_no_retries(self, opener):
        response = MagicMock()
        response.read.return_value = json.dumps({'data': [{'b64_json': base64.b64encode(photo()).decode()}], 'usage': {'cost': .039}}).encode()
        opener.return_value.open.return_value.__enter__.return_value = response
        image, cost = generate(photo(), photo(), photo(), dict(attributes(), category='outerwear'))
        payload = json.loads(opener.return_value.open.call_args.args[0].data)
        self.assertEqual(payload['n'], 1)
        self.assertEqual(len(payload['input_references']), 3)
        self.assertFalse(payload['provider']['allow_fallbacks'])
        self.assertEqual(cost, Decimal('.039'))
        self.assertTrue(image.startswith(b'\xff\xd8'))
        opener.return_value.open.assert_called_once()

    @patch('apps.wardrobe.worn.build_opener')
    def test_quota_and_timeout_are_sanitized(self, opener):
        for failure, expected in [(HTTPError('provider', 429, 'secret', {}, BytesIO(b'secret')), 'GENERATION_RATE_LIMITED'),
                                  (TimeoutError(), 'GENERATION_TIMEOUT')]:
            opener.return_value.open.side_effect = failure
            with self.assertRaises(AnalysisError) as error: generate(photo(), photo(), photo(), attributes())
            self.assertEqual(error.exception.code, expected)

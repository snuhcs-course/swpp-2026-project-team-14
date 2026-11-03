"""Worn-photo contracts, rejection, ownership and existing registration regression."""
import json
import tempfile
from unittest.mock import patch

from django.test import TestCase, override_settings

from apps.users.auth import issue_token
from apps.users.models import User
from .analysis import AnalysisError, PROMPT, _build_request, analyze_image
from .models import Garment
from .tests import attributes, envelope, photo


@override_settings(OPENROUTER_API_KEY='test-key')
class WornRegistrationTests(TestCase):
    def setUp(self):
        self.owner = User.objects.create_user('worn_owner', 'test-password')
        _, token = issue_token(self.owner)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        setting = override_settings(MEDIA_ROOT=directory.name)
        setting.enable()
        self.addCleanup(setting.disable)

    def post(self, category='top'):
        return self.client.post('/api/wardrobe/analyze-worn/?category=' + category, photo(), content_type='image/jpeg')

    @patch('apps.wardrobe.views.analyze_image')
    def test_auth_and_target_validation_precede_provider(self, analyze):
        self.assertEqual(self.post('shoes').status_code, 400)
        self.client.defaults.clear()
        self.assertEqual(self.post().status_code, 401)
        analyze.assert_not_called()

    @patch('apps.wardrobe.views.analyze_image')
    def test_draft_is_private_and_save_allows_unknown_fit_length_without_measured_dimensions(self, analyze):
        analyze.return_value = attributes(), None
        response = self.post()
        self.assertEqual(response.status_code, 200)
        analyze.assert_called_once_with(photo(), 'test-key', target_category='top', include_wearing=True)
        record = response.json()
        self.assertEqual(record['image_source'], 'worn_crop')
        self.assertIsNone(record['dimensions'])
        self.assertFalse(record['saved'])
        path = '/api/wardrobe/items/' + record['id'] + '/'
        image = self.client.get(path + 'image/')
        self.assertEqual(image.status_code, 200)
        image.close()
        self.assertEqual(self.client.get('/api/wardrobe/items/').json()['items'], [])
        payload = {'attributes': attributes(), 'dimensions': None, 'notes': ''}
        self.assertEqual(self.client.put(path, json.dumps(payload), content_type='application/json').status_code, 409)
        self.assertEqual(self.client.post(path + 'review/', json.dumps({'choice': 'crop'}), content_type='application/json').status_code, 200)
        self.assertEqual(self.client.put(path, json.dumps(payload), content_type='application/json').status_code, 400)
        payload['labels'] = {'fit': None, 'length': None, 'confirmed': True}
        self.assertEqual(self.client.put(path, json.dumps(payload), content_type='application/json').status_code, 200)
        # Use the category's vocabulary rather than assuming English or display labels.
        from recommendation.labeling import options
        payload['labels']['fit'] = options()['top']['fit'][0]
        payload['labels']['length'] = options()['top']['length'][0]
        for _ in range(2):
            self.assertEqual(self.client.put(path, json.dumps(payload), content_type='application/json').status_code, 200)
        self.assertEqual(Garment.objects.count(), 1)
        self.assertEqual(len(self.client.get('/api/wardrobe/items/').json()['items']), 1)
        payload['dimensions'] = {'unit': 'cm', 'shoulder_width': {
            'value': 40, 'source': 'user_measured', 'method': 'unspecified', 'reference': None}}
        self.assertEqual(self.client.put(path, json.dumps(payload), content_type='application/json').status_code, 400)
        other = User.objects.create_user('worn_other', 'test-password')
        _, token = issue_token(other)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'
        for suffix in ('', 'image/'):
            self.assertEqual(self.client.get(path + suffix).status_code, 404)
        self.assertEqual(self.client.put(path, json.dumps(payload), content_type='application/json').status_code, 404)
        # Check private access before confirmation too.
        Garment.objects.filter(pk=record['id']).update(saved=False)
        self.assertEqual(self.client.get(path + 'image/').status_code, 404)
        self.client.defaults.clear()
        self.assertEqual(self.client.get(path + 'image/').status_code, 401)

    @patch('apps.wardrobe.views.analyze_image', side_effect=AnalysisError('IMAGE_UNCLEAR', 422))
    def test_unidentifiable_target_creates_no_draft(self, analyze):
        self.assertEqual(self.post().status_code, 422)
        self.assertFalse(Garment.objects.exists())

    def test_target_prompt_does_not_relax_existing_analysis(self):
        original = json.loads(_build_request(photo(), 'test-key').data)
        self.assertEqual(original['messages'][0]['content'], PROMPT)
        target = json.loads(_build_request(photo(), 'test-key', target_category='outerwear').data)
        prompt = target['messages'][0]['content']
        self.assertIn('worn outerwear', prompt)
        self.assertIn('ignore their features completely', prompt)
        self.assertIn('multiple people', prompt)
        self.assertIn('Never reconstruct', prompt)

    @patch('apps.wardrobe.analysis._send_request', return_value=envelope())
    def test_provider_category_mismatch_is_rejected(self, send):
        with self.assertRaises(AnalysisError) as error:
            analyze_image(photo(), 'test-key', target_category='bottom')
        self.assertEqual(error.exception.code, 'IMAGE_TARGET_UNCLEAR')

    @patch('apps.wardrobe.analysis._send_request', return_value=envelope({'image_status': 'multiple', 'attributes': None}))
    def test_existing_multi_garment_rejection_remains(self, send):
        with self.assertRaises(AnalysisError) as error:
            analyze_image(photo(), 'test-key')
        self.assertEqual(error.exception.code, 'IMAGE_MULTIPLE')

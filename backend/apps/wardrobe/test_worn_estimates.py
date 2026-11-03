from copy import deepcopy
import json
import tempfile
import uuid
from unittest.mock import patch

from django.test import SimpleTestCase, TestCase, override_settings

from apps.users.auth import issue_token
from apps.users.models import User
from recommendation.body import Body
from recommendation.fit import owned_fit
from .analysis import _build_request, analyze_image
from .models import Garment
from .schema import AI_FIELDS
from .tests import attributes, envelope, photo
from .test_worn_batch import candidates, source
from .worn_estimates import suggestions


def profile():
    return {'height_cm': 165, 'gender': 'female', 'measurements': [
        {'type': 'chest', 'value_cm': 90, 'confidence': 'medium'},
        {'type': 'waist', 'value_cm': 70, 'confidence': 'medium'},
        {'type': 'hip', 'value_cm': 96, 'confidence': 'medium'}]}


def wearing(**changes):
    return {'fit': '루즈', 'length': None, 'visibility': 'clear', 'waist_visible': False,
            'upright_front': True, 'person_y': [0, 1000], 'garment_length_y': [200, 600], **changes}


class PhotoEstimateMathTests(SimpleTestCase):
    def test_width_uses_existing_category_ease_and_original_fit_with_integer_rough_range(self):
        top_labels, top = suggestions(attributes(), None, wearing(), profile())
        outer_attrs = dict(attributes(), category='outerwear', subcategory='coat')
        _, outer = suggestions(outer_attrs, None, wearing(), profile())
        self.assertEqual(top_labels['fit_basis'], '본인 착용 사진에서 추정')
        self.assertEqual(top['measurements']['chest_width_half'], {'value': 53, 'min': 49, 'max': 57})
        self.assertGreater(outer['measurements']['chest_width_half']['value'], top['measurements']['chest_width_half']['value'])
        self.assertEqual(top['source'], 'body_photo_estimate')
        self.assertEqual(top['method'], 'body_photo_v1')
        self.assertEqual(top['quality'], 'rough')
        self.assertNotIn('shoulder_width', top['measurements'])
        self.assertFalse(top_labels['too_small'])

    def test_no_body_defaults_no_hidden_endpoints_or_generated_geometry(self):
        for body in (None, {}, {'height_cm': 165, 'measurements': []}):
            labels, estimate = suggestions(attributes(), None, wearing(person_y=None), body)
            self.assertEqual(labels['fit'], '루즈')
            self.assertIsNone(estimate)
        for seen in (None, wearing(visibility='unclear')):
            labels, estimate = suggestions(attributes(), None, seen, profile())
            self.assertIsNone(labels['fit'])
            self.assertIsNone(labels['length'])
            self.assertIsNone(estimate)
        for seen in (wearing(upright_front=False), wearing(person_y=None), wearing(garment_length_y=None),
                     wearing(garment_length_y=[600, 200]), wearing(person_y=[100, 500])):
            _, estimate = suggestions(attributes(), None, seen, profile())
            self.assertNotIn('total_length', estimate['measurements'])

    def test_length_uses_owner_height_and_visible_photo_span_with_broad_range(self):
        _, estimate = suggestions(attributes(), None, wearing(), profile())
        length = estimate['measurements']['total_length']
        self.assertEqual(length['value'], 66)
        self.assertLess(length['min'], 60)
        self.assertGreater(length['max'], 70)
        self.assertEqual(estimate['body_basis']['height'], 165)
        _, missing = suggestions(attributes(), None, wearing(), {'height_cm': 165, 'measurements': []})
        self.assertEqual(set(missing['measurements']), {'total_length'})

    def test_bottom_needs_identifiable_waist_and_widens_low_quality_body_range(self):
        attrs = dict(attributes(), category='bottom', subcategory='shorts')
        _, estimate = suggestions(attrs, None, wearing(fit='와이드'), profile())
        self.assertIn('hip_width_half', estimate['measurements'])
        self.assertNotIn('waist_width_half', estimate['measurements'])
        _, visible = suggestions(attrs, None, wearing(fit='와이드', waist_visible=True), profile())
        self.assertIn('waist_width_half', visible['measurements'])
        poor = profile()
        poor['measurements'][0]['confidence'] = 'low'
        _, broad = suggestions(attributes(), None, wearing(), poor)
        _, usual = suggestions(attributes(), None, wearing(), profile())
        self.assertLess(broad['measurements']['chest_width_half']['min'], usual['measurements']['chest_width_half']['min'])

    @patch('apps.wardrobe.analysis._send_request')
    def test_manual_target_wearing_is_in_same_call_and_regular_contract_stays_unchanged(self, send):
        data = {'image_status': 'single', 'attributes': {k: attributes()[k] for k in AI_FIELDS},
                'details': None, 'wearing': wearing()}
        send.return_value = envelope(data)
        attrs, details, seen = analyze_image(photo(), 'test-key', target_category='top', include_wearing=True)
        self.assertEqual(seen['fit'], '루즈')
        send.assert_called_once()
        original = json.loads(_build_request(photo(), 'test-key').data)
        self.assertNotIn('wearing', original['generationConfig']['responseJsonSchema']['properties'])


@override_settings(GEMINI_API_KEY='test-key')
class PhotoEstimateApiTests(TestCase):
    def setUp(self):
        self.user = User.objects.create_user('estimate_owner', 'test-password', body_profile=profile())
        _, token = issue_token(self.user)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        settings = override_settings(MEDIA_ROOT=directory.name)
        settings.enable()
        self.addCleanup(settings.disable)

    def post(self, path, data):
        return self.client.post(path, json.dumps(data), content_type='application/json')

    def draft(self):
        rows = candidates()
        rows[0]['wearing'] = wearing()
        with patch('apps.wardrobe.worn_detection.detect', return_value=rows), patch('apps.wardrobe.worn.generate') as generate:
            result = self.client.post('/api/wardrobe/worn-photos/?request_id=' + str(uuid.uuid4()), source(), content_type='image/jpeg').json()
            path = f"/api/wardrobe/worn-photos/{result['id']}/"
            record = self.post(path + 'prepare/', {'candidate_ids': [result['candidates'][0]['id']]}).json()['items'][0]
            generate.assert_not_called()
        return path, record

    def payload(self, record):
        return {'id': record['id'], 'image_choice': 'crop', 'attributes': record['attributes'],
                'dimensions': None, 'notes': '', 'labels': dict(record['labels'], confirmed=True)}

    def test_estimates_persist_privately_and_never_become_measured_dimensions(self):
        path, record = self.draft()
        self.assertIsNone(record['dimensions'])
        self.assertIsNotNone(record['estimated_dimensions'])
        result = self.post(path + 'save/', {'items': [self.payload(record)]})
        self.assertEqual(result.status_code, 200)
        saved = result.json()['items'][0]
        self.assertEqual(saved['estimated_dimensions'], record['estimated_dimensions'])
        row = Garment.objects.get(pk=record['id'])
        self.assertIsNone(row.dimensions)
        self.assertFalse(owned_fit(Body.from_json(profile()), '아우터', row.dimensions).too_small)
        self.assertIsNone(owned_fit(Body.from_json(profile()), '아우터', row.dimensions).label)
        other = User.objects.create_user('estimate_other', 'test-password')
        _, token = issue_token(other)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'
        self.assertEqual(self.client.get(f"/api/wardrobe/items/{row.pk}/").status_code, 404)

    def test_unknown_fit_length_can_save_and_changed_geometry_invalidates_estimates(self):
        for field, value in [('fit', None), ('length', None), ('subcategory', 'jacket')]:
            path, record = self.draft()
            payload = self.payload(record)
            if field == 'subcategory': payload['attributes'][field] = value
            else: payload['labels'][field] = value
            # The initial length is unknown, so use a real change to invalidate the estimate.
            if field == 'length': payload['labels']['length'] = '롱'
            result = self.post(path + 'save/', {'items': [payload]})
            self.assertEqual(result.status_code, 200)
            self.assertIsNone(result.json()['items'][0]['estimated_dimensions'])

    def test_missing_profile_is_not_replaced_by_height_or_size_defaults_and_no_client_estimate_injection(self):
        self.user.body_profile = None
        self.user.save(update_fields=['body_profile'])
        path, record = self.draft()
        self.assertIsNone(record['estimated_dimensions'])
        payload = self.payload(record)
        payload['labels']['fit'] = payload['labels']['length'] = None
        self.assertEqual(self.post(path + 'save/', {'items': [payload]}).status_code, 200)
        path, record = self.draft()
        payload = self.payload(record)
        payload['estimated_dimensions'] = {'unit': 'cm'}
        self.assertEqual(self.post(path + 'save/', {'items': [payload]}).status_code, 400)
        payload.pop('estimated_dimensions')
        payload['dimensions'] = {'unit': 'cm', 'total_length': {
            'value': 66, 'source': 'body_photo_estimate', 'method': 'body_photo_v1', 'reference': None}}
        self.assertEqual(self.post(path + 'save/', {'items': [payload]}).status_code, 400)

    def test_single_garment_path_also_saves_server_estimates_without_extra_analysis(self):
        with patch('apps.wardrobe.views.analyze_image', return_value=(attributes(), None, wearing())) as analyze:
            result = self.client.post('/api/wardrobe/analyze-worn/?category=top', photo(), content_type='image/jpeg')
        self.assertEqual(result.status_code, 200)
        self.assertIsNotNone(result.json()['estimated_dimensions'])
        self.assertIsNone(result.json()['dimensions'])
        analyze.assert_called_once()

import json
import tempfile
from io import BytesIO
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError

from django.test import SimpleTestCase, TestCase, TransactionTestCase, override_settings
from PIL import Image

from .analysis import AnalysisError, MAX_IMAGE_BYTES, MODEL, analyze_image, prepare_image
from .schema import AI_FIELDS, ARRAY_LIMITS, ENUMS, validate_attributes


def photo():
    output = BytesIO()
    Image.new('RGB', (24, 32), 'white').save(output, 'JPEG')
    return output.getvalue()


def attributes():
    result = {key: [] if key in ARRAY_LIMITS else None for key in ENUMS}
    result.update(name='흰색 티셔츠', category='top', subcategory='tshirt', colors=['white'])
    return result


def envelope(data=None, finish='STOP'):
    result = data if data is not None else {'image_status': 'single', 'attributes': {k: attributes()[k] for k in AI_FIELDS}}
    return json.dumps({'candidates': [{'finishReason': finish, 'content': {'parts': [{'text': json.dumps(result)}]}}]}).encode()


class DatabaseConfigurationTests(SimpleTestCase):
    @patch('apps.wardrobe.views.settings')
    @patch('apps.wardrobe.views.analyze_image')
    def test_missing_database_blocks_persistence_before_paid_analysis(self, analyze, settings):
        settings.DATABASES = {'default': {'ENGINE': 'django.db.backends.dummy'}}
        item = '/api/wardrobe/items/00000000-0000-0000-0000-000000000001/'
        requests = [
            ('get', '/api/wardrobe/items/'), ('get', item), ('put', item),
            ('get', item + 'image/'), ('post', '/api/wardrobe/analyze/'),
        ]
        for method, url in requests:
            with self.subTest(method=method, url=url):
                response = getattr(self.client, method)(url)
                self.assertEqual(response.status_code, 503)
                self.assertEqual(response.json(), {'error': 'DATABASE_NOT_CONFIGURED'})
        analyze.assert_not_called()

    @patch('apps.wardrobe.views.settings')
    @patch('apps.wardrobe.views.detect', return_value={'suggestions': []})
    def test_landmarks_and_options_do_not_require_database(self, detect, settings):
        settings.DATABASES = {}
        self.assertEqual(self.client.get('/api/wardrobe/options/').status_code, 200)
        response = self.client.post('/api/wardrobe/landmarks/?garment=short_sleeve_top',
                                   photo(), content_type='image/jpeg')
        self.assertEqual(response.status_code, 200)
        detect.assert_called_once()


class SchemaTests(SimpleTestCase):
    def test_visual_output(self):
        self.assertEqual(validate_attributes(attributes())['subcategory'], 'tshirt')

    def test_rejects_nonvisual_or_unknown_fields(self):
        for key in ('dimensions', 'material_note', 'stretch', 'seasons', 'thickness', 'owner_id', 'pattern', 'length'):
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_attributes(dict(attributes(), **{key: None}))

    def test_rejects_invalid_enums_duplicates_and_category_conflicts(self):
        for changes in ({'colors': ['white', 'white']}, {'colors': ['beige', 'white', 'black', 'blue']},
                        {'fit_type': 'huge'}, {'subcategory': 'jeans'}, {'leg_shape': 'wide'},
                        {'name': ' '}, {'category': None}, {'colors': [3]}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate_attributes(dict(attributes(), **changes))

    def test_applicable_bottom_fields(self):
        result = dict(attributes(), category='bottom', subcategory='jeans', leg_shape='wide')
        self.assertEqual(validate_attributes(result)['leg_shape'], 'wide')
        with self.assertRaises(ValueError):
            validate_attributes(dict(result, neckline='crew'))


class AttributeMigrationTests(TransactionTestCase):
    def test_removes_old_fields_without_changing_measurements_or_other_data(self):
        from importlib import import_module
        from django.apps import apps
        from django.db import connection
        from .models import Garment

        previous = dict(attributes(), pattern='graphic', length='regular')
        dimensions = {'unit': 'cm', 'total_length': {
            'value': 68.43, 'source': 'arcore_assisted', 'method': 'back_neck_to_hem', 'reference': None}}
        garment = Garment.objects.create(image='garments/example.jpg', attributes=previous,
            original_attributes=previous, dimensions=dimensions, notes='보존할 메모', saved=True)
        migration = import_module('apps.wardrobe.migrations.0002_remove_pattern_and_length')
        with connection.schema_editor(atomic=False) as editor:
            migration.remove_pattern_and_length(apps, editor)
        garment.refresh_from_db()
        self.assertEqual(garment.attributes, attributes())
        self.assertEqual(garment.original_attributes, attributes())
        self.assertEqual(garment.dimensions, dimensions)
        self.assertEqual(garment.notes, '보존할 메모')
        self.assertTrue(garment.saved)
        self.assertEqual(garment.image.name, 'garments/example.jpg')


class ImageTests(SimpleTestCase):
    def test_valid_image_is_jpeg_and_bounded(self):
        with Image.open(BytesIO(prepare_image(photo()))) as image:
            self.assertEqual(image.format, 'JPEG')
            self.assertLessEqual(max(image.size), 1536)

    def test_invalid_or_oversized_images(self):
        for raw, code in ((b'not an image', 'INVALID_IMAGE'), (b'', 'INVALID_IMAGE'),
                          (b'x' * (MAX_IMAGE_BYTES + 1), 'IMAGE_TOO_LARGE')):
            with self.subTest(code=code), self.assertRaises(AnalysisError) as error:
                prepare_image(raw)
            self.assertEqual(error.exception.code, code)


class GeminiTests(SimpleTestCase):
    def mock_response(self, opener, body):
        response = MagicMock()
        response.__enter__.return_value.read.return_value = body
        opener.return_value.open.return_value = response

    @patch('apps.wardrobe.analysis.build_opener')
    def test_key_is_header_only_and_request_uses_fixed_model(self, opener):
        self.mock_response(opener, envelope())
        self.assertEqual(analyze_image(photo(), 'test-secret')['name'], '흰색 티셔츠')
        request = opener.return_value.open.call_args.args[0]
        self.assertIn(MODEL, request.full_url)
        self.assertNotIn('test-secret', request.full_url)
        self.assertNotIn('test-secret', request.data.decode())
        self.assertEqual(request.get_header('X-goog-api-key'), 'test-secret')
        body = json.loads(request.data)
        self.assertEqual(body['contents'][0]['parts'][0]['inlineData']['mimeType'], 'image/jpeg')
        self.assertIn('responseJsonSchema', body['generationConfig'])
        schema = body['generationConfig']['responseJsonSchema']['properties']['attributes']
        self.assertEqual(set(schema['properties']), {'name', 'category', 'colors'})
        self.assertIsNone(analyze_image(photo(), 'test-secret')['fit_type'])
        self.assertEqual(opener.return_value.open.call_args.kwargs['timeout'], 30)

    @patch('apps.wardrobe.analysis.build_opener')
    def test_missing_key_does_not_call_provider(self, opener):
        with self.assertRaises(AnalysisError) as error:
            analyze_image(photo(), '')
        self.assertEqual(error.exception.code, 'AI_NOT_CONFIGURED')
        opener.assert_not_called()

    @patch('apps.wardrobe.analysis.build_opener')
    def test_rejects_blocked_truncated_malformed_and_forbidden_outputs(self, opener):
        for body in (b'bad json', b'{}', envelope(finish='MAX_TOKENS'), envelope(finish='SAFETY'),
                     envelope({'image_status': 'single', 'attributes': dict(attributes(), dimensions={'unit': 'cm'})})):
            with self.subTest(body=body):
                self.mock_response(opener, body)
                with self.assertRaises(AnalysisError) as error:
                    analyze_image(photo(), 'test-key')
                self.assertEqual(error.exception.code, 'AI_INVALID_RESPONSE')

    @patch('apps.wardrobe.analysis.build_opener')
    def test_no_garment_or_multiple_is_not_success(self, opener):
        for status in ('no_garment', 'multiple', 'unsupported', 'unclear'):
            self.mock_response(opener, envelope({'image_status': status, 'attributes': None}))
            with self.assertRaises(AnalysisError) as error:
                analyze_image(photo(), 'test-key')
            self.assertEqual(error.exception.status, 422)

    @patch('apps.wardrobe.analysis.build_opener')
    def test_optional_visual_fields_are_not_generated_and_bottom_dimensions_remain_editable(self, opener):
        from .editor import dimension_fields
        self.mock_response(opener, envelope({'image_status': 'single', 'attributes': {
            'name': '검정 바지', 'category': 'bottom', 'colors': ['black']}}))
        result = analyze_image(photo(), 'test-key')
        self.assertIsNone(result['subcategory'])
        self.assertIsNone(result['fit_type'])
        self.assertIn('waist_width_half', dimension_fields(result))
        self.mock_response(opener, envelope({'image_status': 'single', 'attributes': attributes()}))
        with self.assertRaises(AnalysisError):
            analyze_image(photo(), 'test-key')

    @patch('apps.wardrobe.analysis.build_opener')
    def test_provider_failures_are_sanitized_and_not_retried(self, opener):
        for status, code in ((429, 'AI_RATE_LIMITED'), (403, 'AI_AUTH_FAILED'), (500, 'AI_PROVIDER_ERROR')):
            opener.reset_mock()
            opener.return_value.open.side_effect = HTTPError('https://provider', status, 'private error', {}, BytesIO(b'private detail'))
            with self.assertRaises(AnalysisError) as error:
                analyze_image(photo(), 'test-key')
            self.assertEqual(str(error.exception), code)
            opener.return_value.open.assert_called_once()

    @patch('apps.wardrobe.analysis.build_opener')
    def test_timeout(self, opener):
        opener.return_value.open.side_effect = TimeoutError()
        with self.assertRaises(AnalysisError) as error:
            analyze_image(photo(), 'test-key')
        self.assertEqual(error.exception.code, 'AI_TIMEOUT')


@override_settings(GEMINI_API_KEY='test-key')
class AnalysisEndpointTests(TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        setting = override_settings(MEDIA_ROOT=directory.name)
        setting.enable()
        self.addCleanup(setting.disable)

    @patch('apps.wardrobe.views.analyze_image', return_value=attributes())
    def test_photo_analysis_returns_attributes_and_korean_display(self, analyze):
        response = self.client.post('/api/wardrobe/analyze/', photo(), content_type='image/jpeg')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['model'], MODEL)
        self.assertIn({'label': '종류', 'value': '티셔츠'}, response.json()['display'])
        self.assertNotIn('dimensions', response.json()['attributes'])
        self.assertEqual(response['Cache-Control'], 'no-store')

    @patch('apps.wardrobe.views.analyze_image')
    def test_wrong_content_type_and_method_do_not_call_provider(self, analyze):
        self.assertEqual(self.client.get('/api/wardrobe/analyze/').status_code, 405)
        self.assertEqual(self.client.post('/api/wardrobe/analyze/', '{}', content_type='application/json').status_code, 415)
        analyze.assert_not_called()

    @override_settings(GEMINI_API_KEY='')
    def test_key_missing_returns_safe_error(self):
        response = self.client.post('/api/wardrobe/analyze/', photo(), content_type='image/jpeg')
        self.assertEqual(response.status_code, 503)
        self.assertEqual(response.json(), {'error': 'AI_NOT_CONFIGURED'})

    def test_busy(self):
        from .views import ANALYSIS_SLOTS
        with ANALYSIS_SLOTS:
            response = self.client.post('/api/wardrobe/analyze/', photo(), content_type='image/jpeg')
        self.assertEqual(response.status_code, 429)


class WardrobePersistenceTests(AnalysisEndpointTests):
    def draft(self):
        with patch('apps.wardrobe.views.analyze_image', return_value=attributes()):
            response = self.client.post('/api/wardrobe/analyze/', photo(), content_type='image/jpeg')
        self.assertEqual(response.status_code, 200)
        return response.json()['id']

    def payload(self):
        return {'attributes': dict(attributes(), name='수정한 티셔츠', colors=['black']),
                'dimensions': {'unit': 'cm', 'chest_width_half': {
                    'value': 55.127, 'source': 'arcore_manual', 'method': 'flat_underarm_to_underarm',
                    'reference': '사용자 지정점'}},
                'user_properties': {'material_note': '면 100%', 'seasons': ['spring']},
                'notes': '세탁은 찬물로.\n여행 갈 때 챙기기.'}

    def test_analyze_edit_save_list_photo_and_update_round_trip(self):
        from .models import Garment
        item_id = self.draft()
        self.assertEqual(self.client.get('/api/wardrobe/items/').json()['items'], [])
        self.assertEqual(self.client.get(f'/api/wardrobe/items/{item_id}/image/').status_code, 404)
        url = f'/api/wardrobe/items/{item_id}/'
        payload = self.payload()
        response = self.client.put(url, json.dumps(payload), content_type='application/json')
        self.assertEqual(response.status_code, 200)
        record = response.json()
        self.assertTrue(record['saved'])
        self.assertEqual(record['dimensions']['chest_width_half']['value'], 55.13)
        self.assertEqual(record['notes'], payload['notes'])
        self.assertEqual(record['user_properties']['material_note'], '면 100%')
        self.assertEqual(self.client.get('/api/wardrobe/items/').json()['items'], [record])
        self.assertEqual(self.client.get(url).json(), record)
        image = self.client.get(record['image_url'])
        self.assertEqual(image.status_code, 200)
        self.assertEqual(image['Content-Type'], 'image/jpeg')
        self.assertGreater(len(b''.join(image.streaming_content)), 0)
        # Repeating a timed-out save must not duplicate a wardrobe entry.
        self.client.put(url, json.dumps(payload), content_type='application/json')
        self.assertEqual(Garment.objects.filter(saved=True).count(), 1)
        payload['notes'] = ''
        payload['attributes']['name'] = '다시 수정'
        payload['dimensions'] = None
        response = self.client.put(url, json.dumps(payload), content_type='application/json')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['notes'], '')
        self.assertIsNone(response.json()['dimensions'])
        self.assertEqual(self.client.get(url).json()['attributes']['name'], '다시 수정')
        self.assertEqual(Garment.objects.get(pk=item_id).original_attributes['name'], '흰색 티셔츠')

    def test_invalid_payload_does_not_save_draft(self):
        from .models import Garment
        item_id = self.draft()
        url = f'/api/wardrobe/items/{item_id}/'
        for change in ({'notes': 'x' * 2001}, {'attributes': dict(attributes(), category='shoes')},
                       {'user_properties': {'stretch': 'guess'}}, {'extra': True},
                       {'dimensions': {'unit': 'inch'}},
                       {'dimensions': {'unit': 'cm', 'waist_width_half': None}}):
            with self.subTest(change=change):
                payload = dict(self.payload(), **change)
                response = self.client.put(url, json.dumps(payload), content_type='application/json')
                self.assertEqual(response.status_code, 400)
                self.assertFalse(Garment.objects.get(pk=item_id).saved)
        for number in (True, '55cm', 0, -1, float('nan'), float('inf')):
            payload = self.payload()
            payload['dimensions']['chest_width_half']['value'] = number
            response = self.client.put(url, json.dumps(payload), content_type='application/json')
            self.assertEqual(response.status_code, 400)

    def test_catalog_matches_visual_and_user_fields(self):
        response = self.client.get('/api/wardrobe/options/')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['enums']['category'], ENUMS['category'])
        self.assertNotIn('stretch', response.json()['enums'])
        self.assertIn('stretch', response.json()['user_enums'])

    def test_malformed_json_and_oversized_request(self):
        item_id = self.draft()
        url = f'/api/wardrobe/items/{item_id}/'
        self.assertEqual(self.client.put(url, '{', content_type='application/json').status_code, 400)
        self.assertEqual(self.client.put(url, 'x' * 17000, content_type='application/json').status_code, 413)

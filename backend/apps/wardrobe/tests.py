# AI-generated with Codex, 2026-09-29, reviewed by Hyeon U Jeong; recommendation labels added with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
import json
import tempfile
from io import BytesIO
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError

from django.test import SimpleTestCase, TestCase, TransactionTestCase, override_settings
from PIL import Image

from .analysis import AnalysisError, MAX_IMAGE_BYTES, MODEL, analyze_image, prepare_image
from .schema import AI_FIELDS, ARRAY_LIMITS, COLOR_PALETTE, ENUMS, validate_ai_attributes, validate_attributes


def photo():
    output = BytesIO()
    Image.new('RGB', (24, 32), 'white').save(output, 'JPEG')
    return output.getvalue()


def attributes():
    result = {key: [] if key in ARRAY_LIMITS else None for key in ENUMS}
    result.update(name='흰색 티셔츠', category='top', subcategory='tshirt', colors=['#FFFFFF'])
    return result


def envelope(data=None, finish='STOP'):
    if data is None:
        data = {
            'image_status': 'single',
            'attributes': {key: attributes()[key] for key in AI_FIELDS},
        }
    response = {
        'candidates': [
            {
                'finishReason': finish,
                'content': {'parts': [{'text': json.dumps(data)}]},
            },
        ],
    }
    return json.dumps(response).encode()


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
        for key in ('dimensions', 'material_note', 'touch', 'stretch', 'sheerness', 'seasons', 'thickness',
                    'owner_id', 'pattern', 'length', 'formality', 'closure', 'details', 'shoulder_construction', 'neckline', 'sleeve_length'):
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_attributes(dict(attributes(), **{key: None}))

    def test_rejects_invalid_enums_duplicates_and_category_conflicts(self):
        cases = {
            'duplicate_colors': {'colors': ['#FFFFFF', '#ffffff']},
            'too_many_colors': {'colors': list(COLOR_PALETTE)[:6]},
            'color_name': {'colors': ['white']},
            'short_hex': {'colors': ['#FFF']},
            'invalid_hex': {'colors': ['#FFFFFG']},
            'unknown_style': {'styles': ['lovely']},
            'unknown_fit': {'fit_type': 'huge'},
            'wrong_subcategory': {'subcategory': 'jeans'},
            'inapplicable_leg_shape': {'leg_shape': 'wide'},
            'blank_name': {'name': ' '},
            'missing_category': {'category': None},
            'non_string_color': {'colors': [3]},
        }
        for case, changes in cases.items():
            with self.subTest(case=case), self.assertRaises(ValueError):
                validate_attributes(dict(attributes(), **changes))

    def test_photo_colors_are_normalized_without_palette_quantization(self):
        self.assertEqual(validate_attributes(dict(attributes(), colors=['#ab125f']))['colors'], ['#AB125F'])
        self.assertEqual(validate_attributes(dict(attributes(), colors=[]))['colors'], [])
        self.assertEqual(len(validate_attributes(dict(attributes(), colors=list(COLOR_PALETTE)[:5]))['colors']), 5)

    def test_ai_requires_one_or_two_palette_colors(self):
        data = {key: attributes()[key] for key in AI_FIELDS}
        for colors in (['#FFFFFF'], ['#FFFFFF', '#C83C3C']):
            self.assertEqual(validate_ai_attributes(dict(data, colors=colors))['colors'], colors)
        for colors in ([], ['#123456'], ['#ffffff'], ['#FFFFFF'] * 2, list(COLOR_PALETTE)[:3]):
            with self.subTest(colors=colors), self.assertRaises(ValueError):
                validate_ai_attributes(dict(data, colors=colors))

    def test_applicable_bottom_fields(self):
        result = dict(attributes(), category='bottom', subcategory='jeans', leg_shape='wide')
        self.assertEqual(validate_attributes(result)['leg_shape'], 'wide')
        with self.assertRaises(ValueError):
            validate_attributes(dict(result, sleeve_length='long'))


class InitialMigrationTests(TransactionTestCase):
    def test_fresh_database_creates_current_schema_and_saves_json(self):
        from django.db import connection
        from django.db.migrations.executor import MigrationExecutor
        from .models import Garment

        executor = MigrationExecutor(connection)
        executor.migrate([('wardrobe', None)])
        latest = MigrationExecutor(connection).loader.graph.leaf_nodes('wardrobe')
        MigrationExecutor(connection).migrate(latest)
        with connection.cursor() as cursor:
            columns = {column.name for column in connection.introspection.get_table_description(cursor, Garment._meta.db_table)}
        self.assertEqual(columns, {field.column for field in Garment._meta.local_fields})
        self.assertNotIn('user_properties', columns)
        self.assertNotIn('original_attributes', columns)
        dimensions = {'unit': 'cm', 'shoulder_width': {
            'value': 45.32, 'source': 'arcore_assisted',
            'method': 'flat_shoulder_seam_to_seam', 'reference': None}}
        garment = Garment.objects.create(image='garments/example.jpg', attributes=attributes(), dimensions=dimensions)
        garment.refresh_from_db()
        self.assertEqual(garment.attributes, attributes())
        self.assertEqual(garment.dimensions, dimensions)
        self.assertEqual(garment.notes, '')
        self.assertFalse(garment.saved)


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
        self.assertEqual(analyze_image(photo(), 'test-secret')[0]['name'], '흰색 티셔츠')
        request = opener.return_value.open.call_args.args[0]
        self.assertIn(MODEL, request.full_url)
        self.assertNotIn('test-secret', request.full_url)
        self.assertNotIn('test-secret', request.data.decode())
        self.assertEqual(request.get_header('X-goog-api-key'), 'test-secret')
        body = json.loads(request.data)
        self.assertEqual(body['contents'][0]['parts'][0]['inlineData']['mimeType'], 'image/jpeg')
        self.assertIn('responseJsonSchema', body['generationConfig'])
        schema = body['generationConfig']['responseJsonSchema']['properties']['attributes']
        self.assertEqual(set(schema['properties']), {'name', 'category', 'colors', 'subcategory', 'styles'})
        self.assertEqual(schema['properties']['styles']['maxItems'], 2)
        self.assertEqual(schema['properties']['colors']['maxItems'], 2)
        self.assertEqual(schema['properties']['colors']['minItems'], 1)
        self.assertEqual(schema['properties']['colors']['items']['enum'], list(COLOR_PALETTE))
        self.assertIsNone(analyze_image(photo(), 'test-secret')[0]['fit_type'])
        self.assertEqual(opener.return_value.open.call_args.kwargs['timeout'], 30)

    @patch('apps.wardrobe.analysis.build_opener')
    def test_missing_key_does_not_call_provider(self, opener):
        with self.assertRaises(AnalysisError) as error:
            analyze_image(photo(), '')
        self.assertEqual(error.exception.code, 'AI_NOT_CONFIGURED')
        opener.assert_not_called()

    @patch('apps.wardrobe.analysis.build_opener')
    def test_rejects_blocked_truncated_malformed_and_forbidden_outputs(self, opener):
        forbidden_output = {
            'image_status': 'single',
            'attributes': dict(attributes(), dimensions={'unit': 'cm'}),
        }
        cases = {
            'malformed_json': b'bad json',
            'missing_candidates': b'{}',
            'token_limit': envelope(finish='MAX_TOKENS'),
            'blocked': envelope(finish='SAFETY'),
            'forbidden_fields': envelope(forbidden_output),
        }
        for case, body in cases.items():
            with self.subTest(case=case):
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
    def test_missing_suggestions_are_allowed_and_bottom_dimensions_remain_editable(self, opener):
        from .editor import dimension_fields
        response = {
            'image_status': 'single',
            'attributes': {
                'name': '검정 바지',
                'category': 'bottom',
                'colors': ['#202020'],
            },
        }
        self.mock_response(opener, envelope(response))
        result, details = analyze_image(photo(), 'test-key')
        self.assertIsNone(details)
        self.assertIsNone(result['subcategory'])
        self.assertIsNone(result['fit_type'])
        self.assertIn('waist_width_half', dimension_fields(result))
        self.mock_response(opener, envelope({'image_status': 'single', 'attributes': attributes()}))
        with self.assertRaises(AnalysisError):
            analyze_image(photo(), 'test-key')

    @patch('apps.wardrobe.analysis.build_opener')
    def test_design_details_are_optional_hints_in_the_kfashion_vocabulary(self, opener):
        self.mock_response(opener, envelope())
        analyze_image(photo(), 'test-key')
        body = json.loads(opener.return_value.open.call_args.args[0].data)
        schema = body['generationConfig']['responseJsonSchema']
        self.assertIn('details', schema['required'])
        self.assertEqual(set(schema['properties']['details']['properties']),
                         {'print', 'neckline', 'collar', 'design_details', 'materials'})
        top = {'image_status': 'single', 'attributes': {key: attributes()[key] for key in AI_FIELDS}}
        empty = {'design_details': [], 'materials': []}
        cases = (
            ({'print': '스트라이프', 'neckline': '브이넥', 'collar': None, **empty}, 'top',
             {'print': '스트라이프', 'neckline': '브이넥', 'collar': None, **empty}),
            ({'print': '체크', 'neckline': '라운드넥', 'collar': '셔츠칼라', **empty}, 'bottom',
             {'print': '체크', 'neckline': None, 'collar': None, **empty}),
            ({'print': None, 'neckline': None, 'collar': None,
              'design_details': ['프릴', '리본', 'sparkles', '프릴', '포켓', '지퍼'], 'materials': ['데님', '면', '니트', '가죽']}, 'top',
             {'print': None, 'neckline': None, 'collar': None, 'design_details': ['프릴', '리본', '포켓'], 'materials': ['데님', '니트']}),
            ({'print': 'polka', 'neckline': None, 'collar': None, **empty}, 'top', None),
            ('striped', 'top', None),
            ({'print': '무지', 'neckline': None, 'collar': None}, 'shoes', None),
        )
        for details, category, expected in cases:
            with self.subTest(details=details, category=category):
                data = dict(top, attributes=dict(top['attributes'], category=category), details=details)
                self.mock_response(opener, envelope(data))
                self.assertEqual(analyze_image(photo(), 'test-key')[1], expected)
        self.mock_response(opener, envelope(dict(top, extra=1)))
        with self.assertRaises(AnalysisError):
            analyze_image(photo(), 'test-key')

    @patch('apps.wardrobe.analysis.build_opener')
    def test_subcategory_and_style_suggestions_are_checked(self, opener):
        def analyze(category, subcategory, styles):
            data = {'name': '니트', 'category': category, 'colors': ['#FFFFFF'], 'subcategory': subcategory, 'styles': styles}
            self.mock_response(opener, envelope({'image_status': 'single', 'attributes': data}))
            return analyze_image(photo(), 'test-key')[0]
        result = analyze('top', 'knitwear', ['minimal', 'lovely', 'casual', 'minimal', 'street'])
        self.assertEqual((result['subcategory'], result['styles']), ('knitwear', ['minimal', 'casual']))
        self.assertIsNone(analyze('top', 'jeans', [])['subcategory'])  # not a top
        self.assertIsNone(analyze('bottom', ['skirt'], 'casual')['subcategory'])
        self.assertIsNone(result['fit_type'])  # the fit comes from the measurements, never from the photo

    @patch('apps.wardrobe.analysis.build_opener')
    def test_measured_garment_type_is_a_prompt_hint(self, opener):
        self.mock_response(opener, envelope())
        analyze_image(photo(), 'test-key', 'shorts')
        text = json.loads(opener.return_value.open.call_args.args[0].data)['contents'][0]['parts'][1]['text']
        self.assertIn('measured it as shorts', text)
        analyze_image(photo(), 'test-key', 'gown')
        text = json.loads(opener.return_value.open.call_args.args[0].data)['contents'][0]['parts'][1]['text']
        self.assertEqual(text, 'Classify this garment.')

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

    @patch('apps.wardrobe.views.analyze_image', return_value=(attributes(), None))
    def test_photo_analysis_returns_only_id_model_and_attributes(self, analyze):
        response = self.client.post('/api/wardrobe/analyze/', photo(), content_type='image/jpeg')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['model'], MODEL)
        self.assertEqual(set(response.json()), {'id', 'model', 'attributes', 'details'})
        self.assertEqual(response.json()['attributes'], attributes())
        self.assertNotIn('dimensions', response.json()['attributes'])
        self.assertEqual(response['Cache-Control'], 'no-store')
        self.assertIsNone(analyze.call_args.args[2])

    @patch('apps.wardrobe.views.analyze_image', return_value=(attributes(), None))
    def test_measured_garment_type_is_passed_only_when_known(self, analyze):
        self.client.post('/api/wardrobe/analyze/?garment=skirt', photo(), content_type='image/jpeg')
        self.assertEqual(analyze.call_args.args[2], 'skirt')
        self.client.post('/api/wardrobe/analyze/?garment=gown', photo(), content_type='image/jpeg')
        self.assertIsNone(analyze.call_args.args[2])

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
        with patch('apps.wardrobe.views.analyze_image', return_value=(attributes(), None)):
            response = self.client.post('/api/wardrobe/analyze/', photo(), content_type='image/jpeg')
        self.assertEqual(response.status_code, 200)
        return response.json()['id']

    def payload(self):
        return {
            'attributes': dict(attributes(), name='수정한 티셔츠', colors=['#202020']),
            'dimensions': {
                'unit': 'cm',
                'chest_width_half': {
                    'value': 55.127,
                    'source': 'arcore_manual',
                    'method': 'flat_underarm_to_underarm',
                    'reference': '사용자 지정점',
                },
            },
            'notes': '세탁은 찬물로.\n여행 갈 때 챙기기.',
        }

    def test_analyze_edit_save_list_photo_and_update_round_trip(self):
        from .models import Garment
        item_id = self.draft()
        self.assertEqual(self.client.get('/api/wardrobe/items/').json()['items'], [])
        self.assertEqual(self.client.get(f'/api/wardrobe/items/{item_id}/image/').status_code, 404)
        url = f'/api/wardrobe/items/{item_id}/'
        payload = self.payload()
        payload['attributes']['colors'] = ['#FFFFFF', '#a12b3c']
        response = self.client.put(url, json.dumps(payload), content_type='application/json')
        self.assertEqual(response.status_code, 200)
        record = response.json()
        self.assertTrue(record['saved'])
        self.assertEqual(record['attributes']['colors'], ['#FFFFFF', '#A12B3C'])
        self.assertEqual(Garment.objects.get(pk=item_id).attributes['colors'], ['#FFFFFF', '#A12B3C'])
        self.assertEqual(record['dimensions']['chest_width_half']['value'], 55.13)
        self.assertEqual(record['notes'], payload['notes'])
        self.assertNotIn('user_properties', record)
        self.assertEqual(self.client.get('/api/wardrobe/items/').json()['items'], [record])
        self.assertEqual(self.client.get(url).json(), record)
        image = self.client.get(record['image_url'])
        self.assertEqual(image.status_code, 200)
        self.assertEqual(image['Content-Type'], 'image/jpeg')
        self.assertGreater(len(b''.join(image.streaming_content)), 0)
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
        self.assertEqual(Garment.objects.get(pk=item_id).attributes['name'], '다시 수정')

    def test_invalid_payload_does_not_save_draft(self):
        from .models import Garment
        item_id = self.draft()
        url = f'/api/wardrobe/items/{item_id}/'
        cases = {
            'long_notes': {'notes': 'x' * 2001},
            'wrong_category': {'attributes': dict(attributes(), category='shoes')},
            'removed_properties': {'user_properties': {'stretch': 'guess'}},
            'unknown_field': {'extra': True},
            'wrong_unit': {'dimensions': {'unit': 'inch'}},
            'inapplicable_dimension': {'dimensions': {'unit': 'cm', 'waist_width_half': None}},
        }
        for case, change in cases.items():
            with self.subTest(case=case):
                payload = dict(self.payload(), **change)
                response = self.client.put(url, json.dumps(payload), content_type='application/json')
                self.assertEqual(response.status_code, 400)
                self.assertFalse(Garment.objects.get(pk=item_id).saved)
        for number in (True, '55cm', 0, -1, float('nan'), float('inf')):
            payload = self.payload()
            payload['dimensions']['chest_width_half']['value'] = number
            response = self.client.put(url, json.dumps(payload), content_type='application/json')
            self.assertEqual(response.status_code, 400)

    def test_catalog_contains_only_supported_fields(self):
        response = self.client.get('/api/wardrobe/options/')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['enums']['category'], ENUMS['category'])
        self.assertEqual(set(response.json()['enums']), {
            'category', 'subcategory', 'styles', 'fit_type', 'leg_shape', 'rise_type', 'skirt_shape'})
        self.assertEqual(response.json()['color_palette'], COLOR_PALETTE)
        self.assertNotIn('lovely', response.json()['enums']['styles'])
        self.assertNotIn('user_enums', response.json())
        self.assertNotIn('user_labels', response.json())

    def test_malformed_json_and_oversized_request(self):
        item_id = self.draft()
        url = f'/api/wardrobe/items/{item_id}/'
        self.assertEqual(self.client.put(url, '{', content_type='application/json').status_code, 400)
        self.assertEqual(self.client.put(url, 'x' * 17000, content_type='application/json').status_code, 413)


BODY = {'height_cm': 162, 'gender': 'female', 'measurements': [
    {'type': 'chest', 'value_cm': 84}, {'type': 'waist', 'value_cm': 68}, {'type': 'hip', 'value_cm': 94},
    {'type': 'shoulder_width', 'value_cm': 38}, {'type': 'torso_length', 'value_cm': 44},
    {'type': 'sleeve_length', 'value_cm': 56}, {'type': 'inseam', 'value_cm': 72}]}


def measured(**values):
    return {'unit': 'cm', **{
        key: {'value': value, 'source': 'arcore_manual', 'method': 'unspecified', 'reference': None}
        for key, value in values.items()}}


class RecommendationLabelTests(TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        setting = override_settings(MEDIA_ROOT=directory.name)
        setting.enable()
        self.addCleanup(setting.disable)

    def preview(self, **data):
        return self.client.post('/api/wardrobe/labels/', json.dumps(data), content_type='application/json')

    def test_labels_are_computed_from_dimensions_and_body(self):
        response = self.preview(attributes=attributes(), body=BODY,
                                dimensions=measured(chest_width_half=50, total_length=66, sleeve_length=20),
                                details={'print': '스트라이프', 'neckline': '라운드넥', 'collar': None})
        self.assertEqual(response.status_code, 200)
        labels = response.json()['labels']
        self.assertEqual((labels['fit'], labels['length'], labels['sleeve']), ('루즈', '노멀', '반팔'))
        self.assertEqual((labels['prints'], labels['neckline']), (['스트라이프'], '라운드넥'))
        self.assertFalse(labels['too_small'])
        self.assertFalse(labels['confirmed'])
        self.assertEqual(response['Cache-Control'], 'no-store')
        shoes = dict(attributes(), category='shoes', subcategory='sneakers')
        self.assertIsNone(self.preview(attributes=shoes, body=BODY, dimensions=None).json()['labels'])

    def test_without_a_body_profile_the_user_picks_the_fit(self):
        response = self.preview(attributes=dict(attributes(), fit_type='loose'), dimensions=measured(chest_width_half=50),
                                details={'print': '무지', 'design_details': ['포켓'], 'materials': ['니트']},
                                garment_type='long_sleeve_top')
        self.assertEqual(response.status_code, 200)
        labels = response.json()['labels']
        self.assertEqual((labels['fit'], labels['fit_basis']), ('루즈', '기존에 고른 핏'))
        self.assertEqual((labels['sleeve'], labels['length'], labels['too_small']), ('긴팔', None, False))
        self.assertEqual((labels['design_details'], labels['materials'], labels['body_basis']), (['포켓'], ['니트'], {}))
        self.assertIsNone(self.preview(attributes=attributes(), dimensions=None).json()['labels']['fit'])

    def test_invalid_label_requests(self):
        cases = {
            'bad_garment_type': {'attributes': attributes(), 'dimensions': None, 'body': BODY, 'garment_type': 'gown'},
            'body_out_of_range': {'attributes': attributes(), 'dimensions': None,
                                  'body': {'height_cm': 162, 'measurements': [{'type': 'chest', 'value_cm': 900}]}},
            'no_height': {'attributes': attributes(), 'dimensions': None, 'body': {'chest': 84}},
            'bad_attributes': {'attributes': {'name': 'x'}, 'dimensions': None, 'body': BODY},
            'bad_dimensions': {'attributes': attributes(), 'dimensions': {'unit': 'inch'}, 'body': BODY},
        }
        for case, data in cases.items():
            with self.subTest(case=case):
                self.assertEqual(self.preview(**data).status_code, 400)
        self.assertEqual(self.client.post('/api/wardrobe/labels/', '[]', content_type='application/json').status_code, 400)
        self.assertEqual(self.client.post('/api/wardrobe/labels/', '{}', content_type='text/plain').status_code, 415)

    def test_reviewed_labels_are_saved_with_the_garment(self):
        from .models import Garment
        with patch('apps.wardrobe.views.analyze_image', return_value=(attributes(), None)):
            item_id = self.client.post('/api/wardrobe/analyze/', photo(), content_type='image/jpeg').json()['id']
        url = f'/api/wardrobe/items/{item_id}/'
        dimensions = measured(chest_width_half=50, total_length=66, sleeve_length=20)
        labels = self.preview(attributes=attributes(), body=BODY, dimensions=dimensions).json()['labels']
        labels.update(fit='오버사이즈', materials=['린넨'], confirmed=True)
        payload = {'attributes': attributes(), 'dimensions': dimensions, 'notes': '', 'labels': labels}
        response = self.client.put(url, json.dumps(payload), content_type='application/json')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['labels']['fit'], '오버사이즈')
        self.assertTrue(Garment.objects.get(pk=item_id).labels['confirmed'])
        # a save without the labels key keeps them; an invalid label is rejected
        del payload['labels']
        response = self.client.put(url, json.dumps(payload), content_type='application/json')
        self.assertEqual(response.json()['labels']['fit'], '오버사이즈')
        payload['labels'] = dict(labels, fit='스키니')
        self.assertEqual(self.client.put(url, json.dumps(payload), content_type='application/json').status_code, 400)
        # shoes never keep labels
        shoes = dict(attributes(), category='shoes', subcategory='sneakers')
        payload = {'attributes': shoes, 'dimensions': None, 'notes': ''}
        self.assertIsNone(self.client.put(url, json.dumps(payload), content_type='application/json').json()['labels'])

    def test_saved_labels_reach_model_inputs_and_invalid_category_is_rejected(self):
        from .models import Garment
        from recommendation.wardrobe import to_item
        from recommendation.masked import garment_tokens

        attrs = dict(attributes(), colors=['#FFFFFF', '#24344B', '#C83C3C'])
        with patch('apps.wardrobe.views.analyze_image', return_value=(attrs, None)):
            item_id = self.client.post('/api/wardrobe/analyze/', photo(), content_type='image/jpeg').json()['id']
        url = f'/api/wardrobe/items/{item_id}/'
        labels = self.preview(attributes=attrs, dimensions=None).json()['labels']
        labels.update(season='겨울', prints=['하운즈 투스'], confirmed=True)
        payload = {'attributes': attrs, 'dimensions': None, 'notes': '', 'labels': labels}
        self.assertEqual(self.client.put(url, json.dumps(payload), content_type='application/json').status_code, 200)
        stored = self.client.get(url).json()
        self.assertEqual(Garment.objects.get(pk=item_id).labels, labels)
        self.assertEqual(stored['attributes']['colors'], attrs['colors'])
        item = to_item(stored)
        self.assertEqual((item.attrs['색상'], item.attrs['서브색상']), ('화이트', '네이비'))
        self.assertIn(('상의', '계절', '겨울'), garment_tokens(item.slot, item.attrs))
        for wrong in ('팬츠', '없는카테고리'):
            payload['labels'] = dict(labels, kf_category=wrong)
            self.assertEqual(self.client.put(url, json.dumps(payload), content_type='application/json').status_code, 400)
        self.assertEqual(self.client.get(url).json()['labels'], labels)

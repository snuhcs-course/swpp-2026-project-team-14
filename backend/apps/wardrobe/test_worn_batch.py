from copy import deepcopy
from decimal import Decimal
from io import BytesIO
import json
import tempfile
import uuid
from unittest.mock import patch

from django.test import TestCase, SimpleTestCase, override_settings
from PIL import Image

from apps.users.auth import issue_token
from apps.users.models import User
from .analysis import AnalysisError, MODEL, prepare_image
from .models import Garment, WornGeneration, WornPhoto
from .schema import AI_FIELDS
from .tests import attributes, envelope
from . import worn_detection
from recommendation.labeling import options


def source():
    output = BytesIO()
    Image.new('RGB', (240, 480), '#553322').save(output, 'JPEG')
    return output.getvalue()


def candidates():
    return [{'id': str(uuid.uuid4()), 'bounds': bounds,
             'attributes': dict(attributes(), category=category, name=name, subcategory=kind),
             'details': {'design_details': ['단추'] if category == 'outerwear' else [], 'materials': []}}
            for category, name, kind, bounds in [
                ('outerwear', '갈색 코트', 'coat', [100, 100, 900, 900]),
                ('top', '니트 상의', 'knitwear', [300, 250, 700, 600]),
                ('bottom', '검정 반바지', 'shorts', [200, 600, 800, 900])]]


def layered_candidates():
    rows = candidates()
    rows[1]['attributes'].update(category='bottom', subcategory='skirt', name='회색 플리츠 스커트')
    rows[1]['bounds'] = [250, 450, 800, 700]
    rows[2]['attributes'].update(subcategory='slacks', name='회색 슬랙스')
    rows[2]['bounds'] = [200, 600, 850, 950]
    return rows


class WornDetectionTests(SimpleTestCase):
    def result(self, rows=None, people=1):
        values = []
        for row in rows if rows is not None else candidates():
            box = row['bounds']
            values.append({'box_2d': [box[1], box[0], box[3], box[2]],
                           'attributes': {k: row['attributes'][k] for k in AI_FIELDS}, 'details': row['details']})
        return envelope({'person_count': people, 'garments': values})

    @patch('apps.wardrobe.worn_detection._send_request')
    def test_original_detection_has_separate_boxes_and_original_only_attributes(self, send):
        send.return_value = self.result()
        result = worn_detection.detect(source(), 'test-key')
        payload = json.loads(send.call_args.args[0].data)
        self.assertEqual(payload['model'], MODEL)
        self.assertEqual(send.call_args.args[0].full_url, 'https://openrouter.ai/api/v1/chat/completions')
        self.assertEqual(send.call_args.args[0].get_header('Authorization'), 'Bearer test-key')
        self.assertEqual(len(result), 3)
        self.assertEqual([r['attributes']['category'] for r in result], ['outerwear', 'top', 'bottom'])
        self.assertEqual([r['bounds'] for r in result], [r['bounds'] for r in candidates()])
        self.assertIn('never copy', payload['messages'][0]['content'])
        self.assertNotIn('dimensions', result[0]['attributes'])
        schema = payload['response_format']['json_schema']['schema']
        # Numeric bounds and nested array limits caused real provider HTTP 400s;
        # the server still rejects invalid boxes/counts in the following test.
        self.assertNotIn('maxItems', schema['properties']['garments'])
        fields = schema['properties']['garments']['items']['properties']
        self.assertIn('wearing', fields)
        self.assertEqual(fields['attributes']['properties']['category']['enum'], ['top', 'bottom', 'outerwear'])
        self.assertNotIn('maximum', fields['box_2d']['items'])

    @patch('apps.wardrobe.worn_detection._send_request')
    def test_rejects_multiple_people_duplicate_categories_invalid_boxes_and_empty_results(self, send):
        duplicated = candidates()
        duplicated[1] = deepcopy(duplicated[0])
        invalid = candidates()
        invalid[0]['bounds'] = [900, 100, 100, 900]
        outside = candidates()
        outside[0]['bounds'] = [100, 100, 1001, 900]
        cases = [(self.result(people=2), 'IMAGE_MULTIPLE_PEOPLE'),
                 (self.result(people=0), 'IMAGE_NO_PERSON'),
                 (self.result(duplicated), 'AI_INVALID_RESPONSE'),
                 (self.result(invalid), 'AI_INVALID_RESPONSE'),
                 (self.result(outside), 'AI_INVALID_RESPONSE'),
                 (self.result(candidates() + candidates()[:1]), 'AI_INVALID_RESPONSE'),
                 (self.result([]), 'IMAGE_TARGET_UNCLEAR')]
        for response, code in cases:
            send.return_value = response
            with self.assertRaises(AnalysisError) as error:
                worn_detection.detect(source(), 'test-key')
            self.assertEqual(error.exception.code, code)

    @patch('apps.wardrobe.worn_detection._send_request')
    def test_visible_skirt_over_trousers_is_three_distinct_candidates(self, send):
        rows = layered_candidates()
        send.return_value = self.result(rows)
        result = worn_detection.detect(source(), 'test-key')
        self.assertEqual([r['attributes']['subcategory'] for r in result], ['coat', 'skirt', 'slacks'])
        self.assertEqual([r['bounds'] for r in result], [r['bounds'] for r in rows])
        self.assertEqual(len({r['id'] for r in result}), 3)
        send.assert_called_once()
        duplicated = deepcopy(rows)
        duplicated[1]['attributes']['subcategory'] = 'jeans'
        send.return_value = self.result(duplicated)
        with self.assertRaises(AnalysisError):
            worn_detection.detect(source(), 'test-key')  # two trouser groups remain ambiguous

    def test_crop_uses_original_pixels_and_normalized_coordinate_order(self):
        output = Image.open(BytesIO(worn_detection.crop(source(), [250, 250, 750, 750])))
        self.assertEqual(output.size, (120, 240))
        self.assertLess(sum(abs(a - b) for a, b in zip(output.getpixel((60, 120)), (85, 51, 34))), 8)


@override_settings(OPENROUTER_API_KEY='test-key')
class WornBatchFlowTests(TestCase):
    def setUp(self):
        self.user = User.objects.create_user('batch_owner', 'test-password')
        _, token = issue_token(self.user)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        setting = override_settings(MEDIA_ROOT=directory.name)
        setting.enable()
        self.addCleanup(setting.disable)

    def post(self, path, data):
        return self.client.post(path, json.dumps(data), content_type='application/json')

    def upload(self, key=None):
        with patch('apps.wardrobe.worn_detection.detect', return_value=candidates()):
            result = self.client.post('/api/wardrobe/worn-photos/?request_id=' + (key or str(uuid.uuid4())),
                                      source(), content_type='image/jpeg')
        self.assertEqual(result.status_code, 200)
        return result.json(), f"/api/wardrobe/worn-photos/{result.json()['id']}/"

    def drafts(self):
        photo, path = self.upload()
        selected = [c['id'] for c in photo['candidates']][:2]
        result = self.post(path + 'prepare/', {'candidate_ids': selected})
        self.assertEqual(result.status_code, 200)
        return photo, path, selected, result.json()['items']

    def payload(self, record, choice='generated'):
        labels = deepcopy(record['labels'])
        choices = options()[record['attributes']['category']]
        labels.update(fit=choices['fit'][0], length=choices['length'][0], confirmed=True)
        return {'id': record['id'], 'attributes': record['attributes'], 'dimensions': None,
                'notes': '', 'labels': labels, 'image_choice': choice}

    def generate(self, records):
        with patch('apps.wardrobe.worn.segment', return_value=(b'segmented', b'mask', True)), \
                patch('apps.wardrobe.worn.generate', return_value=(source(), Decimal('.039'))):
            for record in records:
                result = self.post(f"/api/wardrobe/items/{record['id']}/generation/", {'request_id': str(uuid.uuid4())})
                self.assertEqual(result.json()['status'], 'ready')

    def test_upload_and_prepare_retries_do_not_reanalyze_or_duplicate_drafts(self):
        key = str(uuid.uuid4())
        photo, path = self.upload(key)
        with patch('apps.wardrobe.worn_detection.detect') as detect:
            repeated = self.client.post('/api/wardrobe/worn-photos/?request_id=' + key, source(), content_type='image/jpeg')
        detect.assert_not_called()
        self.assertEqual(photo['id'], repeated.json()['id'])
        selected = [c['id'] for c in photo['candidates']]
        first = self.post(path + 'prepare/', {'candidate_ids': selected}).json()
        second = self.post(path + 'prepare/', {'candidate_ids': selected}).json()
        self.assertEqual([i['id'] for i in first['items']], [i['id'] for i in second['items']])
        self.assertEqual(Garment.objects.count(), 3)
        self.assertEqual(WornPhoto.objects.count(), 1)
        for item in first['items']:
            self.assertFalse(item['saved'])
            self.assertIsNone(item['dimensions'])
            self.assertIsNone(item['labels']['fit'])
            self.assertIsNone(item['labels']['length'])  # shorts also must be chosen by the user
        self.assertEqual(self.post(path + 'prepare/', {'candidate_ids': [str(uuid.uuid4())]}).status_code, 400)

    def test_layered_skirt_and_trousers_prepare_and_save_as_separate_garments(self):
        with patch('apps.wardrobe.worn_detection.detect', return_value=layered_candidates()):
            photo = self.client.post('/api/wardrobe/worn-photos/?request_id=' + str(uuid.uuid4()),
                                     source(), content_type='image/jpeg').json()
        path = '/api/wardrobe/worn-photos/' + photo['id'] + '/'
        result = self.post(path + 'prepare/', {'candidate_ids': [c['id'] for c in photo['candidates']]})
        self.assertEqual(result.status_code, 200)
        records = result.json()['items']
        self.assertEqual([r['attributes']['subcategory'] for r in records], ['coat', 'skirt', 'slacks'])
        self.generate(records)  # mocked image generation, no external calls
        saved = self.post(path + 'save/', {'items': [self.payload(r) for r in records]})
        self.assertEqual(saved.status_code, 200)
        self.assertEqual(Garment.objects.filter(saved=True).count(), 3)
        self.assertEqual(WornGeneration.objects.values('candidate_id').distinct().count(), 3)

    def test_batch_save_requires_each_image_approval_and_is_atomic(self):
        photo, path, selected, records = self.drafts()
        self.generate(records[:1])
        rows = [self.payload(record) for record in records]
        self.assertEqual(self.post(path + 'save/', {'items': rows}).status_code, 409)
        self.assertEqual(Garment.objects.filter(saved=True).count(), 0)
        self.generate(records[1:])
        for mutation in ('missing_choice', 'unconfirmed', 'fake_cm'):
            invalid = deepcopy(rows)
            if mutation == 'missing_choice': invalid[0].pop('image_choice')
            if mutation == 'unconfirmed': invalid[1]['labels']['confirmed'] = False
            if mutation == 'fake_cm': invalid[1]['dimensions'] = {'unit': 'cm', 'total_length': {
                'value': 30, 'source': 'user_measured', 'method': 'unspecified', 'reference': None}}
            self.assertEqual(self.post(path + 'save/', {'items': invalid}).status_code, 400)
            self.assertEqual(Garment.objects.filter(saved=True).count(), 0)
        result = self.post(path + 'save/', {'items': rows})
        self.assertEqual(result.status_code, 200)
        self.assertEqual(len(result.json()['items']), 2)
        for record in result.json()['items']:
            self.assertEqual(record['image_source'], 'ai_flatlay')
            row = WornGeneration.objects.get(pk=record['id'])
            self.assertEqual(row.garment.image.name, row.candidate.name)
            self.assertEqual(row.decision, 'generated')
            self.assertIsNone(row.garment.dimensions)
            self.assertEqual(row.details, records[0]['details'] if str(row.pk) == records[0]['id'] else records[1]['details'])
            image = self.client.get(record['image_url'])
            self.assertEqual(b''.join(image.streaming_content), source())
            image.close()
        self.assertEqual(self.post(path + 'save/', {'items': rows}).status_code, 200)
        self.assertEqual(Garment.objects.count(), 2)

    def test_failed_generation_can_be_excluded_or_explicitly_saved_as_crop(self):
        photo, path, selected, records = self.drafts()
        self.generate(records[:1])
        row = WornGeneration.objects.get(pk=records[1]['id'])
        row.status, row.error = 'failed', 'GENERATION_RATE_LIMITED'
        row.save()
        result = self.post(path + 'save/', {'items': [self.payload(records[0])]})
        self.assertEqual(result.status_code, 200)
        self.assertFalse(Garment.objects.get(pk=records[1]['id']).saved)
        result = self.post(path + 'save/', {'items': [self.payload(records[1], 'crop')]})
        self.assertEqual(result.json()['items'][0]['image_source'], 'worn_crop')

    def test_private_photo_candidates_drafts_and_save_are_owner_scoped(self):
        photo, path, selected, records = self.drafts()
        other = User.objects.create_user('batch_other', 'test-password')
        _, token = issue_token(other)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'
        for url in (path, path + 'image/', photo['candidates'][0]['crop_url']):
            self.assertEqual(self.client.get(url).status_code, 404)
        self.assertEqual(self.post(path + 'prepare/', {'candidate_ids': selected}).status_code, 404)
        self.assertEqual(self.post(path + 'save/', {'items': [self.payload(records[0], 'crop')]}).status_code, 404)
        other_photo, other_path = self.upload()
        self.assertEqual(self.post(other_path + 'save/', {'items': [self.payload(records[0], 'crop')]}).status_code, 404)
        self.client.defaults.clear()
        self.assertEqual(self.client.get(path).status_code, 401)
        self.assertEqual(self.client.post('/api/wardrobe/worn-photos/', source(), content_type='image/jpeg').status_code, 401)

    def test_failed_detection_does_not_auto_retry_or_create_garments(self):
        key = str(uuid.uuid4())
        with patch('apps.wardrobe.worn_detection.detect', side_effect=AnalysisError('IMAGE_MULTIPLE_PEOPLE', 422)) as detect:
            for _ in range(2):
                result = self.client.post('/api/wardrobe/worn-photos/?request_id=' + key, source(), content_type='image/jpeg')
                self.assertEqual(result.json()['status'], 'failed')
            self.assertEqual(detect.call_count, 1)
        self.assertEqual(Garment.objects.count(), 0)

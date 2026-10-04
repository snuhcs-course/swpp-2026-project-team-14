from io import BytesIO
from unittest.mock import patch

import numpy as np
from django.test import SimpleTestCase
from PIL import Image

from .analysis import AnalysisError
from .editor import TOP_DIMENSIONS, validate_record
from .landmarks import MAX_FRAME_BYTES, decode, detect, prepare_frame
from .tests import attributes, photo
from .views import ANALYSIS_SLOTS


class LandmarkTests(SimpleTestCase):
    def test_letterbox_coordinates_round_trip_for_both_orientations(self):
        for size in ((300, 600), (600, 300)):
            with self.subTest(size=size):
                stream = BytesIO()
                Image.new('RGB', size, 'white').save(stream, 'PNG')
                tensor, transform = prepare_frame(stream.getvalue())
                self.assertEqual(tensor.shape, (1, 3, 384, 288))
                self.assertEqual(tensor.dtype, np.float32)
                w, h, left, top = transform
                heatmaps = np.zeros((1, 294, 96, 72), dtype=np.float32)
                for index, x in ((6, .25), (24, .75)):
                    heatmaps[0, index, round((h * .5 + top) / 4), round((w * x + left) / 4)] = .9
                _, suggestions = decode(heatmaps, 'short_sleeve_top', transform)
                self.assertEqual(set(suggestions), {'shoulder_width'})
                for actual, expected in zip(suggestions['shoulder_width'], ((.25, .5), (.75, .5))):
                    np.testing.assert_allclose(actual, expected, atol=.02)

    def test_class_offset_and_rejection_of_weak_or_padded_points(self):
        heatmaps = np.zeros((1, 294, 96, 72), dtype=np.float32)
        heatmaps[0, 168, 48, 18] = .9
        heatmaps[0, 170, 48, 54] = .9
        _, suggestions = decode(heatmaps, 'trousers', (288, 384, 0, 0))
        self.assertEqual(suggestions, {'waist_width_half': [[.25, .5], [.75, .5]]})
        self.assertEqual(decode(heatmaps, 'long_sleeve_top', (288, 384, 0, 0))[1], {})
        heatmaps[0, 170, 48, 54] = .2
        self.assertEqual(decode(heatmaps, 'trousers', (288, 384, 0, 0))[1], {})
        heatmaps[0, 170, 0, 54] = .9
        self.assertEqual(decode(heatmaps, 'trousers', (288, 144, 0, 120))[1], {})

    @patch('apps.wardrobe.landmarks.session')
    def test_invalid_input_does_not_load_model(self, engine):
        stream = BytesIO()
        Image.new('RGB', (1001, 1000)).save(stream, 'PNG')
        for raw, garment, code in ((photo(), 'dress', 'INVALID_GARMENT_TYPE'),
                                    (b'bad', 'trousers', 'INVALID_IMAGE'),
                                    (b'x' * (MAX_FRAME_BYTES + 1), 'trousers', 'IMAGE_TOO_LARGE'),
                                    (stream.getvalue(), 'trousers', 'IMAGE_TOO_LARGE')):
            with self.subTest(code=code), self.assertRaises(AnalysisError) as error:
                detect(raw, garment)
            self.assertEqual(error.exception.code, code)
        engine.assert_not_called()

    @patch('apps.wardrobe.landmarks.session')
    def test_endpoint_returns_points_without_db_or_cm(self, engine):
        heatmaps = np.zeros((1, 294, 96, 72), dtype=np.float32)
        heatmaps[0, 6, 24, 18] = .9
        heatmaps[0, 24, 24, 54] = .9
        engine.return_value.run.return_value = [heatmaps]
        response = self.client.post('/api/wardrobe/landmarks/?garment=short_sleeve_top', photo(), content_type='image/jpeg')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Cache-Control'], 'no-store')
        self.assertEqual(response.json()['suggestions']['shoulder_width'], [[.25, .25], [.75, .25]])
        self.assertNotIn('dimensions', response.json())
        self.assertEqual(set(response.json()), {'model', 'garment', 'points', 'suggestions', 'elapsed_ms'})
        engine.return_value.run.assert_called_once()
        self.assertEqual(self.client.get('/api/wardrobe/landmarks/').status_code, 405)
        self.assertEqual(self.client.post('/api/wardrobe/landmarks/', '{}', content_type='application/json').status_code, 415)

    @patch('apps.wardrobe.landmarks.session', side_effect=AnalysisError('LANDMARK_NOT_CONFIGURED', 503))
    def test_busy_and_failure_release_slot(self, engine):
        ANALYSIS_SLOTS.acquire()
        try:
            self.assertEqual(self.client.post('/api/wardrobe/landmarks/?garment=trousers', photo(), content_type='image/jpeg').status_code, 429)
            engine.assert_not_called()
        finally:
            ANALYSIS_SLOTS.release()
        response = self.client.post('/api/wardrobe/landmarks/?garment=trousers', photo(), content_type='image/jpeg')
        self.assertEqual(response.status_code, 503)
        self.assertEqual(response.json(), {'error': 'LANDMARK_NOT_CONFIGURED'})
        self.assertTrue(ANALYSIS_SLOTS.acquire(blocking=False))
        ANALYSIS_SLOTS.release()

    def test_assisted_measurements_preserve_provenance(self):
        measurement = {'value': 45.123, 'source': 'arcore_assisted',
                       'method': TOP_DIMENSIONS['shoulder_width'][1], 'reference': 'HRNet'}
        result = validate_record({'attributes': attributes(), 'dimensions': {'unit': 'cm', 'shoulder_width': measurement},
                                  'notes': ''})
        self.assertEqual(result['dimensions']['shoulder_width'], dict(measurement, value=45.12))

    def test_top_landmarks_supply_all_seven_measurements_and_preserve_sleeve_path(self):
        heatmaps = np.zeros((1, 294, 96, 72), dtype=np.float32)
        # DeepFashion2 short-top fixture: back neck, shoulders, underarms, hem and right sleeve.
        locations = {1: (36, 10), 7: (20, 18), 25: (52, 18), 12: (22, 35), 20: (50, 35),
                     15: (22, 80), 16: (36, 80), 17: (50, 80), 24: (58, 23), 23: (64, 28), 22: (60, 34)}
        for point, (x, y) in locations.items():
            heatmaps[0, point - 1, y, x] = .9
        _, values = decode(heatmaps, 'short_sleeve_top', (288, 384, 0, 0))
        self.assertEqual(set(values), set(TOP_DIMENSIONS))
        self.assertEqual(len(values['sleeve_length']), 3)
        np.testing.assert_allclose(values['sleeve_length'][1], [58 / 72, 23 / 96], atol=1e-6)
        heatmaps[0, 23] = 0  # Do not invent a missing intermediate seam point.
        self.assertNotIn('sleeve_length', decode(heatmaps, 'short_sleeve_top', (288, 384, 0, 0))[1])

    def test_trouser_thigh_point_is_intersection_at_crotch_and_inseam_uses_inner_leg(self):
        heatmaps = np.zeros((1, 294, 96, 72), dtype=np.float32)
        locations = {1: (18, 10), 2: (36, 10), 3: (54, 10), 4: (16, 30), 14: (56, 30),
                     5: (14, 55), 6: (12, 85), 7: (30, 85), 8: (32, 55), 9: (36, 36)}
        for point, (x, y) in locations.items():
            heatmaps[0, 168 + point - 1, y, x] = .9
        _, values = decode(heatmaps, 'trousers', (288, 384, 0, 0))
        self.assertEqual(len(values), 7)
        self.assertEqual(len(values['inseam']), 3)
        thigh = values['thigh_width_half']
        self.assertAlmostEqual(thigh[0][1], thigh[1][1], places=5)
        self.assertLess(thigh[0][0], thigh[1][0])

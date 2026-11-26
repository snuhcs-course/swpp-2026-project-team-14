# AI-generated with Codex and Claude Code (Dongkun Moon), 2026-11-25, reviewed by Dongkun Moon
from io import BytesIO
import tempfile
import numpy as np
from unittest.mock import patch

from django.core.files.base import ContentFile
from django.test import TestCase, SimpleTestCase, override_settings
from PIL import Image

from apps.users.auth import issue_token
from apps.users.models import User
from . import display_cutout
from .analysis import AnalysisError
from .models import Garment
from .tests import attributes, photo
from .worn import png


class DisplayCutoutContractTests(TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        setting = override_settings(MEDIA_ROOT=directory.name)
        setting.enable()
        self.addCleanup(setting.disable)
        self.owner = User.objects.create_user('cutout_owner', 'test-password')
        _, token = issue_token(self.owner)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'
        self.garment = Garment.objects.create(user=self.owner, attributes=attributes(), saved=True,
                                              image=ContentFile(photo(), name='source.jpg'))
        self.url = f'/api/wardrobe/items/{self.garment.pk}/display-cutout/'
        with display_cutout._lock:
            display_cutout._cache.clear()
            display_cutout._cache_bytes = 0

    @patch('apps.wardrobe.display_cutout.render')
    def test_authentication_and_ownership_precede_inference(self, render):
        self.client.defaults.clear()
        self.assertEqual(self.client.get(self.url).status_code, 401)
        other = User.objects.create_user('cutout_other', 'test-password')
        _, token = issue_token(other)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'
        self.assertEqual(self.client.get(self.url).status_code, 404)
        render.assert_not_called()

    @patch('apps.wardrobe.display_cutout.render', return_value=b'derived-png')
    def test_repeated_reads_cache_without_modifying_source_or_record(self, render):
        before = Garment.objects.values().get(pk=self.garment.pk)
        original = self.garment.image.read()
        self.garment.image.close()
        for _ in range(2):
            response = self.client.get(self.url)
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.content, b'derived-png')
            self.assertEqual(response['Content-Type'], 'image/png')
            self.assertIn('no-store', response['Cache-Control'])
        render.assert_called_once()
        self.assertEqual(before, Garment.objects.values().get(pk=self.garment.pk))
        with self.garment.image.open('rb') as source:
            self.assertEqual(original, source.read())

    @patch('apps.wardrobe.display_cutout.render', return_value=b'png')
    def test_changed_source_invalidates_derived_cache(self, render):
        self.client.get(self.url)
        with self.garment.image.open('wb') as source:
            source.write(png(Image.new('RGB', (24, 32), 'blue')))
        self.client.get(self.url)
        self.assertEqual(render.call_count, 2)

    @patch('apps.wardrobe.display_cutout.render', side_effect=AnalysisError('IMAGE_TARGET_UNCLEAR', 422))
    def test_uncertain_images_fail_explicitly_and_are_not_cached(self, render):
        for _ in range(2):
            response = self.client.get(self.url)
            self.assertEqual(response.status_code, 422)
            self.assertEqual(response.json(), {'error': 'IMAGE_TARGET_UNCLEAR'})
        self.assertEqual(render.call_count, 2)

    @patch('apps.wardrobe.display_cutout.render')
    def test_unsaved_and_nonhangable_items_do_not_invoke_model(self, render):
        self.garment.saved = False
        self.garment.save(update_fields=['saved'])
        self.assertEqual(self.client.get(self.url).status_code, 404)
        self.garment.saved = True
        self.garment.attributes['category'] = 'shoes'
        self.garment.save(update_fields=['saved', 'attributes'])
        self.assertEqual(self.client.get(self.url).status_code, 422)
        render.assert_not_called()


class DisplayCutoutPixelsTests(SimpleTestCase):
    @patch('apps.wardrobe.display_cutout.clothing_mask')
    def test_white_foreground_colour_is_preserved_and_background_becomes_transparent(self, segment):
        source = Image.new('RGB', (20, 20), (241, 242, 243))
        mask = Image.new('L', source.size)
        for y in range(3, 17):
            for x in range(3, 17):
                if not (x < 6 and y < 6):
                    mask.putpixel((x, y), 255)
        segment.return_value = mask
        result = Image.open(BytesIO(display_cutout.render(png(source), attributes())))
        self.assertEqual(result.mode, 'RGBA')
        self.assertEqual(result.getpixel((0, 0))[3], 0)
        self.assertEqual(result.getpixel((8, 8)), (241, 242, 243, 255))

    @patch('apps.wardrobe.display_cutout.clothing_mask')
    def test_sparse_partial_segmentation_is_rejected(self, segment):
        mask = Image.new('L', (20, 20))
        mask.putpixel((0, 0), 255)
        mask.putpixel((19, 19), 255)
        segment.return_value = mask
        with self.assertRaises(AnalysisError):
            display_cutout.render(png(Image.new('RGB', mask.size)), attributes())

    def test_flatlay_retains_torso_misclassified_as_dress_and_excludes_skin(self):
        labels = np.array([[0, 4, 4, 0], [0, 4, 7, 0], [0, 7, 4, 11]], dtype=np.uint8)
        mask = np.asarray(display_cutout.apparel_mask(labels, attributes()))
        self.assertTrue((mask[:, 1:3] == 255).all())
        self.assertEqual(mask[2, 3], 0)
        self.assertEqual(mask[0, 0], 0)

    def test_worn_crop_does_not_merge_other_worn_garments(self):
        labels = np.array([[4, 4], [6, 7]], dtype=np.uint8)
        mask = np.asarray(display_cutout.apparel_mask(labels, dict(attributes(), _display_source='worn_crop')))
        self.assertEqual(mask.tolist(), [[255, 255], [0, 0]])

    def test_white_on_white_avoids_colour_cutout_even_if_some_edges_remain(self):
        source = np.full((100, 100, 3), 245, dtype=np.uint8)
        source[15:85, 20:80] = 220
        self.assertIsNone(display_cutout.contrast_mask(Image.fromarray(source)))

    def test_high_contrast_studio_uses_fast_path_preserving_internal_white_logo(self):
        source = np.full((100, 100, 3), 245, dtype=np.uint8)
        source[15:85, 20:80] = 20
        source[40:50, 40:50] = 245
        mask = display_cutout.contrast_mask(Image.fromarray(source))
        self.assertIsNotNone(mask)
        self.assertEqual(mask.getpixel((45, 45)), 255)
        self.assertEqual(mask.getpixel((0, 0)), 0)

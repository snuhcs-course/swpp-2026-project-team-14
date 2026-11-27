# AI-generated with Claude Code (Claude Opus 5.5), 2026-11-26, reviewed by Dongkun Moon
"""Stored rail cutouts: made after saving, served without running the model again, cleaned up on delete."""
from io import BytesIO, StringIO
import json
import tempfile
from unittest.mock import patch

from django.core.files.base import ContentFile
from django.core.files.storage import default_storage
from django.core.management import call_command
from django.test import TestCase, override_settings
from PIL import Image

from apps.users.auth import issue_token
from apps.users.models import User
from . import cutouts
from .analysis import AnalysisError
from .models import Garment
from .tests import attributes, photo


def cutout_png(colour=(20, 20, 20, 255)):
    output = BytesIO()
    Image.new('RGBA', (8, 10), colour).save(output, 'PNG')
    return output.getvalue()


def inline_pool():
    """Run the background job right away and keep the test's database connection open."""
    return (patch.object(cutouts._pool, 'submit', side_effect=lambda job, *args: job(*args)),
            patch('apps.wardrobe.cutouts.close_old_connections'))


class StoredCutoutTests(TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        setting = override_settings(MEDIA_ROOT=directory.name)
        setting.enable()
        self.addCleanup(setting.disable)
        self.owner = User.objects.create_user('stored_cutout_owner', 'test-password')
        _, token = issue_token(self.owner)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'

    def garment(self, saved=True, **extra):
        return Garment.objects.create(user=self.owner, attributes=attributes(), saved=saved,
                                      image=ContentFile(photo(), name='source.jpg'), **extra)

    def save_draft(self, garment):
        body = {'attributes': garment.attributes, 'dimensions': None, 'notes': ''}
        submit, connections = inline_pool()
        with submit, connections, self.captureOnCommitCallbacks(execute=True):
            response = self.client.put(f'/api/wardrobe/items/{garment.pk}/', json.dumps(body),
                                       content_type='application/json')
        self.assertEqual(response.status_code, 200)
        return response

    @patch('apps.wardrobe.cutouts.render', return_value=cutout_png())
    def test_saving_a_garment_stores_its_cutout_after_commit(self, render):
        draft = self.garment(saved=False)
        self.assertFalse(draft.cutout)  # drafts get none: their photo can still change
        self.save_draft(draft)
        draft.refresh_from_db()
        render.assert_called_once()
        self.assertTrue(draft.cutout.name.startswith('cutouts/'))
        self.assertEqual(len(draft.cutout_key), 64)
        with draft.cutout.open('rb') as stored:
            self.assertEqual(stored.read(), cutout_png())
        with draft.image.open('rb') as original:  # the original photo is untouched
            self.assertEqual(original.read(), photo())

    @patch('apps.wardrobe.cutouts.render', side_effect=AnalysisError('IMAGE_TARGET_UNCLEAR', 422))
    def test_an_unclear_photo_still_saves_and_leaves_the_cutout_empty(self, render):
        draft = self.garment(saved=False)
        self.save_draft(draft)
        draft.refresh_from_db()
        self.assertTrue(draft.saved)
        self.assertFalse(draft.cutout)
        self.assertEqual(draft.cutout_key, '')

    @patch('apps.wardrobe.cutouts.render', return_value=cutout_png())
    def test_shoes_are_saved_without_a_cutout(self, render):
        draft = self.garment(saved=False)
        draft.attributes = dict(attributes(), category='shoes', subcategory='sneakers')
        draft.save(update_fields=['attributes'])
        submit, connections = inline_pool()
        with submit as job, connections, self.captureOnCommitCallbacks(execute=True):
            cutouts.schedule_cutout(Garment(pk=draft.pk, user=self.owner, attributes=draft.attributes, saved=True))
        job.assert_not_called()
        render.assert_not_called()

    def test_a_stored_cutout_is_served_without_running_the_model(self):
        garment = self.garment()
        with patch('apps.wardrobe.cutouts.render', return_value=cutout_png()):
            cutouts.ensure_cutout(garment)
        with patch('apps.wardrobe.cutouts.render') as render, \
                patch('apps.wardrobe.cutouts.clothing_mask') as segment:
            for _ in range(2):
                response = self.client.get(f'/api/wardrobe/items/{garment.pk}/display-cutout/')
                self.assertEqual(response.status_code, 200)
                self.assertEqual(response.content, cutout_png())
                self.assertEqual(response['Content-Type'], 'image/png')
        render.assert_not_called()
        segment.assert_not_called()

    def test_a_cutout_from_an_older_photo_or_model_is_made_again_and_the_old_file_removed(self):
        garment = self.garment()
        with patch('apps.wardrobe.cutouts.render', return_value=cutout_png()):
            cutouts.ensure_cutout(garment)
        old_name = garment.cutout.name
        with patch('apps.wardrobe.cutouts.render', return_value=cutout_png((200, 0, 0, 255))) as render, \
                patch('apps.wardrobe.worn.MODEL_REVISION', 'next-model'):
            self.assertEqual(cutouts.ensure_cutout(garment), cutout_png((200, 0, 0, 255)))
        render.assert_called_once()
        garment.refresh_from_db()
        self.assertNotEqual(garment.cutout.name, old_name)
        self.assertFalse(default_storage.exists(old_name))

    def test_making_a_cutout_does_not_change_when_the_garment_was_last_edited(self):
        garment = self.garment()
        before = Garment.objects.get(pk=garment.pk).updated_at
        with patch('apps.wardrobe.cutouts.render', return_value=cutout_png()):
            cutouts.ensure_cutout(garment)
        self.assertEqual(Garment.objects.get(pk=garment.pk).updated_at, before)

    def test_deleting_a_garment_removes_its_cutout_but_keeps_a_shared_one(self):
        garment, other = self.garment(), self.garment()
        with patch('apps.wardrobe.cutouts.render', return_value=cutout_png()):
            cutouts.ensure_cutout(garment)
        name = Garment.objects.get(pk=garment.pk).cutout.name
        Garment.objects.filter(pk=other.pk).update(cutout=name)  # e.g. a copied demo account
        with self.captureOnCommitCallbacks(execute=True):
            self.assertEqual(self.client.delete(f'/api/wardrobe/items/{garment.pk}/').status_code, 200)
        self.assertTrue(default_storage.exists(name))
        with self.captureOnCommitCallbacks(execute=True):
            self.assertEqual(self.client.delete(f'/api/wardrobe/items/{other.pk}/').status_code, 200)
        self.assertFalse(default_storage.exists(name))

    def test_the_item_says_where_its_cutout_is_and_whether_it_is_ready(self):
        garment = self.garment()
        item = self.client.get('/api/wardrobe/items/').json()['items'][0]
        self.assertEqual(item['cutout_url'], f'/api/wardrobe/items/{garment.pk}/display-cutout/')
        self.assertFalse(item['cutout_ready'])
        self.assertEqual(item['image_url'], f'/api/wardrobe/items/{garment.pk}/image/')
        with patch('apps.wardrobe.cutouts.render', return_value=cutout_png()):
            cutouts.ensure_cutout(garment)
        self.assertTrue(self.client.get('/api/wardrobe/items/').json()['items'][0]['cutout_ready'])

    def test_backfill_makes_missing_cutouts_and_skips_current_ones(self):
        first, second = self.garment(), self.garment()
        output = StringIO()
        call_command('make_cutouts', '--dry-run', stdout=output)
        self.assertIn('2 saved garments', output.getvalue())
        self.assertFalse(Garment.objects.exclude(cutout='').exists())
        with patch('apps.wardrobe.cutouts.render', return_value=cutout_png()) as render:
            call_command('make_cutouts', stdout=StringIO())
            call_command('make_cutouts', stdout=StringIO())  # second run finds them current
        self.assertEqual(render.call_count, 2)
        self.assertEqual(Garment.objects.exclude(cutout='').count(), 2)


class GarmentOnlyCropTests(TestCase):
    """SegFormer names a lone garment wrongly (trousers as upper clothes); on a person it must not merge clothes."""

    def test_a_crop_without_a_person_keeps_any_clothing_label(self):
        import numpy as np
        from . import worn
        labels = np.zeros((40, 20), dtype=np.uint8)
        labels[2:38, 3:17] = 4  # lone ivory slacks read as upper clothes
        keep = worn.garment_mask(labels, {'category': 'bottom', 'subcategory': 'slacks'})
        self.assertTrue(keep[2:38, 3:17].all())
        self.assertFalse(keep[0, 0])

    def test_on_a_person_only_the_chosen_garment_is_kept(self):
        import numpy as np
        from . import worn
        labels = np.zeros((40, 20), dtype=np.uint8)
        labels[0:5, 5:15] = 11   # face
        labels[5:20, 2:18] = 4   # T-shirt
        labels[20:40, 4:16] = 6  # jeans
        keep = worn.garment_mask(labels, {'category': 'bottom', 'subcategory': 'jeans'})
        self.assertTrue(keep[20:40, 4:16].all())
        self.assertFalse(keep[5:20, 2:18].any())


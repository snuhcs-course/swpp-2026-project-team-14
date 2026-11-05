"""Owner-scoped garment deletion, outfit removal and post-commit media cleanup."""
import tempfile
import uuid
from unittest.mock import patch

from django.core.files.base import ContentFile
from django.core.files.storage import default_storage
from django.db import connection, transaction
from django.test import TestCase, TransactionTestCase, override_settings

from apps.outfits.models import Outfit, OutfitItem
from apps.users.auth import issue_token
from apps.users.models import User
from .models import Garment, WornGeneration, WornPhoto


class DeleteGarmentTests(TestCase):
    def setUp(self):
        self.owner = User.objects.create_user('delete_owner', 'test-password')
        self.other = User.objects.create_user('delete_other', 'test-password')
        _, token = issue_token(self.owner)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'
        self.target = self.garment(self.owner)
        self.kept = self.garment(self.owner, 'bottom')
        self.foreign = self.garment(self.other)

    def garment(self, user, category='top'):
        return Garment.objects.create(user=user, saved=True, image=f'garments/{uuid.uuid4()}.jpg',
                                      attributes={'name': 'Test garment', 'category': category})

    def outfit(self, user, *garments, saved=True):
        row = Outfit.objects.create(user=user, source='user', saved=saved, key=str(uuid.uuid4()))
        for garment in garments:
            OutfitItem.objects.create(outfit=row, garment=garment, slot=garment.attributes['category'])
        return row

    def delete(self, garment=None):
        return self.client.delete(f'/api/wardrobe/items/{(garment or self.target).pk}/')

    def test_deletes_owner_record_and_omits_it_from_list_and_detail(self):
        response = self.delete()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {'deleted_id': str(self.target.pk)})
        self.assertEqual(response['Cache-Control'], 'no-store')
        self.assertFalse(Garment.objects.filter(pk=self.target.pk).exists())
        rows = self.client.get('/api/wardrobe/items/').json()['items']
        self.assertEqual([row['id'] for row in rows], [str(self.kept.pk)])
        self.assertEqual(self.client.get(f'/api/wardrobe/items/{self.target.pk}/').status_code, 404)
        self.assertEqual(self.client.get(f'/api/wardrobe/items/{self.target.pk}/image/').status_code, 404)

    def test_deletes_every_linked_outfit_but_preserves_other_garments_and_outfits(self):
        linked = self.outfit(self.owner, self.target, self.kept)
        recommended = self.outfit(self.owner, self.target, saved=False)
        unrelated = self.outfit(self.owner, self.kept)
        foreign = self.outfit(self.other, self.foreign)
        self.assertEqual(self.delete().status_code, 200)
        self.assertFalse(Outfit.objects.filter(pk__in=[linked.pk, recommended.pk]).exists())
        self.assertFalse(OutfitItem.objects.filter(outfit_id=linked.pk).exists())
        self.assertTrue(Outfit.objects.filter(pk=unrelated.pk).exists())
        self.assertTrue(Outfit.objects.filter(pk=foreign.pk).exists())
        self.assertTrue(Garment.objects.filter(pk=self.kept.pk).exists())
        self.assertTrue(Garment.objects.filter(pk=self.foreign.pk).exists())

    def test_other_account_cannot_delete_the_garment_or_its_outfits(self):
        outfit = self.outfit(self.other, self.foreign)
        response = self.delete(self.foreign)
        self.assertEqual(response.status_code, 404)
        self.assertEqual(response.json()['error'], 'GARMENT_NOT_FOUND')
        self.assertTrue(Garment.objects.filter(pk=self.foreign.pk).exists())
        self.assertTrue(Outfit.objects.filter(pk=outfit.pk).exists())

    def test_anonymous_delete_requires_authentication(self):
        self.client.defaults.clear()
        self.assertEqual(self.delete().status_code, 401)
        self.assertTrue(Garment.objects.filter(pk=self.target.pk).exists())

    def test_missing_record_and_repeat_delete_return_not_found(self):
        self.assertEqual(self.client.delete(f'/api/wardrobe/items/{uuid.uuid4()}/').status_code, 404)
        self.assertEqual(self.delete().status_code, 200)
        self.assertEqual(self.delete().status_code, 404)
        self.assertEqual(Garment.objects.count(), 2)

    def test_garment_failure_rolls_back_linked_outfit_deletion(self):
        outfit = self.outfit(self.owner, self.target, self.kept)
        with patch.object(Garment, 'delete', side_effect=RuntimeError('simulated database failure')):
            with self.assertRaises(RuntimeError):
                self.delete()
        self.assertTrue(Garment.objects.filter(pk=self.target.pk).exists())
        self.assertTrue(Outfit.objects.filter(pk=outfit.pk).exists())
        self.assertEqual(outfit.items.count(), 2)

    def test_removes_related_generation_record(self):
        WornGeneration.objects.create(garment=self.target, original='worn/test.jpg')
        self.assertEqual(self.delete().status_code, 200)
        self.assertFalse(WornGeneration.objects.filter(garment_id=self.target.pk).exists())


class DeleteGarmentMediaTests(TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        setting = override_settings(MEDIA_ROOT=directory.name)
        setting.enable()
        self.addCleanup(setting.disable)
        self.owner = User.objects.create_user('media_owner', 'test-password')
        self.other = User.objects.create_user('media_other', 'test-password')
        _, token = issue_token(self.owner)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'

    def file(self, name=None):
        return default_storage.save(name or f'garments/{uuid.uuid4()}.jpg', ContentFile(b'image bytes'))

    def garment(self, user=None, image=None, saved=True):
        return Garment.objects.create(user=user or self.owner, image=image or self.file(), saved=saved,
                                      attributes={'name': 'Test garment', 'category': 'top'})

    def photo(self, image=None):
        return WornPhoto.objects.create(user=self.owner, request_key=uuid.uuid4(),
                                        image=image or self.file('worn-sources/source.jpg'))

    def delete(self, garment):
        return self.client.delete(f'/api/wardrobe/items/{garment.pk}/')

    def test_deletes_photo_only_after_commit_and_preserves_response(self):
        garment = self.garment()
        name = garment.image.name
        with self.captureOnCommitCallbacks(execute=True) as callbacks:
            response = self.delete(garment)
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json(), {'deleted_id': str(garment.pk)})
            self.assertEqual(response['Cache-Control'], 'no-store')
            self.assertFalse(Garment.objects.filter(pk=garment.pk).exists())
            self.assertTrue(default_storage.exists(name))
        self.assertEqual(len(callbacks), 1)
        self.assertFalse(default_storage.exists(name))

    def test_removes_generation_files_and_source_once_without_touching_unrelated_files(self):
        original, segmented, candidate = (self.file(f'worn/{name}') for name in ('original.jpg', 'mask.png', 'flat.jpg'))
        photo = self.photo()
        garment = self.garment(image=candidate)
        kept = self.garment()
        WornGeneration.objects.create(garment=garment, photo=photo, original=original,
                                      segmented=segmented, candidate=candidate)
        names = {original, segmented, candidate, photo.image.name}
        with patch.object(default_storage, 'delete', wraps=default_storage.delete) as remove:
            with self.captureOnCommitCallbacks(execute=True):
                self.assertEqual(self.delete(garment).status_code, 200)
        self.assertEqual(remove.call_count, len(names))
        self.assertEqual({call.args[0] for call in remove.call_args_list}, names)
        self.assertTrue(all(not default_storage.exists(name) for name in names))
        self.assertFalse(WornPhoto.objects.filter(pk=photo.pk).exists())
        self.assertFalse(WornGeneration.objects.filter(garment_id=garment.pk).exists())
        self.assertTrue(Garment.objects.filter(pk=kept.pk).exists())
        self.assertTrue(default_storage.exists(kept.image.name))

    def test_shared_garment_image_survives_until_last_account_reference_is_removed(self):
        shared = self.file('he-dummy/shared.jpg')
        garment = self.garment(image=shared)
        other = self.garment(user=self.other, image=shared, saved=False)
        with self.captureOnCommitCallbacks(execute=True):
            self.assertEqual(self.delete(garment).status_code, 200)
        self.assertTrue(default_storage.exists(shared))
        self.assertTrue(Garment.objects.filter(pk=other.pk).exists())
        _, token = issue_token(self.other)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'
        with self.captureOnCommitCallbacks(execute=True):
            self.assertEqual(self.delete(other).status_code, 200)
        self.assertFalse(default_storage.exists(shared))

    def test_shared_source_survives_remaining_draft_then_is_removed_with_last_garment(self):
        photo = self.photo()
        first, second = self.garment(), self.garment(saved=False)
        WornGeneration.objects.create(garment=first, photo=photo, original=first.image.name)
        WornGeneration.objects.create(garment=second, photo=photo, original=second.image.name)
        with self.captureOnCommitCallbacks(execute=True):
            self.assertEqual(self.delete(first).status_code, 200)
        self.assertTrue(WornPhoto.objects.filter(pk=photo.pk).exists())
        self.assertTrue(default_storage.exists(photo.image.name))
        self.assertTrue(default_storage.exists(second.image.name))
        self.assertFalse(default_storage.exists(first.image.name))
        with self.captureOnCommitCallbacks(execute=True):
            self.assertEqual(self.delete(second).status_code, 200)
        self.assertFalse(WornPhoto.objects.filter(pk=photo.pk).exists())
        self.assertFalse(default_storage.exists(photo.image.name))

    def test_preserves_each_generation_field_referenced_by_another_account_draft(self):
        for field in ('original', 'segmented', 'candidate'):
            with self.subTest(field=field):
                shared = self.file()
                garment = self.garment(image=shared)
                other = self.garment(user=self.other, saved=False)
                values = {'original': other.image.name, field: shared}
                generation = WornGeneration.objects.create(garment=other, **values)
                with self.captureOnCommitCallbacks(execute=True):
                    self.assertEqual(self.delete(garment).status_code, 200)
                self.assertTrue(default_storage.exists(shared))
                self.assertTrue(WornGeneration.objects.filter(pk=generation.pk).exists())

    def test_preserves_file_used_by_another_source_photo(self):
        shared = self.file()
        garment = self.garment(image=shared)
        photo = self.photo(image=shared)
        with self.captureOnCommitCallbacks(execute=True):
            self.assertEqual(self.delete(garment).status_code, 200)
        self.assertTrue(WornPhoto.objects.filter(pk=photo.pk).exists())
        self.assertTrue(default_storage.exists(shared))

    def test_deleted_source_file_survives_a_direct_reference_from_another_garment(self):
        photo = self.photo()
        garment = self.garment()
        other = self.garment(user=self.other, image=photo.image.name)
        WornGeneration.objects.create(garment=garment, photo=photo, original=garment.image.name)
        with self.captureOnCommitCallbacks(execute=True):
            self.assertEqual(self.delete(garment).status_code, 200)
        self.assertFalse(WornPhoto.objects.filter(pk=photo.pk).exists())
        self.assertTrue(Garment.objects.filter(pk=other.pk).exists())
        self.assertTrue(default_storage.exists(photo.image.name))

    def test_rechecks_references_added_between_delete_and_commit(self):
        garment = self.garment()
        with self.captureOnCommitCallbacks(execute=True):
            self.assertEqual(self.delete(garment).status_code, 200)
            self.garment(user=self.other, image=garment.image.name, saved=False)
        self.assertTrue(default_storage.exists(garment.image.name))

    def test_outer_rollback_preserves_records_files_and_discards_cleanup(self):
        photo = self.photo()
        garment = self.garment()
        WornGeneration.objects.create(garment=garment, photo=photo, original=garment.image.name)
        outfit = Outfit.objects.create(user=self.owner, source='user', saved=True, key=str(uuid.uuid4()))
        OutfitItem.objects.create(outfit=outfit, garment=garment, slot='top')
        with self.captureOnCommitCallbacks(execute=True) as callbacks:
            with self.assertRaisesMessage(RuntimeError, 'rollback'):
                with transaction.atomic():
                    self.assertEqual(self.delete(garment).status_code, 200)
                    raise RuntimeError('rollback')
        self.assertEqual(callbacks, [])
        self.assertTrue(Garment.objects.filter(pk=garment.pk).exists())
        self.assertTrue(WornGeneration.objects.filter(garment_id=garment.pk).exists())
        self.assertTrue(WornPhoto.objects.filter(pk=photo.pk).exists())
        self.assertTrue(Outfit.objects.filter(pk=outfit.pk).exists())
        self.assertTrue(default_storage.exists(garment.image.name))
        self.assertTrue(default_storage.exists(photo.image.name))

    def test_missing_files_do_not_fail_the_delete(self):
        garment = self.garment(image='garments/missing.jpg')
        with self.captureOnCommitCallbacks(execute=True):
            self.assertEqual(self.delete(garment).status_code, 200)
        self.assertFalse(Garment.objects.filter(pk=garment.pk).exists())

    def test_file_not_found_from_storage_is_harmless(self):
        garment = self.garment()
        with patch.object(default_storage, 'delete', side_effect=FileNotFoundError):
            with self.captureOnCommitCallbacks(execute=True):
                self.assertEqual(self.delete(garment).status_code, 200)

    def test_storage_failure_is_logged_and_other_files_are_still_removed(self):
        garment = self.garment()
        generated = self.file('worn/generated.jpg')
        WornGeneration.objects.create(garment=garment, original=garment.image.name, candidate=generated)
        remove = default_storage.delete

        def fail_one(name):
            if name == garment.image.name:
                raise OSError('storage unavailable')
            remove(name)

        with patch.object(default_storage, 'delete', side_effect=fail_one):
            with self.assertLogs('apps.wardrobe.media', level='ERROR') as logs:
                with self.captureOnCommitCallbacks(execute=True):
                    self.assertEqual(self.delete(garment).status_code, 200)
        self.assertIn('Could not clean up', logs.output[0])
        self.assertFalse(Garment.objects.filter(pk=garment.pk).exists())
        self.assertTrue(default_storage.exists(garment.image.name))
        self.assertFalse(default_storage.exists(generated))

    def test_unauthorized_foreign_and_missing_requests_never_schedule_cleanup(self):
        garment = self.garment(user=self.other)
        with patch.object(default_storage, 'delete') as remove:
            with self.captureOnCommitCallbacks(execute=True) as callbacks:
                self.assertEqual(self.delete(garment).status_code, 404)
                self.assertEqual(self.client.delete(f'/api/wardrobe/items/{uuid.uuid4()}/').status_code, 404)
                self.client.defaults.clear()
                self.assertEqual(self.delete(garment).status_code, 401)
        self.assertEqual(callbacks, [])
        remove.assert_not_called()
        self.assertTrue(default_storage.exists(garment.image.name))


class DeleteGarmentCommitTests(TransactionTestCase):
    def test_api_removes_file_after_real_commit_without_manual_callback_execution(self):
        with tempfile.TemporaryDirectory() as directory, override_settings(MEDIA_ROOT=directory):
            owner = User.objects.create_user('commit_owner', 'test-password')
            _, token = issue_token(owner)
            self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'
            name = default_storage.save('garments/committed.jpg', ContentFile(b'image bytes'))
            garment = Garment.objects.create(user=owner, image=name, saved=True,
                                              attributes={'name': 'Test garment', 'category': 'top'})
            remove = default_storage.delete

            def remove_after_commit(file_name):
                self.assertFalse(connection.in_atomic_block)
                self.assertFalse(Garment.objects.filter(pk=garment.pk).exists())
                remove(file_name)

            with patch.object(default_storage, 'delete', side_effect=remove_after_commit) as cleanup:
                response = self.client.delete(f'/api/wardrobe/items/{garment.pk}/')
            self.assertEqual(response.status_code, 200)
            cleanup.assert_called_once_with(name)
            self.assertFalse(default_storage.exists(name))

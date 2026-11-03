"""Owner-scoped garment deletion and atomic removal of linked outfits."""
import uuid
from unittest.mock import patch

from django.test import TestCase

from apps.outfits.models import Outfit, OutfitItem
from apps.users.auth import issue_token
from apps.users.models import User
from .models import Garment, WornGeneration


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

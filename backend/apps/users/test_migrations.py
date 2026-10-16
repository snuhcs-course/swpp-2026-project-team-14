# AI-generated with Codex, 2026-10-16
from django.db import connection
from django.db.migrations.executor import MigrationExecutor
from django.test import TransactionTestCase

from apps.outfits.models import Outfit, OutfitItem
from apps.wardrobe.models import Garment


class OwnershipMigrationTests(TransactionTestCase):
    def test_removes_only_unowned_records_before_requiring_owner(self):
        latest = MigrationExecutor(connection).loader.graph.leaf_nodes()
        previous = [('wardrobe', '0003_garment_user'), ('outfits', '0002_outfit_user'),
                    ('users', '0002_authtoken')]
        try:
            executor = MigrationExecutor(connection)
            executor.migrate(previous)
            apps = executor.loader.project_state(previous).apps
            OldUser = apps.get_model('users', 'User')
            OldGarment = apps.get_model('wardrobe', 'Garment')
            OldOutfit = apps.get_model('outfits', 'Outfit')
            OldItem = apps.get_model('outfits', 'OutfitItem')
            user = OldUser.objects.create(username='migration_01', password='!')
            unowned_garment = OldGarment.objects.create(image='garments/old.jpg', attributes={})
            unowned_outfit = OldOutfit.objects.create(source='user', key='old')
            old_item = OldItem.objects.create(outfit=unowned_outfit, garment=unowned_garment, slot='top')
            owned_garment = OldGarment.objects.create(user=user, image='garments/new.jpg', attributes={})
            owned_outfit = OldOutfit.objects.create(user=user, source='user', key='new')
            kept_item = OldItem.objects.create(outfit=owned_outfit, garment=owned_garment, slot='top')
        finally:
            MigrationExecutor(connection).migrate(latest)
        self.assertFalse(Garment.objects.filter(pk=unowned_garment.pk).exists())
        self.assertFalse(Outfit.objects.filter(pk=unowned_outfit.pk).exists())
        self.assertFalse(OutfitItem.objects.filter(pk=old_item.pk).exists())
        self.assertTrue(Garment.objects.filter(pk=owned_garment.pk, user_id=user.pk).exists())
        self.assertTrue(Outfit.objects.filter(pk=owned_outfit.pk, user_id=user.pk).exists())
        self.assertTrue(OutfitItem.objects.filter(pk=kept_item.pk).exists())

# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
"""코디 table (apps/outfits/models.py)."""

import django.db.models.deletion
import uuid
from django.db import migrations, models


class Migration(migrations.Migration):

    initial = True

    dependencies = [
        ('wardrobe', '0002_garment_labels'),
    ]

    operations = [
        migrations.CreateModel(
            name='Outfit',
            fields=[
                ('id', models.UUIDField(default=uuid.uuid4, editable=False, primary_key=True, serialize=False)),
                ('source', models.CharField(choices=[('recommended', 'Recommended'), ('user', 'User'), ('chat', 'Chat')], max_length=16)),
                ('saved', models.BooleanField(db_index=True, default=False)),
                ('name', models.CharField(blank=True, default='', max_length=80)),
                ('key', models.CharField(db_index=True, max_length=200)),
                ('score', models.PositiveSmallIntegerField(default=None, null=True)),
                ('components', models.JSONField(default=None, null=True)),
                ('reasons', models.JSONField(default=list)),
                ('context', models.JSONField(default=dict)),
                ('feedback', models.CharField(choices=[('liked', 'Liked'), ('disliked', 'Disliked')], default=None, max_length=16, null=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('updated_at', models.DateTimeField(auto_now=True)),
            ],
        ),
        migrations.CreateModel(
            name='OutfitItem',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('slot', models.CharField(choices=[('outerwear', 'Outerwear'), ('top', 'Top'), ('bottom', 'Bottom')], max_length=16)),
                ('note', models.CharField(blank=True, default='', max_length=200)),
                ('garment', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='outfit_items', to='wardrobe.garment')),
                ('outfit', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='items', to='outfits.outfit')),
            ],
            options={
                'constraints': [models.UniqueConstraint(fields=('outfit', 'slot'), name='one_garment_per_slot')],
            },
        ),
    ]

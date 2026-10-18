# AI-generated with Codex, 2026-09-29, reviewed by Hyeon U Jeong
"""Initial wardrobe schema for a fresh database."""

import uuid
from django.db import migrations, models


class Migration(migrations.Migration):

    initial = True

    dependencies = [
    ]

    operations = [
        migrations.CreateModel(
            name='Garment',
            fields=[
                ('id', models.UUIDField(default=uuid.uuid4, editable=False, primary_key=True, serialize=False)),
                ('image', models.FileField(upload_to='garments/')),
                ('attributes', models.JSONField()),
                ('dimensions', models.JSONField(default=None, null=True)),
                ('notes', models.TextField(blank=True, default='')),
                ('saved', models.BooleanField(db_index=True, default=False)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('updated_at', models.DateTimeField(auto_now=True)),
            ],
        ),
    ]

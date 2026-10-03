import uuid

from django.db import models


class Garment(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    image = models.FileField(upload_to='garments/')
    original_attributes = models.JSONField()
    attributes = models.JSONField()
    dimensions = models.JSONField(null=True, default=None)
    notes = models.TextField(blank=True, default='')
    saved = models.BooleanField(default=False, db_index=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

# AI-generated with Codex, 2026-09-29, reviewed by Hyeon U Jeong; recommendation labels added with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
import uuid

from django.db import models


class Garment(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    image = models.FileField(upload_to='garments/')
    attributes = models.JSONField()
    dimensions = models.JSONField(null=True, default=None)
    notes = models.TextField(blank=True, default='')
    # Recommendation labels on the owner's body (recommendation/labeling.py), confirmed by the user.
    labels = models.JSONField(null=True, default=None)
    saved = models.BooleanField(default=False, db_index=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

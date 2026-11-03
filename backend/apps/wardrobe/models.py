# AI-generated with Codex, 2026-09-29, reviewed by Hyeon U Jeong; recommendation labels added with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon; user ownership added with Codex, 2026-10-16
import uuid

from django.conf import settings
from django.db import models


class CategoryInUse(ValueError):
    pass


class Garment(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, related_name='garments',
                             on_delete=models.CASCADE)
    image = models.FileField(upload_to='garments/')
    image_source = models.CharField(max_length=24, default='garment_photo',
                                    choices=[('garment_photo', '옷 사진'), ('worn_crop', '착용 사진 일부'),
                                             ('ai_flatlay', 'AI 재구성 이미지')])
    attributes = models.JSONField()
    dimensions = models.JSONField(null=True, default=None)
    # Server-derived rough estimates; never passed as measured dimensions to recommendation code.
    estimated_dimensions = models.JSONField(null=True, default=None)
    notes = models.TextField(blank=True, default='')
    # Recommendation labels on the owner's body (recommendation/labeling.py), confirmed by the user.
    labels = models.JSONField(null=True, default=None)
    saved = models.BooleanField(default=False, db_index=True)
    request_key = models.UUIDField(null=True, default=None)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        constraints = [models.UniqueConstraint(fields=['user', 'request_key'], name='wardrobe_owner_request_uniq')]

    def check_category_change(self, attributes):
        if attributes['category'] != self.attributes['category'] and self.outfit_items.exists():
            raise CategoryInUse('Garment is linked to an outfit')


class WornPhoto(models.Model):
    """Private source photo and immutable, original-image garment proposals."""
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE)
    request_key = models.UUIDField()
    image = models.FileField(upload_to='worn-sources/')
    candidates = models.JSONField(default=list)
    status = models.CharField(max_length=16, default='pending')
    error = models.CharField(max_length=48, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        constraints = [models.UniqueConstraint(fields=['user', 'request_key'], name='worn_photo_owner_request_uniq')]


class WornGeneration(models.Model):
    garment = models.OneToOneField(Garment, primary_key=True, on_delete=models.CASCADE, related_name='generation')
    original = models.FileField(upload_to='worn/')
    segmented = models.FileField(upload_to='worn/', blank=True)
    candidate = models.FileField(upload_to='worn/', blank=True)
    details = models.JSONField(null=True, default=None)  # From the original, never from generated pixels.
    status = models.CharField(max_length=16, default='pending')
    decision = models.CharField(max_length=16, default='pending')
    request_id = models.UUIDField(null=True, default=None)
    started_at = models.DateTimeField(null=True, default=None)
    error = models.CharField(max_length=48, blank=True)
    model = models.CharField(max_length=120, blank=True)
    cost_usd = models.DecimalField(max_digits=12, decimal_places=8, null=True, default=None)
    occlusion_warning = models.BooleanField(default=True)
    photo = models.ForeignKey(WornPhoto, null=True, default=None, on_delete=models.CASCADE, related_name='generations')
    candidate_id = models.UUIDField(null=True, default=None)

    class Meta:
        constraints = [models.UniqueConstraint(fields=['photo', 'candidate_id'], name='worn_photo_candidate_uniq')]

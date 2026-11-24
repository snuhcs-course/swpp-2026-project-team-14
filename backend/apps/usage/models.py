import uuid

from django.conf import settings
from django.db import models


class UsageEvent(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE)
    attempt_id = models.UUIDField()
    session_id = models.UUIDField()
    name = models.CharField(max_length=32)
    phase = models.CharField(max_length=12)
    outcome = models.CharField(max_length=12, blank=True)
    occurred_at = models.DateTimeField()
    received_at = models.DateTimeField(auto_now_add=True)
    duration_ms = models.PositiveBigIntegerField(null=True)
    error_code = models.CharField(max_length=64, blank=True)
    details = models.JSONField(default=dict)

    class Meta:
        constraints = [models.UniqueConstraint(fields=['user', 'attempt_id', 'phase'], name='usage_attempt_phase')]
        indexes = [models.Index(fields=['name', 'occurred_at'], name='usage_name_time')]

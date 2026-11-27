import logging
import uuid

from django.db import transaction
from django.utils import timezone

from .models import UsageEvent

logger = logging.getLogger(__name__)


def record_recommendations(user, keys, seen_keys, source):
    seen = {'+'.join(sorted(key.split('+'))) for key in (seen_keys or [])}
    duplicates = 0
    for key in keys:
        canonical = '+'.join(sorted(key.split('+')))
        duplicates += canonical in seen
        seen.add(canonical)
    record_outfit_event(user, 'outfit_recommendation', source=source, total_count=len(keys), duplicate_count=duplicates)


def record_outfit_event(user, name, **details):
    """A metrics failure must not turn a confirmed outfit operation into an app error."""
    try:
        with transaction.atomic():
            UsageEvent.objects.create(user=user, attempt_id=uuid.uuid4(), session_id=uuid.uuid4(),
                                      name=name, phase='finished', outcome='success', occurred_at=timezone.now(),
                                      details=details)
    except Exception:
        logger.exception('Could not persist outfit usage event')

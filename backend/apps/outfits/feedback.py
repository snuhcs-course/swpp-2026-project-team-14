"""Replay feedback with the existing taste algorithm, including chat edits/cancellation."""

from copy import deepcopy
from uuid import uuid4

from django.utils import timezone

from recommendation import preference
from recommendation.wardrobe import to_item

from .models import FeedbackRecord, FeedbackTaste, Outfit


def current_taste(user, fallback):
    state = FeedbackTaste.objects.filter(user=user).first()
    if state is None:
        return fallback
    taste = deepcopy(state.baseline)
    for record in FeedbackRecord.objects.filter(user=user, feedback__isnull=False).order_by("id"):
        sign = 1 if record.feedback == Outfit.Feedback.LIKED else -1
        taste = preference.apply_feedback(taste, [{"item_id": gid, "polarity": sign} for gid in record.garments],
                                          record.garments)
    return taste


def record_feedback(outfit, value, taste, *, channel):
    """Caller holds the user's row lock. A retry never adds another taste event."""
    if channel == "chat":
        record = FeedbackRecord.objects.filter(user=outfit.user, channel=channel, key=outfit.key).first()
        previous = record.feedback if record else None
    else:
        record = None
        previous = outfit.feedback
    if previous != value:
        FeedbackTaste.objects.get_or_create(user=outfit.user, defaults={"baseline": taste})
        items = [to_item({"id": str(i.garment_id), "attributes": i.garment.attributes, "labels": i.garment.labels})
                 for i in outfit.items.select_related("garment")]
        garments = {i.id: {"slot": i.slot, "attrs": i.attrs, "style": i.style} for i in items if i}
        if record is not None:
            record.feedback = value
            record.rated_at = timezone.now()
            record.save(update_fields=["feedback", "rated_at"])
        elif value is not None:
            FeedbackRecord.objects.create(user=outfit.user, channel=channel,
                                          key=outfit.key if channel == "chat" else str(uuid4()),
                                          feedback=value, garments=garments, rated_at=timezone.now())
    # Keep home/Codi Book UI compatible; chat state/metrics use the channel-specific record.
    if channel == "chat":
        Outfit.objects.filter(user=outfit.user, source=Outfit.Source.CHAT, key=outfit.key).update(feedback=value)
    outfit.feedback = value
    outfit.save(update_fields=["feedback", "updated_at"])
    return current_taste(outfit.user, taste)

# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
"""코디 table: an outfit is a set of the user's garments, one per slot (outerwear, top, bottom).

Recommended outfits are stored too, so likes/dislikes and "save" can refer to them by id. The owner
column comes with the user table (login, Iteration 2): until then every outfit belongs to the one
wardrobe of the server.
"""

import uuid

from django.db import models

from apps.wardrobe.models import Garment


class Outfit(models.Model):
    class Source(models.TextChoices):
        RECOMMENDED = "recommended"
        USER = "user"
        CHAT = "chat"

    class Feedback(models.TextChoices):
        LIKED = "liked"
        DISLIKED = "disliked"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    source = models.CharField(max_length=16, choices=Source.choices)
    saved = models.BooleanField(default=False, db_index=True)  # in the 코디북
    name = models.CharField(max_length=80, blank=True, default="")
    key = models.CharField(max_length=200, db_index=True)  # sorted garment ids, "w:<id>+w:<id>"
    score = models.PositiveSmallIntegerField(null=True, default=None)  # 0-100
    components = models.JSONField(null=True, default=None)  # compat, body, color, season, taste (0-100)
    reasons = models.JSONField(default=list)
    context = models.JSONField(default=dict)  # temperature, weather, preferred fit and styles, occasion
    feedback = models.CharField(max_length=16, choices=Feedback.choices, null=True, default=None)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)


class OutfitItem(models.Model):
    class Slot(models.TextChoices):  # the wardrobe category codes
        OUTERWEAR = "outerwear"
        TOP = "top"
        BOTTOM = "bottom"

    outfit = models.ForeignKey(Outfit, related_name="items", on_delete=models.CASCADE)
    garment = models.ForeignKey(Garment, related_name="outfit_items", on_delete=models.CASCADE)
    slot = models.CharField(max_length=16, choices=Slot.choices)
    note = models.CharField(max_length=200, blank=True, default="")  # "내 몸 기준 루즈 (가슴 여유 16 cm)"

    class Meta:
        constraints = [models.UniqueConstraint(fields=["outfit", "slot"], name="one_garment_per_slot")]

# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
"""The one place the server reads the user's body for labels and recommendations.

The body profile is kept on the device for now (BodyProfileStore.kt), so the app sends it with each
request. When the user table exists, read it from request.user here and nothing else has to change.
"""

from __future__ import annotations

from recommendation.body import Body, UnknownBody

# plausible ranges (cm) so a typo cannot turn every garment into "too small"
RANGES = {
    "height": (100, 230), "chest": (50, 180), "waist": (40, 180), "hip": (50, 190), "shoulder": (25, 70),
    "inseam": (40, 120), "torso_length": (30, 90), "sleeve_length": (30, 100), "outseam": (60, 140),
    "thigh": (30, 100),
}
GENDERS = ("female", "male", "unspecified")


def body_from_request(request, data: dict) -> Body:
    """Body from the request JSON ("body": app profile or flat cm values). Raises ValueError."""
    raw = data.get("body")
    if not isinstance(raw, dict):
        raise ValueError("Body profile required")
    try:
        body = Body.from_json(raw)
    except (TypeError, ValueError, AttributeError):
        raise ValueError("Invalid body profile") from None
    if body.gender not in GENDERS:
        body.gender = "unspecified"
    for field, (low, high) in RANGES.items():
        value = getattr(body, field)
        if value is not None and not low <= value <= high:
            raise ValueError(f"Invalid {field}")
    if "height" not in raw and "height_cm" not in raw:
        raise ValueError("Height required")
    return body


def body_or_unknown(request, data: dict) -> Body:
    """Like body_from_request, but a missing body, or one with only preferences and no height (body analysis
    skipped in onboarding), is an UnknownBody so recommendations still work. Raises ValueError otherwise."""
    raw = data.get("body")
    if raw is None or (isinstance(raw, dict) and "height" not in raw and "height_cm" not in raw
                       and not raw.get("measurements")):
        return UnknownBody()
    return body_from_request(request, data)

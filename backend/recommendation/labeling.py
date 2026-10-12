# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
"""Labels for a garment being registered, computed from its measured dimensions and the user's body.

The wardrobe stores what the garment is (category, colours, ...). The recommender also needs how it
sits on this user: fit (ease = garment circumference - body circumference), length relative to the
body, sleeve length, season, and the K-Fashion vocabulary the model was trained on. These labels are
suggestions: the app shows them with their basis ("가슴 여유 16 cm") and the user confirms or edits
them before the garment is saved.

Dimensions follow the wardrobe spec (flat half widths in cm, backend/apps/wardrobe/editor.py).
"""

from __future__ import annotations

import math

from recommendation import fit
from recommendation.body import Body
from recommendation.masked import season_token
from recommendation.vocab import (
    BOTTOM_FITS,
    COLLARS,
    COLORS,
    LENGTHS,
    MATERIALS,
    NECKLINES,
    PRINTS,
    SEASONS,
    SLEEVES,
    UPPER_FITS,
    WARDROBE_CATEGORY,
    WARDROBE_DEFAULT_CATEGORY,
    WARDROBE_SUBCATEGORY,
)

# default material suggestions by wardrobe subcategory (the user can change them)
MATERIAL_HINTS = {"jeans": ["데님"], "knitwear": ["니트"], "fleece": ["플리스"], "puffer": ["패딩"],
                  "hoodie": ["저지"], "sweatshirt": ["저지"], "tshirt": ["저지"], "active_pants": ["저지"]}
DROP_SHOULDER_CM = 4.0


def _value(dimensions: dict | None, key: str) -> float | None:
    m = (dimensions or {}).get(key)
    if isinstance(m, dict) and isinstance(m.get("value"), (int, float)):
        return float(m["value"])
    return None


# ------------------------------------------------------------------ colour
def _lab(hex_: str) -> tuple[float, float, float]:
    h = hex_.lstrip("#")
    rgb = [int(h[i : i + 2], 16) / 255 for i in (0, 2, 4)]
    lin = [c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in rgb]
    x = (0.4124 * lin[0] + 0.3576 * lin[1] + 0.1805 * lin[2]) / 0.95047
    y = 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]
    z = (0.0193 * lin[0] + 0.1192 * lin[1] + 0.9505 * lin[2]) / 1.08883

    def f(t: float) -> float:
        return t ** (1 / 3) if t > 0.008856 else 7.787 * t + 16 / 116

    fx, fy, fz = f(x), f(y), f(z)
    return 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)


def nearest_color(hex_: str) -> str:
    """The K-Fashion colour name closest to a garment colour (CIELab distance)."""
    target = _lab(hex_)
    return min(COLORS, key=lambda name: math.dist(target, _lab(COLORS[name])))


# ------------------------------------------------------------------ pieces
def _upper_fit(slot: str, dims: dict, body: Body) -> tuple[str | None, str | None, str | None]:
    """(fit, basis, too_small reason) from chest ease."""
    half = _value(dims, "chest_width_half")
    if half is None or not body.chest:
        return None, None, None
    ease = 2 * half - body.chest
    if ease < 0:
        return "타이트", f"가슴단면 {half:.0f} cm × 2가 가슴둘레 {body.chest:.0f} cm보다 {-ease:.0f} cm 작아요", \
            f"가슴둘레보다 {-ease:.0f} cm 작아요"
    table = fit.OUTER_CHEST_EASE if slot == "아우터" else fit.TOP_CHEST_EASE
    return fit._band_label(ease, table), f"가슴 여유 {ease:.0f} cm · 가슴둘레 {body.chest:.0f} cm 기준", None


def _bottom_fit(sub: str | None, dims: dict, body: Body) -> tuple[str | None, str | None, str | None]:
    hip_half, waist_half = _value(dims, "hip_width_half"), _value(dims, "waist_width_half")
    thigh, hem = _value(dims, "thigh_width_half"), _value(dims, "hem_opening")
    small = None
    if waist_half and body.waist and 2 * waist_half < body.waist - 2:
        small = f"허리가 {body.waist - 2 * waist_half:.0f} cm 작아요"
    ease = 2 * hip_half - body.hip if hip_half and body.hip else None
    if ease is not None and ease < 0:
        small = f"엉덩이둘레보다 {-ease:.0f} cm 작아요"
    basis = f"엉덩이 여유 {ease:.0f} cm" if ease is not None else None
    if sub == "skirt":
        label = None if ease is None else ("스키니" if ease < 2 else "루즈" if ease > 12 else "노멀")
        return label, basis, small
    ratio = hem / thigh if hem and thigh else None
    if ratio is not None:
        basis = (basis + " · " if basis else "") + f"밑단/허벅지 {ratio:.2f}"
    if ratio is not None and ratio > 1.0:
        label = "벨보텀"
    elif (ratio is not None and ratio >= 0.85) or (ease is not None and ease >= 12):
        label = "와이드"
    elif (ratio is not None and ratio < 0.6) and (ease is None or ease < 6):
        label = "스키니"
    elif ease is None and ratio is None:
        label = None
    else:
        label = "노멀"
    return label, basis, small


def _length(slot: str, sub: str | None, dims: dict, body: Body) -> tuple[str | None, str | None]:
    total = _value(dims, "total_length")
    if slot == "상의" and total:
        torso = body.torso_length or body.height * 0.26
        r = total / torso
        label = "크롭" if r < 1.15 else "롱" if r > 1.7 else "노멀"
        return label, f"총장 {total:.0f} cm · 상체 길이의 {r:.1f}배"
    if slot == "아우터" and total:
        r = total / body.height
        label = "크롭" if r < 0.30 else "노말" if r < 0.42 else "하프" if r < 0.52 else "롱" if r < 0.62 else "맥시"
        return label, f"총장 {total:.0f} cm · 키의 {r * 100:.0f}%"
    if slot == "하의":
        inseam = _value(dims, "inseam")
        if sub != "skirt" and inseam and body.inseam:
            r = inseam / body.inseam
            label = "미니" if r < 0.25 else "니렝스" if r < 0.55 else "미디" if r < 0.85 else "발목" if r <= 1.05 else "맥시"
            return label, f"인심 {inseam:.0f} cm · 다리 안쪽 길이의 {r * 100:.0f}%"
        if total:
            leg = body.outseam or body.height * 0.6
            r = total / leg
            if sub == "skirt":
                label = "미니" if r < 0.42 else "니렝스" if r < 0.55 else "미디" if r < 0.8 else "맥시"
            else:
                label = "미니" if r < 0.35 else "니렝스" if r < 0.6 else "미디" if r < 0.88 else "발목" if r <= 1.04 else "맥시"
            return label, f"총장 {total:.0f} cm · 다리 길이의 {r * 100:.0f}%"
    return None, None


def _sleeve(dims: dict, body: Body) -> tuple[str | None, str | None]:
    sleeve = _value(dims, "sleeve_length")
    if sleeve is None:
        return None, None
    arm = body.sleeve_length or body.height * 0.34
    r = sleeve / arm
    label = "민소매" if r < 0.08 else "캡" if r < 0.2 else "반팔" if r < 0.5 else "7부소매" if r < 0.85 else "긴팔"
    return label, f"소매 {sleeve:.0f} cm · 팔 길이의 {r * 100:.0f}%"


# ------------------------------------------------------------------ main
def label(attributes: dict, dimensions: dict | None, body: Body, details: dict | None = None) -> dict | None:
    """Suggested labels for a wardrobe garment, or None for shoes (not used by the recommender yet)."""
    slot = WARDROBE_CATEGORY.get(attributes.get("category"))
    if slot is None:
        return None
    sub = attributes.get("subcategory")
    dims = dimensions or {}
    details = details or {}
    if slot == "하의":
        fit_label, fit_basis, small = _bottom_fit(sub, dims, body)
    else:
        fit_label, fit_basis, small = _upper_fit(slot, dims, body)
    length, length_basis = _length(slot, sub, dims, body)
    sleeve, sleeve_basis = _sleeve(dims, body) if slot != "하의" else (None, None)
    shoulder = _value(dims, "shoulder_width")
    drop = bool(shoulder and body.shoulder and shoulder > body.shoulder + DROP_SHOULDER_CM)
    materials = list(MATERIAL_HINTS.get(sub or "", []))
    kf_category = WARDROBE_SUBCATEGORY.get(sub or "", WARDROBE_DEFAULT_CATEGORY[slot])
    if sub == "shorts" and not length:
        length = "미니"
    kf_colors = list(dict.fromkeys(nearest_color(c) for c in attributes.get("colors") or []))[:2]
    season_attrs = {"카테고리": kf_category, "소재": materials, "소매기장": sleeve, "기장": length}
    labels = {
        "fit": fit_label,
        "fit_basis": fit_basis,
        "length": length,
        "length_basis": length_basis,
        "sleeve": sleeve,
        "sleeve_basis": sleeve_basis,
        "season": season_token(slot, {k: v for k, v in season_attrs.items() if v}),
        "drop_shoulder": drop,
        "prints": [p for p in [details.get("print")] if p in PRINTS],
        "neckline": details.get("neckline") if details.get("neckline") in NECKLINES else None,
        "collar": details.get("collar") if details.get("collar") in COLLARS else None,
        "materials": materials,
        "kf_category": kf_category,
        "kf_colors": kf_colors,
        "too_small": small is not None,
        "too_small_reason": small,
        "body_basis": body.public(),
        "confirmed": False,
    }
    return labels


def options() -> dict:
    """Allowed values per wardrobe category, for the app's label editor (GET /api/wardrobe/options/)."""
    common = {"season": list(SEASONS), "prints": list(PRINTS), "materials": list(MATERIALS)}
    upper = {"sleeve": list(SLEEVES), "neckline": list(NECKLINES), "collar": list(COLLARS)}
    return {
        "top": {"fit": list(UPPER_FITS), "length": list(LENGTHS["상의"]), **upper, **common},
        "outerwear": {"fit": list(UPPER_FITS), "length": list(LENGTHS["아우터"]), **upper, **common},
        "bottom": {"fit": list(BOTTOM_FITS), "length": list(LENGTHS["하의"]), **common},
        "limits": {"prints": 2, "materials": 2},
    }


# ------------------------------------------------------------------ validation (PUT items/{id})
LABEL_KEYS = frozenset({
    "fit", "fit_basis", "length", "length_basis", "sleeve", "sleeve_basis", "season", "drop_shoulder", "prints",
    "neckline", "collar", "materials", "kf_category", "kf_colors", "too_small", "too_small_reason", "body_basis",
    "confirmed",
})
TEXT_MAX = 200


def validate_labels(data: object, category: str) -> dict | None:
    """Check labels sent with a save request. Raises ValueError."""
    if data is None:
        return None
    if not isinstance(data, dict) or set(data) - LABEL_KEYS:
        raise ValueError("Invalid labels")
    slot = WARDROBE_CATEGORY.get(category)
    if slot is None:
        raise ValueError("Labels are not used for this category")
    fits = BOTTOM_FITS if slot == "하의" else UPPER_FITS

    def one_of(key: str, allowed) -> None:
        v = data.get(key)
        if v is not None and v not in allowed:
            raise ValueError(f"Invalid {key}")

    def list_of(key: str, allowed, limit: int) -> None:
        v = data.get(key, [])
        if not isinstance(v, list) or len(v) > limit or any(x not in allowed for x in v) or len(set(v)) != len(v):
            raise ValueError(f"Invalid {key}")

    one_of("fit", fits)
    one_of("length", LENGTHS[slot])
    one_of("sleeve", SLEEVES)
    one_of("season", SEASONS)
    one_of("neckline", NECKLINES)
    one_of("collar", COLLARS)
    list_of("prints", PRINTS, 2)
    list_of("materials", MATERIALS, 2)
    list_of("kf_colors", tuple(COLORS), 2)
    for key in ("fit_basis", "length_basis", "sleeve_basis", "too_small_reason", "kf_category"):
        v = data.get(key)
        if v is not None and (not isinstance(v, str) or len(v) > TEXT_MAX):
            raise ValueError(f"Invalid {key}")
    for key in ("drop_shoulder", "too_small", "confirmed"):
        if not isinstance(data.get(key, False), bool):
            raise ValueError(f"Invalid {key}")
    basis = data.get("body_basis", {})
    if not isinstance(basis, dict) or len(basis) > 20 or any(
            not isinstance(v, (int, float, str)) or isinstance(v, bool) for v in basis.values()):
        raise ValueError("Invalid body_basis")
    return {**data}

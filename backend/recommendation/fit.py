# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-08, reviewed by Dongkun Moon
"""Fit: preferred-fit match, body-shape silhouette rules, size advice and the owned-garment size check.

K-Fashion has no garment sizes, only the intended fit (타이트 ... 오버사이즈). We read a fit label as
an ease band (garment circumference minus body circumference). With the user's measurements that
gives the garment size to look for ("가슴단면 49-52 cm"), and for the user's own garments (AR
dimensions from the wardrobe) the actual fit on this body, or "too small".
The bands are rough industry rules of thumb and are tunable constants.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from recommendation import body_rules
from recommendation.body import Body
from recommendation.vocab import UPPER_SLOTS

# ease (cm) = garment circumference - body circumference
TOP_CHEST_EASE = {"타이트": (0, 6), "노멀": (6, 12), "루즈": (12, 20), "오버사이즈": (20, 30)}
OUTER_CHEST_EASE = {"타이트": (6, 12), "노멀": (12, 18), "루즈": (18, 26), "오버사이즈": (26, 36)}
BOTTOM_HIP_EASE = {"스키니": (0, 4), "노멀": (4, 8), "벨보텀": (4, 8), "와이드": (10, 18), "루즈": (10, 18)}
WAIST_EASE = (0, 2)

# how well each fit label matches the preferred fit (0 = opposite, filtered out)
UPPER_MATCH = {
    "slim": {"타이트": 1.0, "노멀": 0.6, "루즈": 0.15, "오버사이즈": 0.0},
    "regular": {"노멀": 1.0, "타이트": 0.5, "루즈": 0.55, "오버사이즈": 0.2},
    "loose": {"루즈": 1.0, "오버사이즈": 0.85, "노멀": 0.45, "타이트": 0.0},
}
BOTTOM_MATCH = {
    "slim": {"스키니": 1.0, "노멀": 0.6, "벨보텀": 0.5, "와이드": 0.1, "루즈": 0.1},
    "regular": {"노멀": 1.0, "벨보텀": 0.7, "스키니": 0.5, "와이드": 0.5, "루즈": 0.4},
    "loose": {"와이드": 1.0, "루즈": 1.0, "벨보텀": 0.5, "노멀": 0.45, "스키니": 0.0},
}
FIT_WORD = {"slim": "슬림핏", "regular": "레귤러핏", "loose": "루즈핏"}



def fit_match(pref_fit: str, slot: str, attrs: dict) -> float:
    table = (UPPER_MATCH if slot in UPPER_SLOTS else BOTTOM_MATCH).get(pref_fit, UPPER_MATCH["regular"])
    return table.get(attrs.get("핏"), 0.5)


@dataclass
class ShapeResult:
    score: float  # 0..1, 0.5 = neutral
    reasons: list[str]  # short Korean phrases, styling-only wording (positive rules)
    rules: list = field(default_factory=list)  # body_rules.Fired, with sources


def shape_score(body: Body, slot: str, attrs: dict, bone: str | None = None) -> ShapeResult:
    """Body-shape fit from the cited rule table (rules/body_shapes.json)."""
    score, fired = body_rules.apply(body, bone, slot, attrs)
    return ShapeResult(score, [f.reason for f in sorted(fired, key=lambda f: -f.weight) if f.weight > 0], fired)


def ease_band(slot: str, attrs: dict) -> tuple[float, float] | None:
    fit = attrs.get("핏")
    if slot == "아우터":
        return OUTER_CHEST_EASE.get(fit)
    if slot in {"상의", "원피스"}:
        return TOP_CHEST_EASE.get(fit)
    return BOTTOM_HIP_EASE.get(fit)


def size_advice(body: Body, slot: str, attrs: dict) -> str | None:
    """Garment flat measurements (단면) to look for, from the body and the item's fit label."""
    band = ease_band(slot, attrs)
    if band is None:
        return None
    lo, hi = band
    parts = []
    if slot in UPPER_SLOTS and body.chest:
        parts.append(f"가슴단면 {(body.chest + lo) / 2:.0f}–{(body.chest + hi) / 2:.0f} cm")
        if slot == "아우터":
            parts[-1] += " (안에 입을 옷 여유 포함)"
    if slot == "원피스" and body.hip:
        parts.append(f"엉덩이단면 {(body.hip + 4) / 2:.0f} cm 이상")
    if slot == "하의":
        if body.waist:
            parts.append(f"허리단면 {(body.waist + WAIST_EASE[0]) / 2:.0f}–{(body.waist + WAIST_EASE[1]) / 2:.0f} cm")
        if body.hip:
            parts.append(f"엉덩이단면 {(body.hip + lo) / 2:.0f}–{(body.hip + hi) / 2:.0f} cm")
        if body.inseam and attrs.get("기장") == "발목" and attrs.get("카테고리") != "스커트":
            parts.append(f"안쪽 기장 {body.inseam - 3:.0f}–{body.inseam:.0f} cm")
    return ", ".join(parts) or None


@dataclass
class OwnedFit:
    too_small: bool
    label: str | None  # the fit this garment actually has on this body
    detail: str | None


def owned_fit(body: Body, slot: str, dims: dict | None) -> OwnedFit:
    """Fit of a wardrobe garment on this body from its AR dimensions (half widths in cm; plain numbers
    or the wardrobe's {value, source, method, reference} records)."""
    def cm(key: str) -> float | None:
        v = (dims or {}).get(key)
        v = v.get("value") if isinstance(v, dict) else v
        return float(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else None

    chest, hip, waist = cm("chest_width_half"), cm("hip_width_half"), cm("waist_width_half")
    if slot in UPPER_SLOTS and body.chest and chest:
        ease = 2 * chest - body.chest
        if ease < 0:
            return OwnedFit(True, None, f"가슴둘레보다 {-ease:.0f} cm 작아요")
        table = OUTER_CHEST_EASE if slot == "아우터" else TOP_CHEST_EASE
        return OwnedFit(False, _band_label(ease, table), f"가슴 여유 {ease:.0f} cm")
    if slot == "하의" and body.hip and hip:
        ease = 2 * hip - body.hip
        if ease < 0:
            return OwnedFit(True, None, f"엉덩이둘레보다 {-ease:.0f} cm 작아요")
        if body.waist and waist and 2 * waist < body.waist - 2:
            return OwnedFit(True, None, "허리가 작아요")
        return OwnedFit(False, _band_label(ease, BOTTOM_HIP_EASE), f"엉덩이 여유 {ease:.0f} cm")
    return OwnedFit(False, None, None)


def _band_label(ease: float, table: dict) -> str:
    best = min(table, key=lambda k: 0 if table[k][0] <= ease <= table[k][1] else min(abs(ease - table[k][0]), abs(ease - table[k][1])))
    return best

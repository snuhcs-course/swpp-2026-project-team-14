# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-08, reviewed by Dongkun Moon
"""Season: the common Korean 기온별 옷차림 bands and a warmth estimate per garment.

Warmth is a rough "layers" number from category, material, sleeve and length labels. An outfit is
scored by how close its total warmth is to the band's target; single garments that are clearly
too warm or too cold for the band are filtered out, and the band decides which outfit templates
(top+bottom, outer+top+bottom, one-piece, ...) are tried.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from recommendation.vocab import as_list

BASE = {
    "아우터": {"베스트": 0.6, "가디건": 1.0, "짚업": 1.3, "재킷": 1.6, "점퍼": 2.2, "코트": 2.6, "패딩": 3.6},
    "상의": {"브라탑": 0.2, "탑": 0.4, "티셔츠": 0.8, "블라우스": 0.8, "셔츠": 0.9, "니트웨어": 1.6, "후드티": 1.6},
    "하의": {"스커트": 0.6, "래깅스": 0.8, "팬츠": 1.0, "조거팬츠": 1.0, "청바지": 1.1},
    "원피스": {"드레스": 1.4, "점프수트": 1.6},
}
MATERIAL = {
    "패딩": 1.2, "무스탕": 1.2, "퍼": 1.0, "울/캐시미어": 0.6, "플리스": 0.6, "헤어 니트": 0.5, "트위드": 0.4,
    "니트": 0.3, "코듀로이": 0.3, "가죽": 0.3, "벨벳": 0.2, "스웨이드": 0.2, "레이스": -0.1, "실크": -0.1,
    "시폰": -0.3, "린넨": -0.4, "메시": -0.5,
}
SLEEVE = {"민소매": -0.5, "없음": -0.5, "캡": -0.4, "반팔": -0.35, "7부소매": -0.1}
LENGTH = {"미니": -0.4, "니렝스": -0.2, "맥시": 0.1}
SUMMER_MATERIALS = {"린넨", "시폰", "메시"}
WINTER_MATERIALS = {"패딩", "무스탕", "퍼", "플리스", "헤어 니트"}


@dataclass(frozen=True)
class Band:
    label: str
    target: float  # outfit warmth
    max_item: float  # garments warmer than this are filtered out
    outer: str  # none | optional | recommended | required
    templates: tuple[tuple[tuple[str, ...], float], ...]


TOP_BOTTOM = ("상의", "하의")
LAYERED = ("아우터", "상의", "하의")
DRESS = ("원피스",)
LAYERED_DRESS = ("아우터", "원피스")

# (min °C, band) from warmest to coldest
BANDS = (
    (28, Band("한여름", 1.3, 1.3, "none", ((TOP_BOTTOM, 0.65), (DRESS, 0.35)))),
    (23, Band("초여름", 1.8, 1.7, "none", ((TOP_BOTTOM, 0.65), (DRESS, 0.35)))),
    (20, Band("늦봄·초가을", 2.6, 2.6, "optional", ((TOP_BOTTOM, 0.5), (DRESS, 0.2), (LAYERED, 0.3)))),
    (17, Band("가을 간절기", 3.3, 3.4, "optional", ((TOP_BOTTOM, 0.35), (LAYERED, 0.45), (DRESS, 0.1), (LAYERED_DRESS, 0.1)))),
    (12, Band("선선한 가을", 4.1, 9.0, "recommended", ((LAYERED, 0.75), (LAYERED_DRESS, 0.15), (TOP_BOTTOM, 0.1)))),
    (9, Band("쌀쌀한 날", 4.8, 9.0, "required", ((LAYERED, 0.85), (LAYERED_DRESS, 0.15)))),
    (5, Band("초겨울", 5.6, 9.0, "required", ((LAYERED, 0.9), (LAYERED_DRESS, 0.1)))),
    (-99, Band("한겨울", 6.6, 9.0, "required", ((LAYERED, 0.95), (LAYERED_DRESS, 0.05)))),
)


def band(temp: float) -> Band:
    for low, b in BANDS:
        if temp >= low:
            return b
    return BANDS[-1][1]


def item_warmth(slot: str, attrs: dict) -> float:
    w = BASE.get(slot, {}).get(attrs.get("카테고리"), 1.0)
    w += max((MATERIAL.get(m, 0.0) for m in as_list(attrs.get("소재"))), default=0.0, key=abs)
    w += SLEEVE.get(attrs.get("소매기장"), 0.0)
    if slot in {"하의", "원피스"}:
        w += LENGTH.get(attrs.get("기장"), 0.0)
    return round(max(0.1, w), 2)


def allowed(b: Band, temp: float, slot: str, attrs: dict, warmth: float) -> str | None:
    """None if the garment fits the weather, else a short reason it was filtered."""
    materials = set(as_list(attrs.get("소재")))
    if warmth > b.max_item or (slot == "아우터" and b.outer == "none"):
        return "too_warm"
    if temp >= 23 and materials & WINTER_MATERIALS:
        return "too_warm"
    if temp < 12 and (materials & SUMMER_MATERIALS or attrs.get("소매기장") in {"민소매", "없음", "캡"}):
        return "too_cold"
    if temp < 5 and (attrs.get("소매기장") == "반팔" or (slot in {"하의", "원피스"} and attrs.get("기장") == "미니")):
        return "too_cold"
    if temp < 20 and slot == "하의" and attrs.get("기장") == "미니" and attrs.get("카테고리") != "스커트":
        return "too_cold"  # shorts; mini skirts are still worn with tights
    if temp < 9 and slot == "아우터" and warmth < 2.0:
        return "too_cold"
    return None


def outfit_score(b: Band, total_warmth: float) -> float:
    return math.exp(-((total_warmth - b.target) / 1.1) ** 2)

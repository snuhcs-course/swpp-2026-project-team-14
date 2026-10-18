# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-08, reviewed by Dongkun Moon
"""Korean explanations for the score components (styling wording only, no body judgements)."""

from __future__ import annotations

from recommendation.body import Body
from recommendation.catalog import CATEGORY_WORD, FIT_WORD, Catalog, Item
from recommendation.season import Band
from recommendation.vocab import josa

SLOT_WORD = {"아우터": "아우터", "상의": "상의", "하의": "하의", "원피스": "원피스"}
PREF_FIT_WORD = {"slim": "슬림핏", "regular": "레귤러핏", "loose": "루즈핏"}
MALE_NOTE = "남성은 성별 구분이 약한 옷만 추천해요. K-Fashion이 여성복 데이터라 핏은 여성복 기준으로 참고해 주세요."


def _describe(slot: str, key: str, value: str) -> str:
    slot_word = SLOT_WORD[slot]
    if key == "카테고리":
        return CATEGORY_WORD.get(value, value)
    if key == "핏":
        return f"{FIT_WORD.get(value, value)}핏 {slot_word}" if value != "노멀" else f"기본핏 {slot_word}"
    if key == "소재":
        return f"{value} {slot_word}"
    if key == "기장":
        return f"{value} 기장 {slot_word}"
    return f"{value} {slot_word}"


def compat_reason(catalog: Catalog, items: list[Item]) -> dict | None:
    r = catalog.compat.best_reason({i.slot: i.attrs for i in items})
    if r is None or r.lift < 1.15:
        return None
    a = _describe(r.slot_a, r.key_a, r.value_a)
    b = _describe(r.slot_b, r.key_b, r.value_b)
    return {
        "kind": "compat",
        "text": f"{a}{josa(a, '과', '와')} {b} 조합은 실제 코디에서 평균보다 {r.lift:.1f}배 자주 함께 입었어요.",
        "sub": f"K-Fashion 코디 {r.count:,}개 기준",
    }


def body_reason(body: Body, pref_fit: str, fit_avg: float, shape_reasons: list[str]) -> dict | None:
    parts = []
    if shape_reasons:
        parts.append(shape_reasons[0])
    if fit_avg >= 0.8:
        parts.append(f"선호하는 {PREF_FIT_WORD.get(pref_fit, '핏')}에 맞췄어요")
    if not parts:
        return None
    sub = f"체형 분석 · {body.shape.value}" if body.shape else "선호 핏 기준"
    return {"kind": "body", "text": ". ".join(parts) + ".", "sub": sub}


def season_reason(temp: float, band: Band, warmth: float, has_outer: bool) -> dict:
    gap = warmth - band.target
    if abs(gap) <= 0.6:
        text = f"{temp:.0f}°에 딱 맞는 두께예요."
    elif gap < 0:
        text = f"{temp:.0f}°에는 조금 가벼울 수 있어요."
    else:
        text = f"{temp:.0f}°에는 조금 따뜻한 편이에요."
    if has_outer and band.outer in {"optional", "recommended"}:
        text += " 낮에 더우면 아우터를 벗어도 완성된 코디예요."
    return {"kind": "season", "text": text, "sub": f"기온별 옷차림 · {band.label}"}


def taste_reason(taste_avg: float, chips: list[str], has_feedback: bool) -> dict | None:
    """`chips`: the user's style chips (미니멀, 캐주얼, ...) the outfit's garments match."""
    if chips:
        names = "·".join(dict.fromkeys(chips))
        return {"kind": "taste", "text": f"선호하신 {names} 스타일이에요.", "sub": "선호 스타일"}
    if has_feedback and taste_avg >= 0.6:
        return {"kind": "taste", "text": "좋아요한 옷들과 비슷한 속성이 많아요.", "sub": "내 취향 학습"}
    return None

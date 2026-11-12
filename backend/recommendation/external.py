"""Predict matching garment attributes and build a shopping search link without fetching products."""

from __future__ import annotations

from urllib.parse import urlencode

import numpy as np
from onnxruntime.capi.onnxruntime_pybind11_state import Fail, RuntimeException

from recommendation.catalog import CATEGORY_WORD, FIT_WORD, Item
from recommendation.vocab import BOTTOM_FITS, CATEGORIES, COLORS, PROFILE_STYLES, UPPER_FITS, WARDROBE_STYLES


TARGET_SLOTS = {"상의": "하의", "하의": "상의", "아우터": "상의"}
SLOT_CODES = {"상의": "top", "하의": "bottom"}
SEARCH_BASE = "https://www.musinsa.com/search/goods"
# 무신사 필터 파라미터는 검색어마다 달라지는 내부 ID라서 쓰지 않고, 속성을 검색어에만 담는다
PLAIN_PATTERN = "무지"  # 무늬 없음은 검색어에 넣지 않는다 (상품명에 잘 안 쓰여 결과가 줄어듦)
SCORED_KEYS = ("카테고리", "색상", "핏")
WARDROBE_MATCH_LIMIT = 3
MISSING_ATTRIBUTE_PENALTY = 1.0


class ExternalModelUnavailable(Exception):
    """The existing model cannot produce a matching prediction."""


def preferred_style(preferred_styles: list[str] | None) -> str | None:
    """계정 선호 스타일 중 모델 어휘로 바꿀 수 있는 첫 번째 값. 없으면 None (옷 자체 스타일로 대체)."""
    return next((mapped[0] for style in preferred_styles or []
                 if (mapped := WARDROBE_STYLES.get(style) or PROFILE_STYLES.get(style))), None)


def search_title(category: str, color: str, fit: str, pattern: str | None = None) -> str:
    """검색어 = 색상 + 핏 + (패턴) + 종류. 어울리는 옷 찾기와 옷장 분석이 같은 규칙을 쓴다."""
    fit_word = FIT_WORD.get(fit, "레귤러핏")
    return " ".join(word for word in (color, fit_word, pattern, CATEGORY_WORD.get(category, category)) if word)


def search_url(title: str) -> str:
    return SEARCH_BASE + "?" + urlencode({"keyword": title})


def _candidates(model, probabilities: dict, key: str, allowed) -> list[tuple[float, str]]:
    scores = probabilities.get(key)
    if scores is None:
        raise ExternalModelUnavailable("Missing attribute prediction")
    candidates = [(float(scores[index]), value) for index, (kind, value) in enumerate(model.values)
                  if kind == key and value in allowed and index < len(scores) and np.isfinite(scores[index])]
    if not candidates:
        raise ExternalModelUnavailable("No valid attribute prediction")
    return candidates


def _best(candidates: list[tuple[float, str]]) -> tuple[str, int]:
    """허용 후보 중 최고값과, 후보 안에서 정규화한 확률(%)을 돌려준다."""
    logits = np.array([score for score, _ in candidates])
    shares = np.exp(logits - logits.max())
    shares /= shares.sum()
    best = int(np.argmax(logits))
    return candidates[best][1], int(round(float(shares[best]) * 100))


def _pattern(model, probabilities: dict) -> str | None:
    """프린트(패턴/무늬) 예측. 모델이 프린트를 내지 않거나 무지면 None."""
    if "프린트" not in probabilities:
        return None
    values = {value for kind, value in model.values if kind == "프린트"}
    try:
        pattern, _ = _best(_candidates(model, probabilities, "프린트", values))
    except ExternalModelUnavailable:
        return None
    return None if pattern == PLAIN_PATTERN else pattern


def _wardrobe_score(model, probabilities: dict, attrs: dict) -> float | None:
    """세 속성의 평균. 누락값은 해당 속성의 최저 유효 점수보다 1 낮게 처리한다."""
    index = {value: i for i, value in enumerate(model.values)}
    values = []
    matched = 0
    for key in SCORED_KEYS:
        raw = attrs.get(key)
        value = raw[0] if isinstance(raw, list) and raw else raw
        position = index.get((key, value)) if value else None
        scores = probabilities.get(key)
        known = [float(scores[i]) for (kind, _), i in index.items()
                 if kind == key and scores is not None and i < len(scores) and np.isfinite(scores[i])]
        if not known:
            return None
        if position is not None and scores is not None and position < len(scores) and np.isfinite(scores[position]):
            values.append(float(scores[position]))
            matched += 1
        else:
            values.append(min(known) - MISSING_ATTRIBUTE_PENALTY)
    return float(np.mean(values)) if matched else None


def _wardrobe_matches(model, probabilities: dict, target: str, wardrobe: list[Item]) -> list[dict]:
    scored = []
    for candidate in wardrobe:
        if candidate.slot != target:
            continue
        score = _wardrobe_score(model, probabilities, candidate.attrs)
        if score is not None:
            scored.append((score, candidate))
    scored.sort(key=lambda pair: pair[0], reverse=True)
    return [{"garment_id": candidate.id.removeprefix("w:"), "name": candidate.name}
            for _, candidate in scored[:WARDROBE_MATCH_LIMIT]]


def matching_search(item: Item, model, preferred_styles: list[str] | None = None,
                    wardrobe: list[Item] | None = None) -> dict:
    """Predict a matching garment's category, colour and fit with the shipped masked model."""
    if model is None:
        raise ExternalModelUnavailable("Masked model unavailable")
    target = TARGET_SLOTS.get(item.slot)
    if target is None:
        raise ValueError("Unsupported garment category")
    profile_style = preferred_style(preferred_styles)
    try:
        probabilities = model.slot_logprobs({item.slot: item.attrs}, profile_style or item.style, target)
    except (Fail, RuntimeException) as error:
        raise ExternalModelUnavailable("Model inference unavailable") from error
    category, _ = _best(_candidates(model, probabilities, "카테고리", CATEGORIES[target]))
    color, color_share = _best(_candidates(model, probabilities, "색상", COLORS))
    fit, fit_share = _best(_candidates(model, probabilities, "핏", BOTTOM_FITS if target == "하의" else UPPER_FITS))
    pattern = _pattern(model, probabilities)
    fit_word = FIT_WORD.get(fit, "레귤러핏")
    title = search_title(category, color, fit, pattern)
    traits = " & ".join(word for word in (color, fit_word, pattern) if word)
    basis = "선택한 옷과 선호 스타일" if profile_style else "선택한 옷"
    return {
        "target_slot": SLOT_CODES[target],
        "title": title,
        "headline": f"해당 옷과 가장 잘 어울리는 {target}는 {traits} 속성이에요.",
        "reason": f"{basis}을 기준으로 {color}·{fit_word} 조합의 어울림 점수가 가장 높았어요 "
                  f"(후보 중 색상 {color_share}% · 핏 {fit_share}%).",
        "attributes": {"category": category, "color": color, "fit": fit, "pattern": pattern or PLAIN_PATTERN},
        "search_url": search_url(title),
        "wardrobe_matches": _wardrobe_matches(model, probabilities, target, wardrobe or []),
    }

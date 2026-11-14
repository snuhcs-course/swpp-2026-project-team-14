# AI-generated with Claude Code (Claude Opus 5.5), 2026-11-12, reviewed by Kihwan Kim
"""옷장 분석: 내 옷 여러 벌과 잘 어울리는데 옷장에 없는 옷을 찾는다 (투표 방식).

1. 옷장의 상의·하의·아우터마다 어울리는 옷 찾기와 같은 모델 예측으로 반대 슬롯 옷의
   (카테고리, 색상) 상위 후보 몇 개를 뽑는다. 상의 → 하의, 하의·아우터 → 상의.
2. 후보를 (슬롯, 카테고리, 색상)으로 묶어 몇 벌의 옷이 그 후보를 골랐는지(표 수) 센다.
3. 이미 가진 옷과 같은 종류(`wardrobe.similar`)는 버린다: "옷장에 없는 옷"만 남긴다.
4. 표가 MIN_VOTES 이상인 후보를 표 수 → 평균 확률 순으로 최대 MAX_GAPS개 고른다.
5. 핏은 계정의 선호 핏 중 그 슬롯에 맞는 첫 값을 쓰고, 없으면 표를 던진 옷들이 가장 원한 핏을 쓴다.
6. 남성이면 홈 추천과 같은 규칙(`vocab.is_unisex`)으로 여성 전용 카테고리·색상(스커트, 핑크 등)을 뺀다.

화면에 보이는 "내 옷 N벌과 어울려요"의 N과 근거 옷 목록은 이 표 집계 그대로다.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
from onnxruntime.capi.onnxruntime_pybind11_state import Fail, RuntimeException

from recommendation.catalog import Item
from recommendation.external import (
    PLAIN_PATTERN,
    SLOT_CODES,
    TARGET_SLOTS,
    ExternalModelUnavailable,
    _candidates,
    preferred_style,
    search_title,
    search_url,
)
from recommendation.vocab import BOTTOM_FITS, CATEGORIES, COLORS, UPPER_FITS, is_unisex
from recommendation.wardrobe import similar

MIN_GARMENTS = 4  # 상의·하의·아우터 합계가 이보다 적으면 분석하지 않는다
MIN_VOTES = 2  # 한 벌만 원하는 옷은 옷장 전체의 빈자리로 보지 않는다
MAX_GAPS = 3
CATEGORY_TOP = 2  # 옷 한 벌이 보는 카테고리 후보 수
COLOR_TOP = 3  # 옷 한 벌이 보는 색상 후보 수
VOTES_PER_GARMENT = 3  # 옷 한 벌이 투표하는 (카테고리, 색상) 조합 수
SOURCE_SLOTS = ("상의", "하의", "아우터")
SLOT_CODE = {"상의": "top", "하의": "bottom", "아우터": "outerwear"}

# 계정 선호 핏(마이프로필) → 슬롯별 모델 핏. 슬롯에 없는 선호(상의의 와이드, 테이퍼드 등)는 모델 예측을 쓴다
PROFILE_FITS = {
    "상의": {"oversized": "오버사이즈", "regular": "노멀", "slim": "타이트"},
    "하의": {"wide": "와이드", "regular": "노멀", "slim": "스키니"},
}

STATUS_OK = "ok"
STATUS_ENOUGH = "enough"  # 분석했지만 사야 할 옷이 없다 (O5-4)
STATUS_TOO_FEW = "too_few"  # 옷이 적어서 분석하지 않았다


@dataclass
class _Vote:
    garment: Item
    share: float  # 이 옷의 예측에서 이 (카테고리, 색상) 조합이 차지하는 확률
    fit_shares: dict[str, float]


@dataclass
class _Candidate:
    slot: str
    category: str
    color: str
    votes: list[_Vote] = field(default_factory=list)

    @property
    def mean_share(self) -> float:
        return float(np.mean([vote.share for vote in self.votes]))

    def fit(self) -> str:
        """표를 던진 옷들의 핏 확률을 더해 가장 많이 원한 핏을 고른다."""
        totals: dict[str, float] = {}
        for vote in self.votes:
            for fit, share in vote.fit_shares.items():
                totals[fit] = totals.get(fit, 0.0) + share
        # 동점이면 이름순으로 고정해 결과가 매번 같게 한다
        return min(totals, key=lambda fit: (-totals[fit], fit))


def _shares(candidates: list[tuple[float, str]]) -> list[tuple[float, str]]:
    """허용 후보 안에서 정규화한 확률, 큰 순서 (동점은 이름순)."""
    logits = np.array([score for score, _ in candidates])
    probabilities = np.exp(logits - logits.max())
    probabilities /= probabilities.sum()
    return sorted(((float(p), value) for p, (_, value) in zip(probabilities, candidates)),
                  key=lambda pair: (-pair[0], pair[1]))


def _garment_votes(model, garment: Item, style: str | None) -> tuple[str, list[tuple[str, str, float]], dict]:
    """옷 한 벌의 투표: 대상 슬롯, 상위 (카테고리, 색상, 확률) 목록, 핏 확률."""
    target = TARGET_SLOTS[garment.slot]
    try:
        probabilities = model.slot_logprobs({garment.slot: garment.attrs}, style or garment.style, target)
    except (Fail, RuntimeException) as error:
        raise ExternalModelUnavailable("Model inference unavailable") from error
    categories = _shares(_candidates(model, probabilities, "카테고리", CATEGORIES[target]))[:CATEGORY_TOP]
    colors = _shares(_candidates(model, probabilities, "색상", COLORS))[:COLOR_TOP]
    fits = _shares(_candidates(model, probabilities, "핏", BOTTOM_FITS if target == "하의" else UPPER_FITS))
    pairs = sorted(((cat_share * color_share, category, color)
                    for cat_share, category in categories for color_share, color in colors),
                   key=lambda pair: (-pair[0], pair[1], pair[2]))[:VOTES_PER_GARMENT]
    return target, [(category, color, share) for share, category, color in pairs], \
        {fit: share for share, fit in fits}


def _owned(candidate: _Candidate, wardrobe: list[Item]) -> bool:
    probe = Item(id="gap", slot=candidate.slot, attrs={"카테고리": candidate.category, "색상": candidate.color})
    return any(similar(probe, garment) for garment in wardrobe)


def preferred_fit(slot: str, preferred_fits: list[str] | None) -> str | None:
    """선호 핏 목록에서 이 슬롯에 쓸 수 있는 첫 값 (모델 어휘). 없으면 None."""
    mapping = PROFILE_FITS.get(slot, {})
    return next((mapping[fit] for fit in preferred_fits or [] if fit in mapping), None)


def _wearable(candidate: _Candidate, male: bool) -> bool:
    """남성에게는 홈 추천과 같은 규칙으로 여성 전용 카테고리·색상을 추천하지 않는다."""
    return not male or is_unisex(candidate.slot, {"카테고리": candidate.category, "색상": candidate.color})


def _serialize(candidate: _Candidate, preferred_fits: list[str] | None) -> dict:
    fit = preferred_fit(candidate.slot, preferred_fits) or candidate.fit()
    title = search_title(candidate.category, candidate.color, fit)
    # 근거 옷은 이 후보를 강하게 원한 순서로 보여 준다
    voters = sorted(candidate.votes, key=lambda vote: (-vote.share, vote.garment.id))
    count = len(voters)
    return {
        "title": title,
        "slot": SLOT_CODES[candidate.slot],
        "vote_count": count,
        "reason": f"내 옷 {count}벌과 잘 어울리는데, 비슷한 옷이 옷장에 없어요.",
        "attributes": {"category": candidate.category, "color": candidate.color, "fit": fit,
                       "pattern": PLAIN_PATTERN},
        "matched_garments": [{"garment_id": vote.garment.id.removeprefix("w:"), "name": vote.garment.name,
                              "image_url": vote.garment.image} for vote in voters],
        "search_url": search_url(title),
    }


def slot_counts(wardrobe: list[Item]) -> dict[str, int]:
    return {code: sum(1 for garment in wardrobe if garment.slot == slot) for slot, code in SLOT_CODE.items()}


def enough_garments(wardrobe: list[Item]) -> bool:
    """상의와 하의가 한 벌 이상씩 있고, 상의·하의·아우터가 합쳐 MIN_GARMENTS벌 이상."""
    counts = slot_counts(wardrobe)
    return counts["top"] >= 1 and counts["bottom"] >= 1 and sum(counts.values()) >= MIN_GARMENTS


def wardrobe_gaps(wardrobe: list[Item], model, preferred_styles: list[str] | None = None,
                  preferred_fits: list[str] | None = None, male: bool = False) -> dict:
    """옷장 전체를 보고 부족한 옷을 최대 MAX_GAPS개 추천한다.

    반환값: {"status": ok|enough|too_few, "garment_count": 분석한 옷 수, "slot_counts": {...}, "gaps": [...]}
    garment_count는 분석에 쓴 상의·하의·아우터 수라서 slot_counts의 합과 같다 (원피스·신발 제외).
    모델을 쓸 수 없으면 ExternalModelUnavailable.
    """
    garments = [garment for garment in wardrobe if garment.slot in SOURCE_SLOTS]
    counts = slot_counts(garments)
    summary = {"garment_count": sum(counts.values()), "slot_counts": counts}
    if not enough_garments(garments):
        return {"status": STATUS_TOO_FEW, **summary, "gaps": []}
    if model is None:
        raise ExternalModelUnavailable("Masked model unavailable")
    style = preferred_style(preferred_styles)

    candidates: dict[tuple[str, str, str], _Candidate] = {}
    for garment in garments:
        target, pairs, fit_shares = _garment_votes(model, garment, style)
        for category, color, share in pairs:
            key = (target, category, color)
            candidate = candidates.setdefault(key, _Candidate(target, category, color))
            candidate.votes.append(_Vote(garment, share, fit_shares))

    # (슬롯, 카테고리, 색상)으로 묶었으므로 남은 후보끼리는 서로 similar()하지 않다
    gaps = [candidate for candidate in candidates.values()
            if len(candidate.votes) >= MIN_VOTES and not _owned(candidate, garments) and _wearable(candidate, male)]
    gaps.sort(key=lambda c: (-len(c.votes), -c.mean_share, c.slot, c.category, c.color))
    chosen = [_serialize(candidate, preferred_fits) for candidate in gaps[:MAX_GAPS]]
    return {"status": STATUS_OK if chosen else STATUS_ENOUGH, **summary, "gaps": chosen}

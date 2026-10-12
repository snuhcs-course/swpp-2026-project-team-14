# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-08, reviewed by Dongkun Moon
"""Colour harmony of an outfit: common styling rules, checked against K-Fashion colour pairs.

Rules: neutrals (black, white, grey, beige, brown, navy, khaki, silver; denim blue counts as
neutral) go with anything; at most one or two accent colours; two accents should be close in hue
(analogous) or one of them muted; same-family colours (tone-on-tone) read as intentional. The
data part is the colour-pair PMI learned from K-Fashion outfits (compat stats).
"""

from __future__ import annotations

from dataclasses import dataclass

from recommendation.compat import CompatModel, sigmoid
from recommendation.vocab import NEUTRAL_COLORS, SLOTS, hsv, josa

DENIM = {"블루", "스카이블루", "네이비"}


@dataclass
class ColorResult:
    score: float  # 0..1
    reason: str


def _is_neutral(slot: str, attrs: dict) -> bool:
    color = attrs.get("색상")
    return color in NEUTRAL_COLORS or (attrs.get("카테고리") == "청바지" and color in DENIM)


def _hue_gap(a: str, b: str) -> float:
    d = abs(hsv(a)[0] - hsv(b)[0]) % 360
    return min(d, 360 - d)


def outfit_color(outfit: dict[str, dict], compat: CompatModel | None = None) -> ColorResult:
    slots = [s for s in SLOTS if s in outfit]
    colors = [outfit[s].get("색상") for s in slots if outfit[s].get("색상")]
    accents = sorted({outfit[s]["색상"] for s in slots if outfit[s].get("색상") and not _is_neutral(s, outfit[s])})
    names = " · ".join(dict.fromkeys(colors))
    if len(accents) == 0:
        score = 0.85 if len(set(colors)) > 1 else 0.7  # all one neutral reads flat (화이트+화이트 is rare in the data)
        reason = f"{names} 뉴트럴 조합이라 차분하고 실패가 적어요" if len(set(colors)) > 1 else f"{names} 원톤 코디예요"
    elif len(accents) == 1:
        score = 0.95
        reason = f"{accents[0]}{josa(accents[0], '을', '를')} 포인트로 두고 나머지는 뉴트럴로 받쳐줘요"
    elif len(accents) == 2:
        a, b = accents
        gap = _hue_gap(a, b)
        muted = min(hsv(a)[1], hsv(b)[1]) < 0.35
        if gap <= 40:
            score, reason = 0.8, f"{a}{josa(a, '과', '와')} {b}{josa(b, '은', '는')} 비슷한 계열이라 자연스럽게 어울려요"
        elif muted:
            score, reason = 0.7, f"{a}{josa(a, '과', '와')} {b} 중 한쪽이 차분한 색이라 부딪히지 않아요"
        else:
            score, reason = 0.4, f"{a}{josa(a, '과', '와')} {b}, 두 가지 포인트 색이 강해요"
    else:
        score, reason = 0.25, "포인트 색이 많아 산만해 보일 수 있어요"

    if compat is not None and len(slots) >= 2:
        pmis = []
        for i, sa in enumerate(slots):
            for sb in slots[i + 1 :]:
                x, y = outfit[sa].get("색상"), outfit[sb].get("색상")
                if x and y:
                    pmis.append(compat.lookup(sa, sb, "색상", "색상", x, y)[0])
        if pmis:
            data = float(sigmoid(2 * sum(pmis) / len(pmis)))
            score = 0.65 * score + 0.35 * data
    return ColorResult(round(score, 3), reason)

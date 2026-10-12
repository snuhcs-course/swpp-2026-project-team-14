# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-11, reviewed by Dongkun Moon
"""What the user asked for, as structured intent: "비 오니까 따뜻하게, 상의만 밝은 색으로".

Like the request-type question in the Jev mirror demo, the first decision is what kind of request
it is (whole outfit / one item / styling tweak / nothing), then which slots to change and what the
change should move towards. Keyword rules only: free-text chat (and an LLM to read it) is the chat
feature's job (Iteration 2, 최주현); the test page sends fixed example requests.
"""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass, field

from recommendation.vocab import SLOTS

WANTS = ("brighter", "darker", "warmer", "cooler", "formal", "casual", "looser", "fitted", "colorful", "simpler")
OCCASIONS = ("출근", "데이트", "캐주얼", "운동", "여행", "모임")
TYPES = ("whole", "item", "style", "none")

SLOT_WORDS = {
    "아우터": ("아우터", "겉옷", "코트", "재킷", "자켓", "점퍼", "가디건", "패딩", "집업", "베스트"),
    "상의": ("상의", "티셔츠", "티", "니트", "셔츠", "블라우스", "탑", "후드", "맨투맨", "윗옷"),
    "하의": ("하의", "바지", "팬츠", "청바지", "슬랙스", "스커트", "치마", "레깅스"),
    "원피스": ("원피스", "드레스", "점프수트"),
}
WANT_WORDS = {
    "brighter": ("밝", "화사", "산뜻", "환하"),
    "darker": ("어둡게", "차분", "톤다운", "어두운"),
    "warmer": ("따뜻", "춥", "추워", "두껍", "포근"),
    "cooler": ("시원", "덥", "더워", "가볍", "얇"),
    "formal": ("포멀", "단정", "출근", "회사", "면접", "격식", "오피스"),
    "casual": ("캐주얼", "편하게", "편한", "꾸안꾸", "가볍게"),
    "looser": ("넉넉", "루즈", "오버", "헐렁"),
    "fitted": ("슬림", "딱 맞", "핏되", "붙는"),
    "colorful": ("포인트", "컬러풀", "색감", "화려"),
    "simpler": ("심플", "무난", "깔끔", "미니멀"),
}
OCCASION_WORDS = {
    "출근": ("출근", "회사", "오피스", "면접"), "데이트": ("데이트", "소개팅"), "운동": ("운동", "헬스", "러닝", "등산"),
    "여행": ("여행", "나들이"), "모임": ("모임", "파티", "결혼식", "하객"), "캐주얼": ("동네", "마실", "캐주얼"),
}
WHOLE_WORDS = ("전체 코디", "전부 바꿔", "통째", "다 바꿔", "새로", "다른 코디", "코디 추천", "골라")

@dataclass
class Intent:
    type: str = "none"
    slots: list[str] = field(default_factory=list)
    wants: list[dict] = field(default_factory=list)  # {"slot": str | None, "want": str}
    occasion: str | None = None
    parsed_by: str = "rules"

    def public(self) -> dict:
        return asdict(self)

    def wants_for(self, slot: str) -> list[str]:
        return [w["want"] for w in self.wants if w["slot"] in (None, slot)]


def parse(text: str) -> Intent:
    text = re.sub(r"\s+", " ", text.strip())[:400]
    return rules(text) if text else Intent()


def rules(text: str) -> Intent:
    clauses = [c.strip() for c in re.split(r"는데|은데|인데|지만|그리고|(?<=[가-힣])고\s|[,.!?]", text) if c.strip()]
    intent = Intent(parsed_by="rules")
    for clause in clauses:
        slots = [s for s, words in SLOT_WORDS.items() if any(w in clause for w in words)]
        for s in slots:
            if s not in intent.slots:
                intent.slots.append(s)
        for want, words in WANT_WORDS.items():
            if any(w in clause for w in words):
                targets = slots or [None]
                intent.wants += [{"slot": s, "want": want} for s in targets]
    intent.occasion = next((o for o, words in OCCASION_WORDS.items() if any(w in text for w in words)), None)
    if any(w in text for w in WHOLE_WORDS):
        intent.type = "whole"
    elif intent.slots:
        intent.type = "item"
    elif intent.wants or intent.occasion:
        intent.type = "style"
    return _complete(intent)


OCCASION_WANTS = {"출근": "formal", "모임": "formal", "운동": "casual", "여행": "casual", "캐주얼": "casual"}


def _complete(intent: Intent) -> Intent:
    """An occasion implies a direction; a style request with no slot applies to the whole outfit."""
    if intent.occasion in OCCASION_WANTS and not any(w["want"] == OCCASION_WANTS[intent.occasion] for w in intent.wants):
        intent.wants.append({"slot": None, "want": OCCASION_WANTS[intent.occasion]})
    if intent.type == "none" and (intent.wants or intent.occasion):
        intent.type = "style"
    return intent

"""Where and when a chat request is for: "내일 부산 여행", "토요일 데이트", "11월 15일 제주".

Read with fixed rules, not the model, so a named place or day never depends on the LLM and the
model call stays the same. The chat service looks up that day's forecast at that place and
recommends for it instead of the current weather.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone

from recommendation import intent

KST = timezone(timedelta(hours=9))

# (latitude, longitude). Cities and common trip destinations; anything else falls back to the
# current weather with a note, so the list only has to cover what people usually name.
PLACES = {
    "서울": (37.5665, 126.9780), "부산": (35.1796, 129.0756), "제주": (33.4996, 126.5312),
    "서귀포": (33.2541, 126.5600), "인천": (37.4563, 126.7052), "대구": (35.8714, 128.6014),
    "대전": (36.3504, 127.3845), "광주": (35.1595, 126.8526), "울산": (35.5384, 129.3114),
    "수원": (37.2636, 127.0286), "세종": (36.4800, 127.2890), "청주": (36.6424, 127.4890),
    "천안": (36.8151, 127.1139), "춘천": (37.8813, 127.7298), "강릉": (37.7519, 128.8761),
    "속초": (38.2070, 128.5918), "양양": (38.0754, 128.6190), "평창": (37.3705, 128.3903),
    "가평": (37.8315, 127.5105), "단양": (36.9846, 128.3655), "경주": (35.8562, 129.2247),
    "포항": (36.0190, 129.3435), "안동": (36.5684, 128.7294), "전주": (35.8242, 127.1480),
    "여수": (34.7604, 127.6622), "순천": (34.9506, 127.4872), "목포": (34.8118, 126.3922),
    "통영": (34.8544, 128.4331), "거제": (34.8806, 128.6211), "남해": (34.8376, 127.8924),
    "도쿄": (35.6762, 139.6503), "오사카": (34.6937, 135.5023), "교토": (35.0116, 135.7681),
    "후쿠오카": (33.5904, 130.4017), "삿포로": (43.0618, 141.3545), "오키나와": (26.2124, 127.6809),
    "타이베이": (25.0330, 121.5654), "홍콩": (22.3193, 114.1694), "상하이": (31.2304, 121.4737),
    "방콕": (13.7563, 100.5018), "다낭": (16.0544, 108.2022), "하노이": (21.0278, 105.8342),
    "싱가포르": (1.3521, 103.8198), "발리": (-8.4095, 115.1889), "괌": (13.4443, 144.7937),
    "파리": (48.8566, 2.3522), "런던": (51.5072, -0.1276), "로마": (41.9028, 12.4964),
    "뉴욕": (40.7128, -74.0060), "LA": (34.0522, -118.2437),
}

# Places and plans that imply an occasion, on top of the chat parser's own words.
OCCASION_WORDS = {
    "출근": intent.OCCASION_WORDS["출근"] + ("미팅", "회의", "발표"),
    "데이트": intent.OCCASION_WORDS["데이트"],
    "운동": intent.OCCASION_WORDS["운동"] + ("캠핑", "요가", "필라테스"),
    "여행": intent.OCCASION_WORDS["여행"] + ("바다", "해변", "바닷가", "공원", "피크닉", "놀이공원", "드라이브"),
    "모임": intent.OCCASION_WORDS["모임"] + ("레스토랑", "호텔", "공연", "돌잔치", "생일"),
}

WEEKDAYS = "월화수목금토일"
RELATIVE = {"오늘": 0, "내일": 1, "모레": 2, "글피": 3}


@dataclass
class Trip:
    day: date | None = None  # None: today, with the current weather
    day_words: str = ""      # how the user said it, for the reply
    place: str | None = None
    occasion: str | None = None

    @property
    def coords(self) -> tuple[float, float] | None:
        return PLACES.get(self.place) if self.place else None


def _week_monday(today: date) -> date:
    return today - timedelta(days=today.weekday())


def parse_day(text: str, today: date) -> tuple[date | None, str]:
    """The day a request is for, or None when it names none. Past dates mean next year."""
    m = re.search(r"(\d{1,2})\s*월\s*(\d{1,2})\s*일", text) or re.search(r"(?<![\d.])(\d{1,2})/(\d{1,2})(?![\d/])", text)
    if m:
        month, day = int(m.group(1)), int(m.group(2))
        try:
            found = date(today.year, month, day)
            if found < today:
                found = date(today.year + 1, month, day)
        except ValueError:
            return None, ""
        return found, m.group(0)
    m = re.search(r"(\d{1,2})\s*일\s*(?:뒤|후)", text)
    if m:
        return today + timedelta(days=int(m.group(1))), m.group(0)
    m = re.search(r"(다음\s*주|담주|이번\s*주)?\s*([월화수목금토일])요일", text)
    if m:
        index = WEEKDAYS.index(m.group(2))
        if m.group(1) and m.group(1).replace(" ", "") in ("다음주", "담주"):
            return _week_monday(today) + timedelta(days=7 + index), m.group(0).strip()
        return today + timedelta(days=(index - today.weekday()) % 7), m.group(0).strip()
    m = re.search(r"(다음\s*주|담주|이번\s*주)?\s*주말", text)
    if m:
        later = bool(m.group(1)) and m.group(1).replace(" ", "") in ("다음주", "담주")
        if later:
            return _week_monday(today) + timedelta(days=12), m.group(0).strip()
        return today if today.weekday() >= 5 else today + timedelta(days=5 - today.weekday()), m.group(0).strip()
    for word, offset in RELATIVE.items():
        if word in text:
            return today + timedelta(days=offset), word
    return None, ""


def parse_place(text: str) -> str | None:
    """The first named place; the longest name wins at the same position (서귀포 over a shorter one)."""
    hits = [(text.find(name), -len(name), name) for name in PLACES if name in text]
    return min(hits)[2] if hits else None


def parse_occasion(text: str) -> str | None:
    hits = [(min(text.find(w) for w in words if w in text), occasion)
            for occasion, words in OCCASION_WORDS.items() if any(w in text for w in words)]
    return min(hits)[1] if hits else None


def parse(text: str, now: datetime | None = None) -> Trip:
    text = re.sub(r"\s+", " ", text.strip())[:400]
    today = (now or datetime.now(KST)).astimezone(KST).date()
    day, words = parse_day(text, today)
    return Trip(day=day, day_words=words, place=parse_place(text), occasion=parse_occasion(text))


def day_label(day: date, today: date) -> str:
    """"내일(11/11 수)" style label."""
    names = {0: "오늘", 1: "내일", 2: "모레"}
    short = f"{day.month}/{day.day} {WEEKDAYS[day.weekday()]}"
    return f"{names[(day - today).days]}({short})" if (day - today).days in names else short

# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-08, reviewed by Dongkun Moon
"""K-Fashion value lists and the mappings to the app's own vocabularies.

Value lists come from a scan of all 1,088,781 K-Fashion labels (2026-10-08).
"""

from __future__ import annotations

import colorsys

SLOTS = ("아우터", "상의", "하의", "원피스")
UPPER_SLOTS = ("아우터", "상의", "원피스")  # fit values 노멀/루즈/오버사이즈/타이트

CATEGORIES = {
    "아우터": ("재킷", "가디건", "코트", "베스트", "점퍼", "패딩", "짚업"),
    "상의": ("티셔츠", "블라우스", "니트웨어", "탑", "셔츠", "후드티", "브라탑"),
    "하의": ("팬츠", "청바지", "스커트", "조거팬츠", "래깅스"),
    "원피스": ("드레스", "점프수트"),
}
UPPER_FITS = ("타이트", "노멀", "루즈", "오버사이즈")
BOTTOM_FITS = ("스키니", "노멀", "벨보텀", "와이드", "루즈")
LENGTHS = {
    "상의": ("크롭", "노멀", "롱"),
    "아우터": ("크롭", "노말", "하프", "롱", "맥시"),
    "하의": ("미니", "니렝스", "미디", "발목", "맥시"),
    "원피스": ("미니", "니렝스", "미디", "발목", "맥시"),
}
SLEEVES = ("민소매", "캡", "반팔", "7부소매", "긴팔")
SEASONS = ("여름", "간절기", "겨울")
PRINTS = (
    "무지", "체크", "스트라이프", "레터링", "그래픽", "플로럴", "도트", "깅엄", "호피", "페이즐리", "아가일",
    "하운즈 투스", "카무플라쥬", "지그재그", "믹스", "하트", "타이다이", "그라데이션", "지브라", "뱀피", "해골",
)
NECKLINES = ("라운드넥", "브이넥", "터틀넥", "유넥", "후드", "스퀘어넥", "보트넥", "오프숄더", "스위트하트", "노카라", "홀터넥", "원숄더")
COLLARS = ("셔츠칼라", "테일러드칼라", "밴드칼라", "차이나칼라", "피터팬칼라", "숄칼라", "세일러칼라", "폴로칼라", "보우칼라")
DETAILS = (
    "X스트랩", "글리터", "니트꽈베기", "단추", "더블브레스티드", "드롭숄더", "드롭웨이스트", "디스트로이드", "띠", "러플",
    "레이스", "레이스업", "롤업", "리본", "버클", "비대칭", "비즈", "셔링", "스터드", "스트링", "스티치", "스팽글",
    "슬릿", "싱글브레스티드", "자수", "지퍼", "체인", "컷아웃", "퀄팅", "태슬", "패치워크", "퍼트리밍", "퍼프",
    "페플럼", "포켓", "폼폼", "프린지", "프릴", "플레어", "플리츠",
)
MATERIALS = (
    "우븐", "저지", "니트", "데님", "시폰", "린넨", "울/캐시미어", "실크", "레이스", "가죽", "스웨이드", "코듀로이",
    "트위드", "벨벳", "플리스", "패딩", "퍼", "무스탕", "헤어 니트", "스판덱스", "메시", "자카드", "네오프렌",
    "비닐/PVC", "시퀸/글리터",
)

COLORS = {
    "블랙": "#1E1E1E", "화이트": "#F7F7F5", "그레이": "#9A9A9A", "베이지": "#D8C3A5",
    "브라운": "#7B5134", "네이비": "#1F2A44", "카키": "#6B6B3A", "스카이블루": "#9CC9EA",
    "블루": "#2F5DA8", "핑크": "#F2A7BC", "레드": "#C62F2F", "와인": "#722F37",
    "오렌지": "#E8833A", "옐로우": "#EED35A", "그린": "#3F8F4F", "민트": "#A8E0CF",
    "라벤더": "#C3B4E6", "퍼플": "#7A4FA0", "실버": "#C0C0C0", "골드": "#C9A447", "네온": "#C6F432",
}
NEUTRAL_COLORS = frozenset({"블랙", "화이트", "그레이", "베이지", "브라운", "네이비", "카키", "실버"})
DARK_COLORS = frozenset({"블랙", "네이비", "브라운", "와인", "카키", "그레이"})
BRIGHT_COLORS = frozenset({"화이트", "옐로우", "핑크", "스카이블루", "민트", "라벤더", "오렌지", "네온", "베이지"})

STYLES = (
    "스트리트", "페미닌", "리조트", "모던", "로맨틱", "클래식", "소피스트케이티드", "컨트리", "스포티",
    "젠더리스", "매니시", "톰보이", "레트로", "히피", "섹시", "키치", "오리엔탈", "아방가르드",
    "밀리터리", "프레피", "웨스턴", "힙합", "펑크",
)
# The profile's style chips (frontend STYLE_OPTIONS) and the wardrobe's style codes -> K-Fashion styles.
PROFILE_STYLES = {
    "미니멀": ("모던", "소피스트케이티드"),
    "캐주얼": ("스트리트", "컨트리"),
    "스트릿": ("스트리트", "힙합"),
    "러블리": ("로맨틱", "페미닌"),
    "클래식": ("클래식", "프레피"),
    "스포티": ("스포티",),
}
WARDROBE_STYLES = {
    "minimal": ("모던", "소피스트케이티드"),
    "casual": ("스트리트", "컨트리"),
    "street": ("스트리트", "힙합"),
    "classic": ("클래식", "프레피"),
    "sporty": ("스포티",),
    "formal": ("소피스트케이티드", "클래식"),
    "workwear": ("밀리터리", "매니시"),
}

# --- men's filter: K-Fashion is women's shopping-mall photos only, so men get the pieces whose
# shape is not gendered and see a note that the fit follows women's patterns.
MALE_EXCLUDED_CATEGORIES = frozenset({"스커트", "래깅스", "블라우스", "탑", "브라탑", "드레스", "점프수트"})
MALE_EXCLUDED_DETAILS = frozenset({
    "프릴", "러플", "리본", "퍼프", "셔링", "레이스", "페플럼", "비즈", "스팽글", "글리터", "컷아웃",
    "X스트랩", "레이스업", "드롭웨이스트", "보우칼라",
})
MALE_EXCLUDED_NECKLINES = frozenset({"오프숄더", "원숄더", "홀터넥", "스위트하트", "스퀘어넥", "보트넥"})
MALE_EXCLUDED_MATERIALS = frozenset({"레이스", "시퀸/글리터", "시폰"})
MALE_EXCLUDED_COLLARS = frozenset({"피터팬칼라", "보우칼라", "세일러칼라"})


def is_unisex(slot: str, attrs: dict) -> bool:
    if slot == "원피스" or attrs.get("카테고리") in MALE_EXCLUDED_CATEGORIES:
        return False
    if set(as_list(attrs.get("디테일"))) & MALE_EXCLUDED_DETAILS:
        return False
    if set(as_list(attrs.get("소재"))) & MALE_EXCLUDED_MATERIALS:
        return False
    if attrs.get("넥라인") in MALE_EXCLUDED_NECKLINES or attrs.get("옷깃") in MALE_EXCLUDED_COLLARS:
        return False
    if attrs.get("기장") == "크롭" or (slot == "상의" and attrs.get("핏") == "타이트"):
        return False
    return attrs.get("색상") not in {"핑크", "라벤더", "네온"}


# --- wardrobe schema (backend/apps/wardrobe/schema.py) -> K-Fashion
WARDROBE_CATEGORY = {"top": "상의", "bottom": "하의", "outerwear": "아우터"}
WARDROBE_SUBCATEGORY = {
    "tshirt": "티셔츠", "shirt": "셔츠", "blouse": "블라우스", "sweatshirt": "티셔츠", "hoodie": "후드티",
    "knitwear": "니트웨어", "jeans": "청바지", "slacks": "팬츠", "pants": "팬츠", "active_pants": "조거팬츠",
    "shorts": "팬츠", "skirt": "스커트", "cardigan": "가디건", "hooded_jacket": "짚업", "fleece": "점퍼",
    "jacket": "재킷", "coat": "코트", "puffer": "패딩", "vest": "베스트",
}
WARDROBE_DEFAULT_CATEGORY = {"상의": "티셔츠", "하의": "팬츠", "아우터": "재킷"}
WARDROBE_COLOR = {
    "#202020": "블랙", "#FFFFFF": "화이트", "#808080": "그레이", "#FFFFF0": "화이트", "#D6BE9A": "베이지",
    "#795548": "브라운", "#24344B": "네이비", "#3975C6": "블루", "#4F7952": "그린", "#7B8052": "카키",
    "#C83C3C": "레드", "#E88A3D": "오렌지", "#E8C547": "옐로우", "#E8A0B0": "핑크", "#9165AD": "퍼플",
    "#C0C0C0": "실버", "#C9A447": "골드",
}
WARDROBE_UPPER_FIT = {"skinny": "타이트", "slim": "타이트", "regular": "노멀", "loose": "루즈", "oversized": "오버사이즈"}
WARDROBE_LEG_SHAPE = {
    "skinny": "스키니", "slim": "스키니", "straight": "노멀", "tapered": "노멀", "semi_wide": "와이드",
    "wide": "와이드", "bootcut": "벨보텀", "flared": "벨보텀", "balloon": "루즈",
}


def normalize_value(value: str) -> str:
    """Keep dataset NBSP and UI spaces identical without changing trained token IDs."""
    return " ".join(value.split())


def as_list(value) -> list:
    if value is None:
        return []
    return list(value) if isinstance(value, list) else [value]


def josa(word: str, with_final: str, without_final: str) -> str:
    """Pick the particle (과/와, 을/를, 이/가) by whether the word ends in a final consonant."""
    last = word.strip()[-1:] or "a"
    if "가" <= last <= "힣":
        return with_final if (ord(last) - 0xAC00) % 28 else without_final
    return without_final


def hsv(color: str) -> tuple[float, float, float]:
    """Hue in degrees, saturation and value in 0..1 of a K-Fashion colour name."""
    hex_ = COLORS.get(color, "#808080").lstrip("#")
    r, g, b = (int(hex_[i : i + 2], 16) / 255 for i in (0, 2, 4))
    h, s, v = colorsys.rgb_to_hsv(r, g, b)
    return h * 360, s, v

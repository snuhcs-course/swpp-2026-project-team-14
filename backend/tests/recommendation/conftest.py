# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-08, reviewed by Dongkun Moon
"""A tiny hand-made catalog, so the recommender tests do not need the K-Fashion data."""

import pytest

from recommendation.catalog import Catalog, Item
from recommendation.compat import FEATURE_TYPES, CompatModel


def garment(id_, slot, cat, color, fit, style="모던", **extra):
    attrs = {"카테고리": cat, "색상": color, "핏": fit, **extra}
    return Item(id=id_, slot=slot, attrs=attrs, style=style, image=f"/cutouts/{id_}.webp")


ITEMS = [
    garment("t1", "상의", "니트웨어", "화이트", "루즈", 소재=["니트"], 소매기장="긴팔", 디테일=["드롭숄더"]),
    garment("t2", "상의", "티셔츠", "블랙", "노멀", 소재=["저지"], 소매기장="반팔"),
    garment("t3", "상의", "블라우스", "핑크", "루즈", 소재=["시폰"], 소매기장="긴팔", 디테일=["프릴"], style="로맨틱"),
    garment("t4", "상의", "셔츠", "스카이블루", "오버사이즈", 소재=["우븐"], 소매기장="긴팔", style="스트리트"),
    garment("t5", "상의", "니트웨어", "베이지", "루즈", 소재=["울/캐시미어"], 소매기장="긴팔"),
    garment("b1", "하의", "팬츠", "블랙", "와이드", 소재=["우븐"], 기장="발목"),
    garment("b2", "하의", "청바지", "블루", "스키니", 소재=["데님"], 기장="발목", style="스트리트"),
    garment("b3", "하의", "스커트", "베이지", "노멀", 소재=["우븐"], 기장="미디", style="페미닌"),
    garment("b4", "하의", "팬츠", "베이지", "루즈", 소재=["린넨"], 기장="발목"),
    garment("o1", "아우터", "코트", "브라운", "노멀", 소재=["울/캐시미어"], 소매기장="긴팔", 기장="하프"),
    garment("o2", "아우터", "패딩", "블랙", "루즈", 소재=["패딩"], 소매기장="긴팔"),
    garment("o3", "아우터", "가디건", "그레이", "루즈", 소재=["니트"], 소매기장="긴팔"),
    garment("d1", "원피스", "드레스", "네이비", "노멀", 소재=["우븐"], 소매기장="반팔", 기장="미디", style="페미닌"),
]
PMI = {
    "아우터|상의|카테고리|카테고리|코트|니트웨어": (0.7, 3600),
    "상의|하의|카테고리|카테고리|니트웨어|팬츠": (0.2, 9000),
    "상의|하의|핏|핏|루즈|와이드": (0.3, 29000),
    "상의|하의|색상|색상|화이트|블랙": (0.3, 30000),
    "상의|하의|색상|색상|핑크|블루": (-0.5, 500),
}


@pytest.fixture
def catalog():
    weights = [1.0] * len(FEATURE_TYPES)
    stats = {"calibration": {"real": [-0.7, -0.2, 0.15, 0.55, 1.5]}, "templates": {}, "meta": {}}
    return Catalog([Item(**{**i.__dict__}) for i in ITEMS], CompatModel(PMI, weights, 0.0), stats)


@pytest.fixture
def female():
    from recommendation.body import Body

    return Body(gender="female", height=162, chest=84, waist=68, hip=94, shoulder=38, inseam=72)

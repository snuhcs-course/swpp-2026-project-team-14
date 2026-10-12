# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-08, reviewed by Dongkun Moon
from recommendation import fit, season
from recommendation.body import Body, Shape
from recommendation.color import outfit_color
from recommendation.compat import PairCounter
from recommendation.vocab import is_unisex
from recommendation.wardrobe import to_item


def test_body_shape_matches_the_app_thresholds(female):
    assert female.shape == Shape.LOWER_VOLUME  # hip / chest = 1.12 >= 1.10
    assert Body(chest=96, waist=82, hip=96).shape == Shape.STRAIGHT  # waist / hip = 0.85
    assert Body(chest=90, waist=62, hip=92).shape == Shape.HOURGLASS
    assert Body(chest=90, waist=70, hip=92).shape is None  # too close to call
    assert female.short_legs  # 72 / 162 = 0.444 <= 0.45
    assert Body(height=170, inseam=80).long_legs and not Body(height=170, inseam=78).short_legs


def test_season_filters_and_templates():
    hot, cold = season.band(28), season.band(2)
    padding = {"카테고리": "패딩", "소재": ["패딩"], "소매기장": "긴팔"}
    tee = {"카테고리": "티셔츠", "소재": ["저지"], "소매기장": "반팔"}
    assert season.allowed(hot, 28, "아우터", padding, season.item_warmth("아우터", padding)) == "too_warm"
    assert season.allowed(cold, 2, "상의", tee, season.item_warmth("상의", tee)) == "too_cold"
    assert season.allowed(hot, 28, "상의", tee, season.item_warmth("상의", tee)) is None
    assert all("아우터" in t for t, _ in cold.templates)
    assert not any("아우터" in t for t, _ in hot.templates)
    assert season.item_warmth("아우터", padding) > season.item_warmth("아우터", {"카테고리": "가디건"})


def test_men_only_get_unisex_pieces():
    assert not is_unisex("하의", {"카테고리": "스커트", "색상": "블랙"})
    assert not is_unisex("원피스", {"카테고리": "드레스"})
    assert not is_unisex("상의", {"카테고리": "티셔츠", "디테일": ["프릴"]})
    assert not is_unisex("상의", {"카테고리": "티셔츠", "기장": "크롭"})
    assert is_unisex("상의", {"카테고리": "니트웨어", "색상": "그레이", "핏": "루즈"})
    assert is_unisex("아우터", {"카테고리": "코트", "색상": "브라운", "핏": "노멀"})


def test_fit_match_and_opposite_fit():
    assert fit.fit_match("loose", "상의", {"핏": "루즈"}) == 1.0
    assert fit.fit_match("loose", "하의", {"핏": "스키니"}) == 0.0  # filtered as the opposite fit
    assert fit.fit_match("slim", "상의", {"핏": "오버사이즈"}) == 0.0
    assert 0 < fit.fit_match("regular", "하의", {"핏": "와이드"}) < 1


def test_size_advice_from_body_and_fit_label(female):
    knit = fit.size_advice(female, "상의", {"핏": "루즈", "카테고리": "니트웨어"})
    assert knit == "가슴단면 48–52 cm"  # (84 + 12) / 2 .. (84 + 20) / 2
    pants = fit.size_advice(female, "하의", {"핏": "와이드", "카테고리": "팬츠", "기장": "발목"})
    assert "허리단면 34–35 cm" in pants and "엉덩이단면 52–56 cm" in pants and "안쪽 기장 69–72 cm" in pants


def test_owned_garment_smaller_than_the_body(female):
    small = fit.owned_fit(female, "하의", {"waist_width_half": 31, "hip_width_half": 44})
    assert small.too_small and "엉덩이둘레보다 6 cm 작아요" == small.detail
    roomy = fit.owned_fit(female, "상의", {"chest_width_half": 50})
    assert not roomy.too_small and roomy.label == "루즈"  # ease 16 cm


def test_wardrobe_item_mapping():
    item = to_item({"id": 7, "attributes": {"name": "연청 청바지", "category": "bottom", "subcategory": "jeans",
                                            "colors": ["#3975C6"], "leg_shape": "wide"}})
    assert item.id == "w:7" and item.slot == "하의" and item.source == "wardrobe"
    assert item.attrs["카테고리"] == "청바지" and item.attrs["색상"] == "블루" and item.attrs["핏"] == "와이드"
    assert to_item({"id": 8, "attributes": {"category": "shoes"}}) is None


def test_color_rules():
    neutral = outfit_color({"상의": {"색상": "화이트"}, "하의": {"색상": "블랙"}})
    one_accent = outfit_color({"상의": {"색상": "레드"}, "하의": {"색상": "블랙"}})
    clash = outfit_color({"상의": {"색상": "레드"}, "하의": {"색상": "그린"}, "아우터": {"색상": "옐로우"}})
    denim = outfit_color({"상의": {"색상": "옐로우"}, "하의": {"색상": "블루", "카테고리": "청바지"}})
    assert one_accent.score > neutral.score > clash.score
    assert denim.score == one_accent.score  # denim blue counts as neutral


def test_pair_counter_pmi_prefers_pairs_seen_together():
    counter = PairCounter()
    for _ in range(60):
        counter.add({"상의": {"카테고리": "니트웨어"}, "하의": {"카테고리": "스커트"}})
        counter.add({"상의": {"카테고리": "티셔츠"}, "하의": {"카테고리": "청바지"}})
    for _ in range(10):
        counter.add({"상의": {"카테고리": "니트웨어"}, "하의": {"카테고리": "청바지"}})
    pmi = counter.pmi(shrink=0, min_count=1)
    assert pmi["상의|하의|카테고리|카테고리|니트웨어|스커트"][0] > 0 > pmi["상의|하의|카테고리|카테고리|니트웨어|청바지"][0]

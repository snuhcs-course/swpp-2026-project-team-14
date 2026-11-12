# AI-generated with Claude Code (Claude Opus 5.5), 2026-11-12, reviewed by Kihwan Kim
"""옷장 분석(투표 방식) 계약 테스트. 모델 품질이 아니라, 정해 둔 예측이 어떻게 집계되는지를 본다."""

from urllib.parse import parse_qs, urlsplit

import numpy as np
import pytest
from onnxruntime.capi.onnxruntime_pybind11_state import Fail, RuntimeException

from recommendation import gaps
from recommendation.catalog import Item
from recommendation.external import ExternalModelUnavailable, preferred_style, search_title, search_url
from recommendation.gaps import wardrobe_gaps

VALUES = [
    ("", "<pad>"),
    ("카테고리", "셔츠"), ("카테고리", "니트웨어"), ("카테고리", "티셔츠"),
    ("카테고리", "팬츠"), ("카테고리", "스커트"), ("카테고리", "청바지"),
    ("색상", "블랙"), ("색상", "화이트"), ("색상", "그레이"), ("색상", "베이지"), ("색상", "네이비"), ("색상", "블루"),
    ("핏", "노멀"), ("핏", "루즈"), ("핏", "와이드"), ("핏", "오버사이즈"),
]


def logprobs(categories: dict, colors: dict, fits: dict | None = None) -> dict:
    """원하는 값에만 점수를 주고 나머지는 -inf인 예측 (허용 후보 안에서만 확률이 생긴다)."""
    out = {}
    for key, scores in (("카테고리", categories), ("색상", colors), ("핏", fits or {"노멀": 0.0})):
        array = np.full(len(VALUES), -np.inf)
        for value, score in scores.items():
            array[VALUES.index((key, value))] = score
        out[key] = array
    return out


class ModelDouble:
    """옷의 색상으로 예측을 고른다: 테스트마다 옷 색상을 다르게 두면 옷별 예측을 정할 수 있다."""
    values = VALUES

    def __init__(self, by_color: dict):
        self.by_color = by_color
        self.calls = []

    def slot_logprobs(self, context, style, slot):
        (source_slot, attrs), = context.items()
        self.calls.append((source_slot, attrs["색상"], style, slot))
        return self.by_color[attrs["색상"]]


def item(id_, slot, category, color, fit="노멀", style="모던", name=None):
    return Item(id=f"w:{id_}", slot=slot, attrs={"카테고리": category, "색상": color, "핏": fit}, style=style,
                source="wardrobe", name=name or id_, image=f"/api/wardrobe/items/{id_}/image/")


GRAY_KNIT = logprobs({"니트웨어": 0.0}, {"그레이": 0.0})
WHITE_TEE = logprobs({"티셔츠": 0.0}, {"화이트": 0.0})
BEIGE_PANTS = logprobs({"팬츠": 0.0}, {"베이지": 0.0})


def base_wardrobe():
    """상의 1, 하의 3 (모두 다른 색): 하의들이 상의를 예측한다."""
    return [
        item("top", "상의", "셔츠", "네이비"),
        item("jeans", "하의", "청바지", "블루"),
        item("slacks", "하의", "팬츠", "블랙"),
        item("skirt", "하의", "스커트", "베이지"),
    ]


def test_garments_wanting_the_same_item_are_counted_together_and_ranked_by_votes():
    gray_and_white = logprobs({"니트웨어": 0.0}, {"그레이": 0.0, "화이트": -0.1})
    model = ModelDouble({
        "네이비": BEIGE_PANTS,                      # 상의 → 베이지 팬츠 1표 (MIN_VOTES 미만이라 제외)
        "블루": GRAY_KNIT,                          # 하의 → 그레이 니트
        "블랙": gray_and_white, "베이지": gray_and_white,  # 하의 → 그레이 니트, 화이트 니트
    })
    result = wardrobe_gaps(base_wardrobe(), model)
    assert result["status"] == "ok"
    titles = [(gap["title"], gap["vote_count"]) for gap in result["gaps"]]
    assert titles == [("그레이 레귤러핏 니트", 3), ("화이트 레귤러핏 니트", 2)]


def test_vote_ties_are_broken_by_the_mean_probability():
    # 두 하의가 그레이(강)·화이트(약) 니트를 둘 다 원함 → 같은 2표, 확률 평균이 높은 그레이가 먼저
    strong_gray = logprobs({"니트웨어": 0.0}, {"그레이": 1.0, "화이트": 0.0})
    model = ModelDouble({"네이비": BEIGE_PANTS, "블루": strong_gray, "블랙": strong_gray, "베이지": WHITE_TEE})
    result = wardrobe_gaps(base_wardrobe(), model)
    knits = [gap["attributes"]["color"] for gap in result["gaps"] if gap["attributes"]["category"] == "니트웨어"]
    assert knits == ["그레이", "화이트"]


def test_a_kind_already_in_the_wardrobe_is_never_recommended():
    # 모든 하의가 네이비 셔츠를 원하지만 이미 네이비 셔츠가 있다 ("옷장에 없는 옷"만 추천)
    navy_shirt = logprobs({"셔츠": 0.0}, {"네이비": 0.0})
    model = ModelDouble({"네이비": BEIGE_PANTS, "블루": navy_shirt, "블랙": navy_shirt, "베이지": navy_shirt})
    result = wardrobe_gaps(base_wardrobe(), model)
    assert result == {"status": "enough", "garment_count": 4, "slot_counts": {"top": 1, "bottom": 3, "outerwear": 0},
                      "gaps": []}


def test_owned_check_compares_category_and_color_only():
    # 같은 종류라도 색이 다르면 옷장에 없는 옷이다
    navy_knit = logprobs({"니트웨어": 0.0}, {"네이비": 0.0})
    wardrobe = base_wardrobe() + [item("gray-knit", "상의", "니트웨어", "그레이")]
    model = ModelDouble({"네이비": BEIGE_PANTS, "블루": navy_knit, "블랙": navy_knit, "베이지": WHITE_TEE,
                         "그레이": BEIGE_PANTS})
    result = wardrobe_gaps(wardrobe, model)
    assert [gap["title"] for gap in result["gaps"]] == ["네이비 레귤러핏 니트", "베이지 레귤러핏 팬츠"]


def test_a_single_garments_wish_is_not_a_wardrobe_gap():
    model = ModelDouble({"네이비": BEIGE_PANTS, "블루": GRAY_KNIT, "블랙": WHITE_TEE,
                         "베이지": logprobs({"셔츠": 0.0}, {"블랙": 0.0})})
    assert wardrobe_gaps(base_wardrobe(), model)["status"] == "enough"


def test_at_most_three_distinct_gaps_are_returned():
    # 하의 4벌이 모두 같은 2카테고리 × 3색 = 6조합 중 상위 3조합에 투표
    wide = logprobs({"니트웨어": 0.0, "셔츠": -0.5}, {"그레이": 0.0, "화이트": -0.2, "베이지": -0.4, "블루": -3.0})
    wardrobe = base_wardrobe() + [item("wide", "하의", "팬츠", "그레이")]
    model = ModelDouble({"네이비": BEIGE_PANTS, "블루": wide, "블랙": wide, "베이지": wide, "그레이": wide})
    result = wardrobe_gaps(wardrobe, model)
    assert len(result["gaps"]) == gaps.MAX_GAPS
    keys = [(gap["slot"], gap["attributes"]["category"], gap["attributes"]["color"]) for gap in result["gaps"]]
    assert len(set(keys)) == len(keys)
    assert all(gap["vote_count"] == 4 for gap in result["gaps"])


def test_reason_and_evidence_are_exactly_the_garments_that_voted():
    # 근거 옷 = 실제로 이 옷을 예측한 옷, 이 조합을 강하게 원한 순서. 표를 안 던진 옷은 빠진다
    model = ModelDouble({
        "네이비": BEIGE_PANTS,
        "블루": logprobs({"니트웨어": 0.0}, {"그레이": 0.0, "화이트": 0.0}),  # 그레이 50 %
        "블랙": GRAY_KNIT,                                                   # 그레이 100 %
        "베이지": WHITE_TEE,
    })
    result = wardrobe_gaps(base_wardrobe(), model)
    gray = next(gap for gap in result["gaps"] if gap["attributes"]["color"] == "그레이")
    assert gray["vote_count"] == 2
    assert gray["reason"] == "내 옷 2벌과 잘 어울리는데, 비슷한 옷이 옷장에 없어요."
    assert gray["matched_garments"] == [
        {"garment_id": "slacks", "name": "slacks", "image_url": "/api/wardrobe/items/slacks/image/"},
        {"garment_id": "jeans", "name": "jeans", "image_url": "/api/wardrobe/items/jeans/image/"},
    ]


def test_tops_look_for_bottoms_and_bottoms_and_outerwear_look_for_tops():
    wardrobe = base_wardrobe() + [item("coat", "아우터", "코트", "그레이")]
    model = ModelDouble({"네이비": BEIGE_PANTS, "블루": GRAY_KNIT, "블랙": GRAY_KNIT, "베이지": GRAY_KNIT,
                         "그레이": GRAY_KNIT})
    result = wardrobe_gaps(wardrobe, model)
    targets = {color: slot for _, color, _, slot in model.calls}
    assert targets == {"네이비": "하의", "블루": "상의", "블랙": "상의", "베이지": "상의", "그레이": "상의"}
    assert result["slot_counts"] == {"top": 1, "bottom": 3, "outerwear": 1}
    assert result["gaps"][0]["slot"] == "top"
    assert result["gaps"][0]["vote_count"] == 4  # 아우터의 표도 상의 후보에 합쳐진다


def test_dresses_are_ignored():
    wardrobe = base_wardrobe() + [item("dress", "원피스", "드레스", "화이트")]
    model = ModelDouble({"네이비": BEIGE_PANTS, "블루": GRAY_KNIT, "블랙": GRAY_KNIT, "베이지": GRAY_KNIT})
    result = wardrobe_gaps(wardrobe, model)
    assert "화이트" not in [color for _, color, _, _ in model.calls]
    assert result["slot_counts"] == {"top": 1, "bottom": 3, "outerwear": 0}
    assert result["garment_count"] == 4  # 분석한 옷 수 = slot_counts 합 (원피스 제외)


@pytest.mark.parametrize("wardrobe", [
    [],
    [item("a", "하의", "팬츠", "블랙"), item("b", "하의", "청바지", "블루"), item("c", "아우터", "코트", "그레이"),
     item("d", "하의", "스커트", "베이지")],                                           # 상의 없음
    [item("a", "상의", "셔츠", "블랙"), item("b", "상의", "티셔츠", "화이트"), item("c", "아우터", "코트", "그레이"),
     item("d", "원피스", "드레스", "네이비")],                                          # 하의 없음
    [item("a", "상의", "셔츠", "블랙"), item("b", "하의", "팬츠", "화이트"), item("c", "아우터", "코트", "그레이")],  # 3벌
])
def test_small_wardrobes_are_not_analysed_and_need_no_model(wardrobe):
    result = wardrobe_gaps(wardrobe, None)
    assert result["status"] == "too_few"
    assert result["gaps"] == []


def test_four_garments_with_a_top_and_a_bottom_are_enough_to_analyse():
    model = ModelDouble({"네이비": BEIGE_PANTS, "블루": GRAY_KNIT, "블랙": GRAY_KNIT, "베이지": GRAY_KNIT})
    assert wardrobe_gaps(base_wardrobe(), model)["status"] == "ok"
    assert len(model.calls) == 4


def test_missing_model_is_reported_instead_of_inventing_gaps():
    with pytest.raises(ExternalModelUnavailable):
        wardrobe_gaps(base_wardrobe(), None)


@pytest.mark.parametrize("error", [Fail("broken"), RuntimeException("broken")])
def test_inference_failures_become_model_unavailable(error):
    class Failing(ModelDouble):
        def slot_logprobs(self, context, style, slot):
            raise error
    with pytest.raises(ExternalModelUnavailable):
        wardrobe_gaps(base_wardrobe(), Failing({}))


def test_prediction_without_a_valid_value_is_reported():
    no_color = logprobs({"니트웨어": 0.0}, {})
    model = ModelDouble({"네이비": no_color, "블루": no_color, "블랙": no_color, "베이지": no_color})
    with pytest.raises(ExternalModelUnavailable):
        wardrobe_gaps(base_wardrobe(), model)


def test_account_style_is_the_context_and_garment_style_is_the_fallback():
    by_color = {"네이비": BEIGE_PANTS, "블루": GRAY_KNIT, "블랙": GRAY_KNIT, "베이지": GRAY_KNIT}
    model = ModelDouble(by_color)
    wardrobe_gaps(base_wardrobe(), model, ["minimal"])
    assert {style for _, _, style, _ in model.calls} == {"모던"}
    wardrobe = [item(g.id.removeprefix("w:"), g.slot, g.attrs["카테고리"], g.attrs["색상"], style="스트리트")
                for g in base_wardrobe()]
    model = ModelDouble(by_color)
    wardrobe_gaps(wardrobe, model, ["unknown-style"])
    assert {style for _, _, style, _ in model.calls} == {"스트리트"}


def test_fit_is_the_one_most_wanted_by_the_voters_and_feeds_the_search_link():
    loose = logprobs({"니트웨어": 0.0}, {"그레이": 0.0}, {"루즈": 0.0, "노멀": -2.0})
    normal = logprobs({"니트웨어": 0.0}, {"그레이": 0.0}, {"루즈": -0.5, "노멀": 0.0})
    model = ModelDouble({"네이비": BEIGE_PANTS, "블루": loose, "블랙": loose, "베이지": normal})
    gap = wardrobe_gaps(base_wardrobe(), model)["gaps"][0]
    assert gap["attributes"] == {"category": "니트웨어", "color": "그레이", "fit": "루즈", "pattern": "무지"}
    assert gap["title"] == "그레이 루즈 니트"
    url = urlsplit(gap["search_url"])
    assert (url.scheme, url.netloc, url.path) == ("https", "www.musinsa.com", "/search/goods")
    assert parse_qs(url.query) == {"keyword": ["그레이 루즈 니트"]}


@pytest.mark.parametrize("preferred, title", [
    (["oversized"], "그레이 오버핏 니트"),
    (["slim"], "그레이 슬림 니트"),
    (["regular"], "그레이 레귤러핏 니트"),
    (["wide", "tapered", "oversized"], "그레이 오버핏 니트"),  # 상의에 안 맞는 선호는 건너뛰고 첫 번째로 맞는 값
])
def test_saved_preferred_fit_decides_the_fit_of_a_top(preferred, title):
    loose = logprobs({"니트웨어": 0.0}, {"그레이": 0.0}, {"루즈": 0.0, "노멀": -2.0})
    model = ModelDouble({"네이비": BEIGE_PANTS, "블루": loose, "블랙": loose, "베이지": loose})
    gap = wardrobe_gaps(base_wardrobe(), model, preferred_fits=preferred)["gaps"][0]
    assert gap["title"] == title
    assert parse_qs(urlsplit(gap["search_url"]).query) == {"keyword": [title]}


@pytest.mark.parametrize("preferred, fit", [(["wide"], "와이드"), (["slim"], "스키니"), (["oversized"], "노멀")])
def test_saved_preferred_fit_decides_the_fit_of_a_bottom(preferred, fit):
    # 상의 2벌이 베이지 팬츠를 원함. 오버핏은 하의에 없으니 모델이 가장 원한 핏(노멀)을 쓴다
    wardrobe = [item("tee", "상의", "티셔츠", "블랙"), item("shirt", "상의", "셔츠", "화이트"),
                item("jeans", "하의", "청바지", "블루"), item("skirt", "하의", "스커트", "네이비")]
    pants = logprobs({"팬츠": 0.0}, {"베이지": 0.0}, {"노멀": 0.0, "와이드": -1.0})
    model = ModelDouble({"블랙": pants, "화이트": pants, "블루": WHITE_TEE, "네이비": GRAY_KNIT})
    gap = wardrobe_gaps(wardrobe, model, preferred_fits=preferred)["gaps"][0]
    assert gap["attributes"]["category"] == "팬츠"
    assert gap["attributes"]["fit"] == fit


def test_shared_search_helpers_keep_the_matching_keyword_rules():
    assert search_title("셔츠", "블랙", "오버사이즈", "스트라이프") == "블랙 오버핏 스트라이프 셔츠"
    assert search_title("팬츠", "블랙", "노멀") == "블랙 레귤러핏 팬츠"
    assert search_url("블랙 셔츠") == "https://www.musinsa.com/search/goods?keyword=%EB%B8%94%EB%9E%99+%EC%85%94%EC%B8%A0"
    assert preferred_style(["unknown", "minimal"]) == "모던"
    assert preferred_style(None) is None

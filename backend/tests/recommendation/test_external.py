"""External matching contract tests use controlled model distributions, not model-quality evaluation."""

from urllib.parse import parse_qs, urlsplit

import numpy as np
import pytest

from recommendation.catalog import Item
from recommendation.external import ExternalModelUnavailable, matching_search


class ModelDouble:
    values = [
        ("", "<pad>"), ("카테고리", "코트"), ("카테고리", "셔츠"), ("카테고리", "팬츠"),
        ("핏", "오버사이즈"), ("핏", "와이드"), ("색상", "블랙"),
    ]

    def __init__(self):
        self.calls = []
        self.probabilities = {
            "카테고리": np.array([-np.inf, 9, 5, 4, -np.inf, -np.inf, -np.inf]),
            "핏": np.array([-np.inf, -np.inf, -np.inf, -np.inf, 9, 5, -np.inf]),
            "색상": np.array([-np.inf, -np.inf, -np.inf, -np.inf, -np.inf, -np.inf, 1]),
        }

    def slot_logprobs(self, context, style, slot):
        self.calls.append((context, style, slot))
        return self.probabilities


@pytest.mark.parametrize("source,target,category,fit", [
    ("상의", "bottom", "팬츠", "와이드"),
    ("하의", "top", "셔츠", "오버사이즈"),
    ("아우터", "top", "셔츠", "오버사이즈"),
])
def test_matching_respects_target_slot_and_uses_selected_garment(source, target, category, fit):
    model = ModelDouble()
    item = Item("w:selected", source, {"카테고리": "팬츠", "색상": "화이트"}, style="클래식")
    result = matching_search(item, model)
    assert result["target_slot"] == target
    assert result["attributes"] == {"category": category, "color": "블랙", "fit": fit, "pattern": "무지"}
    assert model.calls[0][0] == {source: item.attrs}
    assert model.calls[0][1] == "클래식"
    assert model.calls[0][2] == ("하의" if target == "bottom" else "상의")


def test_search_url_encodes_all_displayed_attributes_without_requesting_products():
    result = matching_search(Item("w:one", "하의", {"카테고리": "팬츠"}), ModelDouble())
    url = urlsplit(result["search_url"])
    assert (url.scheme, url.netloc, url.path) == ("https", "www.musinsa.com", "/search/goods")
    assert parse_qs(url.query) == {"keyword": [result["title"]]}
    assert result["title"] == "블랙 오버핏 셔츠"
    assert "체형" not in result["reason"] and "날씨" not in result["reason"]


def test_existing_style_mapping_prioritizes_account_preference_over_garment_style():
    model = ModelDouble()
    result = matching_search(Item("w:one", "하의", {"카테고리": "팬츠"}, style="클래식"), model, ["cityboy", "minimal"])
    assert model.calls[0][1] == "모던"
    assert "선호 스타일" in result["reason"]


def test_regular_fit_is_also_included_in_the_search_keyword():
    model = ModelDouble()
    model.values = [*model.values, ("핏", "노멀")]
    model.probabilities = {key: np.append(scores, -np.inf) for key, scores in model.probabilities.items()}
    model.probabilities["핏"][-1] = 15
    result = matching_search(Item("w:one", "하의", {"카테고리": "팬츠"}), model)
    assert result["attributes"]["fit"] == "노멀"
    assert result["title"] == "블랙 레귤러핏 셔츠"
    assert parse_qs(urlsplit(result["search_url"]).query)["keyword"] == [result["title"]]


def test_unmapped_style_falls_back_to_garment_style_without_claiming_profile_basis():
    model = ModelDouble()
    result = matching_search(Item("w:one", "하의", {"카테고리": "팬츠"}, style="클래식"), model, ["cityboy"])
    assert model.calls[0][1] == "클래식"
    assert "선호 스타일" not in result["reason"]


@pytest.mark.parametrize("failure", ["missing_model", "missing_attribute", "no_finite_values"])
def test_unavailable_predictions_do_not_fabricate_search_attributes(failure):
    model = ModelDouble()
    if failure == "missing_attribute":
        model.probabilities.pop("카테고리")
    elif failure == "no_finite_values":
        model.probabilities["색상"][:] = np.nan
    with pytest.raises(ExternalModelUnavailable):
        matching_search(Item("w:one", "하의", {"카테고리": "팬츠"}), None if failure == "missing_model" else model)


def _with_patterns(model, scores):
    """프린트 후보를 모델 더블에 추가한다. scores: {패턴: 로그확률}"""
    model.values = [*model.values, *(("프린트", name) for name in scores)]
    width = len(model.values)
    model.probabilities = {key: np.concatenate([value, np.full(width - len(value), -np.inf)])
                           for key, value in model.probabilities.items()}
    model.probabilities["프린트"] = np.concatenate([np.full(width - len(scores), -np.inf), list(scores.values())])
    return model


def test_predicted_pattern_joins_keyword_and_headline_without_filter_parameters():
    model = _with_patterns(ModelDouble(), {"스트라이프": 5.0, "무지": 2.0})
    result = matching_search(Item("w:one", "하의", {"카테고리": "팬츠"}), model)
    assert result["title"] == "블랙 오버핏 스트라이프 셔츠"
    assert result["headline"] == "해당 옷과 가장 잘 어울리는 상의는 블랙 & 오버핏 & 스트라이프 속성이에요."
    assert result["attributes"]["pattern"] == "스트라이프"
    assert parse_qs(urlsplit(result["search_url"]).query) == {"keyword": ["블랙 오버핏 스트라이프 셔츠"]}


def test_plain_pattern_is_left_out_of_the_keyword():
    model = _with_patterns(ModelDouble(), {"스트라이프": 2.0, "무지": 5.0})
    result = matching_search(Item("w:one", "하의", {"카테고리": "팬츠"}), model)
    assert result["title"] == "블랙 오버핏 셔츠"
    assert result["attributes"]["pattern"] == "무지"


def test_unusable_pattern_prediction_falls_back_to_plain_search():
    model = _with_patterns(ModelDouble(), {"스트라이프": 2.0})
    model.probabilities["프린트"][:] = np.nan
    result = matching_search(Item("w:one", "하의", {"카테고리": "팬츠"}), model)
    assert result["title"] == "블랙 오버핏 셔츠"


def test_headline_names_target_slot_and_predicted_color_and_fit():
    result = matching_search(Item("w:one", "하의", {"카테고리": "팬츠"}), ModelDouble())
    assert result["headline"] == "해당 옷과 가장 잘 어울리는 상의는 블랙 & 오버핏 속성이에요."


def test_reason_reports_candidate_shares_from_model_scores():
    model = ModelDouble()
    model.values = [*model.values, ("색상", "화이트")]
    model.probabilities = {key: np.append(scores, -np.inf) for key, scores in model.probabilities.items()}
    model.probabilities["색상"][-1] = 1 - np.log(3)  # 블랙:화이트 = 3:1 -> 75%
    result = matching_search(Item("w:one", "하의", {"카테고리": "팬츠"}), model)
    assert "색상 75%" in result["reason"]
    assert "핏 100%" in result["reason"]  # 와이드는 상의 핏 후보가 아님
    assert "블랙·오버핏" in result["reason"]


def test_wardrobe_matches_rank_only_target_slot_garments_by_model_score():
    wardrobe = [
        Item("w:best", "상의", {"카테고리": "셔츠", "색상": "블랙", "핏": "오버사이즈"}, name="검정 셔츠"),
        Item("w:mid", "상의", {"카테고리": "팬츠", "색상": "블랙"}, name="애매한 상의"),
        Item("w:bottom", "하의", {"카테고리": "팬츠", "색상": "블랙"}, name="다른 바지"),
        Item("w:unknown", "상의", {"카테고리": "후드티"}, name="모르는 옷"),
    ]
    result = matching_search(Item("w:one", "하의", {"카테고리": "팬츠"}), ModelDouble(), wardrobe=wardrobe)
    assert result["wardrobe_matches"] == [{"garment_id": "best", "name": "검정 셔츠"},
                                          {"garment_id": "mid", "name": "애매한 상의"}]


def test_wardrobe_matches_are_limited_and_empty_without_wardrobe():
    wardrobe = [Item(f"w:{i}", "상의", {"카테고리": "셔츠"}, name=str(i)) for i in range(5)]
    model = ModelDouble()
    assert len(matching_search(Item("w:one", "하의", {"카테고리": "팬츠"}), model, wardrobe=wardrobe)["wardrobe_matches"]) == 3
    assert matching_search(Item("w:one", "하의", {"카테고리": "팬츠"}), model)["wardrobe_matches"] == []


def test_unsupported_source_slot_is_rejected_before_model_call():
    model = ModelDouble()
    with pytest.raises(ValueError):
        matching_search(Item("w:one", "신발", {"카테고리": "운동화"}), model)
    assert model.calls == []

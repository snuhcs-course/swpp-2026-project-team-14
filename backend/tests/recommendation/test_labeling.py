# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
import pytest

from recommendation.body import Body
from recommendation.labeling import label, nearest_color, validate_labels


def dims(**values):
    return {k: {"value": v, "source": "ar", "method": "landmarks", "reference": None} for k, v in values.items()}


BODY = Body(gender="female", height=162, chest=84, waist=68, hip=94, shoulder=38, inseam=72,
            torso_length=44, sleeve_length=56, outseam=98)


def test_body_reads_the_app_profile():
    body = Body.from_json({"height_cm": 175, "gender": "male", "measurements": [
        {"type": "chest", "value_cm": 96}, {"type": "shoulder_width", "value_cm": 46},
        {"type": "torso_length", "value_cm": 50}, {"type": "unknown", "value_cm": 1}]})
    assert (body.height, body.chest, body.shoulder, body.torso_length, body.male) == (175, 96, 46, 50, True)
    assert body.public() == {"gender": "male", "height": 175, "chest": 96, "shoulder": 46, "torso_length": 50}


def test_top_fit_length_sleeve_from_measurements():
    tee = label({"category": "top", "subcategory": "tshirt", "colors": ["#202020"]},
                dims(chest_width_half=50, total_length=68, sleeve_length=20, shoulder_width=44), BODY)
    assert tee["fit"] == "루즈"  # ease 100 - 84 = 16
    assert "16 cm" in tee["fit_basis"]
    assert tee["length"] == "노멀"  # 68 / 44 = 1.55
    assert tee["sleeve"] == "반팔"  # 20 / 56 = 0.36
    assert tee["drop_shoulder"]  # 44 > 38 + 4
    assert tee["season"] == "여름"
    assert tee["kf_category"] == "티셔츠" and tee["kf_colors"] == ["블랙"]
    assert tee["materials"] == ["저지"] and not tee["too_small"] and not tee["confirmed"]
    assert tee["body_basis"]["chest"] == 84

    crop = label({"category": "top", "subcategory": "knitwear", "colors": []},
                 dims(chest_width_half=46, total_length=45, sleeve_length=58), BODY)  # ease 8
    assert (crop["fit"], crop["length"], crop["sleeve"], crop["season"]) == ("노멀", "크롭", "긴팔", "간절기")


def test_garment_smaller_than_the_body_is_flagged():
    small = label({"category": "top", "subcategory": "shirt", "colors": []}, dims(chest_width_half=40), BODY)
    assert small["too_small"] and "4 cm" in small["too_small_reason"]
    jeans = label({"category": "bottom", "subcategory": "jeans", "colors": []},
                  dims(waist_width_half=30, hip_width_half=48), BODY)
    assert jeans["too_small"] and "허리" in jeans["too_small_reason"]


def test_outer_uses_outer_ease_and_height_ratio():
    coat = label({"category": "outerwear", "subcategory": "coat", "colors": ["#795548"]},
                 dims(chest_width_half=55, total_length=95, sleeve_length=60), BODY)
    assert coat["fit"] == "루즈"  # ease 26 sits on the outer 루즈/오버사이즈 boundary
    assert coat["length"] == "롱"  # 95 / 162 = 0.59
    assert coat["kf_colors"] == ["브라운"]


@pytest.mark.parametrize("hem, thigh, hip, expected", [
    (16, 30, 47, "스키니"),  # hem / thigh 0.53, hip ease 0
    (22, 31, 50, "노멀"),
    (29, 32, 53, "와이드"),
    (34, 30, 48, "벨보텀"),
])
def test_pants_leg_shape(hem, thigh, hip, expected):
    pants = label({"category": "bottom", "subcategory": "pants", "colors": []},
                  dims(hip_width_half=hip, thigh_width_half=thigh, hem_opening=hem, inseam=70), BODY)
    assert pants["fit"] == expected
    assert pants["length"] == "발목"  # 70 / 72


def test_bottom_lengths():
    shorts = label({"category": "bottom", "subcategory": "shorts", "colors": []}, dims(inseam=12), BODY)
    assert shorts["length"] == "미니"  # 12 / 72
    skirt = label({"category": "bottom", "subcategory": "skirt", "colors": []},
                  dims(hip_width_half=50, total_length=70), BODY)
    assert (skirt["length"], skirt["fit"], skirt["kf_category"]) == ("미디", "노멀", "스커트")  # 70 / 98 = 0.71


def test_missing_measurements_leave_labels_open():
    top = label({"category": "top", "subcategory": "blouse", "colors": []}, {}, Body(height=160))
    assert top["fit"] is None and top["length"] is None and top["sleeve"] is None
    assert label({"category": "shoes", "colors": []}, {}, BODY) is None


def test_ai_details_are_kept_only_inside_the_vocabulary():
    top = label({"category": "top", "subcategory": "shirt", "colors": []}, {}, BODY,
                {"print": "스트라이프", "neckline": "브이넥", "collar": "없는칼라"})
    assert (top["prints"], top["neckline"], top["collar"]) == (["스트라이프"], "브이넥", None)


def test_nearest_color():
    from recommendation.labeling import kf_color

    assert kf_color("#4F7952") == "그린" and nearest_color("#4F7952") == "카키"  # the palette's own mapping wins
    assert kf_color("#4f7952") == "그린" and kf_color("#2E8B57") == nearest_color("#2E8B57")
    assert nearest_color("#F5F5F0") == "화이트"
    assert nearest_color("#1A2340") == "네이비"
    assert nearest_color("#C0392B") == "레드"


def test_validate_labels():
    good = label({"category": "top", "subcategory": "tshirt", "colors": ["#202020"]},
                 dims(chest_width_half=50, total_length=68, sleeve_length=20), BODY)
    edited = {**good, "fit": "오버사이즈", "materials": ["린넨"], "confirmed": True}
    assert validate_labels(edited, "top")["fit"] == "오버사이즈"
    assert validate_labels(None, "top") is None
    for bad in ({**good, "fit": "스키니"}, {**good, "materials": ["린넨", "린넨"]}, {**good, "extra": 1},
                {**good, "confirmed": "yes"}, {**good, "length": "맥시"}, "labels",
                {**good, "design_details": ["프릴", "리본", "포켓", "지퍼"]}, {**good, "design_details": ["반짝이"]}):
        with pytest.raises(ValueError):
            validate_labels(bad, "top")
    with pytest.raises(ValueError):
        validate_labels(good, "shoes")


def test_ai_design_details_and_visible_materials_win_over_defaults():
    knit = label({"category": "top", "subcategory": "knitwear", "colors": []}, {}, BODY,
                 {"design_details": ["드롭숄더", "포켓", "없는디테일"], "materials": ["울/캐시미어"]})
    assert knit["design_details"] == ["드롭숄더", "포켓"]
    assert knit["materials"] == ["울/캐시미어"]  # the AI saw wool; the subcategory default would be 니트
    plain = label({"category": "top", "subcategory": "knitwear", "colors": []}, {}, BODY, {"materials": []})
    assert plain["materials"] == ["니트"]


def test_without_body_or_measurements_the_capture_type_and_old_fit_fill_in():
    top = label({"category": "top", "subcategory": None, "colors": [], "fit_type": "oversized"}, {}, None,
                garment_type="short_sleeve_top")
    assert (top["fit"], top["fit_basis"]) == ("오버사이즈", "기존에 고른 핏")
    assert (top["sleeve"], top["season"], top["body_basis"]) == ("반팔", "여름", {})
    assert top["length"] is None and not top["too_small"]
    shorts = label({"category": "bottom", "subcategory": None, "colors": [], "leg_shape": "wide"}, {}, None,
                   garment_type="shorts")
    assert (shorts["fit"], shorts["length"], shorts["kf_category"]) == ("와이드", "미니", "팬츠")
    skirt = label({"category": "bottom", "subcategory": None, "colors": []}, {}, None, garment_type="skirt")
    assert skirt["kf_category"] == "스커트" and skirt["fit"] is None
    # measured on the body, the computed fit wins over the old choice
    measured = label({"category": "top", "subcategory": "tshirt", "colors": [], "fit_type": "slim"},
                     dims(chest_width_half=50), BODY)
    assert measured["fit"] == "루즈"


def test_category_must_belong_to_the_garment_slot():
    for category in ("없는카테고리", "팬츠", [], {}):
        with pytest.raises(ValueError):
            validate_labels({"kf_category": category}, "top")
    assert validate_labels({"kf_category": "티셔츠"}, "top")["kf_category"] == "티셔츠"
    assert validate_labels({"kf_category": None}, "top")["kf_category"] is None


def test_primary_and_secondary_colors_keep_the_first_two_positions():
    from recommendation.wardrobe import to_item

    # The first two HEX values can map to the same K-Fashion name. Do not promote color 3.
    attrs = {"category": "top", "subcategory": "tshirt", "colors": ["#FFFFFF", "#FFFFF0", "#202020"]}
    labels = label(attrs, None)
    assert labels["kf_colors"] == ["화이트", "화이트"]
    validate_labels(labels, "top")
    for saved in (None, labels):
        item = to_item({"id": "colors", "attributes": attrs, "labels": saved})
        assert (item.attrs["색상"], item.attrs["서브색상"]) == ("화이트", "화이트")
    attrs["colors"] = ["#FFFFFF", "#24344B", "#202020"]
    item = to_item({"id": "colors", "attributes": attrs, "labels": label(attrs, None)})
    assert (item.attrs["색상"], item.attrs["서브색상"]) == ("화이트", "네이비")


def test_confirmed_season_is_the_model_token_not_a_new_estimate():
    from recommendation.wardrobe import to_item
    from recommendation.masked import garment_tokens, season_token

    attrs = {"category": "top", "subcategory": "tshirt", "colors": []}
    labels = {**label(attrs, None, garment_type="short_sleeve_top"), "season": "겨울", "confirmed": True}
    item = to_item({"id": "season", "attributes": attrs, "labels": validate_labels(labels, "top")})
    assert season_token(item.slot, item.attrs) == "여름"  # the previous automatic estimate
    assert ("상의", "계절", "겨울") in garment_tokens(item.slot, item.attrs)

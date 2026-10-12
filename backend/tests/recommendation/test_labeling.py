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
                {**good, "confirmed": "yes"}, {**good, "length": "맥시"}, "labels"):
        with pytest.raises(ValueError):
            validate_labels(bad, "top")
    with pytest.raises(ValueError):
        validate_labels(good, "shoes")

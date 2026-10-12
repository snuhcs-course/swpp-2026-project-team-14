# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-08, reviewed by Dongkun Moon
import pytest

from recommendation import preference
from recommendation.body import Body
from recommendation.search import RecommendError, Request, recommend
from recommendation.wardrobe import to_item


def ids(result):
    return [[i["id"] for i in o["items"]] for o in result["outfits"]]


def test_three_different_outfits_with_explanations(catalog, female):
    res = recommend(catalog, Request(body=female, pref_fit="loose", temp=17))
    assert len(res["outfits"]) == 3
    assert len({tuple(x) for x in ids(res)}) == 3
    for o in res["outfits"]:
        assert 0 <= o["total"] <= 100 and set(o["components"]) == {"compat", "body", "color", "season", "taste"}
        assert o["reasons"] and all(r["text"] for r in o["reasons"])
        assert all(i["size"] for i in o["items"])  # every catalog garment has size advice


def test_cold_weather_needs_an_outer_and_hot_weather_drops_it(catalog, female):
    cold = recommend(catalog, Request(body=female, pref_fit="loose", temp=0))
    assert all("아우터" in o["template"] for o in cold["outfits"])
    hot = recommend(catalog, Request(body=female, pref_fit="loose", temp=29))
    assert not any("아우터" in o["template"] for o in hot["outfits"])
    assert not any(i["id"] == "o2" for o in hot["outfits"] for i in o["items"])


def test_men_never_get_skirts_dresses_or_frills(catalog):
    man = Body(gender="male", height=175, chest=96, waist=82, hip=96, shoulder=46, inseam=80)
    res = recommend(catalog, Request(body=man, pref_fit="regular", temp=17))
    used = {i for o in ids(res) for i in o}
    assert not used & {"b3", "d1", "t3"}
    assert any("남성" in n for n in res["notes"])


def test_dislike_lowers_similar_garments_and_never_show_removes(catalog, female):
    before = recommend(catalog, Request(body=female, pref_fit="loose", temp=17, count=1))
    first_top = next(i for i in before["outfits"][0]["items"] if i["slot"] == "상의")
    garments = {i.id: {"slot": i.slot, "attrs": i.attrs, "style": i.style} for i in catalog.items}
    pref = preference.apply_feedback(None, [{"item_id": first_top["id"], "polarity": -1, "aspect": None}], garments)
    # every garment sharing the disliked attributes now scores lower than an unrelated one
    disliked = catalog.by_id[first_top["id"]]
    assert preference.score(pref, disliked.slot, disliked.attrs, disliked.style) < 0.5
    pref = preference.apply_feedback(pref, [{"item_id": first_top["id"], "polarity": -1, "exclude": True}], garments)
    after = recommend(catalog, Request(body=female, pref_fit="loose", temp=17, preference=pref))
    assert first_top["id"] not in {i for o in ids(after) for i in o}
    assert after["debug"]["filtered"].get("다시 보지 않기") == 1


def test_aspect_feedback_only_moves_that_attribute(catalog):
    garments = {"t1": {"slot": "상의", "attrs": catalog.by_id["t1"].attrs, "style": "모던"}}
    pref = preference.apply_feedback(None, [{"item_id": "t1", "polarity": -1, "aspect": "color"}], garments)
    assert set(pref["weights"]) == {"색상:화이트"}
    dark = preference.apply_feedback(None, [{"polarity": -1, "aspect": "color", "value": "dark"}], {})
    assert dark["weights"]["색상:블랙"] < 0 and "색상:화이트" not in dark["weights"]


def test_anchor_mode_keeps_my_garment_and_skips_too_small_ones(catalog, female):
    jeans = {"id": 1, "attributes": {"name": "청바지", "category": "bottom", "subcategory": "jeans", "colors": ["#3975C6"],
                                      "leg_shape": "straight"}, "dimensions": {"waist_width_half": 35, "hip_width_half": 50}}
    small = {"id": 2, "attributes": {"name": "작은 슬랙스", "category": "bottom", "subcategory": "slacks", "colors": ["#202020"],
                                      "leg_shape": "wide"}, "dimensions": {"waist_width_half": 31, "hip_width_half": 44}}
    wardrobe = [to_item(jeans), to_item(small)]
    res = recommend(catalog, Request(body=female, pref_fit="regular", temp=17, mode="anchor", anchor="w:1", wardrobe=wardrobe))
    assert all("w:1" in o for o in ids(res))
    assert res["debug"]["too_small"] == ["w:2"]
    with pytest.raises(RecommendError):
        recommend(catalog, Request(body=female, temp=17, mode="anchor", anchor="w:2", wardrobe=wardrobe))


def test_owned_lookalikes_are_penalised_in_new_mode(catalog, female):
    base = recommend(catalog, Request(body=female, pref_fit="loose", temp=17, count=1))
    top = next(i for i in base["outfits"][0]["items"] if i["slot"] == "상의")
    owned = to_item({"id": 3, "attributes": {"category": "top", "subcategory": "knitwear" if "니트" in top["name"] else "tshirt",
                                              "colors": ["#FFFFFF"]}})
    owned.attrs.update({"카테고리": top["attrs"]["카테고리"], "색상": top["attrs"]["색상"]})
    again = recommend(catalog, Request(body=female, pref_fit="loose", temp=17, count=1, wardrobe=[owned]))
    assert again["outfits"][0]["total"] <= base["outfits"][0]["total"]


# --- wardrobe mode (the app): only the user's own garments, with body-based labels
def owned(id_, category, sub, color, name, **labels):
    base = {"fit": None, "length": None, "sleeve": None, "materials": [], "prints": [], "kf_colors": [color],
            "too_small": False, "too_small_reason": None, "confirmed": True}
    return to_item({"id": id_, "attributes": {"name": name, "category": category, "subcategory": sub, "colors": [],
                                              "styles": ["minimal"]},
                    "labels": {**base, **labels}, "image": f"/api/wardrobe/items/{id_}/image/"})


WARDROBE = [
    owned(1, "top", "knitwear", "베이지", "베이지 니트", fit="루즈", sleeve="긴팔", materials=["니트"], fit_basis="가슴 여유 16 cm"),
    owned(2, "top", "tshirt", "화이트", "흰 티", fit="노멀", sleeve="반팔"),
    owned(3, "top", "shirt", "스카이블루", "작은 셔츠", fit="타이트", too_small=True, too_small_reason="가슴둘레보다 4 cm 작아요"),
    owned(4, "bottom", "slacks", "블랙", "검정 슬랙스", fit="와이드", length="발목"),
    owned(5, "bottom", "jeans", "블루", "청바지", fit="스키니", length="발목", materials=["데님"]),
    owned(6, "outerwear", "coat", "브라운", "갈색 코트", fit="노멀", length="하프", materials=["울/캐시미어"]),
]


def test_labels_become_kfashion_attributes():
    knit = WARDROBE[0]
    assert knit.labeled and knit.attrs["핏"] == "루즈" and knit.attrs["소재"] == ["니트"]
    assert knit.fit_note == "루즈 (가슴 여유 16 cm)" and knit.styles == ("모던", "소피스트케이티드")
    assert WARDROBE[2].too_small == "가슴둘레보다 4 cm 작아요"
    frilly = owned(9, "top", "blouse", "화이트", "프릴 블라우스", design_details=["프릴", "리본"], drop_shoulder=True)
    assert frilly.attrs["디테일"] == ["프릴", "리본", "드롭숄더"]


def test_wardrobe_mode_uses_only_my_garments(catalog, female):
    res = recommend(catalog, Request(body=female, pref_fit="loose", styles=["미니멀"], temp=12, mode="wardrobe",
                                     wardrobe=WARDROBE))
    used = {i for o in ids(res) for i in o}
    assert used and all(i.startswith("w:") for i in used)
    assert "w:3" not in used and any("작은 셔츠" in n for n in res["notes"])
    assert not any("남성" in n for n in res["notes"])
    for o in res["outfits"]:
        assert {"상의", "하의"} <= {i["slot"] for i in o["items"]}
        assert o["key"] == "+".join(sorted(i["id"] for i in o["items"]))
        assert all(i["size"].startswith("내 옷") for i in o["items"])
    knit = next(i for o in res["outfits"] for i in o["items"] if i["id"] == "w:1")
    assert "가슴 여유 16 cm" in knit["size"]


def test_shown_outfits_are_not_repeated_first(catalog, female):
    req = dict(body=female, pref_fit="loose", temp=17, mode="wardrobe", wardrobe=WARDROBE, count=1)
    first = recommend(catalog, Request(**req))["outfits"][0]
    second = recommend(catalog, Request(**req, seen_outfits=[first["key"]]))["outfits"][0]
    assert second["key"] != first["key"] and not second["seen"]


def test_wardrobe_needs_a_top_and_a_bottom_that_fit(catalog, female):
    with pytest.raises(RecommendError) as error:
        recommend(catalog, Request(body=female, temp=17, mode="wardrobe", wardrobe=[WARDROBE[2], WARDROBE[3]]))
    assert error.value.code == "WARDROBE_TOO_SMALL"


def test_own_garments_survive_the_weather_with_a_note(catalog, female):
    res = recommend(catalog, Request(body=female, pref_fit="loose", temp=30, mode="wardrobe",
                                     wardrobe=[WARDROBE[0], WARDROBE[3]]))
    assert ids(res)[0] == ["w:1", "w:4"]
    assert any("날씨" in n for n in res["notes"])
    cold = recommend(catalog, Request(body=female, pref_fit="loose", temp=0, mode="wardrobe",
                                      wardrobe=[WARDROBE[0], WARDROBE[3]]))
    assert any("아우터" in n for n in cold["notes"])


def test_shipped_models_score_my_wardrobe(female):
    from recommendation.catalog import Catalog

    models = Catalog.load_models()  # backend/recommendation/models (PMI stats + masked transformer)
    assert models.masked is not None and set(models.masked.pair_calibration) >= {"상의|하의"}
    res = recommend(models, Request(body=female, pref_fit="loose", styles=["미니멀"], temp=12, mode="wardrobe",
                                    wardrobe=WARDROBE))
    assert res["debug"]["masked"] and len(res["outfits"]) == 3
    assert all(0 <= o["components"]["compat"] <= 100 for o in res["outfits"])

"""Change reasons after a chat edit (recommendation/chat_reasons.py)."""
from recommendation.catalog import Item
from recommendation.chat_reasons import reasons


def item(gid, slot, name, **attrs):
    return Item(id=f"w:{gid}", slot=slot, attrs=attrs, source="wardrobe", name=name)


BEFORE = {
    "top": item("t1", "상의", "검정 니트", 카테고리="니트웨어", 색상="블랙", 핏="노멀"),
    "bottom": item("b1", "하의", "슬랙스", 카테고리="팬츠", 색상="블랙", 핏="노멀"),
    "outerwear": item("o1", "아우터", "코트", 카테고리="코트", 색상="네이비"),
}


def test_a_direction_names_the_attribute_that_moved():
    after = [BEFORE["outerwear"], item("t2", "상의", "흰 셔츠", 카테고리="셔츠", 색상="화이트", 핏="노멀"), BEFORE["bottom"]]
    out = reasons(BEFORE, after, [{"slot": None, "want": "brighter"}])
    assert out == [{"slot": "top", "from": "검정 니트", "to": "흰 셔츠",
                    "reason": "요청하신 더 밝은 색으로 바꿨어요 (블랙 → 화이트)."}]


def test_colours_named_garments_added_and_removed_slots():
    red = [item("t2", "상의", "빨간 티", 카테고리="티셔츠", 색상="레드"), BEFORE["bottom"]]
    assert reasons(BEFORE, red, [], target_colors=["레드"])[0]["reason"] == "요청하신 레드 색으로 골랐어요."
    named = reasons(BEFORE, red, [], targets={"top": "t2"})
    assert named[0]["reason"] == "말씀하신 옷으로 바꿨어요."
    # the outerwear left: listed after the garments, with nothing in its place
    removed = reasons(BEFORE, [BEFORE["top"], BEFORE["bottom"]], [], removed_outerwear=True)
    assert removed == [{"slot": "outerwear", "from": "코트", "to": None, "reason": "요청하신 대로 아우터를 뺐어요."}]
    no_outer = {k: v for k, v in BEFORE.items() if k != "outerwear"}
    added = reasons(no_outer, [item("o2", "아우터", "재킷", 카테고리="재킷"), *no_outer.values()], [])
    assert added[0]["reason"] == "요청하신 대로 아우터를 더했어요."


def test_a_slot_changed_to_match_says_so_and_a_forecast_is_named():
    after = [BEFORE["outerwear"], item("t2", "상의", "흰 셔츠", 카테고리="셔츠", 색상="화이트"),
             item("b2", "하의", "청바지", 카테고리="청바지", 색상="블루")]
    out = reasons(BEFORE, after, [{"slot": "top", "want": "brighter"}], requested={"top"})
    assert [r["slot"] for r in out] == ["top", "bottom"]
    assert out[1]["reason"] == "바뀐 상의와 어울리도록 함께 바꿨어요."
    trip = reasons(BEFORE, after[:2] + [BEFORE["bottom"]], [], plan_label="내일(11/24 화) 부산")
    assert trip[0]["reason"].startswith("내일(11/24 화) 부산 날씨에 맞춰 바꿨어요")


def test_warmth_is_only_claimed_for_a_garment_that_got_warmer():
    after = [item("o2", "아우터", "패딩", 카테고리="패딩"), item("t2", "상의", "셔츠", 카테고리="셔츠"), BEFORE["bottom"]]
    out = {r["slot"]: r["reason"] for r in reasons(BEFORE, after, [{"slot": None, "want": "warmer"}])}
    assert out["outerwear"] == "요청하신 더 따뜻한 옷으로 바꿨어요 (코트 → 패딩)."
    # a knit swapped for a lighter shirt is not "warmer": it went with the thicker outer
    assert out["top"] == "코디 전체가 더 따뜻해지도록 함께 바꿨어요."
    # nothing warmer left: the reply says so instead of claiming it
    lighter = [item("o2", "아우터", "재킷", 카테고리="재킷"), BEFORE["top"], BEFORE["bottom"]]
    assert reasons(BEFORE, lighter, [{"slot": None, "want": "warmer"}])[0]["reason"] ==         "옷장에서 더 따뜻해지는 조합을 찾지 못해 비슷한 두께의 다른 옷으로 바꿨어요."

"""The checkers of eval/recommendation/run.py follow RUBRIC.md (rule compliance, part A)."""
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "eval_recommendation", Path(__file__).resolve().parents[2] / "eval" / "recommendation" / "run.py")
run = importlib.util.module_from_spec(spec)
spec.loader.exec_module(run)

BODY = {"chest": 90, "waist": 72, "hip": 98}


def dims(**v):
    return {"unit": "cm", **{k: {"value": x} for k, x in v.items()}}


def garment(gid, category, **d):
    return {"id": gid, "attributes": {"category": category}, "dimensions": dims(**d)}


def test_too_small_uses_the_garments_own_measurements():
    assert run.too_small(garment("t", "top", chest_width_half=42), BODY)  # 84 < 90
    assert not run.too_small(garment("t", "top", chest_width_half=48), BODY)
    assert run.too_small(garment("b", "bottom", hip_width_half=47, waist_width_half=40), BODY)  # hip 94 < 98
    assert run.too_small(garment("b", "bottom", hip_width_half=52, waist_width_half=34), BODY)  # waist 68 < 70
    assert not run.too_small(garment("b", "bottom", hip_width_half=52, waist_width_half=36), BODY)


def test_weather_table():
    assert run.item_weather(25, "아우터", {"카테고리": "재킷"})
    assert run.item_weather(25, "상의", {"소재": ["플리스"]})
    assert run.item_weather(10, "상의", {"소매기장": "민소매"})
    assert run.item_weather(10, "상의", {"소재": ["린넨"]})
    assert run.item_weather(3, "상의", {"소매기장": "반팔"})
    assert run.item_weather(3, "하의", {"카테고리": "스커트", "기장": "미니"})
    assert run.item_weather(15, "하의", {"카테고리": "팬츠", "기장": "미니"})
    assert not run.item_weather(15, "하의", {"카테고리": "스커트", "기장": "미니"})  # mini skirt with tights
    assert not run.item_weather(15, "상의", {"소매기장": "반팔"})
    assert not run.item_weather(25, "상의", {"소매기장": "반팔", "소재": ["린넨"]})


def case(temp, garments):
    return {"temp": temp, "body": BODY, "garments": garments}


def outfit(*items):
    return {"key": "+".join(sorted(i["id"] for i in items)), "items": list(items)}


def item(gid, slot, **attrs):
    return {"id": f"w:{gid}", "slot": slot, "attrs": attrs}


def test_check_outfit_rules_and_disclosed_exceptions():
    gs = [garment("t", "top", chest_width_half=48), garment("b", "bottom", hip_width_half=52, waist_width_half=37),
          garment("s", "top", chest_width_half=40)]
    c = case(3, gs)
    ok = run.check_outfit(c, outfit(item("t", "상의", 소매기장="긴팔"), item("b", "하의", 기장="발목")),
                          ["오늘은 아우터가 필요한 날씨인데 맞는 아우터가 없어 상의와 하의만 골랐어요."], None)
    assert ok["violations"] == [] and ok["disclosed"] == ["R3 no outerwear under 9 °C"]
    # the notice does not excuse it when the wardrobe had a suitable outerwear
    avoidable = run.check_outfit(c, outfit(item("t", "상의", 소매기장="긴팔"), item("b", "하의", 기장="발목")),
                                 ["오늘은 아우터가 필요한 날씨인데 맞는 아우터가 없어 상의와 하의만 골랐어요."], None, {"아우터"})
    assert avoidable["violations"] == ["R3 no outerwear under 9 °C"] and avoidable["disclosed"] == []
    bad = run.check_outfit(c, outfit(item("s", "상의", 소매기장="반팔"), item("x", "하의")), [], None)
    assert any(v.startswith("R1") for v in bad["violations"])
    assert any(v.startswith("R2") for v in bad["violations"])
    assert any(v.startswith("R3 short sleeves") for v in bad["violations"])
    # a locked garment is the user's choice: not checked for the weather, but it must stay
    locked = run.check_outfit(c, outfit(item("t", "상의", 소매기장="반팔"), item("b", "하의", 기장="발목")), [], "w:t")
    assert [v for v in locked["violations"] if v.startswith("R3 short")] == []
    lost = run.check_outfit(c, outfit(item("b", "하의", 기장="발목")), [], "w:t")
    assert any(v.startswith("R4") for v in lost["violations"])

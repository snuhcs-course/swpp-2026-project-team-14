# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-11, reviewed by Dongkun Moon
import json
import math

import pytest

from recommendation import body_rules, decision, intent, weather
from recommendation.body import Body
from recommendation.masked import garment_tokens, season_token
from recommendation.stylist import Stylist, StylistRequest, named_owned
from recommendation.wardrobe import to_item


# ---------------------------------------------------------------- decision (Jev contract)
def test_local_decider_returns_jev_shaped_answers():
    q = decision.ChoiceQuestion("pick", {"A1": "keep", "A2": "coat", "A3": "jacket"}, {"A1": 0.9, "A2": 0.5, "A3": 0.45})
    ans = decision.LocalDecider(temperature=0.1).decide("state", {"outer": q})["outer"]
    assert ans.choice == "A1" and ans.decider == "local"
    assert math.isclose(sum(ans.probabilities.values()), 1.0, abs_tol=1e-3)
    assert 0 <= ans.confidence <= 1
    flat = decision.LocalDecider(temperature=10).decide("s", {"q": q})["q"]
    assert flat.confidence < ans.confidence  # a flatter distribution is less confident


def test_confidence_is_one_minus_normalised_entropy():
    assert decision.confidence_of([1.0, 0.0, 0.0]) == pytest.approx(1.0)
    assert decision.confidence_of([1 / 3] * 3) == pytest.approx(0.0)


def test_jev_failure_falls_back_to_local(monkeypatch):
    class Broken:
        name = "jev"

        def decide(self, state, questions):
            raise decision.JevError("HTTP 429")

    q = {"q": decision.ChoiceQuestion("pick", {"A1": "x", "A2": "y"}, {"A1": 1.0, "A2": 0.0})}
    answers, ms, error = decision.decide("s", q, decision.LocalDecider(temperature=0.1), Broken())
    assert answers["q"].decider == "local" and "Jev" in error and ms >= 0


def test_jev_request_uses_neutral_option_ids(monkeypatch):
    sent = {}

    class Resp:
        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def read(self, n):
            return json.dumps({"answers": {"q": {"type": "choice", "choice": "A2",
                                                 "probabilities": {"A1": 0.2, "A2": 0.8}, "confidence": 0.5}}}).encode()

    def fake_urlopen(req, timeout):
        sent["body"] = json.loads(req.data)
        sent["auth"] = req.headers["Authorization"]
        return Resp()

    monkeypatch.setattr(decision, "urlopen", fake_urlopen)
    jev = decision.JevDecider("AIMLAPI_KEY", "k")
    ans = jev.decide("state", {"q": decision.ChoiceQuestion("pick", {"A1": "그대로", "A2": "코트"})})
    assert ans["q"].choice == "A2" and ans["q"].probabilities["A2"] == 0.8
    assert sent["body"]["questions"]["q"]["criteria"] == {"A1": "그대로", "A2": "코트"}
    assert sent["body"]["model"] == "typesafe/jev" and sent["auth"] == "Bearer k"


# ---------------------------------------------------------------- intent
def test_intent_rules():
    it = intent.rules("상의만 밝은 색으로 바꿔줘")
    assert it.type == "item" and it.slots == ["상의"] and it.wants == [{"slot": "상의", "want": "brighter"}]
    warm = intent.rules("비 오니까 따뜻하게 해줘")
    assert warm.type == "style" and {"slot": None, "want": "warmer"} in warm.wants
    work = intent.rules("출근용으로 단정하게")
    assert work.occasion == "출근" and any(w["want"] == "formal" for w in work.wants)
    assert intent.rules("새 코디 추천해줘").type == "whole"


# ---------------------------------------------------------------- rule table
def test_rule_table_cites_sources_and_scores(female):
    score, fired = body_rules.apply(female, "스트레이트", "상의", {"핏": "오버사이즈", "색상": "화이트", "소재": ["헤어 니트"]})
    ids = {f.id for f in fired}
    assert {"lv-bright-top", "bs-avoid-oversize", "bs-avoid-bulky"} <= ids
    assert score < 0.5 and all(f.source for f in fired)
    good, _ = body_rules.apply(female, "스트레이트", "하의", {"핏": "벨보텀", "색상": "블랙", "기장": "발목"})
    assert good > 0.7
    table = body_rules.table()
    for group in (table["shapes"], table["bone_types"], table["proportions"]):
        for rules in group.values():
            assert all(r["source"] in table["sources"] for r in rules)


# ---------------------------------------------------------------- weather
def test_open_meteo_parsing_and_rain_rules():
    w = weather.parse({"current": {"temperature_2m": 12.3, "apparent_temperature": 9.8, "precipitation": 1.2,
                                   "weather_code": 61, "wind_speed_10m": 14}})
    assert w.rain and not w.snow and w.label == "비" and w.apparent == 9.8
    pen, why = weather.rain_penalty(w, "아우터", {"소재": ["스웨이드"]})
    assert pen < 0 and "스웨이드" in why
    assert weather.rain_penalty(weather.manual(15), "아우터", {"소재": ["스웨이드"]}) == (0.0, None)


# ---------------------------------------------------------------- masked tokens
def test_garment_tokens_add_a_rule_based_season():
    toks = garment_tokens("아우터", {"카테고리": "패딩", "색상": "블랙", "소재": ["패딩"]})
    assert ("아우터", "계절", "겨울") in toks and ("아우터", "카테고리", "패딩") in toks
    assert season_token("상의", {"카테고리": "티셔츠", "소매기장": "반팔"}) == "여름"
    assert season_token("상의", {"카테고리": "셔츠", "소매기장": "긴팔", "소재": ["우븐"]}) == "간절기"


# ---------------------------------------------------------------- stylist turns
@pytest.fixture
def stylist(catalog):
    return Stylist(catalog, None, decision.LocalDecider(temperature=0.08), None)


def turn(stylist, female, text, current, temp=17, wardrobe=()):
    req = StylistRequest(body=female, weather=weather.manual(temp), text=text, pref_fit="loose",
                         current=current, wardrobe=list(wardrobe), seed=1)
    return stylist.turn(req)


def test_unasked_slots_are_kept(stylist, female):
    r = turn(stylist, female, "상의만 밝은 색으로 바꿔줘", {"상의": "t2", "하의": "b1"})
    by_slot = {d["slot"]: d for d in r["decisions"]}
    assert by_slot["하의"]["status"] == "kept"
    top = next(i for i in r["outfit"]["items"] if i["slot"] == "상의")
    assert top["id"] != "t2" and top["attrs"]["색상"] in {"화이트", "베이지", "핑크", "스카이블루"}
    assert all(math.isclose(sum(o["prob"] for o in d["options"]), 1.0, abs_tol=0.05) or len(d["options"]) == 4 for d in r["decisions"])


def test_named_owned_garment_wins(stylist, female):
    jeans = to_item({"id": 9, "attributes": {"name": "연청 청바지", "category": "bottom", "subcategory": "jeans",
                                             "colors": ["#3975C6"], "leg_shape": "straight"}})
    assert named_owned("하의는 내 청바지로", jeans)
    r = turn(stylist, female, "하의는 내 청바지로 바꿔줘", {"상의": "t1", "하의": "b1"}, wardrobe=[jeans])
    bottom = next(i for i in r["outfit"]["items"] if i["slot"] == "하의")
    assert bottom["id"] == "w:9"


def test_cold_weather_adds_an_outer(stylist, female):
    r = turn(stylist, female, "", {"상의": "t1", "하의": "b1"}, temp=3)
    assert any(i["slot"] == "아우터" for i in r["outfit"]["items"])
    assert r["latency_ms"]["total"] >= 0 and r["decider"] == "local"

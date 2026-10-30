"""채팅 평가 스크립트의 채점 로직 테스트 (모델 호출 없음)."""

import importlib.util
import json
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("eval_chat", ROOT / "scripts" / "eval_chat.py")
eval_chat = importlib.util.module_from_spec(spec)
spec.loader.exec_module(eval_chat)

IDS = {"g-top", "g-jeans"}


def output(**changes):
    base = dict(action="edit", change_slots=[], keep_slots=[], wants=[], avoid_colors=[], target_colors=[],
                target_garment_ids=[], remove_outerwear=False, question="")
    return {**base, **changes}


def case(expect, critical=()):
    return {"id": "t", "expect": expect, "critical": list(critical)}


def test_matching_output_passes():
    c = case({"action": "edit", "wants_include": [{"slot": None, "want": "brighter"}], "wants_exclude": ["darker"]})
    assert eval_chat.score(c, output(wants=[{"slot": None, "want": "brighter"}]), IDS) == ([], [])


@pytest.mark.parametrize("want_slot,expected_slot,ok", [
    ("top", None, True),     # 기대 slot이 없으면 어느 slot이든 인정
    (None, "top", True),     # 전체(None) 방향은 특정 slot 기대도 만족
    ("top", "top", True),
    ("bottom", "top", False),
])
def test_want_slot_matching(want_slot, expected_slot, ok):
    c = case({"wants_include": [{"slot": expected_slot, "want": "brighter"}]})
    failed, _ = eval_chat.score(c, output(wants=[{"slot": want_slot, "want": "brighter"}]), IDS)
    assert (not failed) is ok


def test_unlisted_fields_are_not_scored():
    c = case({"action": "edit"})
    assert eval_chat.score(c, output(change_slots=["top"], avoid_colors=["블랙"]), IDS) == ([], [])


def test_include_and_exclude_lists():
    c = case({"change_slots_include": ["top"], "change_slots_exclude": ["bottom"]})
    assert eval_chat.score(c, output(change_slots=["top"]), IDS)[0] == []
    assert eval_chat.score(c, output(change_slots=[]), IDS)[0]
    assert eval_chat.score(c, output(change_slots=["top", "bottom"]), IDS)[0]


def test_garment_ids_must_match_exactly_regardless_of_order():
    c = case({"target_garment_ids": ["g-top", "g-jeans"]})
    assert eval_chat.score(c, output(target_garment_ids=["g-jeans", "g-top"]), IDS)[0] == []
    assert eval_chat.score(c, output(target_garment_ids=["g-top"]), IDS)[0]


def test_critical_violation_is_reported_separately():
    c = case({"wants_exclude": ["brighter"]}, critical=["wants_exclude"])
    failed, critical = eval_chat.score(c, output(wants=[{"slot": None, "want": "brighter"}]), IDS)
    assert failed and len(critical) == 1


def test_non_critical_failure_is_not_critical():
    c = case({"action": "clarify"})
    failed, critical = eval_chat.score(c, output(action="edit"), IDS)
    assert failed and critical == []


def test_invented_garment_and_keep_change_overlap_are_always_critical():
    _, critical = eval_chat.score(case({}), output(target_garment_ids=["g-made-up"]), IDS)
    assert any("옷장에 없는" in c for c in critical)
    _, critical = eval_chat.score(case({}), output(keep_slots=["top"], change_slots=["top"]), IDS)
    assert any("동시" in c for c in critical)


def test_context_matches_the_server_shape():
    wardrobe = [{"id": "g-top", "slot": "top", "name": "티", "category": "티셔츠", "color": "화이트"},
                {"id": "g-jeans", "slot": "bottom", "name": "청바지", "category": "청바지", "color": "블루"}]
    ctx = eval_chat.context({"current": ["g-top", "g-jeans"], "locked": ["g-jeans"], "history": []}, wardrobe)
    assert ctx["current"] == [{"slot": "top", "name": "티"}, {"slot": "bottom", "name": "청바지"}]
    assert ctx["locked_slots"] == ["bottom"]
    assert ctx["wardrobe"] == wardrobe


class FakeIntent:
    class ChatModelError(Exception):
        pass

    def __init__(self, failures):
        self.failures = list(failures)  # 실패할 때 남길 예외 타입 이름 목록
        self.calls = 0

    def parse(self, text, ctx):
        self.calls += 1
        if self.failures:
            self.errors.last = self.failures.pop(0)
            raise self.ChatModelError()
        return output(), "gemini", None


def run(failures, retries=2):
    fake = FakeIntent(failures)
    errors = eval_chat._ErrorType()
    fake.errors = errors
    result = eval_chat.run_case(fake, {"request": "x", "current": [], "locked": [], "history": []}, [], errors,
                                retries, 0)
    return result, fake.calls


def test_format_error_is_not_retried():
    (out, error), calls = run(["ValueError"])
    assert out is None and error == "format:ValueError" and calls == 1


def test_infra_error_is_retried_then_succeeds():
    (out, error), calls = run(["URLError"])
    assert out is not None and error is None and calls == 2


def test_infra_error_after_all_retries_is_reported():
    (out, error), calls = run(["GeminiError"] * 3)
    assert out is None and error == "infra:GeminiError" and calls == 3


def test_shipped_cases_are_well_formed():
    data = json.loads((ROOT / "eval" / "chat" / "cases.json").read_text(encoding="utf-8"))
    ids = {g["id"] for g in data["wardrobe"]}
    allowed = {"action", "wants_include", "wants_exclude", "remove_outerwear", "target_garment_ids"} | {
        f"{f}_{k}" for f in ("change_slots", "keep_slots", "avoid_colors", "target_colors") for k in ("include", "exclude")}
    assert len(data["cases"]) >= 30
    assert len({c["id"] for c in data["cases"]}) == len(data["cases"])
    for c in data["cases"]:
        assert c["expect"] and set(c["expect"]) <= allowed, c["id"]
        assert set(c["critical"]) <= set(c["expect"]), c["id"]
        assert set(c["current"]) <= ids and set(c["locked"]) <= ids, c["id"]
        assert set(c["expect"].get("target_garment_ids", [])) <= ids, c["id"]

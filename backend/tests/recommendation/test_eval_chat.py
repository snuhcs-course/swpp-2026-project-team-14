"""채팅 평가 스크립트의 채점 로직 테스트 (모델 호출 없음)."""

import importlib.util
import json
from pathlib import Path

import pytest

from recommendation import chat_intent, gemini

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
    ChatModelError = chat_intent.ChatModelError

    def __init__(self, failures):
        self.failures = list(failures)
        self.calls = 0

    def parse(self, text, ctx):
        self.calls += 1
        if self.failures:
            kind, code = self.failures.pop(0)
            raise self.ChatModelError(kind, code)
        return output(), "gemini", None


def run(failures, retries=2):
    fake = FakeIntent(failures)
    result = eval_chat.run_case(fake, {"request": "x", "current": [], "locked": [], "history": []}, [],
                                retries, 0)
    return result, fake.calls


def test_format_error_is_not_retried():
    (out, error), calls = run([("format", "ValueError")])
    assert out is None and error == "format:ValueError" and calls == 1


def test_infra_error_is_retried_then_succeeds():
    (out, error), calls = run([("infra", "URLError")])
    assert out is not None and error is None and calls == 2


def test_infra_error_after_all_retries_is_reported():
    (out, error), calls = run([("infra", "AI_RATE_LIMITED")] * 3)
    assert out is None and error == "infra:AI_RATE_LIMITED" and calls == 3


def test_empty_or_unreadable_model_response_is_a_format_error():
    (out, error), calls = run([("format", "AI_INVALID_RESPONSE")])
    assert out is None and error == "format:AI_INVALID_RESPONSE" and calls == 1


@pytest.mark.parametrize("code", ["AI_TIMEOUT", "AI_UNAVAILABLE", "AI_AUTH_FAILED", "AI_PROVIDER_ERROR"])
def test_provider_side_gemini_errors_are_infra(code):
    (out, error), _ = run([("infra", code)], retries=0)
    assert error == f"infra:{code}"


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


@pytest.mark.parametrize("partial,error,critical,passed,verdict", [
    (False, None, [], True, "PASS"),
    (True, None, [], True, "INCOMPLETE"),
    (False, "infra:AI_TIMEOUT", [], False, "INCOMPLETE"),
    (False, "format:AI_INVALID_RESPONSE", [], False, "FAIL"),
    (False, None, ["invented ID"], False, "FAIL"),
    (False, None, [], False, "FAIL"),
])
def test_summary_preserves_pass_fail_and_incomplete(partial, error, critical, passed, verdict):
    rows = [{"id": "test", "error": error, "critical": critical, "passed": passed}]
    summary = eval_chat.summarize(rows, partial=partial)
    assert summary["verdict"] == verdict
    assert summary["criteria"] == {"pass_rate": 0.8, "critical_errors": 0, "format_errors": 0}


def test_eighty_percent_passes_but_a_format_error_still_fails():
    rows = [{"id": str(i), "error": None, "critical": [], "passed": i < 4} for i in range(5)]
    assert eval_chat.summarize(rows)["verdict"] == "PASS"
    rows[-1]["error"] = "format:ValueError"
    assert eval_chat.summarize(rows)["verdict"] == "FAIL"
    assert eval_chat.summarize([])["verdict"] == "INCOMPLETE"


@pytest.fixture
def isolated_evaluation(monkeypatch, tmp_path):
    for name in ("CHAT_PROVIDER", "OPENROUTER_API_KEY", "OPENROUTER_CHAT_MODEL", "GEMINI_TEXT_MODEL"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setattr(gemini, "api_key", lambda: "test-gemini-key")
    monkeypatch.setattr(eval_chat, "load_dotenv", lambda *a, **kw: None)
    monkeypatch.setattr(eval_chat, "EVAL_DIR", tmp_path)
    monkeypatch.setattr(eval_chat, "ROOT", tmp_path)
    # Only mock fixtures are written to pytest's temp folder, never to the official results.
    data = {"version": 1, "wardrobe": [], "cases": [
        {"id": "mock", "category": "test", "request": "test", "current": [], "locked": [],
         "history": [], "expect": {"action": "edit"}, "critical": []}]}
    (tmp_path / "cases.json").write_text(json.dumps(data), encoding="utf-8")
    calls = []
    def parse(*args):
        calls.append(args)
        return output(), chat_intent.model_config()["provider"], None
    monkeypatch.setattr(chat_intent, "parse", parse)
    return tmp_path, calls


def test_main_records_resolved_openrouter_provider_and_model(monkeypatch, isolated_evaluation):
    directory, calls = isolated_evaluation
    monkeypatch.setenv("OPENROUTER_API_KEY", "test-router-key")
    monkeypatch.setenv("OPENROUTER_CHAT_MODEL", "example/router-model")
    assert eval_chat.main(["--delay", "0"]) == 0
    result = json.loads(next((directory / "results").glob("*.json")).read_text(encoding="utf-8"))
    assert result["provider"] == "openrouter"
    assert result["model"] == "example/router-model"
    assert result["temperature"] == 0 and result["summary"]["verdict"] == "PASS"
    assert len(calls) == 1


def test_show_config_never_calls_a_model_or_exposes_keys(monkeypatch, isolated_evaluation, capsys):
    directory, calls = isolated_evaluation
    monkeypatch.setenv("OPENROUTER_API_KEY", "private-test-value")
    assert eval_chat.main(["--show-config"]) == 0
    text = capsys.readouterr().out
    assert json.loads(text)["provider"] == "openrouter"
    assert "private-test-value" not in text and calls == []
    assert not (directory / "results").exists()


@pytest.mark.parametrize("args", [
    ["--expected-provider", "openrouter"], ["--expected-model", "different-model"], ["--case", "missing"]])
def test_config_mismatch_or_unknown_case_stops_before_calling_a_model(args, isolated_evaluation):
    directory, calls = isolated_evaluation
    assert eval_chat.main(args) == 2
    assert calls == [] and not (directory / "results").exists()


def test_missing_key_is_configuration_failure_not_a_format_score(monkeypatch, isolated_evaluation):
    directory, calls = isolated_evaluation
    monkeypatch.setattr(gemini, "api_key", lambda: None)
    assert eval_chat.main([]) == 2
    assert calls == [] and not (directory / "results").exists()


def test_partial_and_infra_runs_do_not_write_official_results(monkeypatch, isolated_evaluation):
    directory, _ = isolated_evaluation
    assert eval_chat.main(["--case", "mock", "--delay", "0"]) == 1
    def unavailable(*args):
        raise chat_intent.ChatModelError("infra", "AI_TIMEOUT")
    monkeypatch.setattr(chat_intent, "parse", unavailable)
    assert eval_chat.main(["--retries", "0"]) == 1
    assert not (directory / "results").exists()

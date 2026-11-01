"""Service/evaluation provider selection and structured failures; no external model calls."""

import io
import json
from urllib.error import HTTPError, URLError

import pytest

from recommendation import chat_intent, gemini


@pytest.fixture(autouse=True)
def isolated_keys(monkeypatch):
    for name in ("CHAT_PROVIDER", "OPENROUTER_API_KEY", "OPENROUTER_CHAT_MODEL", "GEMINI_TEXT_MODEL"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setattr(gemini, "api_key", lambda: "test-google-key")


def google_response(value):
    return {"content": {"parts": [{"text": json.dumps(value)}]}}


def test_auto_without_router_key_uses_the_configured_gemini_model(monkeypatch):
    monkeypatch.setenv("GEMINI_TEXT_MODEL", "test-google-model")
    calls = []
    def generate(model, payload, *args, **kwargs):
        calls.append((model, payload))
        return google_response(chat_intent.empty())
    monkeypatch.setattr(gemini, "generate", generate)
    result, provider, notice = chat_intent.parse("test", {})
    assert provider == "gemini" and result == chat_intent.empty() and notice is None
    assert calls[0][0] == chat_intent.model_config()["model"] == "test-google-model"
    assert calls[0][1]["generationConfig"]["temperature"] == 0


def test_auto_with_router_key_uses_the_configured_openrouter_model(monkeypatch):
    monkeypatch.setenv("OPENROUTER_API_KEY", "private-router-key")
    monkeypatch.setenv("OPENROUTER_CHAT_MODEL", "test-router-model")
    calls = []
    class Opener:
        def open(self, request, **kwargs):
            calls.append(request)
            return io.BytesIO(json.dumps({"choices": [{"message": {"content": json.dumps(chat_intent.empty())}}]}).encode())
    monkeypatch.setattr(chat_intent, "build_opener", lambda *args: Opener())
    _, provider, _ = chat_intent.parse("test", {})
    payload = json.loads(calls[0].data)
    assert provider == "openrouter"
    assert payload["model"] == chat_intent.model_config()["model"] == "test-router-model"
    assert payload["temperature"] == 0
    assert chat_intent.model_config()["configured"] is True
    assert "private-router-key" not in json.dumps(chat_intent.model_config())


def test_explicit_gemini_overrides_router_key(monkeypatch):
    monkeypatch.setenv("CHAT_PROVIDER", "gemini")
    monkeypatch.setenv("OPENROUTER_API_KEY", "router-key")
    assert chat_intent.model_config()["provider"] == "gemini"


@pytest.mark.parametrize("provider,key", [("gemini", None), ("openrouter", "google-only")])
def test_missing_selected_provider_key_is_infra(monkeypatch, provider, key):
    monkeypatch.setenv("CHAT_PROVIDER", provider)
    monkeypatch.setattr(gemini, "api_key", lambda: key)
    with pytest.raises(chat_intent.ChatModelError) as error:
        chat_intent.parse("test", {})
    assert (error.value.kind, error.value.code) == ("infra", "PROVIDER_NOT_CONFIGURED")


@pytest.mark.parametrize("code,kind", [
    ("AI_INVALID_RESPONSE", "format"), ("AI_TIMEOUT", "infra"), ("AI_RATE_LIMITED", "infra"),
    ("AI_AUTH_FAILED", "infra"), ("AI_MODEL_NOT_FOUND", "infra"), ("AI_PROVIDER_ERROR", "infra")])
def test_google_error_kind_is_available_without_reading_logs(monkeypatch, code, kind):
    def fail(*args, **kwargs):
        raise gemini.GeminiError(code)
    monkeypatch.setattr(gemini, "generate", fail)
    with pytest.raises(chat_intent.ChatModelError) as error:
        chat_intent.parse("test", {})
    assert (error.value.kind, error.value.code) == (kind, code)


@pytest.mark.parametrize("candidate", [
    {"content": {"parts": [{"text": "not JSON"}]}}, google_response({}), {},
    google_response(chat_intent.empty() | {"change_slots": ["top"], "keep_slots": ["top"]})])
def test_google_json_and_schema_failures_are_format_errors(monkeypatch, candidate):
    monkeypatch.setattr(gemini, "generate", lambda *args, **kwargs: candidate)
    with pytest.raises(chat_intent.ChatModelError) as error:
        chat_intent.parse("test", {})
    assert error.value.kind == "format"


@pytest.mark.parametrize("status,code", [(429, "AI_RATE_LIMITED"), (401, "AI_AUTH_FAILED"),
    (403, "AI_AUTH_FAILED"), (404, "AI_MODEL_NOT_FOUND"), (500, "AI_PROVIDER_ERROR")])
def test_router_http_failures_are_infra(monkeypatch, status, code):
    monkeypatch.setenv("OPENROUTER_API_KEY", "router-key")
    class Opener:
        def open(self, *args, **kwargs):
            raise HTTPError("https://openrouter.ai", status, "error", {}, None)
    monkeypatch.setattr(chat_intent, "build_opener", lambda *args: Opener())
    with pytest.raises(chat_intent.ChatModelError) as error:
        chat_intent.parse("test", {})
    assert (error.value.kind, error.value.code) == ("infra", code)


@pytest.mark.parametrize("body", [b"not JSON", b"{}", b"x" * 65537,
    json.dumps({"choices": [{"message": {"content": "not JSON"}}]}).encode()],
    ids=["invalid-envelope", "missing-choices", "oversized", "invalid-content"])
def test_router_invalid_responses_are_format_errors(monkeypatch, body):
    monkeypatch.setenv("OPENROUTER_API_KEY", "router-key")
    class Opener:
        def open(self, *args, **kwargs):
            return io.BytesIO(body)
    monkeypatch.setattr(chat_intent, "build_opener", lambda *args: Opener())
    with pytest.raises(chat_intent.ChatModelError) as error:
        chat_intent.parse("test", {})
    assert error.value.kind == "format"


def test_router_network_failure_is_infra(monkeypatch):
    monkeypatch.setenv("OPENROUTER_API_KEY", "router-key")
    class Opener:
        def open(self, *args, **kwargs):
            raise URLError("offline")
    monkeypatch.setattr(chat_intent, "build_opener", lambda *args: Opener())
    with pytest.raises(chat_intent.ChatModelError) as error:
        chat_intent.parse("test", {})
    assert (error.value.kind, error.value.code) == ("infra", "URLError")

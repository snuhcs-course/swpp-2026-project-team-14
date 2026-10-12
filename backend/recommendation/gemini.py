# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-10, reviewed by Dongkun Moon
"""Minimal Gemini REST client (same pattern as apps/wardrobe/analysis.py: fixed host, key in a header,
no redirects, size cap, timeout, errors mapped to codes)."""

from __future__ import annotations

import json
import os
import socket
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener

TEXT_MODEL = os.environ.get("GEMINI_TEXT_MODEL", "gemini-3.1-flash-lite")
# tried in order until one exists for this key (model names change; override with GEMINI_IMAGE_MODEL)
IMAGE_MODELS = tuple(filter(None, (
    os.environ.get("GEMINI_IMAGE_MODEL"), "gemini-3.1-flash-image", "gemini-2.5-flash-image", "gemini-3.1-flash-image-preview",
)))
BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"


class GeminiError(Exception):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def api_key() -> str | None:
    """GEMINI_API_KEY from the environment or backend/.env (never logged or returned)."""
    if os.environ.get("GEMINI_API_KEY"):
        return os.environ["GEMINI_API_KEY"]
    env = Path(__file__).resolve().parents[1] / ".env"
    if env.exists():
        try:
            from dotenv import dotenv_values
        except ImportError:
            return None
        return dotenv_values(env).get("GEMINI_API_KEY") or None
    return None


def generate(model: str, payload: dict, key: str, timeout: int = 60, max_bytes: int = 12 * 1024 * 1024) -> dict:
    request = Request(
        f"{BASE_URL}/{model}:generateContent",
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json", "x-goog-api-key": key},
    )
    try:
        with build_opener(_NoRedirect()).open(request, timeout=timeout) as response:
            body = response.read(max_bytes + 1)
    except HTTPError as error:
        error.close()
        if error.code == 429:
            raise GeminiError("AI_RATE_LIMITED") from None
        if error.code in (401, 403):
            raise GeminiError("AI_AUTH_FAILED") from None
        if error.code == 404:
            raise GeminiError("AI_MODEL_NOT_FOUND") from None
        raise GeminiError("AI_PROVIDER_ERROR") from None
    except (TimeoutError, socket.timeout):
        raise GeminiError("AI_TIMEOUT") from None
    except (URLError, OSError):
        raise GeminiError("AI_UNAVAILABLE") from None
    if len(body) > max_bytes:
        raise GeminiError("AI_INVALID_RESPONSE")
    try:
        envelope = json.loads(body)
        candidates = envelope.get("candidates") or []
        if not candidates:
            raise ValueError("no candidates")
        return candidates[0]
    except (ValueError, AttributeError):
        raise GeminiError("AI_INVALID_RESPONSE") from None

# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-10, reviewed by Dongkun Moon
"""Gemini calls are mocked: request shape and nothing written to disk."""

import base64
import io

import pytest
from PIL import Image

from recommendation import gemini, tryon
from recommendation.body import Body


def png_bytes():
    out = io.BytesIO()
    Image.new("RGBA", (8, 8), (200, 50, 50, 255)).save(out, "WEBP")
    return out.getvalue()


def test_virtual_tryon_sends_references_and_writes_nothing(monkeypatch, tmp_path):
    monkeypatch.chdir(tmp_path)
    sent = {}
    image = base64.b64encode(b"fake-png").decode()

    def fake_generate(model, payload, key, timeout=60, max_bytes=0):
        sent.update(payload)
        return {"content": {"parts": [{"inlineData": {"mimeType": "image/png", "data": image}}]}}

    monkeypatch.setattr(gemini, "generate", fake_generate)
    body = Body(gender="female", height=162, chest=84, waist=68, hip=94, inseam=72)
    garments = [{"slot": "상의", "name": "화이트 루즈 니트", "attrs": {"핏": "루즈"}}]
    data, mime = tryon.virtual_tryon(body, garments, [png_bytes()], "key")
    assert data == b"fake-png" and mime == "image/png"
    parts = sent["contents"][0]["parts"]
    assert "162 cm" in parts[0]["text"] and "real or famous person" in parts[0]["text"]
    assert parts[1]["inlineData"]["mimeType"] == "image/png"
    assert list(tmp_path.iterdir()) == []
    with pytest.raises(gemini.GeminiError):
        tryon.virtual_tryon(body, garments, [], None)

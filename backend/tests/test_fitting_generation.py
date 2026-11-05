import base64
import io
import json
import urllib.error
from unittest.mock import Mock, patch

import pytest
from PIL import Image

from apps.fittings.generation import GeneratedImage, GenerationError, MODEL, NanoBanana, outfit_prompt


def image_bytes():
    output = io.BytesIO()
    Image.new("RGB", (20, 30), "navy").save(output, "PNG")
    return output.getvalue()


def response(data=None):
    return io.BytesIO(json.dumps(data or {"data": [{"b64_json": base64.b64encode(image_bytes()).decode()}],
                                         "usage": {"cost": 0.061}}).encode())


def garments():
    return [("tops", image_bytes(), "flat-lay", True), ("bottoms", image_bytes(), "flat-lay", True),
            ("outerwear", image_bytes(), "model", True)]


def test_complete_outfit_uses_one_pinned_request_with_original_references_and_2k_output():
    opened = Mock(return_value=response())
    with patch("apps.fittings.generation.urllib.request.build_opener", return_value=Mock(open=opened)):
        result = NanoBanana("private-api-key")(image_bytes(), garments())
    assert opened.call_count == 1
    request = opened.call_args.args[0]
    assert request.full_url == "https://openrouter.ai/api/v1/images"
    assert request.get_header("Authorization") == "Bearer private-api-key"
    payload = json.loads(request.data)
    assert payload["model"] == MODEL and payload["resolution"] == "2K" and payload["n"] == 1
    assert payload["provider"] == {"only": ["google-ai-studio"], "allow_fallbacks": False}
    assert len(payload["input_references"]) == 4
    assert all(base64.b64decode(r["image_url"]["url"].split(",")[1]) == image_bytes()
               for r in payload["input_references"])
    with Image.open(io.BytesIO(result.image)) as image:
        assert image.format == "JPEG" and image.size == (20, 30)
    assert result.cost_usd == "0.061"


def test_top_bottom_prompt_removes_existing_coat_and_outer_prompt_replaces_it():
    rows = garments()
    assert "Remove any existing coat" in outfit_prompt(rows[:2])
    assert "Replace any existing outer garment" in outfit_prompt(rows)
    assert "open so the inner top" in outfit_prompt(rows)
    rows[-1] = (*rows[-1][:3], False)
    assert "closed as a separate outer layer" in outfit_prompt(rows)


@pytest.mark.parametrize("status,code", [(400,"FITTING_PROVIDER_REJECTED"),(401,"FITTING_AUTH_FAILED"),
    (402,"FITTING_CREDITS_REQUIRED"),(403,"FITTING_PROVIDER_REJECTED"),(429,"FITTING_RATE_LIMITED"),
    (503,"FITTING_PROVIDER_UNAVAILABLE")])
def test_provider_failures_are_sanitized_and_not_retried(status, code):
    failure = urllib.error.HTTPError("https://openrouter.ai", status, "private provider payload", {}, io.BytesIO(b"private"))
    opened = Mock(side_effect=failure)
    with patch("apps.fittings.generation.urllib.request.build_opener", return_value=Mock(open=opened)):
        with pytest.raises(GenerationError, match=code):NanoBanana("secret")(image_bytes(), garments())
    assert opened.call_count == 1


@pytest.mark.parametrize("error", [TimeoutError(), urllib.error.URLError(TimeoutError())])
def test_timeout_is_actionable_and_not_retried(error):
    opened = Mock(side_effect=error)
    with patch("apps.fittings.generation.urllib.request.build_opener", return_value=Mock(open=opened)):
        with pytest.raises(GenerationError, match="FITTING_TIMEOUT"):
            NanoBanana("secret")(image_bytes(), garments())
    assert opened.call_count == 1


@pytest.mark.parametrize("usage", [None, [], {"cost": "NaN"}, {"cost": -1}])
def test_unknown_cost_does_not_discard_a_valid_image_or_trigger_another_paid_call(usage):
    data = {"data": [{"b64_json": base64.b64encode(image_bytes()).decode()}], "usage": usage}
    opened = Mock(return_value=response(data))
    with patch("apps.fittings.generation.urllib.request.build_opener", return_value=Mock(open=opened)):
        result = NanoBanana("secret")(image_bytes(), garments())
    assert result.image and result.cost_usd is None
    assert opened.call_count == 1


@pytest.mark.parametrize("data", [{"data": []}, {"data": [{"b64_json": "invalid"}]},
                                  {"data": [{"b64_json": "aGVsbG8="}]}])
def test_invalid_provider_output_never_becomes_a_fake_result(data):
    with patch("apps.fittings.generation.urllib.request.build_opener", return_value=Mock(open=Mock(return_value=response(data)))):
        with pytest.raises(GenerationError, match="FITTING_INVALID_RESULT"):
            NanoBanana("secret")(image_bytes(), garments())


def test_missing_key_makes_no_paid_request():
    with patch("apps.fittings.generation.urllib.request.build_opener") as opener:
        with pytest.raises(GenerationError, match="FITTING_NOT_CONFIGURED"):
            NanoBanana("")(image_bytes(), garments())
        opener.assert_not_called()

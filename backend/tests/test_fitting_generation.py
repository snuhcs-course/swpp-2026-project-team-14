import base64
import io
import json
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
from unittest.mock import Mock, patch

import pytest
from PIL import Image

from apps.fittings.generation import GeneratedImage, GenerationError, MODEL, NanoBanana, NoRedirect, outfit_prompt


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


def test_redirects_never_forward_credentials_to_another_endpoint():
    paths = []
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            paths.append(self.path)
            self.send_response(302)
            self.send_header("Location", "/unexpected")
            self.end_headers()
        def log_message(self, *args):
            pass
    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            request = urllib.request.Request(f"http://127.0.0.1:{server.server_port}/original",
                                             headers={"Authorization": "Bearer synthetic-only"})
            with pytest.raises(urllib.error.HTTPError) as error:
                urllib.request.build_opener(NoRedirect()).open(request, timeout=2)
            assert error.value.code == 302
            error.value.close()
            assert paths == ["/original"]
        finally:
            server.shutdown()
            thread.join(2)


@pytest.mark.parametrize("error", [urllib.error.URLError("connection refused"), ConnectionResetError()])
def test_network_failures_are_sanitized_without_paid_retries(error):
    opened = Mock(side_effect=error)
    with patch("apps.fittings.generation.urllib.request.build_opener", return_value=Mock(open=opened)):
        with pytest.raises(GenerationError, match="FITTING_PROVIDER_UNAVAILABLE"):
            NanoBanana("secret")(image_bytes(), garments())
    assert opened.call_count == 1


@pytest.mark.parametrize("limit,value", [("MAX_RESPONSE_BYTES", 16), ("MAX_OUTPUT_BYTES", 16),
                                         ("MAX_OUTPUT_BYTES", 500)])
def test_oversized_provider_payloads_and_encoded_results_are_rejected(limit, value):
    opened = Mock(return_value=response())
    with patch("apps.fittings.generation." + limit, value), patch(
            "apps.fittings.generation.urllib.request.build_opener", return_value=Mock(open=opened)):
        with pytest.raises(GenerationError, match="FITTING_INVALID_RESULT"):
            NanoBanana("secret")(image_bytes(), garments())
    assert opened.call_count == 1


@pytest.mark.parametrize("format,size", [("BMP", (20, 30)), ("PNG", (4001, 4000))])
def test_unsupported_formats_and_excessive_image_dimensions_are_rejected(format, size):
    output = io.BytesIO()
    with Image.new("L", size) as image:
        image.save(output, format)
    data = {"data": [{"b64_json": base64.b64encode(output.getvalue()).decode()}]}
    with patch("apps.fittings.generation.urllib.request.build_opener", return_value=Mock(open=Mock(return_value=response(data)))):
        with pytest.raises(GenerationError, match="FITTING_INVALID_RESULT"):
            NanoBanana("secret")(image_bytes(), garments())

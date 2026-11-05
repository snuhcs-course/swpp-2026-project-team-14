"""One paid image-edit request per outfit; no local inference or automatic retries."""
import base64
import binascii
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
import io
import json
import socket
import urllib.error
import urllib.request

from PIL import Image, UnidentifiedImageError

MODEL = "google/gemini-nano-banana-2.1"
RESOLUTION = "2K"
MAX_RESPONSE_BYTES = 32 * 1024 * 1024
MAX_OUTPUT_BYTES = 16 * 1024 * 1024


class GenerationError(Exception):
    """Only a public error code may leave the provider boundary."""


@dataclass(frozen=True)
class GeneratedImage:
    image: bytes
    cost_usd: str | None = None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def reference(raw):
    with Image.open(io.BytesIO(raw)) as image:
        media_type = Image.MIME[image.format]
    return {"type": "image_url", "image_url": {
        "url": "data:" + media_type + ";base64," + base64.b64encode(raw).decode("ascii")}}


def outfit_prompt(garments):
    roles = {"tops": "upper-body shirt/top", "bottoms": "trousers/skirt", "outerwear": "outer jacket/cardigan/coat"}
    instructions = [
        "Edit reference 1 for a photorealistic virtual outfit preview of the same adult person.",
        "Preserve the exact face, expression, hair, body proportions, pose, hand positions, footwear, "
        "camera framing, furniture and background. Do not stand up a seated person or change crossed legs.",
    ]
    for index, (category, _, photo_type, open_front) in enumerate(garments, 2):
        instructions.append(f"Reference {index} supplies ONLY the {roles[category]}. "
                            "Preserve its colors, prints, logos, seams, buttons and fabric texture.")
        if photo_type == "model":
            instructions.append(f"Ignore the person, background and all other garments in reference {index}.")
        if category == "outerwear":
            instructions.append("Replace any existing outer garment completely, including its sleeves and hanging tails. "
                                "Wear the new outer layer " + ("open so the inner top remains visible." if open_front
                                                              else "closed as a separate outer layer."))
    categories = [row[0] for row in garments]
    if "tops" in categories and "outerwear" not in categories:
        instructions.append("Remove any existing coat, jacket or cardigan, including sleeves and hanging tails, "
                            "to show the requested top. Do not add another outer layer.")
    instructions.append("Change only the specified clothing and reconstruct areas uncovered by removed clothing. "
                        "Keep unspecified clothing. Render natural folds, anatomically plausible hands and clean "
                        "continuous layer boundaries without cutout holes. Output one photograph, not a collage.")
    return " ".join(instructions)


class NanoBanana:
    def __init__(self, api_key):
        self.api_key = api_key

    def __call__(self, person, garments):
        if not self.api_key:
            raise GenerationError("FITTING_NOT_CONFIGURED")
        payload = {"model": MODEL, "resolution": RESOLUTION, "aspect_ratio": "2:3", "n": 1,
                   "prompt": outfit_prompt(garments),
                   "input_references": [reference(person)] + [reference(g[1]) for g in garments],
                   "provider": {"only": ["google-ai-studio"], "allow_fallbacks": False}}
        request = urllib.request.Request("https://openrouter.ai/api/v1/images", method="POST",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Authorization": "Bearer " + self.api_key, "Content-Type": "application/json"})
        try:
            with urllib.request.build_opener(NoRedirect()).open(request, timeout=180) as response:
                body = response.read(MAX_RESPONSE_BYTES + 1)
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            raise GenerationError({400: "FITTING_PROVIDER_REJECTED", 401: "FITTING_AUTH_FAILED",
                403: "FITTING_PROVIDER_REJECTED", 402: "FITTING_CREDITS_REQUIRED",
                429: "FITTING_RATE_LIMITED"}.get(status, "FITTING_PROVIDER_UNAVAILABLE")) from None
        except (TimeoutError, socket.timeout):
            raise GenerationError("FITTING_TIMEOUT") from None
        except urllib.error.URLError as error:
            code = "FITTING_TIMEOUT" if isinstance(error.reason, TimeoutError) else "FITTING_PROVIDER_UNAVAILABLE"
            raise GenerationError(code) from None
        except OSError:
            raise GenerationError("FITTING_PROVIDER_UNAVAILABLE") from None
        try:
            if len(body) > MAX_RESPONSE_BYTES:
                raise ValueError("Response too large")
            data = json.loads(body)
            rows = data["data"]
            if len(rows) != 1:
                raise ValueError("Expected one image")
            raw = base64.b64decode(rows[0]["b64_json"], validate=True)
            if len(raw) > MAX_OUTPUT_BYTES:
                raise ValueError("Image too large")
            with Image.open(io.BytesIO(raw)) as source:
                if source.format not in ("PNG", "JPEG", "WEBP") or source.width * source.height > 16_000_000:
                    raise ValueError("Unsupported output image")
                image = source.convert("RGB")
                try:
                    output = io.BytesIO()
                    image.save(output, "JPEG", quality=95)
                finally:
                    image.close()
            result = output.getvalue()
            if len(result) > MAX_OUTPUT_BYTES:
                raise ValueError("Result too large")
            try:
                usage = data.get("usage")
                cost = Decimal(str(usage.get("cost") if isinstance(usage, dict) else None))
                cost_usd = str(cost) if cost.is_finite() and cost >= 0 else None
            except (InvalidOperation, ValueError, TypeError):
                cost_usd = None
            return GeneratedImage(result, cost_usd)
        except (ValueError, TypeError, KeyError, IndexError, binascii.Error, OSError,
                UnidentifiedImageError, Image.DecompressionBombError):
            raise GenerationError("FITTING_INVALID_RESULT") from None

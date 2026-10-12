# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-10, reviewed by Dongkun Moon
"""'입혀보기' on a virtual model: Gemini draws a person with the user's proportions wearing the
recommended garments (the cut-outs are the references). No user photo is involved, and the image
is returned to the caller without being written anywhere.
"""

from __future__ import annotations

import base64
import io

from PIL import Image

from recommendation import gemini
from recommendation.body import Body, Shape

SHAPE_EN = {
    Shape.LOWER_VOLUME: "hips noticeably wider than the bust and shoulders",
    Shape.UPPER_VOLUME: "shoulders and bust broader than the hips",
    Shape.HOURGLASS: "bust and hips similar with a clearly defined waist",
    Shape.STRAIGHT: "bust, waist and hips of similar width",
}
SLOT_EN = {"아우터": "outerwear", "상의": "top", "하의": "bottom", "원피스": "one-piece dress"}


def prompt(body: Body, garments: list[dict]) -> str:
    who = "man" if body.male else "woman"
    shape = SHAPE_EN.get(body.shape, "average proportions") if body.shape else "average proportions"
    legs = f"inseam about {body.inseam:.0f} cm" if body.inseam else ""
    lines = [
        f"Photorealistic full-body fashion photo of an adult Korean {who}, about {body.height:.0f} cm tall, "
        f"{shape}{', ' + legs if legs else ''}.",
        "Standing straight, front view, arms relaxed, plain light-grey studio background, soft even light.",
        "Face calm and generic (not a real or famous person), no text, no logos, no watermark.",
        "The person wears exactly these garments, shown in the reference images in this order:",
    ]
    for i, g in enumerate(garments, 1):
        attrs = g.get("attrs", {})
        lines.append(f"{i}. {SLOT_EN.get(g['slot'], g['slot'])}: {g.get('name', '')} "
                     f"(fit: {attrs.get('핏', '-')}, length: {attrs.get('기장', '-')}) - keep its colour, pattern and shape.")
    lines.append("Show how the fit and length look on this body. Do not add other clothing except plain shoes.")
    return "\n".join(lines)


def _png(webp_bytes: bytes) -> bytes:
    with Image.open(io.BytesIO(webp_bytes)) as im:
        bg = Image.new("RGB", im.size, (255, 255, 255))
        bg.paste(im.convert("RGBA"), mask=im.convert("RGBA").split()[-1])
        out = io.BytesIO()
        bg.save(out, "PNG")
        return out.getvalue()


def virtual_tryon(body: Body, garments: list[dict], images: list[bytes], key: str | None,
                  models: tuple[str, ...] = gemini.IMAGE_MODELS) -> tuple[bytes, str]:
    """(image bytes, mime type). Raises gemini.GeminiError."""
    if not key:
        raise gemini.GeminiError("AI_NOT_CONFIGURED")
    parts = [{"text": prompt(body, garments)}]
    for raw in images[:3]:
        parts.append({"inlineData": {"mimeType": "image/png", "data": base64.b64encode(_png(raw)).decode("ascii")}})
    payload = {"contents": [{"role": "user", "parts": parts}], "generationConfig": {"responseModalities": ["IMAGE", "TEXT"]}}
    for i, model in enumerate(models):
        try:
            candidate = gemini.generate(model, payload, key, timeout=120)
            break
        except gemini.GeminiError as e:
            if e.code != "AI_MODEL_NOT_FOUND" or i == len(models) - 1:
                raise
    for part in candidate.get("content", {}).get("parts", []):
        data = part.get("inlineData") or part.get("inline_data")
        if data and str(data.get("mimeType", data.get("mime_type", ""))).startswith("image/"):
            return base64.b64decode(data["data"]), data.get("mimeType", "image/png")
    raise gemini.GeminiError("AI_NO_IMAGE")

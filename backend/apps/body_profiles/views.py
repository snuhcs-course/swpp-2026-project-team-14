"""POST /api/body-profile/analyze/ — docs/body-analysis/02-design.md §7."""

from __future__ import annotations

import uuid

from django.http import JsonResponse
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_POST

from body_analysis import AnalysisError, AnalysisInput, Gender

from . import services

MAX_PHOTO_BYTES = 8 * 1024 * 1024


def _json(body: dict, status: int = 200) -> JsonResponse:
    return JsonResponse(body, status=status, json_dumps_params={"ensure_ascii": False})


def _analysis_error(error: AnalysisError) -> JsonResponse:
    return _json({"error": error.code, "photo": error.photo, "hint": error.hint}, status=422)


def _bad_request(field: str, message: str) -> JsonResponse:
    return _json({"error": "invalid_field", "field": field, "hint": message}, status=400)


def _number(value: str | None, low: float, high: float) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if low <= number <= high else None


@csrf_exempt  # token auth for the mobile client comes with P7; no cookies are used here
@require_POST
def analyze(request):
    front = request.FILES.get("front_photo")
    side = request.FILES.get("side_photo")
    if front is None:
        return _analysis_error(AnalysisError("front_photo_required", "front"))
    if side is None:
        return _analysis_error(AnalysisError("side_photo_required", "side"))
    if front.size > MAX_PHOTO_BYTES or side.size > MAX_PHOTO_BYTES:
        return _json({"error": "photo_too_large", "hint": "사진 용량이 너무 커요 (최대 8MB)."}, status=413)

    height = _number(request.POST.get("height_cm"), 100, 220)
    if height is None:
        return _bad_request("height_cm", "키는 100~220cm 사이로 입력해주세요.")
    weight = None
    if request.POST.get("weight_kg"):
        weight = _number(request.POST["weight_kg"], 30, 200)
        if weight is None:
            return _bad_request("weight_kg", "몸무게는 30~200kg 사이로 입력해주세요.")
    try:
        gender = Gender(request.POST.get("gender") or Gender.UNSPECIFIED.value)
    except ValueError as error:
        return _bad_request("gender", str(error))
    # A "clothing" field sent by older app builds is ignored: clothing is now detected from the photos.

    input_ = AnalysisInput(height_cm=height, weight_kg=weight, gender=gender)
    front_bytes, side_bytes = front.read(), side.read()
    front.close()
    side.close()
    try:
        result = services.analyze(front_bytes, side_bytes, input_)
    except AnalysisError as error:
        return _analysis_error(error)
    finally:
        del front_bytes, side_bytes  # photos may show underwear: drop them as soon as possible

    body = {
        "analysis_id": uuid.uuid4().hex,
        "inputs": {
            "height_cm": height,
            "weight_kg": weight,
            "gender": gender.value,
        },
        **result.to_dict(),
    }
    return _json(body)

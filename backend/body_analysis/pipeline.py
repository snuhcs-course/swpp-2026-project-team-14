"""End-to-end analysis: image bytes in, measurements out. Photos stay in memory only."""

from __future__ import annotations

import numpy as np

from .geometry import largest_component, vertical_extent
from .measurer import RawMeasurements, measure
from .pose import PoseEstimator, PoseResult
from .types import (
    AnalysisError,
    AnalysisInput,
    AnalysisResult,
    Clothing,
    Confidence,
    Gender,
    Group,
    Measurement,
    MeasurementType,
)

PIPELINE_VERSION = "baseline-geometric-1.0"
MAX_SIDE_PX = 1280
REQUIRED_LANDMARKS = (
    "nose",
    "left_shoulder",
    "right_shoulder",
    "left_hip",
    "right_hip",
    "left_knee",
    "right_knee",
    "left_ankle",
    "right_ankle",
)
MIN_VISIBILITY = 0.5
LOW_VISIBILITY = 0.8  # below this (on average) every measurement loses one confidence level


def decode_image(data: bytes) -> np.ndarray:
    """Decodes JPEG/PNG bytes to an RGB array, downscaled to MAX_SIDE_PX. Never touches disk."""
    import cv2

    array = np.frombuffer(data, dtype=np.uint8)
    bgr = cv2.imdecode(array, cv2.IMREAD_COLOR)
    if bgr is None:
        raise AnalysisError("invalid_image")
    h, w = bgr.shape[:2]
    factor = MAX_SIDE_PX / max(h, w)
    if factor < 1:
        bgr = cv2.resize(bgr, (round(w * factor), round(h * factor)), interpolation=cv2.INTER_AREA)
    return cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)


def check_quality(pose: PoseResult | None, view: str) -> PoseResult:
    """Rejects photos we cannot measure. `view` is "front" or "side"."""
    if pose is None:
        raise AnalysisError("no_person", view)
    if pose.num_people > 1:
        raise AnalysisError("multiple_people", view)
    lm = pose.landmarks
    if view == "front":
        visible = all(lm[name].visibility >= MIN_VISIBILITY for name in REQUIRED_LANDMARKS)
    else:
        # In profile one side of the body is hidden, so either side counts.
        visible = lm["nose"].visibility >= MIN_VISIBILITY and all(
            max(lm[f"left_{part}"].visibility, lm[f"right_{part}"].visibility) >= MIN_VISIBILITY
            for part in ("shoulder", "hip", "knee", "ankle")
        )
    if not visible:
        raise AnalysisError("body_cropped", view)

    mask = largest_component(pose.mask)
    top, bottom = vertical_extent(mask)
    h = mask.shape[0]
    if top <= 1 or bottom >= h - 2:
        raise AnalysisError("body_cropped", view)

    body_px = bottom - top
    shoulder_spread = abs(lm["left_shoulder"].x - lm["right_shoulder"].x) / body_px
    if view == "front" and shoulder_spread < 0.12:
        raise AnalysisError("not_frontal", view)
    if view == "side" and shoulder_spread > 0.10:
        raise AnalysisError("not_side_view", view)
    return pose


def score_confidence(
    type_: MeasurementType,
    has_side: bool,
    clothing: Clothing,
    mean_visibility: float,
) -> Confidence:
    confidence = type_.base_confidence
    if type_.needs_side_photo and not has_side:
        confidence = confidence.downgrade()
    if clothing is Clothing.LOOSE and type_.group is Group.CIRCUMFERENCE:
        confidence = confidence.downgrade()
    if mean_visibility < LOW_VISIBILITY:
        confidence = confidence.downgrade()
    return confidence


def build_warnings(input_: AnalysisInput, has_side: bool, raw: RawMeasurements) -> list[str]:
    warnings = []
    if input_.clothing is Clothing.LOOSE:
        warnings.append("loose_clothing")
    if not has_side:
        warnings.append("no_side_photo")
    if input_.weight_kg is None:
        warnings.append("no_weight")
    return warnings + raw.warnings


class BodyAnalysisPipeline:
    def __init__(self, estimator: PoseEstimator):
        self.estimator = estimator

    def analyze_images(
        self,
        front_rgb: np.ndarray,
        side_rgb: np.ndarray | None,
        input_: AnalysisInput,
    ) -> tuple[AnalysisResult, RawMeasurements]:
        front = check_quality(self.estimator.estimate(front_rgb), "front")
        side = check_quality(self.estimator.estimate(side_rgb), "side") if side_rgb is not None else None

        raw = measure(front, side, input_.height_cm)
        mean_visibility = float(np.mean([front.landmarks[n].visibility for n in REQUIRED_LANDMARKS]))
        measurements = [
            Measurement(
                type=type_,
                value_cm=round(value * 2) / 2,  # 0.5 cm steps
                confidence=score_confidence(type_, side is not None, input_.clothing, mean_visibility),
            )
            for type_, value in raw.values.items()
            if type_ is not MeasurementType.UNDERBUST or input_.gender is Gender.FEMALE
        ]
        measurements.sort(key=lambda m: list(MeasurementType).index(m.type))
        result = AnalysisResult(
            measurements=measurements,
            derived=raw.derived,
            warnings=build_warnings(input_, side is not None, raw),
            pipeline_version=PIPELINE_VERSION,
        )
        return result, raw

    def analyze(self, front: bytes, side: bytes | None, input_: AnalysisInput) -> AnalysisResult:
        front_rgb = decode_image(front)
        side_rgb = decode_image(side) if side else None
        try:
            result, _ = self.analyze_images(front_rgb, side_rgb, input_)
        finally:
            # Drop pixel data as soon as possible (photos may show underwear).
            del front_rgb, side_rgb
        return result

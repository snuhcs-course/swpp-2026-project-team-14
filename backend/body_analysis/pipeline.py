# AI-generated with Claude Code (Claude Opus 5.5), 2026-09-24, reviewed by Dongkun Moon
"""End-to-end analysis: image bytes in, measurements out. Photos stay in memory only."""

from __future__ import annotations

import numpy as np

from .geometry import largest_component, vertical_extent
from .measurer import RawMeasurements, measure
from .pose import PoseEstimator, PoseResult
from .clothing import ClothingDetector
from .reference import ReferenceSet, erase_marker
from .regressor import MeasurementCorrector
from .types import (
    AnalysisError,
    AnalysisInput,
    AnalysisResult,
    ClothingAssessment,
    Confidence,
    Gender,
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

    if not data:  # cv2.imdecode raises (→ 500) on an empty buffer instead of returning None
        raise AnalysisError("invalid_image")
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
    # Upside-down or sideways photos still find a pose, but every measurement would be nonsense
    # (an upside-down front photo gave a 176 cm chest). The head must be clearly above the ankles.
    ankle_x = (lm["left_ankle"].x + lm["right_ankle"].x) / 2
    ankle_y = (lm["left_ankle"].y + lm["right_ankle"].y) / 2
    if lm["nose"].y >= ankle_y or abs(lm["nose"].x - ankle_x) > (ankle_y - lm["nose"].y):
        raise AnalysisError("not_upright", view)
    mask = largest_component(pose.mask)
    top, bottom = vertical_extent(mask)

    # Orientation first: in a wrongly-oriented photo half the body is hidden, which would otherwise
    # be reported as "body cropped" and give the user the wrong retake hint.
    shoulder_spread = abs(lm["left_shoulder"].x - lm["right_shoulder"].x) / max(bottom - top, 1)
    if view == "front" and shoulder_spread < 0.12:
        raise AnalysisError("not_frontal", view)
    if view == "side" and shoulder_spread > 0.10:
        raise AnalysisError("not_side_view", view)

    if view == "front":
        visible = all(lm[name].visibility >= MIN_VISIBILITY for name in REQUIRED_LANDMARKS)
    else:
        # In profile one side of the body is hidden, so either side counts.
        visible = lm["nose"].visibility >= MIN_VISIBILITY and all(
            max(lm[f"left_{part}"].visibility, lm[f"right_{part}"].visibility) >= MIN_VISIBILITY
            for part in ("shoulder", "hip", "knee", "ankle")
        )
    if not visible or top <= 1 or bottom >= mask.shape[0] - 2:
        raise AnalysisError("body_cropped", view)
    return pose


def score_confidence(type_: MeasurementType, clothing: ClothingAssessment, mean_visibility: float) -> Confidence:
    confidence = type_.base_confidence
    if type_ in clothing.affected():
        confidence = confidence.downgrade()
    if mean_visibility < LOW_VISIBILITY:
        confidence = confidence.downgrade()
    return confidence


def build_warnings(input_: AnalysisInput, clothing: ClothingAssessment, raw: RawMeasurements) -> list[str]:
    warnings = []
    if clothing.top_loose:
        warnings.append("loose_top")
    if clothing.bottom_loose:
        warnings.append("loose_bottom")
    if input_.weight_kg is None:
        warnings.append("no_weight")
    return warnings + raw.warnings


def _derived_from(values: dict[MeasurementType, float], height_cm: float) -> dict[str, float]:
    """Ratios that depend only on measurements (recomputed after a learned correction)."""
    return {
        "waist_hip_ratio": round(values[MeasurementType.WAIST] / values[MeasurementType.HIP], 3),
        "torso_leg_ratio": round(values[MeasurementType.TORSO_LENGTH] / values[MeasurementType.INSEAM], 3),
        "inseam_height_ratio": round(values[MeasurementType.INSEAM] / height_cm, 3),
    }


class BodyAnalysisPipeline:
    def __init__(
        self,
        estimator: PoseEstimator,
        corrector: MeasurementCorrector | None = None,
        detector: ClothingDetector | None = None,
        references: ReferenceSet | None = None,
    ):
        """`corrector`: learned correction (MeasurementCorrector.load()); None = pure geometry.
        `detector`: loose-clothing detector (ClothingDetector.load()); None = assume fitted clothing.
        `references`: benchmark bodies with known measurements (ReferenceSet.load()); None = never
        report a reference."""
        self.estimator = estimator
        self.corrector = corrector
        self.detector = detector
        self.references = references

    def analyze_images(
        self,
        front_rgb: np.ndarray,
        side_rgb: np.ndarray,
        input_: AnalysisInput,
    ) -> tuple[AnalysisResult, RawMeasurements]:
        if side_rgb is None:
            raise AnalysisError("side_photo_required", "side")
        reference = None
        if self.references is not None:
            reference = self.references.match(front_rgb, side_rgb)
            front_rgb, side_rgb = erase_marker(front_rgb), erase_marker(side_rgb)  # no-op without a marker
        front = check_quality(self.estimator.estimate(front_rgb), "front")
        side = check_quality(self.estimator.estimate(side_rgb), "side")

        raw = measure(front, side, input_.height_cm)
        version = PIPELINE_VERSION
        clothing = ClothingAssessment()
        if self.detector is not None:
            clothing = self.detector.assess(raw.features, input_.gender)
            version += f"+{self.detector.version}"
        if clothing.top_loose:
            # A loose sleeve covers the upper arm, so the "bicep" would be the sleeve (a 53 cm bicep for a
            # 25 cm arm in the benchmark). Leave it out, as when the arms touch the body.
            raw.values.pop(MeasurementType.BICEP, None)
        if self.corrector is not None:
            raw.values = self.corrector.correct(raw.values, raw.features, input_, skip=clothing.affected())
            raw.derived.update(_derived_from(raw.values, input_.height_cm))
            version += f"+{self.corrector.version}"
        mean_visibility = float(np.mean([front.landmarks[n].visibility for n in REQUIRED_LANDMARKS]))
        measurements = [
            Measurement(
                type=type_,
                value_cm=round(value * 2) / 2,  # 0.5 cm steps
                confidence=score_confidence(type_, clothing, mean_visibility),
            )
            for type_, value in raw.values.items()
            if type_ is not MeasurementType.UNDERBUST or input_.gender is Gender.FEMALE
        ]
        measurements.sort(key=lambda m: list(MeasurementType).index(m.type))
        result = AnalysisResult(
            measurements=measurements,
            derived=raw.derived,
            warnings=build_warnings(input_, clothing, raw),
            pipeline_version=version,
            clothing=clothing,
            reference=reference,
        )
        return result, raw

    def analyze(self, front: bytes, side: bytes, input_: AnalysisInput) -> AnalysisResult:
        if not side:
            raise AnalysisError("side_photo_required", "side")
        front_rgb = decode_image(front)
        side_rgb = decode_image(side)
        try:
            result, _ = self.analyze_images(front_rgb, side_rgb, input_)
        finally:
            # Drop pixel data as soon as possible (photos may show underwear).
            del front_rgb, side_rgb
        return result

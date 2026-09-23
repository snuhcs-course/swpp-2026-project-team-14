"""Data types shared by the body analysis pipeline.

Measurement keys and confidence rules mirror the Android client
(`android/.../data/BodyMeasurements.kt`) and docs/body-analysis/02-design.md §2.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum


class Confidence(str, Enum):
    HIGH = "high"
    MEDIUM = "medium"
    LOW = "low"

    def downgrade(self) -> "Confidence":
        return Confidence.MEDIUM if self is Confidence.HIGH else Confidence.LOW


class Group(str, Enum):
    LENGTH = "length"
    CIRCUMFERENCE = "circumference"


class Gender(str, Enum):
    FEMALE = "female"
    MALE = "male"
    UNSPECIFIED = "unspecified"


class Clothing(str, Enum):
    UNDERWEAR = "underwear"
    TIGHT = "tight"
    LOOSE = "loose"


class MeasurementType(str, Enum):
    """ISO 8559-1 based measurement set. Value = API key."""

    SHOULDER_WIDTH = "shoulder_width"
    SLEEVE_LENGTH = "sleeve_length"
    TORSO_LENGTH = "torso_length"
    RISE = "rise"
    INSEAM = "inseam"
    OUTSEAM = "outseam"
    NECK = "neck"
    CHEST = "chest"
    UNDERBUST = "underbust"
    WAIST = "waist"
    HIP = "hip"
    ARMHOLE = "armhole"
    BICEP = "bicep"
    WRIST = "wrist"
    THIGH = "thigh"
    CALF = "calf"

    @property
    def group(self) -> Group:
        return Group.LENGTH if self in _LENGTHS else Group.CIRCUMFERENCE

    @property
    def needs_side_photo(self) -> bool:
        return self not in _FRONT_ONLY

    @property
    def base_confidence(self) -> Confidence:
        return _BASE_CONFIDENCE[self]


_LENGTHS = {
    MeasurementType.SHOULDER_WIDTH,
    MeasurementType.SLEEVE_LENGTH,
    MeasurementType.TORSO_LENGTH,
    MeasurementType.RISE,
    MeasurementType.INSEAM,
    MeasurementType.OUTSEAM,
}

_FRONT_ONLY = {
    MeasurementType.SHOULDER_WIDTH,
    MeasurementType.SLEEVE_LENGTH,
    MeasurementType.INSEAM,
    MeasurementType.OUTSEAM,
    MeasurementType.WRIST,
}

_BASE_CONFIDENCE = {
    MeasurementType.SHOULDER_WIDTH: Confidence.HIGH,
    MeasurementType.SLEEVE_LENGTH: Confidence.HIGH,
    MeasurementType.TORSO_LENGTH: Confidence.HIGH,
    MeasurementType.RISE: Confidence.MEDIUM,
    MeasurementType.INSEAM: Confidence.HIGH,
    MeasurementType.OUTSEAM: Confidence.HIGH,
    MeasurementType.NECK: Confidence.LOW,
    MeasurementType.CHEST: Confidence.MEDIUM,
    MeasurementType.UNDERBUST: Confidence.LOW,
    MeasurementType.WAIST: Confidence.MEDIUM,
    MeasurementType.HIP: Confidence.MEDIUM,
    MeasurementType.ARMHOLE: Confidence.LOW,
    MeasurementType.BICEP: Confidence.MEDIUM,
    MeasurementType.WRIST: Confidence.LOW,
    MeasurementType.THIGH: Confidence.MEDIUM,
    MeasurementType.CALF: Confidence.MEDIUM,
}


@dataclass(frozen=True)
class AnalysisInput:
    height_cm: float
    weight_kg: float | None = None
    gender: Gender = Gender.UNSPECIFIED
    clothing: Clothing = Clothing.UNDERWEAR


@dataclass(frozen=True)
class Measurement:
    type: MeasurementType
    value_cm: float
    confidence: Confidence

    def to_dict(self) -> dict:
        return {"type": self.type.value, "value_cm": self.value_cm, "confidence": self.confidence.value}


@dataclass
class AnalysisResult:
    measurements: list[Measurement]
    derived: dict[str, float]
    warnings: list[str] = field(default_factory=list)
    pipeline_version: str = ""

    def value(self, type_: MeasurementType) -> float | None:
        return next((m.value_cm for m in self.measurements if m.type is type_), None)

    def to_dict(self) -> dict:
        return {
            "pipeline_version": self.pipeline_version,
            "measurements": [m.to_dict() for m in self.measurements],
            "derived": self.derived,
            "warnings": self.warnings,
        }


class AnalysisError(Exception):
    """Photo cannot be analysed. `code` is returned to the client as a 422 error code."""

    HINTS = {
        "invalid_image": "사진 파일을 읽을 수 없어요. 다른 사진을 선택해주세요.",
        "no_person": "사진에서 사람을 찾지 못했어요. 전신이 보이게 다시 찍어주세요.",
        "multiple_people": "사진에 여러 사람이 있어요. 혼자 나온 사진으로 찍어주세요.",
        "body_cropped": "머리부터 발끝까지 전신이 나오게 찍어주세요.",
        "not_frontal": "정면 사진은 카메라를 정면으로 바라보고 찍어주세요.",
        "not_side_view": "측면 사진은 몸을 옆으로 돌려 찍어주세요.",
    }

    def __init__(self, code: str, photo: str = "front"):
        self.code = code
        self.photo = photo
        super().__init__(f"{photo}: {code}")

    @property
    def hint(self) -> str:
        return self.HINTS.get(self.code, "사진을 다시 찍어주세요.")

# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-08, reviewed by Dongkun Moon
"""The user's body as the recommender sees it: measurements (cm) and the derived shape type.

Thresholds are the app's (frontend data/BodyAnalyzer.kt, `InsightThresholds` / `bodyShapeOf`) so the
shape shown in My Profile and the shape used here always agree.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

LEG_RATIO = 0.46  # inseam / height
LEG_MARGIN = 0.01
SHOULDER_RATIO = 0.255  # shoulder width / height
SHOULDER_MARGIN = 0.01
HIP_CHEST_RATIO = 1.05
HIP_CHEST_MARGIN = 0.05
WAIST_HIP_RATIO = 0.75
WAIST_HIP_MARGIN = 0.05


class Shape(str, Enum):
    LOWER_VOLUME = "하체 볼륨형"
    UPPER_VOLUME = "상체 볼륨형"
    HOURGLASS = "허리 라인형"
    STRAIGHT = "일자형"


@dataclass
class Body:
    gender: str = "female"  # female | male | unspecified
    height: float = 165.0
    chest: float | None = None
    waist: float | None = None
    hip: float | None = None
    shoulder: float | None = None
    inseam: float | None = None
    torso_length: float | None = None
    sleeve_length: float | None = None
    outseam: float | None = None
    thigh: float | None = None

    # app profile measurement keys (MeasurementType.apiKey) -> fields
    PROFILE_KEYS = {
        "chest": "chest", "waist": "waist", "hip": "hip", "shoulder_width": "shoulder", "inseam": "inseam",
        "torso_length": "torso_length", "sleeve_length": "sleeve_length", "outseam": "outseam", "thigh": "thigh",
    }

    @classmethod
    def from_json(cls, data: dict) -> "Body":
        """Flat test-page form ({height, chest, ...}) or the app's stored profile
        ({height_cm, gender, measurements: [{type, value_cm}]})."""
        values = dict(data)
        if isinstance(data.get("measurements"), list):
            for m in data["measurements"]:
                field_name = cls.PROFILE_KEYS.get(str(m.get("type")))
                if field_name and m.get("value_cm") not in (None, ""):
                    values[field_name] = m["value_cm"]
        if data.get("height_cm") not in (None, ""):
            values["height"] = data["height_cm"]

        def num(key: str) -> float | None:
            v = values.get(key)
            return float(v) if v not in (None, "") else None

        return cls(
            gender=str(values.get("gender") or "unspecified"),
            height=num("height") or 165.0,
            chest=num("chest"), waist=num("waist"), hip=num("hip"), shoulder=num("shoulder"), inseam=num("inseam"),
            torso_length=num("torso_length"), sleeve_length=num("sleeve_length"), outseam=num("outseam"),
            thigh=num("thigh"),
        )

    def public(self) -> dict:
        return {k: v for k, v in self.__dict__.items() if v is not None}

    @property
    def male(self) -> bool:
        return self.gender == "male"

    @property
    def shape(self) -> Shape | None:
        if not (self.chest and self.waist and self.hip):
            return None
        bar = HIP_CHEST_RATIO + HIP_CHEST_MARGIN
        if self.hip / self.chest >= bar:
            return Shape.LOWER_VOLUME
        if self.chest / self.hip >= bar:
            return Shape.UPPER_VOLUME
        if self.waist / self.hip <= WAIST_HIP_RATIO - WAIST_HIP_MARGIN:
            return Shape.HOURGLASS
        if self.waist / self.hip >= WAIST_HIP_RATIO + WAIST_HIP_MARGIN:
            return Shape.STRAIGHT
        return None

    @property
    def short_legs(self) -> bool:
        return bool(self.inseam) and self.inseam / self.height <= LEG_RATIO - LEG_MARGIN

    @property
    def long_legs(self) -> bool:
        return bool(self.inseam) and self.inseam / self.height >= LEG_RATIO + LEG_MARGIN

    @property
    def broad_shoulders(self) -> bool:
        return bool(self.shoulder) and self.shoulder / self.height >= SHOULDER_RATIO + SHOULDER_MARGIN

"""Learned correction of the geometric measurements (Option 2 in docs/body-analysis/01-model-research.md).

A per-measurement ridge regression maps the scale-free geometric features (RawMeasurements.features),
the user's inputs (height, weight, gender, clothing) to measurement / height. It is trained on the
synthetic Anny dataset by scripts/train_corrector.py and shipped as a small JSON file, so the server
needs no ML library beyond numpy. Measurements or clothing conditions the model was not validated
for keep the geometric value.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from .types import AnalysisInput, Clothing, Gender, MeasurementType

DEFAULT_MODEL_PATH = Path(__file__).resolve().parent / "models" / "measurement_corrector.json"


def input_features(input_: AnalysisInput) -> dict[str, float]:
    """User-provided inputs, encoded the same way at training and inference time."""
    weight_known = input_.weight_kg is not None
    bmi = input_.weight_kg / (input_.height_cm / 100) ** 2 if weight_known else 0.0
    return {
        "in_height_m": input_.height_cm / 100,
        "in_bmi": bmi,
        "in_weight_known": float(weight_known),
        **{f"in_gender_{g.value}": float(input_.gender is g) for g in Gender},
        **{f"in_clothing_{c.value}": float(input_.clothing is c) for c in Clothing},
    }


@dataclass(frozen=True)
class _Linear:
    features: list[str]
    mean: np.ndarray
    scale: np.ndarray
    coef: np.ndarray
    intercept: float

    def predict(self, values: dict[str, float]) -> float:
        # A missing feature (e.g. no arm measurements) is replaced by its training mean → contributes 0.
        x = np.array([values.get(name, np.nan) for name in self.features], float)
        x = np.where(np.isnan(x), self.mean, x)
        return float(((x - self.mean) / self.scale) @ self.coef + self.intercept)


class MeasurementCorrector:
    def __init__(self, models: dict[MeasurementType, _Linear], conditions: set[Clothing], version: str):
        self.models = models
        self.conditions = conditions
        self.version = version

    @classmethod
    def load(cls, path: Path = DEFAULT_MODEL_PATH) -> "MeasurementCorrector | None":
        if not path.exists():
            return None
        data = json.loads(path.read_text(encoding="utf-8"))
        models = {
            MeasurementType(key): _Linear(
                features=m["features"],
                mean=np.array(m["mean"], float),
                scale=np.array(m["scale"], float),
                coef=np.array(m["coef"], float),
                intercept=float(m["intercept"]),
            )
            for key, m in data["measurements"].items()
        }
        return cls(models, {Clothing(c) for c in data["conditions"]}, data["version"])

    def applies_to(self, input_: AnalysisInput) -> bool:
        return input_.clothing in self.conditions

    def correct(
        self,
        geometric_cm: dict[MeasurementType, float],
        features: dict[str, float],
        input_: AnalysisInput,
    ) -> dict[MeasurementType, float]:
        """Returns corrected values; measurements without a model keep their geometric value."""
        if not self.applies_to(input_):
            return dict(geometric_cm)
        values = {**features, **input_features(input_)}
        corrected = dict(geometric_cm)
        for type_, model in self.models.items():
            if type_ in geometric_cm:  # never invent a measurement the geometry could not produce
                corrected[type_] = model.predict(values) * input_.height_cm
        return corrected

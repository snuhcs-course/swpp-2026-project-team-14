# AI-generated with Claude Code (Claude Opus 5.5), 2026-09-24, reviewed by Dongkun Moon
"""Learned correction of the geometric measurements (Option 2 in docs/body-analysis/01-model-research.md).

A per-measurement ridge regression maps the scale-free geometric features (RawMeasurements.features)
and the user's inputs (height, weight, gender) to measurement / height. It is trained on the
synthetic Anny dataset by scripts/train_corrector.py and shipped as a small JSON file, so the server
needs no ML library beyond numpy. It is trained on fitted clothing only, so measurements in a region
where loose clothing was detected keep their geometric value.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from .types import AnalysisInput, Gender, MeasurementType

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
    }


@dataclass(frozen=True)
class _Linear:
    features: list[str]
    mean: np.ndarray
    scale: np.ndarray
    coef: np.ndarray
    intercept: float

    @classmethod
    def from_dict(cls, m: dict) -> "_Linear":
        return cls(
            features=m["features"],
            mean=np.array(m["mean"], float),
            scale=np.array(m["scale"], float),
            coef=np.array(m["coef"], float),
            intercept=float(m["intercept"]),
        )

    def predict(self, values: dict[str, float]) -> float:
        # A missing feature (e.g. no arm measurements) is replaced by its training mean → contributes 0.
        x = np.array([values.get(name, np.nan) for name in self.features], float)
        x = np.where(np.isnan(x), self.mean, x)
        return float(((x - self.mean) / self.scale) @ self.coef + self.intercept)


class MeasurementCorrector:
    def __init__(self, models: dict[MeasurementType, _Linear], version: str):
        self.models = models
        self.version = version

    @classmethod
    def load(cls, path: Path = DEFAULT_MODEL_PATH) -> "MeasurementCorrector | None":
        if not path.exists():
            return None
        data = json.loads(path.read_text(encoding="utf-8"))
        models = {MeasurementType(key): _Linear.from_dict(m) for key, m in data["measurements"].items()}
        return cls(models, data["version"])

    def correct(
        self,
        geometric_cm: dict[MeasurementType, float],
        features: dict[str, float],
        input_: AnalysisInput,
        skip: set[MeasurementType] = frozenset(),
    ) -> dict[MeasurementType, float]:
        """Returns corrected values. Measurements without a model, or listed in `skip` (distorted by
        detected loose clothing, which the model was not trained on), keep their geometric value."""
        values = {**features, **input_features(input_)}
        corrected = dict(geometric_cm)
        for type_, model in self.models.items():
            if type_ in geometric_cm and type_ not in skip:  # never invent a measurement
                corrected[type_] = model.predict(values) * input_.height_cm
        return corrected

"""Detects loose clothing from the silhouette, so users no longer have to say what they wore.

Two logistic-regression models (loose top, loose bottom) on the same scale-free features as the
measurement corrector (RawMeasurements.features) plus gender. Trained on the synthetic dataset by
scripts/train_clothing_detector.py and shipped as JSON; numpy only at runtime.
"""

from __future__ import annotations

import json
import math
from pathlib import Path

from .regressor import _Linear
from .types import ClothingAssessment, Gender

DEFAULT_MODEL_PATH = Path(__file__).resolve().parent / "models" / "clothing_detector.json"


def detector_features(features: dict[str, float], gender: Gender) -> dict[str, float]:
    """Silhouette features + gender, encoded the same way at training and inference time."""
    return {**features, **{f"in_gender_{g.value}": float(gender is g) for g in Gender}}


class ClothingDetector:
    def __init__(self, models: dict[str, tuple[_Linear, float]], version: str):
        self.models = models  # region -> (linear model, probability threshold)
        self.version = version

    @classmethod
    def load(cls, path: Path = DEFAULT_MODEL_PATH) -> "ClothingDetector | None":
        if not path.exists():
            return None
        data = json.loads(path.read_text(encoding="utf-8"))
        models = {
            region: (_Linear.from_dict(m), float(m["threshold"]))
            for region, m in data["models"].items()
        }
        return cls(models, data["version"])

    def probability(self, region: str, features: dict[str, float], gender: Gender) -> float:
        model, _ = self.models[region]
        score = model.predict(detector_features(features, gender))
        return 1.0 / (1.0 + math.exp(-score))

    def assess(self, features: dict[str, float], gender: Gender) -> ClothingAssessment:
        top = self.probability("top", features, gender)
        bottom = self.probability("bottom", features, gender)
        return ClothingAssessment(
            top_loose=top >= self.models["top"][1],
            bottom_loose=bottom >= self.models["bottom"][1],
            top_probability=top,
            bottom_probability=bottom,
        )

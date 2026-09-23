"""Loads the real MediaPipe model. Skipped if the model file has not been downloaded."""

import numpy as np
import pytest

from body_analysis.pose import DEFAULT_MODEL_PATH, MediaPipePoseEstimator

pytestmark = pytest.mark.skipif(not DEFAULT_MODEL_PATH.exists(), reason="pose model not downloaded")


def test_model_loads_and_finds_nobody_in_blank_image():
    estimator = MediaPipePoseEstimator()
    try:
        assert estimator.estimate(np.full((640, 480, 3), 200, np.uint8)) is None
    finally:
        estimator.close()

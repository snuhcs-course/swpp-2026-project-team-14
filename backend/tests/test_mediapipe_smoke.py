"""MediaPipe estimator: loads the real model (skipped if not downloaded) and handles image sizes."""

import numpy as np
import pytest

from body_analysis.pose import DEFAULT_MODEL_PATH, MediaPipePoseEstimator

needs_model = pytest.mark.skipif(not DEFAULT_MODEL_PATH.exists(), reason="pose model not downloaded")


@needs_model
def test_model_loads_and_finds_nobody_in_blank_image():
    estimator = MediaPipePoseEstimator()
    try:
        assert estimator.estimate(np.full((640, 480, 3), 200, np.uint8)) is None
    finally:
        estimator.close()


class _FakeMp:
    """Stands in for the mediapipe module: records the image width MediaPipe would receive."""

    class ImageFormat:
        SRGB = "srgb"

    class Image:
        def __init__(self, image_format, data):
            self.data = data


class _FakeLandmarker:
    """Returns one pose with every landmark at the right edge of the padded image, and a full mask."""

    def __init__(self):
        self.widths = []

    def detect(self, image):
        h, w = image.data.shape[:2]
        self.widths.append(w)
        point = type("P", (), {"x": 1.0, "y": 0.5, "visibility": 0.9})()
        mask = type("M", (), {"numpy_view": lambda self: np.ones((h, w, 1), np.float32)})()
        return type("R", (), {"pose_landmarks": [[point] * 33], "segmentation_masks": [mask]})()


@pytest.mark.parametrize("width", [960, 961, 962, 963, 481])
def test_width_is_padded_to_a_multiple_of_4_and_mapped_back(width):
    """MediaPipe aborts the process on widths that are not a multiple of 4 (found with 641×481 photos)."""
    estimator = MediaPipePoseEstimator.__new__(MediaPipePoseEstimator)
    estimator._mp, estimator._landmarker, estimator._mask_threshold = _FakeMp, _FakeLandmarker(), 0.5

    result = estimator.estimate(np.zeros((100, width, 3), np.uint8))

    assert estimator._landmarker.widths[0] % 4 == 0
    assert estimator._landmarker.widths[0] - width < 4
    assert result.mask.shape == (100, width)  # cropped back to the original image
    assert result.landmarks["nose"].x == estimator._landmarker.widths[0]  # pixels of the padded image

"""Pose + person-mask estimation. The pipeline depends only on `PoseEstimator`."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

import numpy as np

# MediaPipe BlazePose landmark indices we use.
LANDMARK_INDEX = {
    "nose": 0,
    "left_ear": 7,
    "right_ear": 8,
    "mouth_left": 9,
    "mouth_right": 10,
    "left_shoulder": 11,
    "right_shoulder": 12,
    "left_elbow": 13,
    "right_elbow": 14,
    "left_wrist": 15,
    "right_wrist": 16,
    "left_hip": 23,
    "right_hip": 24,
    "left_knee": 25,
    "right_knee": 26,
    "left_ankle": 27,
    "right_ankle": 28,
    "left_heel": 29,
    "right_heel": 30,
}

DEFAULT_MODEL_PATH = Path(__file__).resolve().parent.parent / "models" / "pose_landmarker_heavy.task"


@dataclass(frozen=True)
class Landmark:
    x: float  # pixels
    y: float  # pixels
    visibility: float


@dataclass
class PoseResult:
    landmarks: dict[str, Landmark]
    mask: np.ndarray  # bool (H, W), True = person
    num_people: int


class PoseEstimator(Protocol):
    def estimate(self, image_rgb: np.ndarray) -> PoseResult | None:
        """Returns None if no person is found."""


class MediaPipePoseEstimator:
    """BlazePose GHUM (heavy) with segmentation mask, via MediaPipe Tasks."""

    def __init__(self, model_path: Path = DEFAULT_MODEL_PATH, mask_threshold: float = 0.5):
        import mediapipe as mp
        from mediapipe.tasks.python import BaseOptions, vision

        if not model_path.exists():
            raise FileNotFoundError(
                f"Pose model not found at {model_path}. See backend/README.md for the download command."
            )
        self._mp = mp
        self._mask_threshold = mask_threshold
        options = vision.PoseLandmarkerOptions(
            # Pass bytes, not a path: MediaPipe's C++ loader cannot open non-ASCII (e.g. Korean) paths.
            base_options=BaseOptions(model_asset_buffer=model_path.read_bytes()),
            running_mode=vision.RunningMode.IMAGE,
            num_poses=2,  # detect a second person so we can reject group photos
            output_segmentation_masks=True,
        )
        self._landmarker = vision.PoseLandmarker.create_from_options(options)

    def estimate(self, image_rgb: np.ndarray) -> PoseResult | None:
        h, w = image_rgb.shape[:2]
        # MediaPipe aborts the whole process (image_frame.cc "1 == ChannelSize()") when reading the
        # segmentation mask of an image whose width is not a multiple of 4, so pad the right edge
        # with at most 3 replicated columns and crop the mask back afterwards.
        padded_w = -(-w // 4) * 4
        if padded_w != w:
            image_rgb = np.pad(image_rgb, ((0, 0), (0, padded_w - w), (0, 0)), mode="edge")
        image = self._mp.Image(image_format=self._mp.ImageFormat.SRGB, data=np.ascontiguousarray(image_rgb))
        result = self._landmarker.detect(image)
        if not result.pose_landmarks:
            return None
        points = result.pose_landmarks[0]
        landmarks = {
            name: Landmark(points[i].x * padded_w, points[i].y * h, float(points[i].visibility or 0.0))
            for name, i in LANDMARK_INDEX.items()
        }
        mask = result.segmentation_masks[0].numpy_view().squeeze()[:, :w] > self._mask_threshold
        return PoseResult(landmarks=landmarks, mask=mask, num_people=len(result.pose_landmarks))

    def close(self) -> None:
        self._landmarker.close()

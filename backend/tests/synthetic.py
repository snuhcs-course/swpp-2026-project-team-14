# AI-generated with Claude Code (Claude Opus 5.5), 2026-09-24, reviewed by Dongkun Moon
"""A procedurally drawn mannequin with known dimensions, used instead of real body photos in tests.

Units are cm; the body is 170 cm tall and rasterised at PX_PER_CM. Front and side masks are
consistent, so every measurement has an exact expected value.
"""

from __future__ import annotations

import cv2
import numpy as np

from body_analysis.pose import LANDMARK_INDEX, Landmark, PoseResult

HEIGHT_CM = 170.0
PX_PER_CM = 4
MARGIN_CM = 5
WIDTH_CM = 100

# (y, width) profiles, linearly interpolated. y = cm from the top of the head.
TORSO_WIDTH = [(26, 30), (30, 38), (33, 33), (40, 32), (50, 29), (58, 28), (66, 32), (74, 36), (80, 34)]
TORSO_DEPTH = [(26, 14), (30, 20), (40, 25), (50, 21), (58, 20), (66, 22), (74, 24), (80, 22)]
LEG_WIDTH = [(79, 16.5), (84, 16), (110, 12.5), (125, 11), (140, 12), (162, 7), (170, 8)]
LEG_DEPTH = [(79, 17.5), (84, 17), (110, 14), (125, 11.5), (140, 12.5), (162, 7.5), (170, 22)]
NECK = {"y": (18, 27), "width": 12, "depth": 12}

SHOULDER_Y, HIP_Y, MOUTH_Y, CROTCH_Y, FLOOR_Y = 28, 74, 17, 80, 170
SHOULDER_X, HIP_X = 18, 9
WAIST_Y, CHEST_Y = 58, 40


def profile(points: list[tuple[float, float]], y: float) -> float:
    ys, vs = zip(*points)
    return float(np.interp(y, ys, vs))


def _px(x_cm: float, y_cm: float) -> tuple[int, int]:
    return round((x_cm + WIDTH_CM / 2) * PX_PER_CM), round((y_cm + MARGIN_CM) * PX_PER_CM)


def _canvas() -> np.ndarray:
    return np.zeros((round((HEIGHT_CM + 2 * MARGIN_CM) * PX_PER_CM), WIDTH_CM * PX_PER_CM), np.uint8)


def _band(canvas, points, center_x, y0, y1, step=0.25, x_offset=0.0):
    ys = np.arange(y0, y1 + step, step)
    left = [_px(center_x(y) - profile(points, y) / 2 + x_offset, y) for y in ys]
    right = [_px(center_x(y) + profile(points, y) / 2 + x_offset, y) for y in ys[::-1]]
    cv2.fillPoly(canvas, [np.array(left + right, np.int32)], 1)


def _limb(canvas, a, b, width_a, width_b):
    (ax, ay), (bx, by) = a, b
    length = np.hypot(bx - ax, by - ay)
    nx, ny = -(by - ay) / length, (bx - ax) / length
    quad = [
        _px(ax + nx * width_a / 2, ay + ny * width_a / 2),
        _px(bx + nx * width_b / 2, by + ny * width_b / 2),
        _px(bx - nx * width_b / 2, by - ny * width_b / 2),
        _px(ax - nx * width_a / 2, ay - ny * width_a / 2),
    ]
    cv2.fillPoly(canvas, [np.array(quad, np.int32)], 1)


def _landmarks(points_cm: dict[str, tuple[float, float]]) -> dict[str, Landmark]:
    return {name: Landmark(*_px(*points_cm[name]), 0.99) for name in LANDMARK_INDEX}


ARM_FRONT = {"shoulder": (SHOULDER_X, SHOULDER_Y), "elbow": (27, 56), "wrist": (33, 82)}
BICEP_WIDTH, WRIST_WIDTH = 9.0, 5.5


def front_pose(num_people: int = 1, crop_feet: bool = False) -> PoseResult:
    canvas = _canvas()
    cv2.ellipse(canvas, _px(0, 10), (8 * PX_PER_CM, 10 * PX_PER_CM), 0, 0, 360, 1, -1)
    y0, y1 = NECK["y"]
    cv2.rectangle(canvas, _px(-NECK["width"] / 2, y0), _px(NECK["width"] / 2, y1), 1, -1)
    _band(canvas, TORSO_WIDTH, lambda y: 0.0, 26, 80.5)
    for sign in (-1, 1):
        _band(canvas, LEG_WIDTH, lambda y, s=sign: s * profile([(79, 9.5), (170, 7.5)], y), 79, FLOOR_Y)
        s, e, w = [(sign * x, y) for x, y in ARM_FRONT.values()]
        _limb(canvas, s, e, BICEP_WIDTH, 7.5)
        _limb(canvas, e, w, 7.5, WRIST_WIDTH)
    if crop_feet:
        canvas = canvas[: _px(0, 160)[1]]  # photo ends at the shins

    points = {
        "nose": (0, 12), "left_ear": (-7, 10), "right_ear": (7, 10),
        "mouth_left": (-2, MOUTH_Y), "mouth_right": (2, MOUTH_Y),
        "left_shoulder": (-SHOULDER_X, SHOULDER_Y), "right_shoulder": (SHOULDER_X, SHOULDER_Y),
        "left_elbow": (-27, 56), "right_elbow": (27, 56),
        "left_wrist": (-33, 82), "right_wrist": (33, 82),
        "left_hip": (-HIP_X, HIP_Y), "right_hip": (HIP_X, HIP_Y),
        "left_knee": (-8.5, 125), "right_knee": (8.5, 125),
        "left_ankle": (-7.7, 162), "right_ankle": (7.7, 162),
        "left_heel": (-7.7, 169), "right_heel": (7.7, 169),
    }
    return PoseResult(landmarks=_landmarks(points), mask=canvas.astype(bool), num_people=num_people)


def side_pose() -> PoseResult:
    canvas = _canvas()
    cv2.ellipse(canvas, _px(0, 10), (10 * PX_PER_CM, 10 * PX_PER_CM), 0, 0, 360, 1, -1)
    y0, y1 = NECK["y"]
    cv2.rectangle(canvas, _px(-NECK["depth"] / 2, y0), _px(NECK["depth"] / 2, y1), 1, -1)
    _band(canvas, TORSO_DEPTH, lambda y: 0.0, 26, 80.5)
    _band(canvas, LEG_DEPTH, lambda y: 0.0, 79, FLOOR_Y)
    points = {name: (0.0, y) for name, (_, y) in _front_points_y().items()}
    return PoseResult(landmarks=_landmarks(points), mask=canvas.astype(bool), num_people=1)


def _front_points_y() -> dict[str, tuple[float, float]]:
    pose = front_pose()
    return {name: (0.0, lm.y / PX_PER_CM - MARGIN_CM) for name, lm in pose.landmarks.items()}

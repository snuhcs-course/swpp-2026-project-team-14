"""Turns front/side pose results into garment measurements (baseline v1, geometric).

Method (docs/body-analysis/02-design.md §6, Option 1):
- Scale: user height / person-mask height in pixels, per photo.
- Lengths: landmark distances and landmark-defined rows (waist, crotch, floor).
- Circumferences: front width × side depth at the same relative body height → ellipse perimeter.
  Without a side photo, depth = width × population depth ratio.

All CALIBRATION / DEPTH_RATIO values are initial guesses to be fitted in the benchmark
(docs/body-analysis/03-benchmark-plan.md).
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

from .geometry import (
    contains,
    cos_from_vertical,
    distance,
    ellipse_perimeter,
    largest_component,
    lerp,
    run_at,
    vertical_extent,
    width_at,
)
from .pose import PoseResult
from .types import MeasurementType as M

# Multipliers applied to the raw geometric value.
CALIBRATION: dict[M, float] = {
    M.SHOULDER_WIDTH: 1.12,  # shoulder joint centres → acromion breadth
    M.SLEEVE_LENGTH: 1.04,  # joint centre → shoulder point
    M.TORSO_LENGTH: 1.0,
    M.RISE: 1.0,
    M.INSEAM: 1.0,
    M.OUTSEAM: 1.0,
    M.NECK: 1.0,
    M.CHEST: 1.03,  # torso sections are closer to rounded rectangles than ellipses
    M.UNDERBUST: 1.03,
    M.WAIST: 1.03,
    M.HIP: 1.03,
    M.ARMHOLE: 1.0,
    M.BICEP: 1.0,
    M.WRIST: 1.0,
    M.THIGH: 1.0,
    M.CALF: 1.0,
}

# depth / width when there is no side photo (population averages, to be calibrated).
DEPTH_RATIO: dict[M, float] = {
    M.NECK: 0.95,
    M.CHEST: 0.70,
    M.UNDERBUST: 0.68,
    M.WAIST: 0.72,
    M.HIP: 0.72,
    M.THIGH: 0.95,
    M.CALF: 1.0,
}

ACROMION_OFFSET_CM = 3.0  # top of the armhole sits above the shoulder joint centre


@dataclass
class Guide:
    """A measured line, for debug overlays."""

    label: str
    view: str  # "front" | "side"
    y: float
    x0: float
    x1: float


@dataclass
class RawMeasurements:
    values: dict[M, float]
    derived: dict[str, float]
    warnings: list[str] = field(default_factory=list)
    guides: list[Guide] = field(default_factory=list)


class BodyView:
    """One photo: cleaned mask, landmarks and pixel→cm scale."""

    def __init__(self, pose: PoseResult, height_cm: float, name: str):
        self.name = name
        self.mask = largest_component(pose.mask)
        self.lm = pose.landmarks
        top, bottom = vertical_extent(self.mask)
        heel = max(self.lm["left_heel"].y, self.lm["right_heel"].y)
        self.top = float(top)
        self.floor = float(max(bottom, heel))
        self.scale = height_cm / (self.floor - self.top)  # cm per px

    def p(self, name: str) -> tuple[float, float]:
        point = self.lm[name]
        return point.x, point.y

    def mid(self, a: str, b: str) -> tuple[float, float]:
        (ax, ay), (bx, by) = self.p(a), self.p(b)
        return (ax + bx) / 2, (ay + by) / 2

    def frac(self, y: float) -> float:
        return (y - self.top) / (self.floor - self.top)

    def y_at(self, fraction: float) -> float:
        return self.top + fraction * (self.floor - self.top)

    def center_x(self, y: float) -> float:
        """Body midline, interpolated between shoulder and hip midpoints."""
        (sx, sy), (hx, hy) = self.mid("left_shoulder", "right_shoulder"), self.mid("left_hip", "right_hip")
        t = 0.0 if hy == sy else min(max((y - sy) / (hy - sy), -0.5), 1.5)
        return lerp(sx, hx, t)


def _rows(start: float, end: float, count: int = 40) -> np.ndarray:
    return np.linspace(start, end, count)


def _narrowest_row(rows: np.ndarray, width_fn) -> float:
    """Middle of the rows whose width is within 1 px of the minimum.

    Waists are often flat over several cm; taking the first minimum biases the row upwards.
    """
    widths = np.array([width_fn(y) for y in rows])
    candidates = rows[widths <= widths.min() + 1]
    return float(np.median(candidates))


def measure(front_pose: PoseResult, side_pose: PoseResult | None, height_cm: float) -> RawMeasurements:
    f = BodyView(front_pose, height_cm, "front")
    s = BodyView(side_pose, height_cm, "side") if side_pose is not None else None
    values: dict[M, float] = {}
    warnings: list[str] = []
    guides: list[Guide] = []

    ls, rs = f.p("left_shoulder"), f.p("right_shoulder")
    lh, rh = f.p("left_hip"), f.p("right_hip")
    sh_y = (ls[1] + rs[1]) / 2
    hip_y = (lh[1] + rh[1]) / 2
    knee_y = (f.p("left_knee")[1] + f.p("right_knee")[1]) / 2
    mouth_y = f.mid("mouth_left", "mouth_right")[1]
    torso_px = hip_y - sh_y
    shoulder_px = distance(ls, rs)
    torso_clamp = (min(ls[0], rs[0]) - 0.05 * shoulder_px, max(ls[0], rs[0]) + 0.05 * shoulder_px)

    def torso_width(y: float) -> float:
        return width_at(f.mask, y, f.center_x(y), clamp=torso_clamp)

    def side_depth(front_y: float) -> float | None:
        if s is None:
            return None
        y = s.y_at(f.frac(front_y))
        depth = width_at(s.mask, y, s.center_x(y))
        seg = run_at(s.mask, y, s.center_x(y))
        if seg:
            guides.append(Guide("depth", "side", y, seg[0], seg[1]))
        return depth * s.scale

    def circumference(type_: M, front_y: float, front_width_px: float, x_center: float) -> float:
        width_cm = front_width_px * f.scale
        depth_cm = side_depth(front_y)
        if depth_cm is None:
            depth_cm = width_cm * DEPTH_RATIO[type_]
        guides.append(Guide(type_.value, "front", front_y, x_center - front_width_px / 2, x_center + front_width_px / 2))
        return ellipse_perimeter(width_cm / 2, depth_cm / 2) * CALIBRATION[type_]

    # --- rows -----------------------------------------------------------------------------
    waist_y = _narrowest_row(_rows(sh_y + 0.45 * torso_px, hip_y), torso_width)

    crotch_y = None
    cx = (lh[0] + rh[0]) / 2
    for y in np.arange(hip_y, knee_y):  # every pixel row
        if not contains(f.mask, y, cx):
            crotch_y = float(y)
            break
    if crotch_y is None:
        crotch_y = lerp(hip_y, knee_y, 0.25)
        warnings.append("crotch_not_found")

    neck_base_y = sh_y - 0.1 * (sh_y - mouth_y)

    # --- lengths --------------------------------------------------------------------------
    values[M.SHOULDER_WIDTH] = shoulder_px * f.scale * CALIBRATION[M.SHOULDER_WIDTH]
    guides.append(Guide("shoulder_width", "front", sh_y, min(ls[0], rs[0]), max(ls[0], rs[0])))

    arm_lengths = [
        distance(f.p(f"{side}_shoulder"), f.p(f"{side}_elbow")) + distance(f.p(f"{side}_elbow"), f.p(f"{side}_wrist"))
        for side in ("left", "right")
    ]
    values[M.SLEEVE_LENGTH] = float(np.mean(arm_lengths)) * f.scale * CALIBRATION[M.SLEEVE_LENGTH]

    torso_lengths = [(waist_y - neck_base_y) * f.scale]
    if s is not None:
        s_sh_y = s.mid("left_shoulder", "right_shoulder")[1]
        s_hip_y = s.mid("left_hip", "right_hip")[1]
        s_mouth_y = s.mid("mouth_left", "mouth_right")[1]
        s_band = _rows(s_sh_y + 0.45 * (s_hip_y - s_sh_y), s_hip_y)
        s_waist_y = _narrowest_row(s_band, lambda y: width_at(s.mask, y, s.center_x(y)))
        s_neck_y = s_sh_y - 0.1 * (s_sh_y - s_mouth_y)
        torso_lengths.append((s_waist_y - s_neck_y) * s.scale)
    values[M.TORSO_LENGTH] = float(np.mean(torso_lengths)) * CALIBRATION[M.TORSO_LENGTH]
    values[M.RISE] = (crotch_y - waist_y) * f.scale * CALIBRATION[M.RISE]
    values[M.INSEAM] = (f.floor - crotch_y) * f.scale * CALIBRATION[M.INSEAM]
    values[M.OUTSEAM] = (f.floor - waist_y) * f.scale * CALIBRATION[M.OUTSEAM]

    # --- torso circumferences -------------------------------------------------------------
    if s is not None:
        chest_y = float(max(_rows(sh_y + 0.18 * torso_px, sh_y + 0.42 * torso_px), key=lambda y: side_depth_raw(s, f, y)))
    else:
        chest_y = sh_y + 0.3 * torso_px
    values[M.CHEST] = circumference(M.CHEST, chest_y, torso_width(chest_y), f.center_x(chest_y))
    underbust_y = chest_y + 0.12 * torso_px
    values[M.UNDERBUST] = circumference(M.UNDERBUST, underbust_y, torso_width(underbust_y), f.center_x(underbust_y))
    values[M.WAIST] = circumference(M.WAIST, waist_y, torso_width(waist_y), f.center_x(waist_y))

    hip_joint_px = distance(lh, rh)
    hip_clamp = (cx - 1.3 * hip_joint_px, cx + 1.3 * hip_joint_px)
    hip_rows = _rows(hip_y - 0.1 * torso_px, crotch_y - 1)
    hip_row = float(max(hip_rows, key=lambda y: width_at(f.mask, y, cx, clamp=hip_clamp)))
    hip_width_px = width_at(f.mask, hip_row, cx, clamp=hip_clamp)
    values[M.HIP] = circumference(M.HIP, hip_row, hip_width_px, cx)

    neck_y = sh_y - 0.3 * (sh_y - mouth_y)
    neck_x = f.mid("left_shoulder", "right_shoulder")[0]
    neck_w = width_at(f.mask, neck_y, neck_x)
    if s is not None:
        s_neck_y = s.y_at(f.frac(neck_y))
        s_neck_x = (s.mid("left_ear", "right_ear")[0] + s.mid("left_shoulder", "right_shoulder")[0]) / 2
        neck_depth_cm = width_at(s.mask, s_neck_y, s_neck_x) * s.scale
    else:
        neck_depth_cm = neck_w * f.scale * DEPTH_RATIO[M.NECK]
    values[M.NECK] = ellipse_perimeter(neck_w * f.scale / 2, neck_depth_cm / 2) * CALIBRATION[M.NECK]
    guides.append(Guide("neck", "front", neck_y, neck_x - neck_w / 2, neck_x + neck_w / 2))

    # --- legs (left leg; legs overlap in the side view) ------------------------------------
    leg_px = f.floor - crotch_y
    thigh_y = crotch_y + 0.04 * leg_px
    lk, la = f.p("left_knee"), f.p("left_ankle")
    thigh_x = lerp(lh[0], lk[0], (thigh_y - lh[1]) / max(lk[1] - lh[1], 1))
    thigh_w = width_at(f.mask, thigh_y, thigh_x) * cos_from_vertical(lh, lk)
    values[M.THIGH] = _limb_circumference(M.THIGH, f, s, thigh_y, thigh_w, ("left_hip", "left_knee"), guides, thigh_x)

    def calf_width(y: float) -> float:
        t = (y - lk[1]) / max(la[1] - lk[1], 1)
        return width_at(f.mask, y, lerp(lk[0], la[0], t))

    calf_y = float(max(_rows(lerp(lk[1], la[1], 0.15), lerp(lk[1], la[1], 0.55)), key=calf_width))
    calf_x = lerp(lk[0], la[0], (calf_y - lk[1]) / max(la[1] - lk[1], 1))
    calf_w = calf_width(calf_y) * cos_from_vertical(lk, la)
    values[M.CALF] = _limb_circumference(M.CALF, f, s, calf_y, calf_w, ("left_knee", "left_ankle"), guides, calf_x)

    # --- arms ------------------------------------------------------------------------------
    arm = _arm_measurements(f, torso_width_fn=torso_width)
    if arm is None:
        warnings.append("arms_touching_body")
    else:
        bicep_w, wrist_w, armpit_drop_px = arm
        values[M.BICEP] = ellipse_perimeter(bicep_w * f.scale / 2, 0.95 * bicep_w * f.scale / 2) * CALIBRATION[M.BICEP]
        values[M.WRIST] = ellipse_perimeter(wrist_w * f.scale / 2, 0.75 * wrist_w * f.scale / 2) * CALIBRATION[M.WRIST]
        armhole_height = armpit_drop_px * f.scale + ACROMION_OFFSET_CM
        values[M.ARMHOLE] = ellipse_perimeter(armhole_height / 2, 1.1 * bicep_w * f.scale / 2) * CALIBRATION[M.ARMHOLE]

    derived = {
        "shoulder_hip_ratio": round(values[M.SHOULDER_WIDTH] / (hip_width_px * f.scale), 3),
        "waist_hip_ratio": round(values[M.WAIST] / values[M.HIP], 3),
        "torso_leg_ratio": round(values[M.TORSO_LENGTH] / values[M.INSEAM], 3),
        "inseam_height_ratio": round(values[M.INSEAM] / height_cm, 3),
    }
    return RawMeasurements(values=values, derived=derived, warnings=warnings, guides=guides)


def side_depth_raw(s: BodyView, f: BodyView, front_y: float) -> float:
    y = s.y_at(f.frac(front_y))
    return width_at(s.mask, y, s.center_x(y))


def _limb_circumference(
    type_: M,
    f: BodyView,
    s: BodyView | None,
    front_y: float,
    front_width_px: float,
    joints: tuple[str, str],
    guides: list[Guide],
    x_center: float,
) -> float:
    width_cm = front_width_px * f.scale
    if s is not None:
        y = s.y_at(f.frac(front_y))
        (ax, ay), (bx, by) = s.p(joints[0]), s.p(joints[1])
        x = lerp(ax, bx, (y - ay) / max(by - ay, 1))
        depth_cm = width_at(s.mask, y, x) * cos_from_vertical((ax, ay), (bx, by)) * s.scale
    else:
        depth_cm = width_cm * DEPTH_RATIO[type_]
    guides.append(Guide(type_.value, "front", front_y, x_center - front_width_px / 2, x_center + front_width_px / 2))
    return ellipse_perimeter(width_cm / 2, depth_cm / 2) * CALIBRATION[type_]


def _arm_measurements(f: BodyView, torso_width_fn) -> tuple[float, float, float] | None:
    """(bicep width px, wrist width px, shoulder→armpit drop px) from whichever arm is clear of the torso."""
    for side in ("left", "right"):
        sh, el, wr = f.p(f"{side}_shoulder"), f.p(f"{side}_elbow"), f.p(f"{side}_wrist")

        def point_on(a, b, t):
            return lerp(a[0], b[0], t), lerp(a[1], b[1], t)

        def separate_from_torso(x: float, y: float) -> bool:
            arm_seg = run_at(f.mask, y, x)
            torso_seg = run_at(f.mask, y, f.center_x(y))
            return arm_seg is not None and arm_seg != torso_seg

        bx, by = point_on(sh, el, 0.45)
        if not separate_from_torso(bx, by):
            continue
        bicep_w = width_at(f.mask, by, bx) * cos_from_vertical(sh, el)

        wx, wy = point_on(el, wr, 0.9)
        wrist_w = width_at(f.mask, wy, wx) * cos_from_vertical(el, wr)

        armpit_y = None
        for t in np.linspace(0.05, 0.6, 40):
            x, y = point_on(sh, el, t)
            if separate_from_torso(x, y):
                armpit_y = y
                break
        if armpit_y is None:
            continue
        return bicep_w, wrist_w, armpit_y - sh[1]
    return None

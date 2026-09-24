"""Turns front/side pose results into garment measurements (baseline v1, geometric).

Method (docs/body-analysis/02-design.md §6, Option 1):
- Scale: user height / person-mask height in pixels, per photo.
- Lengths: landmark distances and landmark-defined rows (waist, crotch, floor).
- Circumferences: front width × side depth at the same relative body height → ellipse perimeter.

Both photos are required: circumferences and the waist row depend on body depth.
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
# Fitted on the synthetic Anny benchmark (8 bodies, front + side; docs/body-analysis/04-synthetic-benchmark.md)
# for measurements with a well-defined mesh ground truth. The others keep their initial guesses
# until real tape-measure data exists.
CALIBRATION: dict[M, float] = {
    # 1.12 × 1.175 fitted on the benchmark. The mesh ground truth for shoulder width is weakly
    # defined (between biacromial and bideltoid), so re-check against real tape measurements.
    M.SHOULDER_WIDTH: 1.316,
    M.SLEEVE_LENGTH: 1.04,  # initial guess: joint centre → shoulder point
    M.TORSO_LENGTH: 1.056,
    M.RISE: 1.005,
    M.INSEAM: 0.974,
    M.OUTSEAM: 0.981,
    M.NECK: 1.001,
    M.CHEST: 1.063,
    M.UNDERBUST: 1.03,  # initial guess, not evaluated
    M.WAIST: 1.050,
    M.HIP: 0.990,
    M.ARMHOLE: 1.0,  # initial guess, not evaluated
    M.BICEP: 1.089,
    M.WRIST: 1.0,  # initial guess
    M.THIGH: 1.013,
    M.CALF: 0.992,
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
    # Scale-free geometric features (all lengths divided by body height) for the learned
    # correction model; see body_analysis/regressor.py.
    features: dict[str, float] = field(default_factory=dict)


# Relative heights (share of body height from the floor) where silhouette widths/depths are sampled.
PROFILE_LEVELS = [round(0.30 + 0.025 * i, 3) for i in range(23)]  # 0.30 … 0.85


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


def _extreme_row(rows: np.ndarray, fn, tolerance: float, largest: bool = False) -> float:
    """Middle of the rows whose value is within `tolerance` of the min (or max).

    Body profiles are often flat over several cm; taking the first extreme biases the row.
    """
    values = np.array([fn(y) for y in rows])
    if largest:
        candidates = rows[values >= values.max() - tolerance]
    else:
        candidates = rows[values <= values.min() + tolerance]
    return float(np.median(candidates))


def measure(front_pose: PoseResult, side_pose: PoseResult, height_cm: float) -> RawMeasurements:
    f = BodyView(front_pose, height_cm, "front")
    s = BodyView(side_pose, height_cm, "side")
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

    def side_depth(front_y: float) -> float:
        y = s.y_at(f.frac(front_y))
        seg = run_at(s.mask, y, s.center_x(y))
        if seg is None:
            return 0.0
        guides.append(Guide("depth", "side", y, seg[0], seg[1]))
        return (seg[1] - seg[0]) * s.scale

    def circumference(type_: M, front_y: float, front_width_px: float, x_center: float) -> float:
        width_cm = front_width_px * f.scale
        depth_cm = side_depth(front_y)
        guides.append(Guide(type_.value, "front", front_y, x_center - front_width_px / 2, x_center + front_width_px / 2))
        return ellipse_perimeter(width_cm / 2, depth_cm / 2) * CALIBRATION[type_]

    cx = (lh[0] + rh[0]) / 2
    hip_joint_px = distance(lh, rh)
    hip_clamp = (cx - 1.3 * hip_joint_px, cx + 1.3 * hip_joint_px)

    def hip_width(y: float) -> float:
        return width_at(f.mask, y, cx, clamp=hip_clamp)

    def estimated_circumference(width_fn, y: float) -> float:
        """Uncalibrated circumference (cm) used only to pick rows."""
        return ellipse_perimeter(width_fn(y) * f.scale / 2, side_depth_raw(s, f, y) * s.scale / 2)

    # --- rows -----------------------------------------------------------------------------
    # Rows are chosen by estimated circumference: the front width alone is nearly constant over
    # the waist region, and depth decides where the true minimum is.
    waist_rows = _rows(sh_y + 0.45 * torso_px, hip_y)
    waist_y = _extreme_row(waist_rows, lambda y: estimated_circumference(torso_width, y), tolerance=0.3)

    crotch_y = None
    for y in np.arange(hip_y, knee_y):  # every pixel row
        if not contains(f.mask, y, cx):
            crotch_y = float(y)
            break
    if crotch_y is None:
        crotch_y = lerp(hip_y, knee_y, 0.25)
        warnings.append("crotch_not_found")

    # C7 sits roughly 40 % of the way from the shoulder joints up to the mouth
    neck_base_y = sh_y - 0.4 * (sh_y - mouth_y)

    # --- lengths --------------------------------------------------------------------------
    values[M.SHOULDER_WIDTH] = shoulder_px * f.scale * CALIBRATION[M.SHOULDER_WIDTH]
    guides.append(Guide("shoulder_width", "front", sh_y, min(ls[0], rs[0]), max(ls[0], rs[0])))

    arm_lengths = [
        distance(f.p(f"{side}_shoulder"), f.p(f"{side}_elbow")) + distance(f.p(f"{side}_elbow"), f.p(f"{side}_wrist"))
        for side in ("left", "right")
    ]
    values[M.SLEEVE_LENGTH] = float(np.mean(arm_lengths)) * f.scale * CALIBRATION[M.SLEEVE_LENGTH]

    s_sh_y = s.mid("left_shoulder", "right_shoulder")[1]
    s_mouth_y = s.mid("mouth_left", "mouth_right")[1]
    s_neck_y = s_sh_y - 0.4 * (s_sh_y - s_mouth_y)
    torso_lengths = [(waist_y - neck_base_y) * f.scale, (s.y_at(f.frac(waist_y)) - s_neck_y) * s.scale]
    values[M.TORSO_LENGTH] = float(np.mean(torso_lengths)) * CALIBRATION[M.TORSO_LENGTH]
    values[M.RISE] = (crotch_y - waist_y) * f.scale * CALIBRATION[M.RISE]
    values[M.INSEAM] = (f.floor - crotch_y) * f.scale * CALIBRATION[M.INSEAM]
    values[M.OUTSEAM] = (f.floor - waist_y) * f.scale * CALIBRATION[M.OUTSEAM]

    # --- torso circumferences -------------------------------------------------------------
    def arm_merged(y: float) -> bool:
        """True if either upper arm touches the torso on this row (its width would leak in)."""
        torso_seg = run_at(f.mask, y, f.center_x(y))
        for side in ("left", "right"):
            (sx, sy), (ex, ey) = f.p(f"{side}_shoulder"), f.p(f"{side}_elbow")
            arm_x = lerp(sx, ex, (y - sy) / max(ey - sy, 1))
            if contains(f.mask, y, arm_x) and run_at(f.mask, y, arm_x) == torso_seg:
                return True
        return False

    chest_rows = _rows(sh_y + 0.15 * torso_px, sh_y + 0.45 * torso_px)
    clear_rows = np.array([y for y in chest_rows if not arm_merged(y)])
    if clear_rows.size < 3:
        clear_rows = chest_rows[len(chest_rows) // 2 :]
        warnings.append("arms_touching_body")
    chest_y = _extreme_row(clear_rows, lambda y: estimated_circumference(torso_width, y), 0.3, largest=True)
    values[M.CHEST] = circumference(M.CHEST, chest_y, torso_width(chest_y), f.center_x(chest_y))
    underbust_y = chest_y + 0.12 * torso_px
    values[M.UNDERBUST] = circumference(M.UNDERBUST, underbust_y, torso_width(underbust_y), f.center_x(underbust_y))
    values[M.WAIST] = circumference(M.WAIST, waist_y, torso_width(waist_y), f.center_x(waist_y))

    hip_rows = _rows(hip_y - 0.1 * torso_px, crotch_y - 1)
    hip_row = _extreme_row(hip_rows, lambda y: estimated_circumference(hip_width, y), 0.3, largest=True)
    hip_width_px = hip_width(hip_row)
    values[M.HIP] = circumference(M.HIP, hip_row, hip_width_px, cx)

    # narrowest row between the chin and the shoulders
    neck_x = f.mid("left_shoulder", "right_shoulder")[0]
    neck_rows = _rows(mouth_y + 0.35 * (sh_y - mouth_y), sh_y)
    neck_y = _extreme_row(neck_rows, lambda y: width_at(f.mask, y, neck_x), tolerance=1)
    neck_w = width_at(f.mask, neck_y, neck_x)
    s_neck_x = (s.mid("left_ear", "right_ear")[0] + s.mid("left_shoulder", "right_shoulder")[0]) / 2
    neck_depth_cm = width_at(s.mask, s.y_at(f.frac(neck_y)), s_neck_x) * s.scale
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
    features = _features(f, s, values, height_cm, waist_y=waist_y, crotch_y=crotch_y, chest_y=chest_y,
                         hip_row=hip_row, arms_found=arm is not None)
    return RawMeasurements(values=values, derived=derived, warnings=warnings, guides=guides, features=features)


def _features(
    f: "BodyView",
    s: "BodyView",
    values: dict[M, float],
    height_cm: float,
    *,
    waist_y: float,
    crotch_y: float,
    chest_y: float,
    hip_row: float,
    arms_found: bool,
) -> dict[str, float]:
    """Scale-free description of the body for the learned correction model."""
    feats: dict[str, float] = {}
    for type_, value in values.items():
        feats[f"geo_{type_.value}"] = value / CALIBRATION[type_] / height_cm  # uncalibrated, / height
    body_px = f.floor - f.top
    for level in PROFILE_LEVELS:
        y_front = f.floor - level * body_px
        y_side = s.y_at(f.frac(y_front))
        feats[f"w_{level:.3f}"] = width_at(f.mask, y_front, f.center_x(y_front)) / body_px
        feats[f"d_{level:.3f}"] = width_at(s.mask, y_side, s.center_x(y_side)) / (s.floor - s.top)
    for name in ("left_shoulder", "left_hip", "left_knee", "left_ankle", "left_elbow", "left_wrist", "nose"):
        feats[f"lm_{name}"] = (f.floor - f.p(name)[1]) / body_px
    feats["row_waist"] = (f.floor - waist_y) / body_px
    feats["row_crotch"] = (f.floor - crotch_y) / body_px
    feats["row_chest"] = (f.floor - chest_y) / body_px
    feats["row_hip"] = (f.floor - hip_row) / body_px
    feats["shoulder_spread"] = distance(f.p("left_shoulder"), f.p("right_shoulder")) / body_px
    feats["hip_joint_spread"] = distance(f.p("left_hip"), f.p("right_hip")) / body_px
    feats["arms_found"] = float(arms_found)
    return feats


def side_depth_raw(s: BodyView, f: BodyView, front_y: float) -> float:
    y = s.y_at(f.frac(front_y))
    return width_at(s.mask, y, s.center_x(y))


def _limb_circumference(
    type_: M,
    f: BodyView,
    s: BodyView,
    front_y: float,
    front_width_px: float,
    joints: tuple[str, str],
    guides: list[Guide],
    x_center: float,
) -> float:
    width_cm = front_width_px * f.scale
    y = s.y_at(f.frac(front_y))
    (ax, ay), (bx, by) = s.p(joints[0]), s.p(joints[1])
    x = lerp(ax, bx, (y - ay) / max(by - ay, 1))
    depth_cm = width_at(s.mask, y, x) * cos_from_vertical((ax, ay), (bx, by)) * s.scale
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

        # Bicep = widest part of the upper arm, searched over rows already clear of the torso. The
        # armpit often sits more than halfway down the upper arm, so a single fixed row would
        # usually still touch the torso (synthetic dataset: arms found for only 15 % of bodies).
        clear = [point_on(sh, el, t) for t in np.linspace(0.4, 0.8, 17)]
        clear = [(x, y) for x, y in clear if separate_from_torso(x, y)]
        if not clear:
            continue
        bicep_w = max(width_at(f.mask, y, x) for x, y in clear) * cos_from_vertical(sh, el)

        wx, wy = point_on(el, wr, 0.9)
        wrist_w = width_at(f.mask, wy, wx) * cos_from_vertical(el, wr)

        armpit_y = None
        for t in np.linspace(0.05, 0.85, 60):
            x, y = point_on(sh, el, t)
            if separate_from_torso(x, y):
                armpit_y = y
                break
        if armpit_y is None:
            continue
        return bicep_w, wrist_w, armpit_y - sh[1]
    return None

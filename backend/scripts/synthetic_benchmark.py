"""Synthetic benchmark: render Anny 3D bodies, measure them with the pipeline, compare with the mesh.

    python scripts/synthetic_benchmark.py [--out private/synthetic_benchmark] [--bodies 8]

For each body (varied gender / weight / height / muscle / proportions):
1. Pose the Anny mesh (Apache 2.0, NAVER) in an A-pose and render front + side photos with a
   simple perspective camera (phone at waist height, 2.5 m away).
2. Compute ground-truth measurements directly from the mesh: horizontal slices → convex hull
   perimeter (what a tape measure follows), landmark heights from the skeleton.
3. Run BodyAnalysisPipeline on the rendered photos with the true height.
4. Repeat with simulated clothing (tight, loose) while keeping the unclothed ground truth.
5. Write per-body CSV, a summary table (MAE per measurement and condition), insight agreement and
   overlay images.

Ground-truth definitions are approximations of ISO 8559-1 landmarks on a mesh. See
docs/body-analysis/04-synthetic-benchmark.md for what each one means and its limits.
"""

from __future__ import annotations

import argparse
import csv
import math
import sys
import time
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from body_analysis.cli import draw_overlay  # noqa: E402
from body_analysis.pipeline import BodyAnalysisPipeline  # noqa: E402
from body_analysis.pose import MediaPipePoseEstimator  # noqa: E402
from body_analysis.regressor import MeasurementCorrector  # noqa: E402
from body_analysis.clothing import ClothingDetector  # noqa: E402
from body_analysis.types import AnalysisError, AnalysisInput, Gender, MeasurementType as M, Region  # noqa: E402

IMAGE_W, IMAGE_H = 960, 1280
CAMERA_DISTANCE_M = 2.5
CAMERA_HEIGHT_M = 1.0  # above the floor
VERTICAL_FOV_DEG = 55
SKIN = np.array([222, 184, 158], float)
BACKGROUND = (236, 234, 230)

# Bodies to generate. Anny phenotype values are in [0, 1]. In Anny, gender 0 = male, 1 = female,
# and age 0.8 is its adult average. Height values were picked to span ~150–190 cm.
BODIES = [
    {"name": "f_avg", "gender": 1.0, "age": 0.8, "weight": 0.5, "height": 0.35, "muscle": 0.5, "proportions": 0.5},
    {"name": "f_slim_tall", "gender": 1.0, "age": 0.8, "weight": 0.25, "height": 0.6, "muscle": 0.4, "proportions": 0.6},
    {"name": "f_heavy", "gender": 1.0, "age": 0.8, "weight": 0.8, "height": 0.3, "muscle": 0.5, "proportions": 0.4},
    {"name": "f_short", "gender": 1.0, "age": 0.8, "weight": 0.55, "height": 0.1, "muscle": 0.5, "proportions": 0.5},
    {"name": "m_avg", "gender": 0.0, "age": 0.8, "weight": 0.5, "height": 0.3, "muscle": 0.5, "proportions": 0.5},
    {"name": "m_slim", "gender": 0.0, "age": 0.8, "weight": 0.25, "height": 0.4, "muscle": 0.4, "proportions": 0.5},
    {"name": "m_heavy", "gender": 0.0, "age": 0.8, "weight": 0.8, "height": 0.3, "muscle": 0.5, "proportions": 0.5},
    {"name": "m_muscular_tall", "gender": 0.0, "age": 0.8, "weight": 0.6, "height": 0.55, "muscle": 0.85, "proportions": 0.6},
]

EVALUATED = [
    M.SHOULDER_WIDTH, M.SLEEVE_LENGTH, M.TORSO_LENGTH, M.RISE, M.INSEAM, M.OUTSEAM,
    M.NECK, M.CHEST, M.WAIST, M.HIP, M.BICEP, M.WRIST, M.THIGH, M.CALF,
]  # underbust and armhole have no reliable mesh definition yet


# --- body generation --------------------------------------------------------------------------

def _rotation(axis: str, degrees: float):
    import torch

    a = math.radians(degrees)
    c, s = math.cos(a), math.sin(a)
    r = torch.eye(4)
    i, j = {"x": (1, 2), "y": (2, 0), "z": (0, 1)}[axis]
    r[i, i], r[i, j], r[j, i], r[j, j] = c, -s, s, c
    return r


@dataclass
class Body:
    name: str
    vertices: np.ndarray  # (N, 3) metres, z up, body faces -y
    faces: np.ndarray
    bones: dict[str, np.ndarray]
    gender: Gender


def make_bodies(specs: list[dict]) -> list[Body]:
    import anny
    import torch

    model = anny.Anny().to(dtype=torch.float32)
    labels = model.bone_labels
    pose = torch.eye(4)[None, None].repeat(1, model.bone_count, 1, 1)
    for side in "LR":  # rest pose has forearms forward; bring arms into the frontal plane
        pose[0, labels.index(f"upperarm01.{side}")] = _rotation("x", 22)
    faces = np.asarray(model.faces)

    bodies = []
    for spec in specs:
        phenotype = {k: 0.5 for k in model.phenotype_labels}
        phenotype.update({k: v for k, v in spec.items() if k in phenotype})
        out = model(pose_parameters=pose, phenotype_kwargs=phenotype)
        vertices = out["vertices"][0].detach().numpy().astype(np.float64)
        vertices[:, 2] -= vertices[:, 2].min()  # floor at z = 0
        heads = out["bone_poses"][0, :, :3, 3].detach().numpy().astype(np.float64)
        heads[:, 2] -= out["vertices"][0, :, 2].min().item()
        bones = {name: heads[i] for i, name in enumerate(labels)}
        gender = Gender.FEMALE if spec["gender"] >= 0.5 else Gender.MALE  # Anny: 0 = male
        bodies.append(Body(spec["name"], vertices, faces, bones, gender))
    return bodies


# --- rendering --------------------------------------------------------------------------------

def render(
    body: Body,
    view: str,
    distance_m: float = CAMERA_DISTANCE_M,
    camera_height_m: float = CAMERA_HEIGHT_M,
    fov_deg: float = VERTICAL_FOV_DEG,
    yaw_deg: float = 0.0,
) -> np.ndarray:
    """Flat-shaded painter's-algorithm render with a pinhole camera.

    `yaw_deg` turns the person about the vertical axis (not standing exactly square to the camera).
    """
    v = body.vertices
    if yaw_deg:
        a = math.radians(yaw_deg)
        rot = np.array([[math.cos(a), -math.sin(a), 0], [math.sin(a), math.cos(a), 0], [0, 0, 1.0]])
        v = v @ rot.T
    if view == "front":  # camera on -y looking +y (sees the body's front)
        cam = np.array([0.0, -distance_m, camera_height_m])
        right, forward = np.array([1.0, 0, 0]), np.array([0, 1.0, 0])
    else:  # camera on +x looking -x: body's left side
        cam = np.array([distance_m, 0.0, camera_height_m])
        right, forward = np.array([0, -1.0, 0]), np.array([-1.0, 0, 0])
    up = np.array([0, 0, 1.0])

    rel = v - cam
    depth = rel @ forward
    f = (IMAGE_H / 2) / math.tan(math.radians(fov_deg / 2))
    px = IMAGE_W / 2 + f * (rel @ right) / depth
    py = IMAGE_H / 2 - f * (rel @ up) / depth
    points = np.stack([px, py], 1)

    tri = body.faces
    a, b, c = v[tri[:, 0]], v[tri[:, 1]], v[tri[:, 2]]
    normals = np.cross(b - a, c - a)
    normals /= np.linalg.norm(normals, axis=1, keepdims=True) + 1e-12
    light = -forward + np.array([0.3, 0, 0.5])
    light /= np.linalg.norm(light)
    shade = 0.35 + 0.65 * np.abs(normals @ light)
    order = np.argsort(-depth[tri].mean(1))  # far → near

    image = np.full((IMAGE_H, IMAGE_W, 3), BACKGROUND, np.uint8)
    scaled = np.round(points * 16).astype(np.int32)  # 4-bit subpixel precision
    for i in order:
        colour = tuple(int(x) for x in SKIN * shade[i])
        cv2.fillConvexPoly(image, scaled[tri[i]], colour, lineType=cv2.LINE_AA, shift=4)
    return image


# --- ground truth from the mesh ---------------------------------------------------------------

def _loops(mesh, origin, normal) -> list[np.ndarray]:
    """Closed cross-section loops as 3-D point arrays."""
    section = mesh.section(plane_origin=origin, plane_normal=normal)
    if section is None:
        return []
    return [np.asarray(loop) for loop in section.discrete if len(loop) >= 3]


def _hull_perimeter(points_2d: np.ndarray) -> float:
    hull = cv2.convexHull(points_2d.astype(np.float32))
    return float(cv2.arcLength(hull, True))


def _horizontal(mesh, z: float) -> list[np.ndarray]:
    return [loop[:, :2] for loop in _loops(mesh, [0, 0, z], [0, 0, 1])]


def _torso_loop(loops: list[np.ndarray]) -> np.ndarray | None:
    central = [l for l in loops if abs(l[:, 0].mean()) < 0.05]
    return max(central, key=lambda l: cv2.contourArea(l.astype(np.float32)), default=None)


def _torso_perimeter(mesh, z: float) -> float:
    loop = _torso_loop(_horizontal(mesh, z))
    return _hull_perimeter(loop) if loop is not None else 0.0


def _leg_perimeters(mesh, z: float) -> list[float]:
    legs = [l for l in _horizontal(mesh, z) if 0.02 < abs(l[:, 0].mean()) < 0.3]
    legs = sorted(legs, key=lambda l: cv2.contourArea(l.astype(np.float32)), reverse=True)[:2]
    return [_hull_perimeter(l) for l in legs]


def _limb_perimeter(mesh, start: np.ndarray, end: np.ndarray, t: float) -> float:
    origin = start + (end - start) * t
    normal = (end - start) / np.linalg.norm(end - start)
    loops = _loops(mesh, origin, normal)
    if not loops:
        return float("nan")
    nearest = min(loops, key=lambda l: np.linalg.norm(l.mean(0) - origin))
    # project onto the plane to measure a 2-D perimeter
    u = np.cross(normal, [0, 0, 1.0]) if abs(normal[2]) < 0.9 else np.cross(normal, [1.0, 0, 0])
    u /= np.linalg.norm(u)
    w = np.cross(normal, u)
    rel = nearest - origin
    return _hull_perimeter(np.stack([rel @ u, rel @ w], 1))


def ground_truth(body: Body) -> tuple[float, dict[M, float]]:
    import trimesh

    mesh = trimesh.Trimesh(body.vertices, body.faces, process=False)
    b = body.bones
    height = float(body.vertices[:, 2].max())
    shoulder_z = (b["upperarm01.L"][2] + b["upperarm01.R"][2]) / 2
    hip_z = (b["upperleg01.L"][2] + b["upperleg01.R"][2]) / 2
    torso = shoulder_z - hip_z

    # crotch: first height (going down) where the lower torso splits into two legs
    crotch_z = hip_z
    for z in np.arange(hip_z + 0.05, hip_z - 0.25, -0.002):
        if len([l for l in _horizontal(mesh, z) if 0.01 < abs(l[:, 0].mean()) < 0.3]) >= 2 and _torso_loop(
            _horizontal(mesh, z)
        ) is None:
            crotch_z = float(z)
            break

    zs = np.arange(hip_z + 0.25 * torso, hip_z + 0.7 * torso, 0.003)
    waist_z = float(min(zs, key=lambda z: _torso_perimeter(mesh, z)))
    chest_zs = np.arange(shoulder_z - 0.45 * torso, shoulder_z - 0.15 * torso, 0.003)
    joint_span = abs(b["upperarm01.L"][0] - b["upperarm01.R"][0])

    def chest_perimeter(z: float) -> float:
        loop = _torso_loop(_horizontal(mesh, z))
        if loop is None or np.ptp(loop[:, 0]) > 1.15 * joint_span:  # arm merged into the slice
            return 0.0
        return _hull_perimeter(loop)

    chest = max(chest_perimeter(z) for z in chest_zs)
    hip = max(_torso_perimeter(mesh, z) for z in np.arange(crotch_z + 0.01, hip_z + 0.12, 0.003))
    neck = min(
        (p for p in (_torso_perimeter(mesh, z) for z in np.arange(b["neck01"][2], b["head"][2], 0.003)) if p > 0),
        default=float("nan"),
    )

    # shoulder breadth: across the shoulder caps just above the joints
    top = _torso_loop(_horizontal(mesh, shoulder_z + 0.04))
    shoulder_width = float(np.ptp(top[:, 0])) if top is not None else float("nan")
    acromion = [np.array([s * shoulder_width / 2, b[f"upperarm01.{k}"][1], shoulder_z + 0.04]) for s, k in ((1, "L"), (-1, "R"))]
    sleeve = np.mean([
        np.linalg.norm(acromion[i] - b[f"lowerarm01.{k}"]) + np.linalg.norm(b[f"lowerarm01.{k}"] - b[f"wrist.{k}"])
        for i, k in enumerate("LR")
    ])

    thigh = np.mean(_leg_perimeters(mesh, crotch_z - 0.03))
    knee_z, ankle_z = b["lowerleg01.L"][2], b["foot.L"][2]
    calf = max((np.mean(p) for p in (_leg_perimeters(mesh, z) for z in np.arange(ankle_z + 0.4 * (knee_z - ankle_z), knee_z, 0.004)) if p), default=float("nan"))
    bicep = np.nanmean([_limb_perimeter(mesh, b[f"upperarm01.{k}"], b[f"lowerarm01.{k}"], 0.45) for k in "LR"])
    wrist = np.nanmean([_limb_perimeter(mesh, b[f"lowerarm01.{k}"], b[f"wrist.{k}"], 0.9) for k in "LR"])

    cm = 100.0
    truth = {
        M.SHOULDER_WIDTH: shoulder_width * cm,
        M.SLEEVE_LENGTH: sleeve * cm,
        M.TORSO_LENGTH: (b["neck01"][2] - waist_z) * cm,
        M.RISE: (waist_z - crotch_z) * cm,
        M.INSEAM: crotch_z * cm,
        M.OUTSEAM: waist_z * cm,
        M.NECK: neck * cm,
        M.CHEST: chest * cm,
        M.WAIST: _torso_perimeter(mesh, waist_z) * cm,
        M.HIP: hip * cm,
        M.BICEP: bicep * cm,
        M.WRIST: wrist * cm,
        M.THIGH: thigh * cm,
        M.CALF: calf * cm,
    }
    return height * cm, truth


# --- summary + calibration -------------------------------------------------------------------

def _fit_scale(pred: np.ndarray, truth: np.ndarray) -> float:
    """Least-squares multiplier k minimising |k * pred - truth|."""
    return float(pred @ truth / (pred @ pred))


def summarise(rows: list[dict], body_names: list[str], modes: list[str]) -> list[str]:
    """MAE per measurement and condition, before and after a per-measurement multiplier.

    The multiplier is evaluated with 2-fold cross-validation over bodies (fit on one half, test on
    the other) so the calibrated error is not measured on the bodies it was fitted to.
    """
    fold_of = {name: i % 2 for i, name in enumerate(body_names)}
    lines = []
    for mode in modes:
        lines += [
            f"### {mode}",
            "",
            "| Measurement | Mean truth (cm) | MAE raw (cm) | Mean error raw (cm) | MAE calibrated, 2-fold CV (cm) | Multiplier (all bodies) |",
            "|---|---|---|---|---|---|",
        ]
        rejected = [r for r in rows if r["mode"] == mode and r.get("measurement") == "ALL"]
        for type_ in EVALUATED:
            data = [r for r in rows if r.get("measurement") == type_.value and r["mode"] == mode]
            if not data:
                continue
            pred = np.array([r["predicted_cm"] for r in data], float)
            truth = np.array([r["truth_cm"] for r in data], float)
            folds = np.array([fold_of[r["body"]] for r in data])
            calibrated = np.empty_like(pred)
            for fold in (0, 1):
                train, test = folds != fold, folds == fold
                k = _fit_scale(pred[train], truth[train]) if train.any() else 1.0
                calibrated[test] = k * pred[test]
            lines.append(
                f"| {type_.value} | {truth.mean():.1f} | {np.abs(pred - truth).mean():.1f} | "
                f"{(pred - truth).mean():+.1f} | {np.abs(calibrated - truth).mean():.1f} | "
                f"{_fit_scale(pred, truth):.3f} |"
            )
        if rejected:
            lines.append(f"\nRejected photos: {', '.join(r['body'] + ' (' + r['error'] + ')' for r in rejected)}")
        lines.append("")
    return lines


# --- clothing simulation ----------------------------------------------------------------------

# Garment thickness (m) pushed outward along the surface normal. "Loose" is a rough stand-in for
# an oversized top and wide trousers; real fabric drapes and folds, which this does not model.
CONDITIONS: dict[str, dict[str, float] | None] = {
    "underwear": None,
    "tight": {"top": 0.005, "pants": 0.005},
    "loose": {"top": 0.04, "pants": 0.04},
}


def dress(body: Body, offsets: dict[str, float] | None) -> Body:
    """Returns a copy of `body` with a top (torso + sleeves to the elbow) and trousers."""
    if offsets is None:
        return body
    import trimesh

    v = body.vertices
    normals = trimesh.Trimesh(v, body.faces, process=False).vertex_normals
    b = body.bones
    neck_z = b["neck01"][2]
    hip_z = (b["upperleg01.L"][2] + b["upperleg01.R"][2]) / 2
    ankle_z = (b["foot.L"][2] + b["foot.R"][2]) / 2 + 0.03
    torso_half = abs(b["upperarm01.L"][0])  # shoulder joint x

    def along(side: str, start: str, end: str) -> np.ndarray:
        """Position of each vertex along a bone, 0 at `start`, 1 at `end`."""
        a, e = b[f"{start}.{side}"], b[f"{end}.{side}"]
        return (v - a) @ (e - a) / ((e - a) @ (e - a))

    on_arm = {}
    for side, sign in (("L", 1), ("R", -1)):
        lateral = sign * v[:, 0] > torso_half * 0.85
        t = along(side, "upperarm01", "lowerarm01")
        on_arm[side] = lateral & (t > 0.05)
    arm_any = on_arm["L"] | on_arm["R"]
    sleeve = np.zeros(len(v), bool)
    for side in ("L", "R"):
        sleeve |= on_arm[side] & (along(side, "upperarm01", "lowerarm01") <= 1.0)

    top = ((v[:, 2] < neck_z - 0.02) & (v[:, 2] > hip_z - 0.08) & ~arm_any) | sleeve
    pants = (v[:, 2] <= hip_z + 0.10) & (v[:, 2] > ankle_z) & ~arm_any

    thickness = np.zeros(len(v))
    thickness[top] = offsets["top"]
    thickness[pants] = np.maximum(thickness[pants], offsets["pants"])
    return Body(body.name, v + normals * thickness[:, None], body.faces, body.bones, body.gender)


# --- insight agreement (mirrors buildInsights in frontend/.../data/BodyAnalyzer.kt) -------------

@dataclass(frozen=True)
class InsightRule:
    """Mirrors InsightThresholds + buildInsights in the Android app."""

    ratio: object  # ({MeasurementType: cm}, height) -> float
    threshold: float
    margin: float
    higher_is_positive: bool
    two_sided: bool  # shows an opposite sentence when clearly on the other side (long legs / long torso)
    regions: tuple[Region, ...] = ()  # hidden when loose clothing is detected in any of these regions

    def truth_side(self, m, h) -> int:
        """+1 / -1: which side of the threshold the body really is on (no margin)."""
        above = self.ratio(m, h) >= self.threshold
        return 1 if above == self.higher_is_positive else -1

    def shown_side(self, m, h) -> int:
        """+1 positive sentence, -1 opposite sentence (two-sided only), 0 nothing shown."""
        r = self.ratio(m, h)
        sign = 1 if self.higher_is_positive else -1
        if sign * (r - self.threshold) >= self.margin:
            return 1
        if self.two_sided and sign * (self.threshold - r) >= self.margin:
            return -1
        return 0


INSIGHT_RULES = {
    "leg_proportion": InsightRule(lambda m, h: m[M.INSEAM] / h, 0.46, 0.01, True, True, (Region.BOTTOM,)),
    "broad_shoulders": InsightRule(lambda m, h: m[M.SHOULDER_WIDTH] / h, 0.255, 0.01, True, False, (Region.TOP,)),
    "lower_body_volume": InsightRule(lambda m, h: m[M.HIP] / m[M.CHEST], 1.05, 0.05, True, False, (Region.TOP, Region.BOTTOM)),
    "defined_waist": InsightRule(lambda m, h: m[M.WAIST] / m[M.HIP], 0.75, 0.05, False, False, (Region.TOP, Region.BOTTOM)),
}


def insight_table(insight_rows: list[dict], modes: list[str]) -> list[str]:
    lines = [
        "### Insight agreement with the true body",
        "",
        "Per body: *correct* = a sentence is shown and matches the true body; *wrong* = a sentence is shown",
        "and contradicts it; *missed* = the true body qualifies for a sentence but none is shown (inside the",
        "margin, or hidden because loose clothing was detected in its region).",
        "",
        "| Condition | Insight | Correct | Wrong on screen | Missed |",
        "|---|---|---|---|---|",
    ]
    for mode in modes:
        for name in INSIGHT_RULES:
            data = [r for r in insight_rows if r["mode"] == mode and r["insight"] == name]
            if not data:
                continue
            correct = sum(r["shown"] != 0 and r["shown"] == r["truth"] for r in data)
            wrong = sum(r["shown"] != 0 and r["shown"] != r["truth"] for r in data)
            missed = sum(r["shown"] == 0 and (r["truth"] == 1 or r["two_sided"]) for r in data)
            lines.append(f"| {mode} | {name} | {correct} | {wrong} | {missed} |")
    return lines + [""]


def detection_table(detections: list[dict]) -> list[str]:
    lines = ["### Detected clothing", "", "| Simulated condition | Bodies | Top flagged loose | Bottom flagged loose |", "|---|---|---|---|"]
    for condition in CONDITIONS:
        data = [d for d in detections if d["condition"] == condition]
        if data:
            top = sum(d["clothing"].top_loose for d in data)
            bottom = sum(d["clothing"].bottom_loose for d in data)
            lines.append(f"| {condition} | {len(data)} | {top} | {bottom} |")
    return lines + [""]


# --- main -------------------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=Path("private/synthetic_benchmark"))
    parser.add_argument("--bodies", type=int, default=len(BODIES))
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    started = time.time()
    print("loading Anny and generating bodies…", flush=True)
    bodies = make_bodies(BODIES[: args.bodies])
    estimator = MediaPipePoseEstimator()
    # Both pipelines detect clothing; "learned" also applies the learned measurement correction.
    detector = ClothingDetector.load()
    pipelines = {"geometry": BodyAnalysisPipeline(estimator, None, detector)}
    corrector = MeasurementCorrector.load()
    if corrector is not None:
        pipelines["learned"] = BodyAnalysisPipeline(estimator, corrector, detector)
    detections = []
    modes = [f"{c} / {p}" for c in CONDITIONS for p in pipelines]

    rows, insight_rows = [], []
    for body in bodies:
        height_cm, truth = ground_truth(body)  # always the unclothed body
        for condition, offsets in CONDITIONS.items():
            dressed = dress(body, offsets)
            front, side = render(dressed, "front"), render(dressed, "side")
            input_ = AnalysisInput(round(height_cm, 1), None, body.gender)  # clothing is detected
            for pipeline_name, pipeline in pipelines.items():
                mode = f"{condition} / {pipeline_name}"
                try:
                    result, raw = pipeline.analyze_images(front, side, input_)
                except AnalysisError as error:
                    print(f"{body.name} {mode}: rejected ({error.photo}: {error.code})", flush=True)
                    rows.append({"body": body.name, "mode": mode, "measurement": "ALL", "error": error.code})
                    continue
                if pipeline_name == "geometry" and condition in ("underwear", "loose"):
                    for view, image in (("front", front), ("side", side)):
                        pose = estimator.estimate(image)
                        overlay = draw_overlay(image, pose.mask, pose.landmarks, raw, view)
                        name = f"{body.name}_{condition}_{view}_overlay.png"
                        cv2.imwrite(str(args.out / name), cv2.cvtColor(overlay, cv2.COLOR_RGB2BGR))
                predicted = {m.type: m.value_cm for m in result.measurements}
                if pipeline_name == "geometry":
                    detections.append({"condition": condition, "offsets": offsets, "clothing": result.clothing})
                for type_ in EVALUATED:
                    if type_ not in predicted or not np.isfinite(truth[type_]):
                        continue
                    rows.append({
                        "body": body.name,
                        "mode": mode,
                        "measurement": type_.value,
                        "truth_cm": round(truth[type_], 1),
                        "predicted_cm": predicted[type_],
                        "error_cm": round(predicted[type_] - truth[type_], 1),
                    })
                for name, rule in INSIGHT_RULES.items():
                    hidden = bool(set(rule.regions) & result.clothing.loose_regions)
                    insight_rows.append({
                        "mode": mode,
                        "insight": name,
                        "truth": rule.truth_side(truth, height_cm),
                        "shown": 0 if hidden else rule.shown_side(predicted, height_cm),
                        "two_sided": rule.two_sided,
                    })
        print(f"{body.name}: height {height_cm:.1f} cm done", flush=True)
    estimator.close()

    with open(args.out / "results.csv", "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=["body", "mode", "measurement", "truth_cm", "predicted_cm", "error_cm", "error"])
        writer.writeheader()
        writer.writerows(rows)

    summary = "\n".join(
        summarise(rows, [b.name for b in bodies], modes) + insight_table(insight_rows, modes) + detection_table(detections)
    )
    (args.out / "summary.md").write_text(summary + "\n", encoding="utf-8")
    print(summary)
    print(f"\n{len(bodies)} bodies × {len(modes)} conditions in {time.time() - started:.0f} s → {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

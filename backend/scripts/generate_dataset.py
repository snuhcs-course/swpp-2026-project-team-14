# AI-generated with Claude Code (Claude Opus 5.5), 2026-09-24, reviewed by Dongkun Moon
"""Generate a synthetic training set for the learned measurement correction.

    python scripts/generate_dataset.py --bodies 200 [--seed 7] [--out private/dataset]

For each random Anny body (NAVER, Apache 2.0) and each clothing condition, render front + side
photos with a randomised phone camera, run the geometric pipeline, and store its features next to
the ground-truth measurements from the unclothed mesh. One CSV row per body × condition.
Rows are appended as they are produced, so an interrupted run can be resumed with --resume.
"""

from __future__ import annotations

import argparse
import csv
import sys
import time
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import synthetic_benchmark as sb  # noqa: E402
from body_analysis.pipeline import BodyAnalysisPipeline  # noqa: E402
from body_analysis.pose import MediaPipePoseEstimator  # noqa: E402
from body_analysis.types import AnalysisError, AnalysisInput  # noqa: E402

BODY_DENSITY_KG_M3 = 1010  # average human body density


def random_specs(n: int, rng: np.random.Generator) -> list[dict]:
    specs = []
    for i in range(n):
        specs.append({
            "name": f"b{i:04d}",
            "gender": float(rng.choice([0.0, 1.0])),  # Anny: 0 = male, 1 = female
            "age": float(rng.uniform(0.65, 0.95)),  # adults
            "height": float(rng.uniform(0.0, 0.6)),  # ≈ 145–190 cm
            "weight": float(rng.uniform(0.1, 0.9)),
            "muscle": float(rng.uniform(0.2, 0.9)),
            "proportions": float(rng.uniform(0.2, 0.8)),
        })
    return specs


def random_clothing(condition: str, rng: np.random.Generator) -> dict[str, float] | None:
    if condition == "underwear":
        return None
    if condition == "tight":
        t = float(rng.uniform(0.003, 0.008))
        return {"top": t, "pants": t}
    # loose: independent top and trousers, from slim to very baggy
    return {"top": float(rng.uniform(0.02, 0.06)), "pants": float(rng.uniform(0.005, 0.06))}


def random_camera(rng: np.random.Generator) -> dict[str, float]:
    return {
        "distance_m": float(rng.uniform(2.0, 3.2)),
        "camera_height_m": float(rng.uniform(0.8, 1.4)),
        "fov_deg": float(rng.uniform(50, 70)),
        "front_yaw_deg": float(rng.uniform(-6, 6)),
        "side_yaw_deg": float(rng.uniform(-8, 8)),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bodies", type=int, default=200)
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--out", type=Path, default=Path("private/dataset"))
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    csv_path = args.out / "samples.csv"

    rng = np.random.default_rng(args.seed)
    specs = random_specs(args.bodies, rng)
    done = set()
    if args.resume and csv_path.exists():
        with open(csv_path, encoding="utf-8") as fh:
            done = {row["body"] for row in csv.DictReader(fh)}

    print("loading Anny…", flush=True)
    import trimesh

    estimator = MediaPipePoseEstimator()
    pipeline = BodyAnalysisPipeline(estimator)
    writer = None
    fh = open(csv_path, "a" if done else "w", newline="", encoding="utf-8")
    started = time.time()
    try:
        for index, spec in enumerate(specs):
            # draw randomness even for skipped bodies so a resumed run stays identical
            clothing = {c: random_clothing(c, rng) for c in ("underwear", "tight", "loose")}
            cameras = {c: random_camera(rng) for c in ("underwear", "tight", "loose")}
            if spec["name"] in done:
                continue
            body = sb.make_bodies([spec])[0]
            height_cm, truth = sb.ground_truth(body)
            weight_kg = abs(trimesh.Trimesh(body.vertices, body.faces, process=False).volume) * BODY_DENSITY_KG_M3

            for condition in ("underwear", "tight", "loose"):
                dressed = sb.dress(body, clothing[condition])
                cam = cameras[condition]
                common = dict(distance_m=cam["distance_m"], camera_height_m=cam["camera_height_m"], fov_deg=cam["fov_deg"])
                front = sb.render(dressed, "front", yaw_deg=cam["front_yaw_deg"], **common)
                side = sb.render(dressed, "side", yaw_deg=cam["side_yaw_deg"], **common)
                if index < 3:
                    cv2.imwrite(str(args.out / f"{spec['name']}_{condition}_front.png"), cv2.cvtColor(front, cv2.COLOR_RGB2BGR))
                input_ = AnalysisInput(round(height_cm, 1), None, body.gender)
                row = {
                    "body": spec["name"],
                    "condition": condition,
                    "gender": body.gender.value,
                    "height_cm": round(height_cm, 2),
                    "weight_kg": round(weight_kg, 2),
                    **{f"cam_{k}": round(v, 3) for k, v in cam.items()},
                    **{f"cloth_{k}": v for k, v in (clothing[condition] or {"top": 0.0, "pants": 0.0}).items()},
                    **{f"truth_{t.value}": round(v, 2) for t, v in truth.items()},
                }
                try:
                    result, raw = pipeline.analyze_images(front, side, input_)
                    row["error"] = ""
                    row.update({f"pred_{m.type.value}": m.value_cm for m in result.measurements})
                    row.update({k: round(v, 5) for k, v in raw.features.items()})
                except AnalysisError as error:
                    row["error"] = error.code
                if writer is None:
                    fieldnames = list(row) + [k for k in _all_columns() if k not in row]
                    writer = csv.DictWriter(fh, fieldnames=fieldnames, extrasaction="ignore")
                    if not done:
                        writer.writeheader()
                writer.writerow(row)
            fh.flush()
            elapsed = time.time() - started
            print(f"{spec['name']} ({index + 1}/{len(specs)}) height {height_cm:.0f} cm, {elapsed:.0f} s", flush=True)
    finally:
        fh.close()
        estimator.close()
    return 0


def _all_columns() -> list[str]:
    """Every feature/prediction column, so rows from bodies with missing arms still line up."""
    from body_analysis.measurer import PROFILE_LEVELS
    from body_analysis.types import MeasurementType

    cols = [f"pred_{t.value}" for t in MeasurementType] + [f"geo_{t.value}" for t in MeasurementType]
    for level in PROFILE_LEVELS:
        cols += [f"w_{level:.3f}", f"d_{level:.3f}"]
    cols += [f"lm_{n}" for n in ("left_shoulder", "left_hip", "left_knee", "left_ankle", "left_elbow", "left_wrist", "nose")]
    cols += ["row_waist", "row_crotch", "row_chest", "row_hip", "shoulder_spread", "hip_joint_spread", "arms_found"]
    return cols


if __name__ == "__main__":
    raise SystemExit(main())

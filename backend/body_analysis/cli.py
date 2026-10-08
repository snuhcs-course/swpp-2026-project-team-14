# AI-generated with Claude Code (Claude Opus 5.5), 2026-09-24, reviewed by Dongkun Moon
"""Run the pipeline on local photos.

    python -m body_analysis.cli --front front.jpg --side side.jpg --height 172 --weight 65 \
        --gender male --debug-dir out/

Prints the API-shaped JSON. With --debug-dir, also writes overlay images (person mask, landmarks,
measured lines) so you can check *where* each value was measured. Photos never leave this machine.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

from .clothing import ClothingDetector
from .measurer import RawMeasurements
from .pipeline import BodyAnalysisPipeline, decode_image
from .pose import MediaPipePoseEstimator
from .regressor import MeasurementCorrector
from .types import AnalysisError, AnalysisInput, Gender

GUIDE_COLORS = {"front": (255, 90, 40), "side": (40, 160, 255)}


def draw_overlay(image_rgb: np.ndarray, mask: np.ndarray, landmarks, raw: RawMeasurements, view: str) -> np.ndarray:
    import cv2

    out = image_rgb.copy()
    tint = np.zeros_like(out)
    tint[mask] = (60, 200, 120)
    out = cv2.addWeighted(out, 1.0, tint, 0.35, 0)
    for point in landmarks.values():
        cv2.circle(out, (int(point.x), int(point.y)), 4, (255, 255, 255), -1)
    for guide in raw.guides:
        if guide.view != view:
            continue
        y = int(guide.y)
        cv2.line(out, (int(guide.x0), y), (int(guide.x1), y), GUIDE_COLORS[view], 2)
        if view == "front":
            cv2.putText(out, guide.label, (int(guide.x1) + 4, y + 4), cv2.FONT_HERSHEY_SIMPLEX, 0.4, GUIDE_COLORS[view], 1)
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--front", type=Path, required=True)
    parser.add_argument("--side", type=Path, required=True)
    parser.add_argument("--height", type=float, required=True, help="cm")
    parser.add_argument("--weight", type=float, help="kg")
    parser.add_argument("--gender", choices=[g.value for g in Gender], default=Gender.UNSPECIFIED.value)
    parser.add_argument("--debug-dir", type=Path, help="write overlay PNGs here")
    parser.add_argument("--geometry-only", action="store_true", help="skip the learned models")
    args = parser.parse_args(argv)

    missing = [str(p) for p in (args.front, args.side) if not p.is_file()]
    if missing:
        parser.error(f"photo not found: {', '.join(missing)} (current folder: {Path.cwd()})")

    input_ = AnalysisInput(args.height, args.weight, Gender(args.gender))
    estimator = MediaPipePoseEstimator()
    if args.geometry_only:
        pipeline = BodyAnalysisPipeline(estimator)
    else:
        pipeline = BodyAnalysisPipeline(estimator, MeasurementCorrector.load(), ClothingDetector.load())
    try:
        front = decode_image(args.front.read_bytes())
        side = decode_image(args.side.read_bytes())
        result, raw = pipeline.analyze_images(front, side, input_)
    except AnalysisError as error:
        print(json.dumps({"error": error.code, "photo": error.photo, "hint": error.hint}, ensure_ascii=False, indent=2))
        return 2

    print(json.dumps(result.to_dict(), ensure_ascii=False, indent=2))

    if args.debug_dir:
        import cv2

        args.debug_dir.mkdir(parents=True, exist_ok=True)
        for view, image in (("front", front), ("side", side)):
            pose = estimator.estimate(image)
            overlay = draw_overlay(image, pose.mask, pose.landmarks, raw, view)
            path = args.debug_dir / f"{view}_overlay.png"
            cv2.imwrite(str(path), cv2.cvtColor(overlay, cv2.COLOR_RGB2BGR))
            print(f"wrote {path}", file=sys.stderr)
    estimator.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-02, reviewed by Dongkun Moon
"""Renders the benchmark bodies as demo photos and exports their true measurements.

    python scripts/export_benchmark_reference.py [--out private/benchmark_photos]

Writes
- `private/benchmark_photos/{body}_front.png` / `_side.png`: underwear renders with the benchmark
  marker in the top-left corner, to pick in the app (git-ignored like all body photos), and
- `body_analysis/models/benchmark_reference.json`: each body's true height and measurements from
  the mesh, in marker order, so the server can show real accuracy for these photos
  (body_analysis/reference.py).

Use the printed height when analysing a body in the app; the accuracy is only meaningful then.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from body_analysis.reference import DEFAULT_REFERENCE_PATH, draw_marker, read_marker  # noqa: E402
from synthetic_benchmark import BODIES, CONDITIONS, EVALUATED, dress, ground_truth, make_bodies, render  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=Path("private/benchmark_photos"))
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    print("loading Anny and generating bodies…", flush=True)
    entries = []
    for index, body in enumerate(make_bodies(BODIES)):
        height_cm, truth = ground_truth(body)
        dressed = dress(body, CONDITIONS["underwear"])
        for view in ("front", "side"):
            image = draw_marker(render(dressed, view), index, view, body.name)
            assert read_marker(image) == (index, view)
            cv2.imwrite(str(args.out / f"{body.name}_{view}.png"), cv2.cvtColor(image, cv2.COLOR_RGB2BGR))
        entries.append({
            "body": body.name,
            "gender": body.gender.value,
            "height_cm": round(height_cm, 1),
            "measurements": {t.value: round(truth[t], 1) for t in EVALUATED if np.isfinite(truth[t])},
        })
        print(f"{body.name}: {body.gender.value}, height {height_cm:.1f} cm", flush=True)

    DEFAULT_REFERENCE_PATH.write_text(
        json.dumps({"version": "benchmark-1", "bodies": entries}, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(f"wrote {DEFAULT_REFERENCE_PATH} and photos in {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

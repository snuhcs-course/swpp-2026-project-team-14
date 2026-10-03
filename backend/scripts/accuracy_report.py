"""Accuracy of the shipped pipeline on every benchmark photo with known measurements.

    python scripts/accuracy_report.py [--out ../docs/body-analysis/07-accuracy-report.md]

For the 8 benchmark bodies (scripts/synthetic_benchmark.py) in underwear, tight and loose clothing:
- runs the pipeline exactly as the server does (MediaPipe + clothing detector + learned correction),
  with each body's true height and gender and no weight,
- for underwear, analyses the marked demo photos from private/benchmark_photos after a JPEG
  round trip like the app's re-encoding, i.e. the numbers the app shows as "정확도 %",
- for tight and loose clothing, renders the clothed bodies (no demo photos exist for those),
- writes a Markdown report with accuracy = 100 − |estimate − truth| / truth × 100 per measurement.

Needs the benchmark extras (requirements-benchmark.txt) and the demo photos from
scripts/export_benchmark_reference.py.
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from body_analysis.clothing import ClothingDetector  # noqa: E402
from body_analysis.pipeline import BodyAnalysisPipeline  # noqa: E402
from body_analysis.pose import MediaPipePoseEstimator  # noqa: E402
from body_analysis.reference import ReferenceSet  # noqa: E402
from body_analysis.regressor import MeasurementCorrector  # noqa: E402
from body_analysis.types import AnalysisError, AnalysisInput, MeasurementType as M  # noqa: E402
from synthetic_benchmark import BODIES, CONDITIONS, EVALUATED, dress, ground_truth, make_bodies, render  # noqa: E402

PHOTOS = Path("private/benchmark_photos")
LABELS = {
    M.SHOULDER_WIDTH: "Shoulder width (어깨너비)", M.SLEEVE_LENGTH: "Sleeve (소매길이)",
    M.TORSO_LENGTH: "Torso length (상체길이)", M.RISE: "Rise (밑위길이)", M.INSEAM: "Inseam (안쪽 다리길이)",
    M.OUTSEAM: "Outseam (바깥 다리길이)", M.NECK: "Neck (목둘레)", M.CHEST: "Chest (가슴둘레)",
    M.WAIST: "Waist (허리둘레)", M.HIP: "Hip (엉덩이둘레)", M.BICEP: "Bicep (팔뚝둘레)",
    M.WRIST: "Wrist (손목둘레)", M.THIGH: "Thigh (허벅지둘레)", M.CALF: "Calf (종아리둘레)",
}


def accuracy(estimate: float, truth: float) -> float:
    return max(0.0, 100.0 - abs(estimate - truth) / truth * 100.0)


def as_app_upload(path: Path) -> bytes:
    """The app decodes the photo (960×1280 stays full size) and re-encodes it as JPEG quality 90."""
    ok, data = cv2.imencode(".jpg", cv2.imread(str(path)), [cv2.IMWRITE_JPEG_QUALITY, 90])
    assert ok
    return data.tobytes()


def fmt(values: list[float]) -> str:
    return f"{np.mean(values):.1f}" if values else "–"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=Path("../docs/body-analysis/07-accuracy-report.md"))
    args = parser.parse_args()
    started = time.time()

    references = ReferenceSet.load()
    pipeline = BodyAnalysisPipeline(
        MediaPipePoseEstimator(), MeasurementCorrector.load(), ClothingDetector.load(), references
    )
    print("loading Anny and generating bodies…", flush=True)
    bodies = make_bodies(BODIES)

    # rows: (condition, body, measurement, truth, estimate); notes: per-run remarks
    rows, notes, flagged = [], [], {}
    for body in bodies:
        height_cm, truth = ground_truth(body)
        input_ = AnalysisInput(round(height_cm, 1), None, body.gender)
        for condition, offsets in CONDITIONS.items():
            try:
                if condition == "underwear":
                    result = pipeline.analyze(
                        as_app_upload(PHOTOS / f"{body.name}_front.png"),
                        as_app_upload(PHOTOS / f"{body.name}_side.png"),
                        input_,
                    )
                    if result.reference is None or result.reference.body != body.name:
                        notes.append(f"{body.name}: demo photos were not recognised as this body")
                else:
                    dressed = dress(body, offsets)
                    result, _ = pipeline.analyze_images(render(dressed, "front"), render(dressed, "side"), input_)
            except AnalysisError as error:
                notes.append(f"{body.name} / {condition}: rejected ({error.photo}: {error.code})")
                continue
            flagged[(condition, body.name)] = sorted(r.value for r in result.clothing.loose_regions)
            for m in result.measurements:
                if m.type in EVALUATED and np.isfinite(truth[m.type]):
                    rows.append((condition, body.name, m.type, truth[m.type], m.value_cm))
        print(f"{body.name} done", flush=True)

    names = [b.name for b in bodies]
    acc = {(c, b, t): accuracy(e, tr) for c, b, t, tr, e in rows}
    err = {(c, b, t): abs(e - tr) for c, b, t, tr, e in rows}

    def values(table, condition=None, body=None, type_=None):
        return [v for (c, b, t), v in table.items()
                if (condition is None or c == condition) and (body is None or b == body) and (type_ is None or t == type_)]

    lines = [
        "# Body Analysis — Accuracy on the Benchmark Bodies",
        "",
        f"> Generated by `backend/scripts/accuracy_report.py` on {time.strftime('%Y-%m-%d')} · pipeline "
        f"`{pipeline.estimator.__class__.__name__}` + clothing detector + learned correction (as deployed)",
        "",
        "Accuracy per measurement = 100 − |estimate − truth| / truth × 100. This is the same number the app",
        "shows as \"정확도 %\" for benchmark photos. Truth comes from the 3D mesh of each body",
        "(`04-synthetic-benchmark.md` §3); every run uses the body's true height and gender and no weight.",
        "",
        "## 1. Summary",
        "",
        "| Clothing | Mean accuracy | Lowest single value | Mean abs. error (cm) | Measurements |",
        "|---|---|---|---|---|",
    ]
    for c in CONDITIONS:
        a, e = values(acc, c), values(err, c)
        if a:
            lines.append(f"| {c} | **{np.mean(a):.1f} %** | {min(a):.1f} % | {np.mean(e):.2f} | {len(a)} |")
    lines += [
        "",
        "- **underwear** = the marked demo photos in `private/benchmark_photos/` after a JPEG round trip, i.e.",
        "  exactly what the app shows. **tight / loose** = the same bodies rendered with simulated clothing.",
        "- In loose clothing the detector flags the garment and the app lowers confidence and warns",
        "  (`06-clothing-detection.md`); those values are shown here for completeness, not as a target.",
        "- \"Measurements\" counts the values the app showed (8 bodies × 14). Fewer in loose clothing because",
        "  the bicep is left out under a loose top (the sleeve covers the arm) and, where the arms merge into",
        "  the sleeves, the wrist too (warning `arms_touching_body`). Missing values are not counted as errors.",
        "",
        "## 2. Underwear, per body and measurement (what the app shows)",
        "",
        "| Measurement | " + " | ".join(names) + " | Mean | Min |",
        "|---|" + "---|" * (len(names) + 2),
    ]
    for t in EVALUATED:
        cells = [f"{acc[('underwear', b, t)]:.1f}" if ("underwear", b, t) in acc else "–" for b in names]
        a = values(acc, "underwear", type_=t)
        if a:
            lines.append(f"| {LABELS[t]} | " + " | ".join(cells) + f" | **{fmt(a)}** | {min(a):.1f} |")
    lines.append("| **Mean (body)** | " + " | ".join(f"**{fmt(values(acc, 'underwear', body=b))}**" for b in names)
                 + f" | **{fmt(values(acc, 'underwear'))}** | |")
    lines += [
        "",
        "## 3. Per measurement and clothing",
        "",
        "Mean accuracy % (mean abs. error in cm) over the 8 bodies.",
        "",
        "| Measurement | " + " | ".join(CONDITIONS) + " |",
        "|---|" + "---|" * len(CONDITIONS),
    ]
    for t in EVALUATED:
        cells = []
        for c in CONDITIONS:
            a, e = values(acc, c, type_=t), values(err, c, type_=t)
            cells.append(f"{np.mean(a):.1f} ({np.mean(e):.1f})" if a else "–")
        lines.append(f"| {LABELS[t]} | " + " | ".join(cells) + " |")
    lines += [
        "",
        "## 4. Clothing detected per run",
        "",
        "| Clothing | " + " | ".join(names) + " |",
        "|---|" + "---|" * len(names),
    ]
    for c in CONDITIONS:
        cells = [", ".join(flagged.get((c, b), [])) or ("fitted" if (c, b) in flagged else "rejected") for b in names]
        lines.append(f"| {c} | " + " | ".join(cells) + " |")
    lines += [
        "",
        "## 5. Notes from this run",
        "",
        *([f"- {n}" for n in notes] or ["- Every photo was analysed; every demo photo was recognised as its own body."]),
        "",
        "## 6. How to read these numbers",
        "",
        "- **Synthetic, so optimistic.** The bodies, the renders and the training data of the learned",
        "  correction all come from the same 3D model (Anny), and the photos are perfectly framed with a",
        "  plain background. Real phone photos will be less accurate; validating on real photos with tape",
        "  measurements is the next step (`03-benchmark-plan.md`).",
        "- **Not training data.** The 8 bodies are fixed specs; the correction was trained on 240 randomly",
        "  sampled bodies (`05-learned-correction.md`). The stricter held-out result is in `05` §4 and `06` §5.",
        "- **Height matters.** Every value scales with the entered height; entering a wrong height lowers the",
        "  accuracy shown in the app accordingly.",
        "- Underbust and armhole are not listed: there is no reliable mesh definition for them yet.",
        "",
        f"Run time: {time.time() - started:.0f} s.",
        "",
    ]
    args.out.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {args.out}")
    for c in CONDITIONS:
        print(f"{c}: mean {fmt(values(acc, c))} %")
    for n in notes:
        print("NOTE:", n)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

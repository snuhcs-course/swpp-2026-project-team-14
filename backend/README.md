# StyleMate backend — body analysis

`body_analysis/` turns a front photo (+ optional side photo) and the user's height into garment measurements.
It is a plain Python package with no Django dependency. The Django REST app will import it once the backend project (P7) exists.
Design: [`docs/body-analysis/02-design.md`](../docs/body-analysis/02-design.md).

## Setup (Windows, Python 3.11)

```bash
cd backend
py -3.11 -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt
mkdir models
curl -L -o models/pose_landmarker_heavy.task https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/latest/pose_landmarker_heavy.task
```

## Test

```bash
.venv\Scripts\python -m pytest
```

Tests use a synthetic mannequin (`tests/synthetic.py`) with known dimensions instead of real body photos.
`test_mediapipe_smoke.py` checks that the real model loads, and is skipped if the model is not downloaded.

## Try it on your own photos

Put photos in `backend/private/`. That folder is git-ignored, so **never commit body photos**.

```bash
.venv\Scripts\python -m body_analysis.cli --front private/front.jpg --side private/side.jpg --height 172 --weight 65 --gender male --clothing underwear --debug-dir private/out
```

- Prints the same JSON the API will return.
- `private/out/front_overlay.png` shows the person mask, landmarks and every line that was measured. Use it to check *where* each value came from.

## Layout

| Module | Role |
|---|---|
| `types.py` | measurement keys, confidence levels, inputs/results, `AnalysisError` codes + Korean hints |
| `pose.py` | `PoseEstimator` protocol, MediaPipe BlazePose (heavy) with segmentation mask |
| `geometry.py` | mask helpers: segments per row, widths, ellipse perimeter |
| `measurer.py` | landmarks + masks → 16 measurements. Contains `CALIBRATION` / `DEPTH_RATIO` constants (to be fitted in the benchmark) |
| `pipeline.py` | decode in memory → quality gate → measure → confidence + warnings |
| `cli.py` | local runner with debug overlays |

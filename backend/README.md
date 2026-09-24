# StyleMate backend — body analysis

`body_analysis/` turns a front photo, a side photo and the user's height into garment measurements.
It is a plain Python package with no Django dependency. `body_profiles/` (Django) exposes it over HTTP for the Android app.
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

## Run the API server (for the Android app)

`stylemate_server/` + `body_profiles/` is a minimal Django project that serves the body-analysis endpoint:
- `POST /api/body-profile/analyze/` (contract in `docs/body-analysis/02-design.md` §7)
- multipart fields: `front_photo`, `side_photo`, `height_cm`, and optionally `weight_kg`, `gender`, `clothing`
- `422` responses carry an `error` code and a Korean `hint`
- uploads are kept in memory only; nothing is written to disk

When the team's Django project (P7) exists, add `body_profiles` to its `INSTALLED_APPS`/urls and copy the upload settings from `stylemate_server/settings.py`.

```bash
.venv\Scripts\python manage.py runserver 0.0.0.0:8000
```

- **Android emulator:** the app's default base URL `http://10.0.2.2:8000` reaches this server.
- **Real phone (Galaxy S23):**
  - The phone and PC must be on the same Wi-Fi.
  - Add `stylemate.apiBaseUrl=http://<PC IP>:8000` to `android/local.properties`; find the PC's IP with `ipconfig`.
  - Allow Python through the Windows firewall when prompted.
- Debug builds allow plain HTTP. Release builds need HTTPS.

Quick check with the synthetic renders:

```bash
curl -X POST http://127.0.0.1:8000/api/body-profile/analyze/ -F front_photo=@private/synthetic_benchmark/m_avg_front.png -F side_photo=@private/synthetic_benchmark/m_avg_side.png -F height_cm=168 -F gender=male -F clothing=tight
```

`android/app/src/test/resources/analyze_response_*.json` are real responses captured this way. The Android `ServerContractTest` parses them, so re-capture them when the API changes.

## Try it on your own photos

Put photos in `backend/private/`. That folder is git-ignored, so **never commit body photos**.

```bash
.venv\Scripts\python -m body_analysis.cli --front private/front.jpg --side private/side.jpg --height 172 --weight 65 --gender male --clothing underwear --debug-dir private/out
```

- Prints the same JSON the API will return.
- `private/out/front_overlay.png` shows the person mask, landmarks and every line that was measured. Use it to check *where* each value came from.

## Synthetic benchmark (no real photos needed)

Renders 8 Anny 3D bodies (NAVER, Apache 2.0), measures them with the pipeline and compares against measurements taken from the 3D mesh.
Method and results: [`docs/body-analysis/04-synthetic-benchmark.md`](../docs/body-analysis/04-synthetic-benchmark.md).

```bash
.venv\Scripts\python -m pip install torch==2.14.0 --index-url https://download.pytorch.org/whl/cpu
.venv\Scripts\python -m pip install -r requirements-benchmark.txt
.venv\Scripts\python scripts/synthetic_benchmark.py
```

Outputs go to `private/synthetic_benchmark/`:
- rendered photos and overlays
- `results.csv` (per body and per measurement)
- `summary.md` (MAE before and after cross-validated calibration)

The first run spends about 1–2 minutes compiling Anny's skinning kernels.

## Learned correction model

The server applies `body_analysis/models/measurement_corrector.json`, a ridge regression per measurement trained on 240 synthetic bodies, on top of the geometry (underwear and tight clothing only).
- Results and method: [`docs/body-analysis/05-learned-correction.md`](../docs/body-analysis/05-learned-correction.md).
- Retrain after changing measurer features:

```bash
.venv\Scripts\python scripts/generate_dataset.py --bodies 240
.venv\Scripts\python scripts/train_corrector.py --export
```

- `--geometry-only` on the CLI skips the model.

## Layout

| Module | Role |
|---|---|
| `types.py` | measurement keys, confidence levels, inputs/results, `AnalysisError` codes + Korean hints |
| `pose.py` | `PoseEstimator` protocol, MediaPipe BlazePose (heavy) with segmentation mask |
| `geometry.py` | mask helpers: segments per row, widths, ellipse perimeter |
| `measurer.py` | landmarks + masks → 16 measurements and the scale-free features for the learned model. Contains the `CALIBRATION` constants |
| `pipeline.py` | decode in memory → quality gate → measure → learned correction (optional) → confidence + warnings |
| `regressor.py` | loads and applies the learned correction model (numpy only) |
| `cli.py` | local runner with debug overlays |

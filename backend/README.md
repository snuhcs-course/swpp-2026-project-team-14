# StyleMate backend

The team backend: a Django project (`config/` + `apps/`) deployed to the team14 Kubernetes namespace by
`.github/workflows/backend-release.yaml` → GHCR image → ArgoCD (`infra/`).

| Endpoint | Purpose |
|---|---|
| `GET /healthz/` | Kubernetes readiness/liveness probe |
| `GET /api/hello/` | smoke test (kept from the original `app.py`) |
| `POST /api/body-profile/analyze/` | body measurements from front + side photos (`apps/body_profiles/`) |

`body_analysis/` turns a front photo, a side photo and the user's height into garment measurements.
It is a plain Python package with no Django dependency.
Design: [`docs/body-analysis/02-design.md`](../docs/body-analysis/02-design.md).

## Setup (Windows, Python 3.12)

```bash
cd backend
py -3.12 -m venv .venv
.venv\Scripts\python -m pip install -r requirements-dev.txt
mkdir models
curl -L -o models/pose_landmarker_heavy.task https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/latest/pose_landmarker_heavy.task
```

## Test

```bash
.venv\Scripts\python -m pytest
```

Tests use a synthetic mannequin (`tests/synthetic.py`) with known dimensions instead of real body photos.
`test_mediapipe_smoke.py` checks that the real model loads, and is skipped if the model is not downloaded.

## Docker image (what runs in the cluster)

```bash
docker build -t team14-backend .
docker run --rm -p 8000:8000 --memory=1g team14-backend
curl -H "Host: localhost" http://localhost:8000/healthz/
```

- **Image:** Python 3.12, gunicorn with one worker, runs as non-root uid 10001.
  - Includes the MediaPipe pose model (downloaded at build time, checksum-verified).
  - Includes the system libraries OpenCV/MediaPipe need (`libgl1`, `libglib2.0-0`, `libegl1`, `libgles2`). Without `libegl1` every analysis fails with a 500.
- **Memory:** about 340 MiB after the first analysis. With the original 256 Mi limit the worker is OOM-killed, so `infra/apps/backend.yaml` requests 512 Mi with a 1 Gi limit.
- **Environment:** `DJANGO_ALLOWED_HOSTS` (the image sets `localhost,127.0.0.1,backend`; add the public host when an Ingress is added), `DJANGO_SECRET_KEY`, `DJANGO_DEBUG`.
- **CI:** runs `pytest` before building and deploying.

## Run the API server locally (for the Android app)

`config/` (settings, URLs) + `apps/body_profiles/` serve the body-analysis endpoint:
- `POST /api/body-profile/analyze/` (contract in `docs/body-analysis/02-design.md` §7)
- multipart fields: `front_photo`, `side_photo`, `height_cm`, and optionally `weight_kg`, `gender`, `clothing`
- `422` responses carry an `error` code and a Korean `hint`
- uploads are kept in memory only; nothing is written to disk

New Django apps go in `apps/` and are registered in `config/settings.py` / `config/urls.py` (layout: wiki → Directory Structure).

```bash
$env:DJANGO_DEBUG="1"
.venv\Scripts\python manage.py runserver 0.0.0.0:8000
```

- **Android emulator:** the app's default base URL `http://10.0.2.2:8000` reaches this server.
- **Real phone (Galaxy S23):**
  - The phone and PC must be on the same Wi-Fi.
  - Add `stylemate.apiBaseUrl=http://<PC IP>:8000` to `frontend/local.properties`; find the PC's IP with `ipconfig`.
  - Allow Python through the Windows firewall when prompted.
- Debug builds allow plain HTTP. Release builds need HTTPS.

Quick check with the synthetic renders:

```bash
curl -X POST http://127.0.0.1:8000/api/body-profile/analyze/ -F front_photo=@private/synthetic_benchmark/m_avg_front.png -F side_photo=@private/synthetic_benchmark/m_avg_side.png -F height_cm=168 -F gender=male
```

`frontend/app/src/test/resources/analyze_response_*.json` are real responses captured this way. The Android `ServerContractTest` parses them, so re-capture them when the API changes.

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

### Demo photos with real accuracy (정확도 %)

```bash
.venv\Scripts\python scripts/export_benchmark_reference.py
```

This writes the 8 benchmark bodies as demo photos to `private/benchmark_photos/` (git-ignored) and their true measurements to `body_analysis/models/benchmark_reference.json` (committed).
- Each photo carries a small marker in its top-left corner. The server recognises it, paints it out before analysis, and returns the body's true measurements as `reference`.
- The app then shows "정확도 98.7%" (100 − relative error) per measurement. Other photos have no marker, so they never get an accuracy %.
- Enter the body's true height for a fair comparison. The script prints it, e.g. `f_avg` 160.0 cm and `m_avg` 168.2 cm.

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

Django side: `config/` (settings, root URLs, `/healthz/`, `/api/hello/`), `apps/body_profiles/` (analyze endpoint, pipeline singleton).


## Wardrobe integration

`apps/wardrobe/` runs in the same Django project as body analysis, through `manage.py` and `config.wsgi`.
The endpoints under `/api/wardrobe/` provide analysis drafts, landmarks, editor options, saved garments and images.

Copy `.env.example` to `.env` inside `backend/` and enter your MySQL account/database and Gemini key.
`python-dotenv` loads this file; deployment environment variables take precedence. Credentials are git-ignored.
Leave `MYSQL_DATABASE` empty to run body analysis and landmarks without a DB. Persistence endpoints return
`503 DATABASE_NOT_CONFIGURED` until configured, before any paid Gemini call.

Once the MySQL database and account exist and `.env` is filled in:

```powershell
.\.venv\Scripts\python.exe manage.py migrate
.\.venv\Scripts\python.exe manage.py runserver 127.0.0.1:8000
```

Photos default to `private/wardrobe-media/`; HRNet weights belong in `wardrobe/garment-landmarks/`.
Run `scripts/prepare_landmarks.py` in a separate conversion environment to prepare weights.
See [common app setup](../docs/wardrobe/local-development.md) for model preparation and Android USB configuration.

Wardrobe tests use an isolated in-memory SQLite database, without real MySQL or Gemini calls:

```powershell
.\.venv\Scripts\python.exe manage.py test apps.wardrobe --settings=config.test_settings --noinput
```

The Docker build downloads checksum-verified GarmentIQ source/weights and exports HRNet in a separate build
stage. The final image includes ONNX weights, metadata and the license at `/app/wardrobe/garment-landmarks`,
without PyTorch or export dependencies. No manual model upload or model volume is needed; do not mount an
empty volume over this directory. Backend CI checks actual HRNet inference inside the image.

The cluster needs MySQL configuration, migrations, a persistent photo volume and write access for uid 10001
before wardrobe persistence can run. Authentication and S3 are not integrated yet. The existing 1 Gi memory
limit was sized for body analysis; remeasure it with HRNet before wardrobe deployment.

# StyleMate — Iteration 4 Working Demo

StyleMate is an Android wardrobe assistant for people who want to choose outfits quickly. It manages clothing and body profiles, recommends outfits from owned garments, and supports conversational outfit editing and AI-generated fitting images.

**Branch:** `iteration-4-demo`

**Source:** [`main` at `f6d471a8a9b08d393df387eb5ccd436a5a72b19f`](https://github.com/snuhcs-course/swpp-2026-project-team-14/commit/f6d471a8a9b08d393df387eb5ccd436a5a72b19f), after PRs #82 and #83 were merged on 19 November 2026.

Submission tracking: [#84](https://github.com/snuhcs-course/swpp-2026-project-team-14/issues/84).

## Short demo video

**[Watch or download the demo video (MP4, 1 minute 37 seconds, approximately 42 MiB)](docs/demo/stylemate-develop-20261118-152707.mp4)**

The original video was recorded on **18 November 2026**, using the then-current `develop` build on a Galaxy S22. It shows profile/navigation, chat conditions and free-text outfit requests, and the Codi Book/save flow. Its cream/orange appearance predates the monochrome UI in this branch. The recording covers part of the implementation; use the walkthrough below to demonstrate the current features.

## How to run the demo

### Environment

| Component | Requirements / environment used |
| --- | --- |
| Client | Android **8.0+** (API 26+); previous device smoke tests used a Galaxy S22 with Android 13 |
| Development computer | Windows 11, Android Studio, Android SDK platform 36 and build tools 36.0.0 |
| Build | JDK 21 in CI; Android Studio JBR 25.0.2 for this Windows validation; Gradle wrapper 9.3.1, AGP 9.1.1 |
| Local backend | Python 3.12, `backend/requirements-dev.txt`, and a configured MySQL database |
| AI / network | Internet access and a server-side OpenRouter API key with credit for vision, chat and image generation |
| Device access | USB debugging for installation; camera/photo access for capture/import; optional calendar permission |

An ordinary Android phone is sufficient for the main flows. Only AR measurement needs ARCore support. iOS is not supported. Server models run on CPU, and no dedicated GPU server or large phone-side model download is required.

### Option A: run the Android app against the hosted backend

1. Clone the submission branch:

   ```powershell
   git clone --branch iteration-4-demo --single-branch https://github.com/snuhcs-course/swpp-2026-project-team-14.git
   cd swpp-2026-project-team-14
   ```

2. Open **`frontend/`** in Android Studio, install SDK platform 36/build tools 36.0.0, and let Android Studio generate `frontend/local.properties` with your SDK path. Set this property in that file:

   ```properties
   stylemate.apiBaseUrl=http://swpp26t14.147-46-242-169.sslip.io
   ```

3. Connect an unlocked phone, enable USB debugging, and accept its debugging prompt. From `frontend/`:

   ```powershell
   .\gradlew.bat assembleDebug
   .\gradlew.bat installDebug
   ```

   Output: `frontend/app/build/outputs/apk/debug/app-debug.apk`; application ID: `com.swpp.stylemate`. Alternatively, download artifact **`stylemate-debug-apk`** from [the main Frontend CI run](https://github.com/snuhcs-course/swpp-2026-project-team-14/actions/runs/44512800925) (GitHub sign-in required).

4. Open **StyleMate**, register a test account or sign in, and complete the style-profile steps. Choose your own test credentials; none are published here. New accounts start with an empty wardrobe.

The hosted API was reachable from an off-campus network on 19 November 2026. It follows deployed `main`; use option B to reproduce this branch's backend locally. Debug builds permit its HTTP address. **Release/AAB builds require HTTPS** and will not work with the current HTTP endpoint configuration.

### Option B: run the backend locally

Prepare a MySQL database and an account with permission to create application tables. **Login and service APIs require a configured database**; the health endpoint alone does not validate that setup.

From the repository root, in PowerShell:

```powershell
cd backend
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

Edit `backend/.env`: fill in `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD`, `DJANGO_SECRET_KEY` and `OPENROUTER_API_KEY`. Keep provider credentials on the backend. The default `CHAT_PROVIDER=auto` uses OpenRouter when its key is set. Paid vision/image functions need the provider key and available credit.

Download the pose and clothing-segmentation models:

```powershell
New-Item -ItemType Directory -Force models | Out-Null
curl.exe -L -o models/pose_landmarker_heavy.task https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/latest/pose_landmarker_heavy.task
.\.venv\Scripts\python.exe manage.py prepare_worn_model
```

Prepare the garment landmark model in a separate export environment:

```powershell
py -3.12 -m venv private/model-export-venv
.\private\model-export-venv\Scripts\python.exe -m pip install torch==2.6.0 --index-url https://download.pytorch.org/whl/cpu
.\private\model-export-venv\Scripts\python.exe -m pip install onnx==1.19.0
.\private\model-export-venv\Scripts\python.exe scripts/prepare_landmarks.py
```

The export and segmentation scripts download pinned weights and verify checksums. Recommendation models are already included under `backend/recommendation/models/`; the original K-Fashion dataset is unnecessary for execution. First-time model downloads/export require additional disk space and time.

Migrate the database and start the API:

```powershell
.\.venv\Scripts\python.exe manage.py migrate
$env:DJANGO_DEBUG = "1"
.\.venv\Scripts\python.exe manage.py runserver 127.0.0.1:8000 --noreload
```

Keep that terminal open. In another terminal:

```powershell
curl.exe http://127.0.0.1:8000/healthz/
& "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -d reverse tcp:8000 tcp:8000
```

Set `stylemate.apiBaseUrl=http://127.0.0.1:8000` in `frontend/local.properties`, then rebuild/install as in option A. Keep USB reverse active while using this address. For an Android emulator use `http://10.0.2.2:8000` instead.

The [production Dockerfile](backend/Dockerfile) packages all three server models automatically. It uses one Gunicorn worker with eight threads. Fitting jobs use a process-local queue, so restarting the backend can interrupt unfinished generation. Docker still requires database configuration, migrations and writable media storage. See [backend setup](backend/README.md), [wardrobe development](docs/wardrobe/local-development.md), and [deployment](docs/deployment/school-cpu.md) for details; the database-required authentication contract above takes precedence over older optional-database examples.

## What the demo demonstrates

### Implemented features and walkthrough

1. **Account and style profile:** register/sign in, complete onboarding and review style preferences. Account wardrobes and saved outfits persist between sessions.
2. **Body profile:** optionally supply front and side photos plus height to estimate measurements. AR/manual garment measurement is a separate flow.
3. **Wardrobe / try-off:** import a garment photo or a worn-outfit photo, select detected garments, generate standalone clothing images where needed, review/edit attributes, then save. Bring at least a top and a bottom; four usable garments including both are needed for overall wardrobe analysis.
4. **Today's outfit:** generate weather-aware outfits from owned garments, swipe through existing alternatives, read explanations, and save an outfit.
5. **Conversational editing:** lock a garment, select a direction such as `더 따뜻하게`, or type `내일 부산 여행 가는데 뭐 입지?`. Check retained locks, forecast context and the revised outfit, then save it to the Codi Book.
6. **Wardrobe analysis / matching:** inspect missing-item suggestions and the supporting owned garments, then open a Musinsa search. These are attribute-based searches rather than a live product catalogue.
7. **Virtual fitting:** choose an outfit and either a gallery photo or a built-in body-matched virtual model, generate a fitting image, and view/save the result. External generation can take tens of seconds and depends on provider availability and credit.

### Major progress in Iteration 4

| Area | Progress included in this snapshot |
| --- | --- |
| HE feedback and UI | Clearer browsing/save/cancel actions, registration/editing, error recovery, account boundaries and a shared monochrome UI (PRs #72, #82) |
| Context-aware chat | Place/date/travel parsing, daily forecasts, occasion directions and explicit fallback behavior (PR #74) |
| Overall wardrobe analysis | Ranked missing-item suggestions supported by owned garments and linked to Musinsa searches (PR #76) |
| Evaluation and operations | Fixed garment-analysis evaluation, recommendation evaluation with committed pass criteria, and reproducible API load-test scripts (PRs #69, #78, #80) |
| Fitting experience | Photo fitting and background/result handling, with dedicated device/worker regression coverage |

## Validation

Validated on **19 November 2026** against the source snapshot identified above.

| Check | Result |
| --- | --- |
| Android debug build, JVM tests and lint | **APK built; 375 tests passed; lint passed with 0 errors** (38 warnings, 1 hint) |
| Django: users, wardrobe, outfits and fittings | **254 passed**, isolated in-memory database |
| Python tests | **321 passed, 1 skipped** (optional pose smoke needs a downloaded model in the fresh clone) |
| Real CPU model smoke | **4/4 passed:** MediaPipe pose, HRNet, recommendation models and SegFormer, using existing local weights |
| Migration drift | **No changes detected** |
| Off-campus hosted API | `/healthz/`: **200**; authenticated routes: expected **401** without a token |

To repeat automated checks:

```powershell
# From frontend/
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug

# From backend/
.\.venv\Scripts\python.exe -m pytest -q tests
.\.venv\Scripts\python.exe manage.py test apps.users apps.wardrobe apps.outfits apps.fittings --settings=config.test_settings --noinput
.\.venv\Scripts\python.exe manage.py makemigrations --check --dry-run --settings=config.test_settings
```

Backend test settings isolate the database and clear provider keys. The separate CPU smoke covered the skipped pose-model loading check. Automated checks do not evaluate paid-provider quality. Earlier Galaxy S22 testing of the same application revision covered photo recognition, two try-off generations and a virtual-fitting result; a transient segmentation failure cleared after restarting the unchanged backend. No physical phone was connected during submission preparation, so physical AR, body-analysis execution and a new end-to-end phone session were not repeated.

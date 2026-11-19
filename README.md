# StyleMate – AI Fashion Partner · Iteration 4 Demo

SWPP 2026 Fall · Team 14 (Hyeon U Jeong, Dongkun Moon, Kihwan Kim, Juhyeon Choi)

StyleMate is an Android app that learns the user's body and wardrobe and suggests outfits from clothes they already own. Iteration 4 improves usability, adds place/date-aware chat and overall wardrobe analysis, and strengthens evaluation and regression testing.

This submission uses `main` at [`f6d471a`](https://github.com/snuhcs-course/swpp-2026-project-team-14/commit/f6d471a8a9b08d393df387eb5ccd436a5a72b19f), after PRs #82 and #83 were merged on 19 November 2026. Submission branch: `iteration-4-demo`; tracking issue: [#84](https://github.com/snuhcs-course/swpp-2026-project-team-14/issues/84).

## Demo video

[Watch or download the demo video — MP4, 1 minute 37 seconds, approximately 42 MiB](docs/demo/stylemate-develop-20261118-152707.mp4)

Recorded on 18 November 2026 using the then-current `develop` build. It shows profile/navigation, chat conditions and free-text requests, and the Codi Book/save flow. The recording predates the monochrome UI in this branch and covers part of the implementation; follow **Try the demo** for the current walkthrough.

---

## What this demo demonstrates

### Features implemented

- **Accounts and profiles:** required sign-in, account-owned wardrobes/outfits, style onboarding, and optional body measurements from front/side photos and height. Recommendations and chat also work without body measurements, with less body-specific fit information.
- **Wardrobe registration and try-off:** import a garment-only photo or a photo with one person, select detected garments, generate standalone garment images where needed, review/edit attributes, and save. Photos with multiple people are rejected. AR/manual measurement remains available as a separate flow.
- **Outfit recommendations and Codi Book:** weather-aware outfits from owned garments, swipe between existing alternatives, read explanations, give feedback, and save outfits.
- **Conversational editing:** use lock buttons to keep garments, send selected conditions or free text, and request outfits for a place, date or occasion. The app explains forecast context and fallback behavior.
- **Matching and overall wardrobe analysis:** find partners for a selected garment or identify missing items across the wardrobe. Suggestions show supporting owned garments and link to Musinsa searches, rather than a live product catalogue.
- **Virtual fitting:** generate an outfit preview on a gallery photo or a built-in body-matched virtual model, then view/save the result. Background completion notifications depend on the user's settings and permission.

### Major progress made during Iteration 4

| Area | Iteration 3 | Iteration 4 |
| --- | --- | --- |
| UI and HE feedback | Existing screens with usability findings | Shared monochrome design, clearer browsing/actions, draft handling and error recovery (PRs #72, #82) |
| Chat context | Outfit edits with locks and free text | Place/date/travel forecasts, occasion directions and explicit fallbacks (PR #74) |
| Shopping suggestions | Matching for one garment | Overall wardrobe analysis with ranked missing items and supporting garments (PR #76) |
| AI evaluation | Fixed body, garment and chat evaluations | Retained garment-analysis evaluation and strengthened recommendation evaluation with committed pass criteria (PR #80) |
| Reliability and testing | Existing tests and CI | Additional regression coverage and reproducible recommendation API load-test scripts (PRs #78, #82) |

Known limitations in this demo:
- Vision, free-text chat and image generation require server-side provider credentials, internet access and available credit. Image generation can take tens of seconds.
- Fitting uses a process-local queue; restarting the backend can interrupt unfinished jobs.
- The hosted endpoint is HTTP and is used by debug builds. Release/AAB builds need HTTPS.
- AR measurement requires an ARCore-supported physical phone. Body-measurement accuracy has been evaluated on synthetic bodies; the results do not establish accuracy for all real photos.
- The attached video uses the earlier UI and does not demonstrate every current feature. Fresh real-backend onboarding/matching verification remains tracked in [#71](https://github.com/snuhcs-course/swpp-2026-project-team-14/issues/71).

---

## How to run the demo

### Environment we used

| Component | Environment |
| --- | --- |
| OS | Windows 11 |
| Android development | Android Studio, SDK platform 36, build tools 36.0.0 |
| Java and build | JDK 21 in CI; Android Studio JBR 25.0.2 locally; Gradle wrapper 9.3.1, AGP 9.1.1 |
| Test phone / supported OS | Galaxy S22, Android 13 for the recording and earlier smoke tests; minimum Android 8.0 (API 26) |
| Backend | Python 3.12, Django, MySQL, ONNX Runtime and MediaPipe; CPU execution |
| Deployment | Docker/Gunicorn, K3s and Argo CD on the school server |
| External APIs | OpenRouter for vision/chat/image generation; Open-Meteo for weather |

An ordinary Android phone is sufficient for the main flows. Only AR measurement needs ARCore support; iOS is unsupported. No dedicated GPU or large phone-side model download is required. Allow camera/photo access when needed; calendar access is optional.

### Option A: use the deployed server

1. Clone the submission branch and open `frontend/` in Android Studio:

   ```powershell
   git clone --branch iteration-4-demo --single-branch https://github.com/snuhcs-course/swpp-2026-project-team-14.git
   cd swpp-2026-project-team-14
   ```

2. Install SDK platform 36/build tools 36.0.0. Let Android Studio generate `frontend/local.properties` with your SDK path, then set:

   ```properties
   stylemate.apiBaseUrl=http://swpp26t14.147-46-242-169.sslip.io
   ```

3. Connect an unlocked phone, enable USB debugging and accept the prompt. From `frontend/`:

   ```powershell
   .\gradlew.bat assembleDebug
   .\gradlew.bat installDebug
   ```

The APK is `frontend/app/build/outputs/apk/debug/app-debug.apk`, application ID `com.swpp.stylemate`. Alternatively, download `stylemate-debug-apk` from [the main Frontend CI run](https://github.com/snuhcs-course/swpp-2026-project-team-14/actions/runs/44512800925) while signed into GitHub.

Check `http://swpp26t14.147-46-242-169.sslip.io/healthz/` for `{"status":"ok"}`. It was reachable off campus on 19 November 2026. The hosted backend follows deployed `main`; option B runs this branch's backend locally. Rebuild after changing the API address.

### Option B: run everything locally

**1. Start the backend** (PowerShell, from the repository root)

Prepare a MySQL database and an account allowed to create application tables. Login and service APIs require this database.

```powershell
cd backend
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

Fill in `backend/.env`: `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD`, `DJANGO_SECRET_KEY` and `OPENROUTER_API_KEY`. Keep credentials out of Git. The default `CHAT_PROVIDER=auto` uses OpenRouter when its key is set.

Prepare the pose and clothing-segmentation models:

```powershell
New-Item -ItemType Directory -Force models | Out-Null
curl.exe -L -o models/pose_landmarker_heavy.task https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/latest/pose_landmarker_heavy.task
.\.venv\Scripts\python.exe manage.py prepare_worn_model
```

Export the garment landmark model in a separate environment:

```powershell
py -3.12 -m venv private/model-export-venv
.\private\model-export-venv\Scripts\python.exe -m pip install torch==2.6.0 --index-url https://download.pytorch.org/whl/cpu
.\private\model-export-venv\Scripts\python.exe -m pip install onnx==1.19.0
.\private\model-export-venv\Scripts\python.exe scripts/prepare_landmarks.py
```

The export/segmentation scripts download pinned weights and verify checksums. Recommendation models are included in the repository; the original K-Fashion dataset is unnecessary for execution. Allow extra disk space/time for first-time downloads and export.

```powershell
.\.venv\Scripts\python.exe manage.py migrate
$env:DJANGO_DEBUG = "1"
.\.venv\Scripts\python.exe manage.py runserver 127.0.0.1:8000 --noreload
```

Keep the server terminal open. Check `http://127.0.0.1:8000/healthz/` in another terminal. The [Dockerfile](backend/Dockerfile) packages the server models automatically; Docker still needs database configuration, migrations and writable media storage. See [backend setup](backend/README.md), [wardrobe development](docs/wardrobe/local-development.md) and [deployment](docs/deployment/school-cpu.md). The database-required authentication contract above supersedes older optional-database examples.

**2. Run the app**

Open `frontend/` in Android Studio and set `stylemate.apiBaseUrl` in `frontend/local.properties`:

| Connection | API base URL |
| --- | --- |
| Emulator | `http://10.0.2.2:8000` |
| USB phone | `http://127.0.0.1:8000` |

For USB, run this in another terminal and repeat it after reconnecting:

```powershell
& "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -d reverse tcp:8000 tcp:8000
```

Rebuild/install as in option A. USB reverse must remain active for the phone's loopback address to reach the PC.

### Try the demo

1. **Register/sign in** and choose fit and mood preferences during onboarding. You can record colours later in MY. Use your own test credentials. New accounts have empty wardrobes.
2. **Body profile (optional):** provide front/side photos and height, then review the estimated measurements. Recommendations and chat remain usable if this step is skipped.
3. **Add clothes:** open the wardrobe, select a supported photo, choose detected garments, generate images where needed, review the labels and save. Prepare at least a usable top and bottom; overall wardrobe analysis needs at least four garments including both.
4. **Home and Codi Book:** request an outfit, swipe through alternatives, inspect its explanation and save it.
5. **Chat:** use a garment's lock button, select conditions or type `내일 부산 여행 가는데 뭐 입지?`, then send. Check the revised outfit, locks and weather context, and save it if desired.
6. **Shopping suggestions:** try matching from a garment detail and overall wardrobe analysis from the wardrobe. Inspect the evidence and open a Musinsa search.
7. **Virtual fitting:** choose an outfit and a gallery photo or virtual model, generate the preview, then view/save it.

### Tests

From the repository root (PowerShell):

```powershell
cd backend
.\.venv\Scripts\python.exe -m pytest -q tests
.\.venv\Scripts\python.exe manage.py test apps.users apps.wardrobe apps.outfits apps.fittings --settings=config.test_settings --noinput
.\.venv\Scripts\python.exe manage.py makemigrations --check --dry-run --settings=config.test_settings
cd ..\frontend
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

Validation on 19 November 2026:

| Check | Result |
| --- | --- |
| Android | Debug APK built; 375 JVM tests passed; lint: 0 errors, 38 warnings and 1 hint |
| Backend | 254 Django tests and 321 pytest cases passed; 1 optional pose-model smoke skipped in the fresh clone |
| Real CPU models | 4/4 passed: pose, HRNet, recommendation models and SegFormer, using existing local weights |
| Migrations | No changes detected |
| Off-campus API | Health: HTTP 200; authenticated routes: expected 401 without a token |

Tests use an isolated database and mock paid providers. Separate real-model checks covered the skipped pose-model loading test; they do not establish model accuracy. Earlier Galaxy S22 testing of the same application revision covered photo recognition, two try-off generations and a fitting result. A transient segmentation failure cleared after restarting the unchanged backend. No phone was connected during submission preparation, so physical AR, body-analysis execution and a fresh end-to-end phone session were not repeated.

---

## Repository layout

| Path | Contents |
| --- | --- |
| `frontend/` | Android app: screens, repositories, stores and tests |
| `backend/` | Django API, body analysis, recommendation engine/models, wardrobe and fitting services |
| `backend/eval/` | Fixed AI evaluation cases, rubrics and results |
| `backend/loadtest/` | Recommendation API load-test and helper scripts |
| `infra/` | Kubernetes manifests managed by Argo CD |
| `docs/` | Design, API, development and deployment documentation |
| `docs/demo/` | The original demo recording for this submission |

Requirements, design and testing documents are on the [GitHub Wiki](https://github.com/snuhcs-course/swpp-2026-project-team-14/wiki).

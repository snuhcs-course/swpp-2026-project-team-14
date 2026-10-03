# StyleMate – AI Fashion Partner · Iteration 1 Demo

SWPP 2026 Fall · Team 14 "0103" (Hyeon U Jeong, Dongkun Moon, Kihwan Kim, Juhyeon Choi)

StyleMate is an Android app that learns the user's body and wardrobe and suggests outfits from the clothes they already own.
Iteration 1 prototypes the riskiest part first: **estimating garment measurements from two phone photos**, plus the backend and deployment pipeline everything else will run on.

| Photo input | Results (AI가 추정한 체형) | Real accuracy on a benchmark body |
|---|---|---|
| <img src="demo/screenshots/1-input.png" width="240"> | <img src="demo/screenshots/2-results.png" width="240"> | <img src="demo/screenshots/3-accuracy-detail.png" width="240"> |

## Demo video

▶ **[TODO: link to the demo video]**

---

## What this demo demonstrates

### Features implemented

**Body analysis (P9 screen, P10 AI integration)**
- **Onboarding flow:** capture guide → front + side photo (camera or album) → height (required), weight and gender (optional) → analysis → results → My Profile.
- **16 garment measurements** (ISO 8559-1 names) from the two photos on a CPU-only server:
  - lengths: shoulder width, sleeve, torso, rise, inseam, outseam
  - circumferences: neck, chest, underbust, waist, hip, armhole, bicep, wrist, thigh, calf
- **Results screen:** a cartoon body figure (female / male / neutral) with shoulder, chest, waist, hip and inseam always visible. Tapping any other body part shows that measurement, which the user can correct.
- **Body-shape insights** in neutral styling language (body type, leg proportion, shoulders). An insight is shown only when it is clearly past its threshold.
- **Loose clothing is detected automatically.** Users are not asked what they wore. The app warns, lowers confidence only for the affected measurements, and hides insights that depend on them.
- **Retake hints** for unusable photos: no person, several people, body cut off, wrong view, upside down, unreadable file.
- **Privacy:** photos are processed in memory and dropped right after analysis. Only numbers are kept.
- **Real accuracy for benchmark photos:** for our 8 synthetic benchmark bodies, whose true measurements are known, the app shows "정확도 98.7%" and the true value per measurement.

**Backend and infrastructure**
- Django API (`POST /api/body-profile/analyze/`), Docker image, and a GHCR + Argo CD GitOps pipeline on k3s (Azure VM).
- CI on every PR: backend tests plus a Docker image check (the pose model must load inside the image), and Android unit tests plus an APK build.

**[TODO (owners): other Iteration 1 features, e.g. clothing addition screen, wardrobe, storage integration]**

### Goals and what we validated

| Question | Result |
|---|---|
| Can phone photos give usable garment measurements? | On 8 synthetic benchmark bodies: **mean accuracy 98.7 %** in underwear and 98.8 % in tight clothing, about 0.5 cm mean error ([report](docs/body-analysis/07-accuracy-report.md)). Real-photo validation with tape measurements is the next step. |
| Does it run on our server budget? | Yes: CPU only, about 0.5 s per analysis on a laptop CPU once the model is loaded, about 340 MiB memory in the container (1 Gi limit on the VM). |
| Is it robust to bad input? | Stress-tested with 11 image formats, sizes from 30×40 to 6000×8000, broken files and wrong photos, all with no server errors. Concurrent requests are safe. |
| Can users trust it? | Every value is editable and labelled with confidence. Loose clothing triggers a warning instead of silently wrong numbers. |

Known limitations in this demo:
- **No database yet.** The confirmed profile lives in app memory and is lost when the app is closed. Saving it is part of the backend/storage integration.
- The 홈 and 옷장 tabs are placeholders.
- The accuracy numbers come from synthetic bodies and are optimistic.
- The results-screen design is still under discussion.

---

## How to run the demo

### Environment we used

| | Version |
|---|---|
| OS | Windows 11 |
| Android Studio | 2025.3.3 (bundled JDK 21) |
| Android emulator | "Medium Phone", Android 16 (API 36), x86_64, 4 GB RAM, Graphics: Software |
| App | Kotlin 2.2, Jetpack Compose, minSdk 26, targetSdk 36 |
| Backend (local) | Python 3.11.9, Django 5.2, MediaPipe 1.0.1 |
| Backend (deployed) | Docker `python:3.12-slim`, gunicorn |

### 1. Start the backend

```bash
cd backend
py -3.11 -m venv .venv
.venv\Scripts\python -m pip install -r requirements-dev.txt
mkdir models
curl -L -o models/pose_landmarker_heavy.task https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/latest/pose_landmarker_heavy.task
set DJANGO_DEBUG=1
.venv\Scripts\python manage.py runserver 0.0.0.0:8000
```

Check: `curl http://127.0.0.1:8000/healthz/` returns `{"status": "ok"}`.

The first analysis takes a few seconds longer while the pose model loads.
To run the Docker image instead, see [backend/README.md](backend/README.md#docker-image-what-runs-in-the-cluster). For the emulator, add `-e DJANGO_ALLOWED_HOSTS=localhost,127.0.0.1,10.0.2.2`.

### 2. Run the app

1. Open the `frontend/` folder in Android Studio and let Gradle sync.
2. Start an emulator and press **Run**. The app reaches the local server at `http://10.0.2.2:8000` by default.
   - On a real phone on the same Wi-Fi, add `stylemate.apiBaseUrl=http://<PC IP>:8000` to `frontend/local.properties`.
   - If the emulator shows a black screen, set Graphics to **Software** and RAM to 4 GB in the Device Manager.

### 3. Try the body analysis with the demo photos

`demo/photos/` has front and side photos of the 8 benchmark bodies. These are synthetic 3D renders, not real people.

1. Copy the photos into the emulator's gallery (PowerShell, from the repository root):
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" push demo\photos\. /sdcard/Download/
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell content call --uri content://media --method scan_volume --arg external_primary
   ```
   Dragging files onto the emulator does not make them appear in the photo picker; the scan command does.
2. In the app, tap **사진 찍으러 가기** and pick `<body>_front.png` as 정면 and `<body>_side.png` as 측면 (앨범).
3. Enter the body's **true height** and gender from the table below, then tap **분석하기**.
4. On the results screen, tap a body part. Each value shows "정확도 …%" and the true measurement. Open **전체 치수 보기** to see all 16.

| Body | Gender | Height (cm) | | Body | Gender | Height (cm) |
|---|---|---|---|---|---|---|
| `f_avg` | 여성 | 160.0 | | `m_avg` | 남성 | 168.2 |
| `f_slim_tall` | 여성 | 186.8 | | `m_slim` | 남성 | 179.2 |
| `f_heavy` | 여성 | 154.8 | | `m_heavy` | 남성 | 168.2 |
| `f_short` | 여성 | 133.0 | | `m_muscular_tall` | 남성 | 196.3 |

A different height lowers the accuracy, because every value scales with it; the app says so. Your own full-body photos also work (A-pose, plain background, tight clothing). For those the app shows confidence badges instead of an accuracy %.

### Tests

```bash
cd backend && .venv\Scripts\python -m pytest
cd frontend && gradlew testDebugUnitTest
```

---

## Repository layout

| Path | Contents |
|---|---|
| `frontend/` | Android app (Kotlin, Jetpack Compose) |
| `backend/` | Django API and `body_analysis/` (pose, measurement, clothing detection, learned correction) |
| `infra/` | Kubernetes manifests deployed by Argo CD |
| `docs/body-analysis/` | Model research, design, benchmark plan and results, accuracy report |
| `demo/` | Demo photos and screenshots (this branch only) |

Design and requirements documents are on the [GitHub Wiki](https://github.com/snuhcs-course/swpp-2026-project-team-14/wiki).

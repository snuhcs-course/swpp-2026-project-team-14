# StyleMate – AI Fashion Partner · Iteration 3 Demo

SWPP 2026 Fall · Team 14 "0103" (Hyeon U Jeong, Dongkun Moon, Kihwan Kim, Juhyeon Choi)

StyleMate is an Android app that learns the user's body and wardrobe and suggests outfits from the clothes they already own.
Iteration 2 made it a login-required service with outfit recommendations, a 코디북 and conversational editing. Iteration 3 adds **registration from a photo of the user wearing the clothes**, **matching-item recommendations with a shopping link**, **reasons and satisfaction ratings in chat**, **wardrobe deletion** and **varied recommendations on every refresh**, and evaluates the AI modules with fixed test sets.

## Demo video

[▶ Watch the Iteration 3 demo (57 s)](demo/demo_video_iter3.mp4)

The video walks through 홈 recommendations with reasons, editing an outfit in chat with a locked garment, finding a matching item and opening the Musinsa search, deleting a garment, and registering three garments from one worn-outfit photo. It is edited for length, so waiting times are shortened.

---

## What this demo demonstrates

### Features implemented

**Registration from a worn-outfit photo (#44, PR #46, #49)**
- 옷장 → **착용 사진으로 등록**: pick a photo in which the user wears the clothes. The server finds up to three garments (top, bottom, outerwear) in it and cuts each one out.
- For each garment the app suggests the category, colours, fit and length from the photo and the user's body profile, and an approximate size range. The user reviews, corrects and registers the garments in one step.
- A flat-lay image of each garment can be generated (OpenRouter image model); if it fails, the crop from the original photo can be used.
- Garment analysis for both flows now goes through OpenRouter vision, with a concurrency limit and clear errors for missing credit or configuration.

**Matching items and shopping link (#34, PR #36)**
- Select a saved top, bottom or outerwear and choose **어울리는 옷 찾기**: the recommender predicts the category, colour, fit and pattern of a matching garment, shows up to three matching garments the user already owns, and opens a Musinsa search for the predicted attributes.

**Reasons and satisfaction in chat (#35, PR #37)**
- Each recommended outfit shows why it was chosen. In 대화로 수정하기, the user can rate the revised outfit; chat ratings are stored per account and outfit and feed the taste weights.

**Wardrobe deletion (#52, PR #53)**
- A garment can be deleted after a confirmation. Saved 코디북 outfits that contain it are removed in the same transaction.

**Recommendation variety (PR #58)**
- Each 홈 refresh draws a new random seed and a small random spread on the pick order, so near-tied outfits come out differently each time while the scores and reasons stay the same.

**Testing and evaluation (#38, #40, PR #39, #41)**
- Chat intent: a fixed set of 34 requests with a rubric and pass criteria committed before the run; 33 of 34 passed (97.06 %, goal ≥ 80 %).
- ViewModel unit tests for Home, Chat and 코디북 with `kotlinx-coroutines-test`.
- The full results are in the [Testing Documentation](https://github.com/snuhcs-course/swpp-2026-project-team-14/wiki/Testing-Documentation).

**Deployment fixes (PR #43, #47)**
- The API no longer fails during a rollout restart, and the debug UI preview no longer appears as a second launcher icon.

### Major progress made during Iteration 3

| Area | Iteration 2 | Iteration 3 |
|---|---|---|
| Wardrobe registration | AR measurement, then a front photo | Also from a photo of the user wearing up to three garments, reviewed together |
| Garment analysis | Gemini API | OpenRouter vision with a concurrency limit and explicit error codes |
| Wardrobe management | Add and edit | Add, edit and delete with confirmation |
| Recommendation | Three outfits from the wardrobe | Reasons shown, different outfits on each refresh, matching items with a Musinsa link |
| Chat | Edit with locks and free text | Plus satisfaction ratings and an evaluated intent parser |
| Testing | Unit tests and coverage in CI | Fixed-case AI evaluation, ViewModel tests, Heuristic Evaluation (11/04) and the UAT plan |

Known limitations in this demo:
- Virtual try-on is moved to Iteration 4 (#33).
- Wardrobe AR measurement needs an ARCore-supported physical phone; the emulator cannot run AR capture.
- Garment analysis, worn-photo registration and free-text chat need `OPENROUTER_API_KEY` on the server; without it the app shows a retryable error.
- Shoes are not recommended, because K-Fashion has no shoe data.
- Body-measurement accuracy is validated on synthetic bodies only.
- The school endpoint is plain HTTP, so it is used by debug builds only.

---

## How to run the demo

### Environment we used

| | Version |
|---|---|
| OS | Windows 11 |
| Android Studio | Bundled JDK 21 (JBR 21.0.10) |
| Test phone | Samsung Galaxy S22, Android 16, ARCore supported |
| Android emulator | "Medium Phone", Android 16 (API 36), x86_64 |
| App | Kotlin, Jetpack Compose, Gradle wrapper 9.3.1, compile/target SDK 36, minSdk 26 |
| Backend (local) | Python 3.11.9, Django 5.2, MySQL, ONNX Runtime 1.23.2 |
| Backend (CI and deployed) | Python 3.12, Docker image with gunicorn, K3s + Argo CD on the school server |
| External APIs | OpenRouter (`google/gemini-3.5-flash-lite` vision, `google/gemini-2.5-flash-image` flat-lay, `google/gemini-3.1-flash-lite` chat); Open-Meteo weather |

### Option A: use the deployed server

The deployed backend is at `http://swpp26t14.147-46-242-169.sslip.io` (check: `/healthz/` returns `{"status": "ok"}`).

1. Open `frontend/` in Android Studio and add this line to `frontend/local.properties`:
   ```properties
   stylemate.apiBaseUrl=http://swpp26t14.147-46-242-169.sslip.io
   ```
2. Run the `app` configuration on an emulator or phone, then follow [Try the demo](#try-the-demo).

### Option B: run everything locally

**1. Start the backend** (PowerShell, from `backend/`)

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
mkdir models
curl.exe -L -o models/pose_landmarker_heavy.task https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/latest/pose_landmarker_heavy.task
.\.venv\Scripts\python.exe manage.py prepare_worn_model
copy .env.example .env
```

Fill in `backend/.env`:

| Variable | Value |
|---|---|
| `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD` | An existing local MySQL database and account (required for accounts, wardrobe and outfits) |
| `OPENROUTER_API_KEY` | Needed for garment analysis, worn-photo registration and free-text chat |
| `GEMINI_API_KEY` | Optional; chat uses it only when `OPENROUTER_API_KEY` is empty |

Keep `.env` out of Git. `prepare_worn_model` downloads and checks the clothes segmentation model used by worn-photo registration. Then prepare the HRNet garment model with the [model setup instructions](docs/wardrobe/local-development.md#4-모델-준비) and start the server:

```powershell
.\.venv\Scripts\python.exe manage.py migrate
.\.venv\Scripts\python.exe manage.py runserver 0.0.0.0:8000
```

Check: `curl.exe http://127.0.0.1:8000/healthz/` returns `{"status": "ok"}`.

**Optional sample wardrobe:** the script below creates the local account `samplechat` (password `samplechat2026`) with 14 fictional garments, so recommendations and chat can be tried without registering clothes. It runs only against a local database.

```powershell
.\.venv\Scripts\python.exe scripts\seed_chat_wardrobe.py
```

**2. Run the app**

1. Open `frontend/` in Android Studio and let Gradle sync.
2. Set `stylemate.apiBaseUrl` in `frontend/local.properties`, then press **Run**:

| Connection | `stylemate.apiBaseUrl` |
|---|---|
| Emulator | `http://10.0.2.2:8000` (default when not set) |
| USB phone | `http://127.0.0.1:8000`, after `adb -d reverse tcp:8000 tcp:8000` |
| Phone on the same Wi-Fi | `http://<PC IP>:8000` |

Rebuild the app after changing the address. If the emulator shows a black screen, set Graphics to **Software** in the Device Manager.

### Try the demo

1. **Sign up** with an ID (4–20 lowercase letters, digits or `_`) and a password (8–64 characters).
2. **Onboarding:** choose preferred fit, mood and colours. Then run body analysis with a front and a side photo and your height; the demo photos on the `iteration-1-demo` branch (`demo/photos/`) can be used. Recommendations and chat need a body profile.
3. **Add clothes from a worn photo:** 옷장 → **착용 사진으로 등록** → pick a photo of a person wearing a top, bottom or outerwear → check the garments found → correct the suggested labels → register them.
4. **Add a garment by AR** (physical phone): 옷장 → **촬영하기** → choose the type → AR capture → **확인** → front photo for tops and outerwear → **분석하기** → **옷장에 추가**.
5. **홈:** see three outfits for today's weather with their reasons. Refresh to get different outfits. Tap **좋아요** or **별로예요**, or save an outfit to the **코디북**.
6. **대화로 수정하기:** tap a garment to lock it, then type "아우터 밝게" or "출근용으로 바꿔줘". Rate the revised outfit and save it to the 코디북.
7. **어울리는 옷 찾기:** open a saved garment and choose 어울리는 옷 찾기 to see the predicted match, owned matches and the Musinsa search link.
8. **Delete a garment:** open it in 옷장, tap delete and confirm.

### Tests

From the repository root (PowerShell):

```powershell
cd backend
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe manage.py test apps.users apps.wardrobe apps.outfits --settings=config.test_settings --noinput
cd ..\frontend
.\gradlew.bat --no-daemon testDebugUnitTest
```

The Django tests use an isolated in-memory database and mock paid AI calls. The chat evaluation calls the real model and is run separately (`backend/eval/chat/README.md`). Coverage commands, device-test setup and the latest results are in the [Testing Documentation](https://github.com/snuhcs-course/swpp-2026-project-team-14/wiki/Testing-Documentation).

---

## Repository layout

| Path | Contents |
|---|---|
| `frontend/` | Android app (Kotlin, Jetpack Compose): `data/` repositories and stores, `ui/` screens including `home/`, `wardrobe/`, `profile/` and `chat/` |
| `backend/` | Django API: `apps/users/` (accounts, import), `apps/wardrobe/` (registration, worn photos, deletion), `apps/outfits/` (recommend, 코디북, chat, matching), `body_analysis/`, `recommendation/` (engine and models) |
| `backend/eval/` | Fixed AI evaluation sets and results (chat intent) |
| `infra/` | Kubernetes manifests deployed by Argo CD (backend, MySQL, storage, ingress) |
| `docs/` | Design notes for body analysis, wardrobe, login, recommendation and deployment |

Requirements, design and testing documents are on the [GitHub Wiki](https://github.com/snuhcs-course/swpp-2026-project-team-14/wiki).

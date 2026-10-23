# StyleMate – AI Fashion Partner · Iteration 2 Demo

SWPP 2026 Fall · Team 14 "0103" (Hyeon U Jeong, Dongkun Moon, Kihwan Kim, Juhyeon Choi)

StyleMate is an Android app that learns the user's body and wardrobe and suggests outfits from the clothes they already own.
Iteration 1 prototyped body measurement and measured, AI-labelled wardrobe items. Iteration 2 turns them into one **login-required service**: accounts and onboarding, **outfit recommendations from the user's own wardrobe**, a **코디북**, and **conversational outfit editing**, deployed on the school server.

## Demo video

https://github.com/user-attachments/assets/0627ab2e-b013-40cf-a831-02dcfa68c73e

---

## What this demo demonstrates

### Features implemented

**Accounts and onboarding (P18, P19)**
- **Login required:** ID/password registration and login, 30-day Bearer tokens stored only as SHA-256 hashes, and an Android session encrypted with Keystore AES-256-GCM. Logout, token expiry or any 401 returns to the login screen.
- **Account isolation:** every garment, outfit and profile belongs to one account; another account's IDs return 404.
- **Eight-step onboarding:** preferred fit, mood and colours, then optional body analysis and first garments. The style and body profile are saved to the account on the server.
- **Manual import of earlier local data:** from 마이프로필, the user can import an older on-device snapshot into the account, with conflict choices and safe retries.

**Wardrobe registration (extended from Iteration 1)**
- AR measurement as before, plus a **front photo** for tops and outerwear.
- One Gemini call now also suggests the subcategory, styles, details and visible materials.
- **"내 몸 기준 라벨":** fit, length, sleeve, season, colour and category are calculated from the garment's measurements and the user's body, shown with their reasons, and confirmed or corrected by the user before saving.

**Outfit recommendation and 코디북 (P20, P21)**
- **Today's outfits on 홈:** three outfits built only from the user's own wardrobe, using the current weather.
- **Scoring:** compatibility 30 % (PMI statistics + a masked attribute transformer trained on K-Fashion), body and fit 25 %, colour 15 %, weather 15 %, taste 15 %. K-Fashion is used only as training data; its garments are never shown.
- **Feedback:** 좋아요 / 별로예요 updates the user's taste weights; outfits can be saved to and renamed in the **코디북** tab.

**Conversational outfit editing (PR #28)**
- **대화로 수정하기** on 홈 opens a chat for the current outfit.
- Tap a garment to **lock** it; quick buttons (for example, brighter top) apply without calling a model.
- Free text such as "상의만 밝게", "바지는 그대로" or "출근용으로 바꿔줘" is turned into structured constraints by Gemini or OpenRouter, and the existing recommendation engine picks the garments. A text "keep" applies only to that edit; a tapped lock persists.
- The revised outfit is saved to the 코디북 only when the user saves it.

**Infrastructure and testing**
- **School server deployment:** K3s + Argo CD with MySQL, a persistent volume for garment photos and an ingress at `http://swpp26t14.147-46-242-169.sslip.io`. Every merge to `main` builds the image on GHCR and Argo CD deploys it.
- **Coverage in CI:** backend statement/branch coverage per component and Android JaCoCo coverage per layer are written to each CI run, with the reports attached.

### Major progress made during Iteration 2

| Area | Iteration 1 | Iteration 2 |
|---|---|---|
| Users | Single local user, no login | Login required, per-account data, onboarding saved to the server |
| Wardrobe | AR measurement and Gemini name/category/colour | Front photo, extended Gemini fields, body-based labels confirmed before saving |
| 홈 | Placeholder | Three outfits from the user's wardrobe and the weather, feedback, 코디북 |
| Editing | — | Chat with locks, quick buttons and free text, reusing the recommendation engine |
| Deployment | Azure VM | School server (K3s + Argo CD) with MySQL and persistent photo storage |
| Testing | Unit tests in CI | Coverage targets and per-component coverage in CI; see the [Testing Documentation](https://github.com/snuhcs-course/swpp-2026-project-team-14/wiki/Testing-Documentation) |

Known limitations in this demo:
- Wardrobe measurement needs an ARCore-supported physical phone; the emulator cannot run AR capture.
- Free-text chat needs `GEMINI_API_KEY` or `OPENROUTER_API_KEY` on the server; when the model is unavailable the app shows a retryable error (buttons and locks still work).
- Shoes are not recommended, because K-Fashion has no shoe data.
- Body-measurement accuracy is validated on synthetic bodies only; real-photo validation with tape measurements is still open.
- The school endpoint is plain HTTP, so it is used by debug builds only.
- Android ViewModel coverage (33.9 %) is below its 60 % target; ViewModel unit tests are planned for Iteration 3.

---

## How to run the demo

### Environment we used

| | Version |
|---|---|
| OS | Windows 11 |
| Android Studio | Bundled JDK 21 (JBR 21.0.10) |
| Android emulator | "Medium Phone", Android 16 (API 36), x86_64 |
| App | Kotlin, Jetpack Compose, Gradle wrapper 9.3.1, compile/target SDK 36, minSdk 26 |
| Wardrobe capture device | ARCore-supported Android phone with Google Play Services for AR |
| Backend (local) | Python 3.11.9, Django 5.2, MySQL, ONNX Runtime 1.23.2 |
| Backend (CI and deployed) | Python 3.12, Docker image with gunicorn, K3s + Argo CD on the school server |
| External APIs | Gemini (`gemini-3.1-flash-lite`) for garment analysis and chat; OpenRouter optional for chat |

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
copy .env.example .env
```

Fill in `backend/.env`:

| Variable | Value |
|---|---|
| `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD` | An existing local MySQL database and account (required for accounts, wardrobe and outfits) |
| `GEMINI_API_KEY` | Needed for garment analysis and free-text chat |
| `OPENROUTER_API_KEY` | Optional; with `CHAT_PROVIDER=auto`, chat uses OpenRouter when this is set |

Keep `.env` out of Git. Then prepare the HRNet garment model with the [model setup instructions](docs/wardrobe/local-development.md#4-모델-준비) and start the server:

```powershell
.\.venv\Scripts\python.exe manage.py migrate
.\.venv\Scripts\python.exe manage.py runserver 0.0.0.0:8000
```

Check: `curl.exe http://127.0.0.1:8000/healthz/` returns `{"status": "ok"}`.

**Optional sample wardrobe for the chat demo:** the script below creates the local account `samplechat` (password `samplechat2026`) with 14 fictional garments, so recommendations and chat can be tried without AR capture. It runs only against a local database.

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

1. **Sign up** with an ID (4–20 lowercase letters, digits or `_`) and a password (8–64 characters), or log in as `samplechat` if you ran the sample script.
2. **Onboarding:** choose preferred fit, mood and colours. Body analysis and first garments are optional; for body analysis, the demo photos from the `iteration-1-demo` branch (`demo/photos/`) can be used.
3. **Add a garment** (physical phone): 옷장 → **촬영하기** → choose the type → AR capture → check points → **확인** → front photo for tops and outerwear → **분석하기** → review "내 몸 기준 라벨" → **옷장에 추가**.
4. **홈:** see three outfits for today's weather. Tap **좋아요** or **별로예요**, or save an outfit to the **코디북**.
5. **대화로 수정하기:** tap a garment to lock it, use a quick button, or type "상의만 밝게" or "출근용으로 바꿔줘". Save the result to the 코디북.
6. **마이프로필:** check the saved profile, log out, and log back in; the account's data is restored.

### Tests

From the repository root (PowerShell):

```powershell
cd backend
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe manage.py test apps.users apps.wardrobe apps.outfits --settings=config.test_settings --noinput
cd ..\frontend
.\gradlew.bat --no-daemon testDebugUnitTest
```

The Django tests use an isolated in-memory database and mock paid AI calls. Coverage commands, device-test setup and the latest results are in the [Testing Documentation](https://github.com/snuhcs-course/swpp-2026-project-team-14/wiki/Testing-Documentation).

---

## Repository layout

| Path | Contents |
|---|---|
| `frontend/` | Android app (Kotlin, Jetpack Compose): `data/` repositories and stores, `ui/` screens including `home/`, `wardrobe/`, `profile/` and `chat/` |
| `backend/` | Django API: `apps/users/` (accounts, import), `apps/wardrobe/`, `apps/outfits/` (recommend, 코디북, chat), `body_analysis/`, `recommendation/` (engine and models) |
| `infra/` | Kubernetes manifests deployed by Argo CD (backend, MySQL, storage, ingress) |
| `docs/` | Design notes for body analysis, wardrobe, login, recommendation and deployment |

Requirements, design and testing documents are on the [GitHub Wiki](https://github.com/snuhcs-course/swpp-2026-project-team-14/wiki).

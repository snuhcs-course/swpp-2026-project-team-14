# Body Analysis — Feature Design

> Owner: Dongkun Moon (P9 screen, P10 AI integration) · Status: Iteration 1 draft, rev. 2 (2026-09-24)
> Stack (team decision): **Android — Kotlin + Jetpack Compose** · **Backend — Django (REST API)** · MySQL · object storage for images · Python AI module
> Wiki targets: *Requirements Specification* (§1–4) and *Design Documentation* (§5–9)

**Rev. 2 changes:**
- Scope changed from "body-shape category" to **garment measurements** (§2).
- Photos are now **front + side, both required** (rev. 3, 2026-09-24: circumferences and the waist row depend on body depth, so there is no front-only mode).
- Users are guided to wear **underwear or tight clothing**; loose clothing still works but lowers confidence.
- Privacy requirements are stricter because photos may show underwear.

**Rev. 4 changes (2026-09-28), after trying the prototype:**
- Users are **no longer asked what they wore**. The server detects loose tops and bottoms from the silhouette (`06-clothing-detection.md`) and warns per region.
- Results and My Profile show a **cartoon body figure**: key measurements are pinned next to it, and tapping any body part shows that measurement.
- Insights are cards led by a **body-shape type** (하체 볼륨형 / 상체 볼륨형 / 허리 라인형 / 일자형).

**Open decisions (Iteration 1):**
- **The results design is not final.** How to present the measurements (the body figure with values pinned around it, US-B2 AC1) is still to be discussed by the team. The current screen is a draft for that discussion.
- **Confidence badges (높음 / 보통 / 낮음) are included on purpose for this stage** so we can check the pipeline. Users do not need to see them, so they will be removed from the final iteration. The confidence values stay in the API for internal use.

## 1. User stories and acceptance criteria

**US-B1 — Measure my body during onboarding**
*As a new user, I want to take two photos and enter my height so that StyleMate knows my body measurements without a tape measure.*
- AC1: Before the camera, a capture guide explains:
  - wear underwear or tight clothing
  - stand in an A-pose
  - keep the full body in frame
  - hold the phone at waist height
  - use a plain background
- AC2: I add a **front photo and a side photo (both required)** from the camera or the gallery, and I see previews. "분석하기" stays disabled until both are added.
- AC3: Height is required (100–220 cm). Weight (30–200 kg) and gender (for size charts) are optional.
- AC4: I am not asked what I wore. The input screen says underwear or tight clothing gives the best accuracy and that loose clothing is detected automatically.
- AC5: While analysis runs I see a progress state. On success I see results within 5 s (p95, server path).
- AC6: If a photo is unusable (no person, several people, body cut off, wrong view), I get a specific retake hint.
- AC7: I can skip and create the profile later from My Profile.

**US-B2 — Review and correct the measurements**
*As a user, I want to see each estimated measurement and fix wrong ones.*
- AC1: A body figure matching my gender (female / male / neutral) shows 어깨너비·가슴둘레·허리둘레·엉덩이둘레·안쪽 다리길이 as always-visible chips. Every other measurement is a highlighted dot; tapping it shows its name, description, value and confidence badge. The full list (lengths and circumferences) is available, collapsed, below the insights.
- AC2: If the server detects a loose top and/or bottom, a warning names the affected measurements and suggests retaking in underwear or tight clothing. Only the affected measurements lose confidence. The analysis still returns results.
- AC3: If I gave no weight, a warning says circumferences would be more accurate with it.
- AC4: "수정" on the tapped part (or tapping a row in the full list) opens an editor. An edited value is marked "직접 수정함" and treated as ground truth.
- AC5: I choose a preferred fit (slim / regular / loose) and up to 3 styles (미니멀, 캐주얼, 스트릿, 러블리, 클래식, 스포티). A 4th style cannot be selected.
- AC6: "옷장 등록하고 시작하기" saves the profile and opens the main app.

**US-B3 — See my profile and insights (My Profile tab)**
- AC1: Shows the same body figure and insight cards as the results screen (read-only), the full list collapsed, and basic info including the detected clothing ("자동 판단 · 몸에 붙는 옷 / 헐렁한 상의 …").
- The results screen shows the same insight cards directly below the figure, before the profile is saved.
- AC2: Insights use neutral, styling-only wording. No weight/health/"obesity" terms.
- AC3: A disclaimer states the values are approximate styling estimates, not medical measurements.
- AC4: An insight is hidden when loose clothing was detected in a region it depends on (body type: top and bottom; leg proportion: bottom; shoulders: top), and a note suggests retaking in tight clothing. An insight is shown again if the user typed its values in themselves. Evidence: `04-synthetic-benchmark.md` §8.
- AC5: An insight is only shown when its ratio is clearly past the rule's threshold (margin ≈ measurement error). Near the threshold nothing is shown rather than a possibly wrong sentence. Evidence: `04-synthetic-benchmark.md` §9.

**US-B4 — Re-measure**
- AC1: "체형 다시 분석하기" reopens the flow pre-filled with my height, weight, gender and preferences.
- AC2: The previous profile stays until the new one is confirmed. Leaving the flow keeps the old profile.

**US-B5 — Privacy of body photos**
- AC1: The capture guide and input screen state that photos are used only for measurement, deleted right after analysis, and only numbers are stored.
- AC2: Photos are never written to disk or object storage on the server. They are processed in memory and discarded on success or failure.
- AC3: Photos are never sent to third-party AI APIs.
- AC4: I can delete my body profile at any time.

## 2. Measurement set

Names follow ISO 8559-1 (garment sizing). The "Photos" column shows which photo the value is mainly measured from; both photos are always required.
Expected accuracy assumes underwear or tight clothing. It is validated in the benchmark (`03-benchmark-plan.md`).

| Group | Measurement (UI label) | Definition | Photos | Expected accuracy | Main garment use |
|---|---|---|---|---|---|
| Length | Shoulder width (어깨너비) | acromion to acromion across the back | front | high | tops, jackets |
| Length | Sleeve length (소매길이) | shoulder point → wrist, arm slightly bent | front | high | tops, outerwear |
| Length | Torso length (상체길이) | neck base (C7) → natural waist | front + side | high | top length, crop vs. regular |
| Length | Rise (밑위길이) | natural waist → crotch | front + side | medium | trousers rise |
| Length | Inseam (안쪽 다리길이) | crotch → floor | front | high | trousers length |
| Length | Outseam (바깥 다리길이) | natural waist → floor, outside leg | front | high | trousers length |
| Circ. | Neck (목둘레) | around the neck base | front + side | low | shirt collar |
| Circ. | Chest / bust (가슴둘레) | fullest part of chest, horizontal | front + side | medium | tops |
| Circ. | Underbust (밑가슴둘레, female) | directly below the bust | front + side | low | underwear, fitted tops |
| Circ. | Natural waist (허리둘레) | narrowest point of the torso | front + side | medium | trousers, skirts, dresses |
| Circ. | Hip (엉덩이둘레) | fullest part of the buttocks | front + side | medium | trousers, skirts |
| Circ. | Armhole (암홀둘레) | around the arm–torso junction | front + side | low | sleeve fit |
| Circ. | Bicep (팔뚝둘레) | fullest part of the upper arm | front + side | medium | sleeve fit |
| Circ. | Wrist (손목둘레) | around the wrist bone | front | low | cuffs |
| Circ. | Thigh (허벅지둘레) | fullest part of the upper thigh | front + side | medium | trousers fit |
| Circ. | Calf (종아리둘레) | fullest part of the calf | front + side | medium | slim trousers, boots |

**Confidence rules** (v1, implemented in the prototype's `FakeBodyAnalyzer.confidenceFor` and to be reused by the server):
- Start from the expected accuracy above.
- Downgrade one level for the measurements a detected loose garment distorts: top → neck, shoulder width, chest, underbust, waist, armhole, bicep, torso length; bottom → hip, thigh, calf, inseam, rise (`LOOSE_AFFECTS`, same table on server and app).
- The real pipeline additionally lowers confidence for poor landmark visibility, crop or angle.

**Derived styling features** (computed from the measurements, used by recommendation):
- shoulder-to-hip ratio
- waist-to-hip ratio
- torso-to-leg ratio (torso length / inseam)
- inseam/height
- chest-to-hip balance

## 3. Non-functional requirements

| ID | Requirement | Target |
|---|---|---|
| NFR-B1 | Analysis latency (server, CPU) | p95 ≤ 5 s including upload on Wi-Fi; inference ≤ 1.5 s for two photos |
| NFR-B2 | Robustness | ≥ 90 % of valid photos produce results; invalid photos get a specific retake reason |
| NFR-B3 | Accuracy, tight clothing, both photos (vs. tape) | lengths MAE ≤ 2.5 cm; chest/waist/hip MAE ≤ 4 cm; low-confidence items reported but not targeted |
| NFR-B4 | Loose-clothing behaviour | detected without asking the user (synthetic held-out: top recall 1.00, bottom 0.96, false alarms ≤ 1.1 %); still returns all measurements; affected confidence lowered; warning shown whenever detected |
| NFR-B5 | Consistency | same person, two sessions: lengths within 2 cm, chest/waist/hip within 3 cm |
| NFR-B6 | Privacy | photos never persisted, never sent to third parties; HTTPS only |
| NFR-B7 | Usefulness | user correction rate tracked in beta; target < 30 % of high-confidence fields edited |
| NFR-B8 | Cost | $0 per analysis in the default pipeline |

## 4. Key constraints

- A phone photo cannot give tape-measure accuracy. All values are approximate, labelled with confidence, and editable.
- Accurate circumferences need body depth, hence the side photo. Accurate scale needs a known height, hence height is required.
- Photos may show underwear, so this is highly sensitive personal data (PIPA). Minimise, process in memory, delete immediately. On-device extraction is the preferred long-term design (§6, decision 4).
- Research-only licenses (SMPL, BodyM, Sapiens-1) are fine for the course but must not end up in a commercial path.
- Backend must run on a CPU-only free-tier instance.
- Iteration 1 budget: P9 7 h + P10 5 h. Iteration 1 therefore ships the UI prototype with a fake analyzer plus the API contract; the real pipeline follows in Iterations 2–3.

## 5. UI flow (Compose) — implemented in `frontend/`

```
first launch
  └▶ [체형 촬영 가이드]  A-pose illustration (front / side), what to wear, privacy note   (건너뛰기 → main)
       └▶ [체형 분석 입력]  front photo* / side photo* (camera or album), "wear tight clothing" tip,
             height* / weight / gender  →  "분석하기" (enabled when both photos + valid height)
             └▶ [분석 중]
                  └▶ [AI가 추정한 체형]  warnings → body figure (5 pinned values, tap a part → detail + 수정)
                        → 체형 인사이트 → 전체 치수 보기 (collapsed) → 선호 핏, 선호 스타일 (≤ 3)
                        →  "옷장 등록하고 시작하기"
                          └▶ main app, bottom nav: 홈 | 옷장 | 마이프로필
[마이프로필]  body figure, insights, 전체 치수 (collapsed), basic info, disclaimer, "체형 다시 분석하기", "체형 프로필 삭제"
```

Code structure:

| File | Role |
|---|---|
| `data/BodyMeasurements.kt` | `MeasurementType` (§2 table), `Confidence`, `KEY_MEASUREMENTS`, `DetectedClothing` + `LOOSE_AFFECTS`, `BodyProfile`, `toggleStyle` |
| `data/BodyFigureGeometry.kt` | pure figure geometry: proportions per gender, `hotspotFor(type, gender)`, tap hit-testing (unit-tested) |
| `data/BodyAnalyzer.kt` | `BodyAnalyzer` interface, `FakeBodyAnalyzer` (placeholder values + real confidence rules), `BodyShape`, `buildInsights` → `Insight` cards |
| `ui/profile/BodyProfileViewModel.kt` | `SetupState` / `AppState` as `StateFlow`; drops photos right after analysis |
| `ui/profile/*Screen.kt` | Guide, input, analyzing, review, My Profile |
| `ui/profile/BodyFigure.kt` | Canvas-drawn figure, pinned key chips, detail card |
| `ui/profile/BodyResultComponents.kt` | insight cards, collapsible full measurement list |
| `ui/StyleMateApp.kt` | switches between setup flow and bottom-nav tabs (home / wardrobe are placeholders for P8, P11, P12) |

**Implemented (2026-09-24):** `RemoteBodyAnalyzer` (OkHttp) posts both photos to the endpoint in §7.
- The base URL comes from `BuildConfig.BODY_API_BASE_URL`: `local.properties` → `stylemate.apiBaseUrl`, default `http://10.0.2.2:8000` for the emulator.
- Camera photos are captured at full resolution into a temporary cache file that is deleted after decoding.
- EXIF rotation is applied, and photos are re-encoded as JPEG, which drops EXIF metadata.
- Server `hint`s are shown on the input screen as retake messages.
- `FakeBodyAnalyzer` remains for previews and tests.

## 6. System architecture

```
┌──────────────── Android (Compose) ───────────────┐
│ Setup screens / MyProfileScreen                  │
│ BodyProfileViewModel ─ BodyAnalyzer (interface)  │
│   ├ RemoteBodyAnalyzer (OkHttp → Django)  default│
│   └ FakeBodyAnalyzer   (previews / tests)        │
└───────────────┬──────────────────────────────────┘
                │ HTTPS multipart: front, side, height, weight?, gender
┌───────────────▼──────── Django backend ──────────┐
│ profiles app (DRF): analyze / confirm / get / delete │
│ BodyProfile model (MySQL)                        │
└───────────────┬──────────────────────────────────┘
                │ in-process call, photos kept in memory only
┌───────────────▼──────── body_analysis (pure Python) ─┐
│ QualityGate (person count, full body, view check)    │
│ → PoseEstimator (MediaPipe / RTMPose)                │
│ → Segmenter (person mask; SCHP parsing for clothing) │
│ → Measurer: front widths + side depths at landmark-  │
│   defined heights → ellipse/regression circumference │
│   → lengths from landmarks, scaled by height         │
│ → ClothingDetector (loose top / bottom, logistic)    │
│ → Calibrator (ridge correction, fitted regions only) │
│ → ConfidenceScorer + warnings                        │
└──────────────────────────────────────────────────────┘
```

Architectural decisions:
1. **The AI is a pure-Python package with no Django imports.** It is reused by the benchmark harness, and each stage sits behind an interface (Strategy pattern, a candidate for the Iteration 5 design-pattern work).
2. **Photos never touch storage.** The storage teammate's image service is not needed for this feature, which removes a cross-team dependency.
3. **Numbers, not pictures, are the contract.** Recommendation and chat editing read measurements and derived features only.
4. **On-device option.** MediaPipe also runs on Android. Pose and masks can move into the app so only landmarks and widths are uploaded, a privacy-driven technical constraint.
5. **The client depends on an interface.** `BodyAnalyzer` lets the UI ship now with a fake and switch to the server without UI changes.

## 7. API (Django, implemented in `backend/apps/body_profiles`)

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/api/body-profile/analyze/` | multipart: `front_photo` (req), `side_photo` (req), `height_cm` (req), `weight_kg`, `gender` (`female`/`male`/`unspecified`). A `clothing` field from older app builds is ignored. | `200 {draft}` or `422 {"error", "hint"}` |
| PUT | `/api/body-profile/` | confirmed profile (draft + edits + preferences) | `200 {profile}` |
| GET | `/api/body-profile/` | — | `200 {profile}` / `404` |
| DELETE | `/api/body-profile/` | — | `204` |

Error responses:
- **`422`** (with Korean `hint`): `front_photo_required`, `side_photo_required`, `invalid_image`, `no_person`, `multiple_people`, `not_frontal`, `not_side_view`, `body_cropped`.
  - Orientation is checked before visibility, so a side photo uploaded as the front gets "정면으로 바라보고", not "전신이 나오게".
- **`400`:** `invalid_field` for height outside 100–220, weight outside 30–200, or an unknown gender value.
- **`413`:** `photo_too_large` above 8 MB.

Draft response:
```json
{
  "analysis_id": "b1f3…",
  "pipeline_version": "baseline-geometric-1.0+clothing-1+ridge-2",
  "inputs": {"height_cm": 172, "weight_kg": 65, "gender": "male"},
  "measurements": [
    {"type": "shoulder_width", "value_cm": 44.5, "confidence": "high"},
    {"type": "chest",          "value_cm": 95.5, "confidence": "low"},
    {"type": "inseam",         "value_cm": 78.0, "confidence": "high"}
  ],
  "derived": {"shoulder_hip_ratio": 0.48, "waist_hip_ratio": 0.85, "torso_leg_ratio": 0.58},
  "warnings": ["loose_top"],
  "clothing": {"top": "loose", "bottom": "fitted", "top_loose_probability": 0.93, "bottom_loose_probability": 0.04}
}
```

## 8. Data model

```python
class BodyProfile(models.Model):
    user = models.OneToOneField(User, on_delete=models.CASCADE)
    height_cm = models.PositiveSmallIntegerField()
    weight_kg = models.PositiveSmallIntegerField(null=True)
    gender = models.CharField(max_length=12, default="unspecified")
    clothing = models.JSONField()                       # detected: {"top": "fitted|loose", "bottom": …}

    # [{type, value_cm, confidence, source: "ai" | "user"}] — types from §2
    measurements = models.JSONField()
    derived = models.JSONField()                        # styling ratios, recomputed on save

    preferred_fit = models.CharField(max_length=12)     # slim | regular | loose
    preferred_styles = models.JSONField()               # ≤ 3 of the 6 style keys

    pipeline_version = models.CharField(max_length=32)
    confirmed_at = models.DateTimeField()
    updated_at = models.DateTimeField(auto_now=True)
```
To be aligned with 김기환's P6 data model before merging. No image fields, by design.

Interface for other components (`profiles.selectors.get_styling_profile(user)`):
```python
@dataclass(frozen=True)
class StylingProfile:
    measurements_cm: dict[str, float]
    confidence: dict[str, str]
    shoulder_hip_ratio: float
    waist_hip_ratio: float
    torso_leg_ratio: float
    preferred_fit: str
    preferred_styles: list[str]
    def to_prompt_block(self) -> str: ...   # compact text for the chat-editing LLM
```

## 9. Test plan

**Implemented (Iteration 1, `frontend/app/src/test/.../BodyMeasurementsTest.kt`, 7 tests passing):**
- style selection refuses a 4th style
- underwear + both photos keeps base confidence
- a detected loose top / bottom lowers only the affected measurements and adds `loose_top` / `loose_bottom`
- insights: body-type classification, per-region hiding, margins, neutral wording (`InsightsTest`)
- every measurement has a tappable place on each figure, and tapping a part's centre selects it (`BodyFigureGeometryTest`)
- underbust only for female
- no warnings for ideal input

**Implemented (P10 baseline, `backend/tests`, 22 tests passing):**
- **Geometry:** mask segments, clamped widths, ellipse perimeter.
- **Measurer vs. a synthetic mannequin** (`tests/synthetic.py`: front and side masks drawn from known width and depth profiles):
  - every length within 0.5–1.5 cm
  - torso circumferences within 3 %
  - legs and arms
  - derived ratios
- **Pipeline:**
  - confidence rules (ideal, detected loose top / bottom)
  - a missing side photo is rejected with `side_photo_required`
  - underbust only for female
  - API-shaped output
  - quality-gate codes (`no_person`, `multiple_people`, `body_cropped`, `not_frontal`, `not_side_view`, `invalid_image`)
  - large-image downscaling
- **MediaPipe smoke test:** the real model loads.
  - This test found that MediaPipe's C++ loader cannot open paths with Korean characters. The model is now loaded from bytes.

Known limitation found by the tests: a waist that is flat over several cm leaves the waist row ambiguous by about ±1.5 cm, which moves rise, outseam and torso length. The measurer takes the middle of the near-minimum rows.

**Planned:**
- **Server unit (pytest):** serializer validation; no photo is persisted after a request.
- **Integration:** analyze → confirm → get → delete round trip.
- **UI (Compose test):** state transitions; "분석하기" disabled without both photos and a valid height; 4th style chip disabled; warning banner shown for detected loose clothing.
- **Smoke:** Galaxy S23 against a dev server.

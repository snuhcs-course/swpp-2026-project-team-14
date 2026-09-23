# Body Analysis — Feature Design

> Owner: Dongkun Moon (P9 screen, P10 AI integration) · Status: Iteration 1 draft (2026-09-23)
> Stack (team decision): **Android — Kotlin + Jetpack Compose** · **Backend — Django (REST API)** · MySQL · object storage for images · Python AI module
> Wiki targets: *Requirements Specification* (§1–3) and *Design Documentation* (§4–8)

## 1. User stories and acceptance criteria

**US-B1 — Create my body profile during onboarding**
*As a new user, I want to upload a full-body photo and my height so that StyleMate understands my proportions without me measuring myself.*
- AC1: I can take a photo with the camera or pick one from the gallery, and I see a preview before analysis.
- AC2: Height is required (100–220 cm). Weight and gender/fit category are optional.
- AC3: While the analysis runs I see a progress state. On success I see the result within 5 s (p95, server path).
- AC4: If the photo is unusable (no person, several people, body cut off, strong side angle), I get a specific retake hint instead of a result.
- AC5: I can skip onboarding and fill the profile manually later.

**US-B2 — Review and correct the AI result**
*As a user, I want to see what the AI estimated and fix anything wrong, so that recommendations use correct information.*
- AC1: Estimated fields (top size, bottom size, silhouette, shoulder/hip balance, torso/leg balance) are shown with a confidence hint. Low-confidence fields are highlighted.
- AC2: Every field can be edited with a picker. Editing marks the field as `user_confirmed`.
- AC3: I choose a preferred fit (slim / regular / loose) and up to 3 preferred styles (미니멀, 캐주얼, 스트릿, 러블리, 클래식, 스포티). Choosing a 4th style is blocked.
- AC4: Pressing "옷장 등록하고 시작하기" saves the profile and moves to the wardrobe.

**US-B3 — Understand my body profile (My Profile tab)**
*As a user, I want easy-to-read insights about my proportions and what styles balance them.*
- AC1: The profile tab shows the confirmed values and 2–4 insight sentences (e.g. torso/leg balance → recommended rise and top length).
- AC2: Insights use neutral, non-judgemental wording. No weight/health/"obesity" terms.
- AC3: A disclaimer states that values are approximate styling estimates, not measurements.

**US-B4 — Re-analyse**
*As a user whose body or preferences changed, I want to re-run the analysis from My Profile.*
- AC1: "체형 다시 분석하기" runs US-B1/US-B2 again, pre-filled with my current values.
- AC2: The previous profile is kept until I confirm the new one.

**US-B5 — Privacy of my body photo**
*As a user, I want my body photo to be used only for analysis.*
- AC1: Before the first upload, a consent notice explains what is sent, what is extracted, and that the photo is deleted.
- AC2: The photo is deleted from the server immediately after analysis (success or failure). Only numeric features are stored.
- AC3: I can delete my body profile at any time.

## 2. Non-functional requirements

| ID | Requirement | Target |
|---|---|---|
| NFR-B1 | Analysis latency (server, CPU) | p95 ≤ 5 s including upload on Wi-Fi; model inference ≤ 1 s |
| NFR-B2 | Robustness | ≥ 90 % of valid full-body front photos produce a result; invalid photos get a specific retake reason |
| NFR-B3 | Consistency | Same person, two photos: silhouette class identical in ≥ 80 % of cases; ratio difference ≤ 0.05 |
| NFR-B4 | Accuracy (vs. tape measure, test set) | shoulder-to-hip ratio MAE ≤ 0.08; top-size exact-or-adjacent ≥ 90 % |
| NFR-B5 | Privacy | body photo never persisted; not sent to third-party APIs in the default pipeline |
| NFR-B6 | Usefulness | user correction rate tracked; target < 40 % of fields edited on average |
| NFR-B7 | Cost | $0 per analysis in the default pipeline (LLM insight call optional, ≤ 1 call per confirmed profile) |

## 3. Key constraints

- One phone photo cannot give exact measurements, so every output is approximate and editable.
- The model licenses we may use for research (SMPL, BodyM, Sapiens-1) are **non-commercial**. They are allowed for the course but must not end up in the default production path.
- Backend must run on a CPU-only free-tier cloud instance.
- Body photos are sensitive personal data (PIPA). Minimise and delete (team task "Define Photo Privacy Rules").
- Iteration 1 budget is P9 7 h + P10 5 h, so Iteration 1 ships Option 1 (baseline). Options 2 and 3 follow in Iterations 2–3.

## 4. UI flow (Compose)

```
[Splash] ──first launch──▶ [Onboarding: 프로필 설정]
                              │  photo box (camera / gallery) + height, weight, gender inputs
                              │  "분석하기"
                              ▼
                           [Analyzing…]  ── fail(reason) ──▶ retake hint (stay on screen)
                              ▼
                           [Result review]  AI가 추정한 체형 (editable chips, confidence dots)
                              │  선호 핏 · 선호 스타일 (max 3)
                              │  "옷장 등록하고 시작하기"
                              ▼
                           [Wardrobe]   (P11/P12)
Bottom nav: 홈 | 옷장 | 마이프로필
[마이프로필] ── "체형 다시 분석하기" ──▶ [Onboarding screen in re-analysis mode]
[마이프로필] shows: confirmed values, insight cards, preferences, "프로필 삭제"
```

Compose structure (P9):
- `BodyProfileSetupScreen` (stateless) + `BodyProfileViewModel` (StateFlow `UiState`)
- `UiState = Idle | PhotoSelected | Analyzing | Review(draft) | Error(reason) | Saved`
- `BodyProfileRepository` → Retrofit `BodyProfileApi`
- Photo pick: `ActivityResultContracts.PickVisualMedia` / `TakePicture`. Downscale to max 1280 px and strip EXIF before upload.

## 5. System architecture

```
┌──────────────── Android (Compose) ───────────────┐
│ BodyProfileSetupScreen / MyProfileScreen         │
│ BodyProfileViewModel ─ BodyProfileRepository     │
└───────────────┬──────────────────────────────────┘
                │ HTTPS, multipart (photo + height/weight/gender)
┌───────────────▼──────── Django backend ──────────┐
│ profiles app (DRF)                               │
│  views: analyze / confirm / get / delete         │
│  services.body_analysis  ◀── AnalysisPipeline    │
│  models: BodyProfile (MySQL)                     │
└───────────────┬──────────────────────────────────┘
                │ in-process Python call
┌───────────────▼──────── body_analysis package ───┐
│ QualityGate → PoseEstimator (MediaPipe)          │
│ → Segmenter (mask; SCHP in Option 2)             │
│ → FeatureExtractor (widths, lengths, ratios)     │
│ → ProfileEstimator (rules v1 / regressor v2)     │
│ → InsightGenerator (templates v1 / LLM v2)       │
└──────────────────────────────────────────────────┘
```

Architectural decisions:
1. **AI runs inside Django as a pure-Python package (`body_analysis/`) with no Django imports.** It is testable on its own, reusable by the benchmark harness, and swappable (each stage is an interface → Strategy pattern, a natural candidate for the Iteration 5 design-pattern refactoring).
2. **Photo is processed in memory and never written to object storage.** The storage teammate's image service is not needed for this feature, which removes a cross-team dependency for Iteration 1.
3. **Numbers, not pictures, are the contract with other components.** The recommender and chat editor read `BodyProfile.features`. Neither ever sees a photo.
4. **Future option:** move `PoseEstimator` + `Segmenter` on-device (MediaPipe Android) and send only landmarks/widths. The server-side `FeatureExtractor` stays the same.

## 6. API (Django REST Framework)

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/api/body-profile/analyze/` | multipart: `photo` (jpg/png ≤ 8 MB), `height_cm` (req), `weight_kg`, `gender` (`female`/`male`/`unspecified`) | `200 {draft}` or `422 {"error": "<code>", "hint": "…"}` |
| PUT | `/api/body-profile/` | confirmed profile JSON (draft + user edits + preferences) | `200 {profile}` |
| GET | `/api/body-profile/` | — | `200 {profile}` / `404` |
| DELETE | `/api/body-profile/` | — | `204` |

Error codes for `422`: `no_person`, `multiple_people`, `body_cropped`, `not_frontal`, `low_quality`, `invalid_image`.

Draft response example:
```json
{
  "analysis_id": "b1f3…",
  "pipeline_version": "baseline-1.0",
  "inputs": {"height_cm": 172, "weight_kg": null, "gender": "unspecified"},
  "features": {
    "shoulder_hip_ratio":   {"value": 1.12, "confidence": 0.78},
    "waist_definition":     {"value": 0.18, "confidence": 0.55},
    "torso_leg_ratio":      {"value": 0.94, "confidence": 0.83},
    "shoulder_width_cm":    {"value": 43.0, "confidence": 0.60}
  },
  "silhouette": {"value": "inverted_triangle", "confidence": 0.62},
  "size_estimate": {"top": "M", "bottom": "30", "confidence": 0.45},
  "quality": {"clothing_looseness": "medium", "warnings": ["loose_top"]},
  "insights": [
    {"key": "long_legs", "text": "다리가 상체에 비해 긴 편이라 크롭 기장 상의나 하이웨이스트 하의가 비율을 살려줘요."}
  ]
}
```

## 7. Data model

```python
class BodyProfile(models.Model):
    user = models.OneToOneField(User, on_delete=models.CASCADE)
    height_cm = models.PositiveSmallIntegerField()
    weight_kg = models.PositiveSmallIntegerField(null=True)
    gender = models.CharField(max_length=12, default="unspecified")

    # structured body state (reused by recommendation and chat editing)
    features = models.JSONField()          # {name: {value, confidence, source}}; source ∈ {ai, user}
    silhouette = models.CharField(max_length=24)   # rectangle | triangle | inverted_triangle | oval | hourglass
    top_size = models.CharField(max_length=8)
    bottom_size = models.CharField(max_length=8)

    # user preferences
    preferred_fit = models.CharField(max_length=12)   # slim | regular | loose
    preferred_styles = models.JSONField()             # ≤ 3 of the 6 style keys

    insights = models.JSONField(default=list)
    pipeline_version = models.CharField(max_length=32)
    confirmed_at = models.DateTimeField()
    updated_at = models.DateTimeField(auto_now=True)
```
The table name and field types are to be aligned with 김기환's P6 data-model document before merging.

**Interface for other components** (`profiles.selectors.get_styling_profile(user) -> StylingProfile`):
```python
@dataclass(frozen=True)
class StylingProfile:
    silhouette: str
    shoulder_hip_ratio: float
    waist_definition: float
    torso_leg_ratio: float
    confidence: dict[str, float]     # recommender down-weights low-confidence features
    top_size: str; bottom_size: str
    preferred_fit: str; preferred_styles: list[str]
    def to_prompt_block(self) -> str: ...   # compact text for LLM prompts in chat editing
```
This keeps the chat editor's LLM prompt small (a few numbers instead of a photo). That supports the professor's point about managing structured state for cost-efficient, consistent editing.

## 8. Feature definitions (baseline v1)

All widths are measured on the person mask at rows defined by landmarks, and normalised by body height in pixels (nose-to-heel scaled by 1.08 to approximate the full head).

| Feature | Definition |
|---|---|
| `shoulder_width` | mask width at shoulder landmarks' y, or landmark distance × 1.15, whichever is smaller (limits hood/padding inflation) |
| `hip_width` | max mask width in the band from 10 % above to 15 % below the hip landmarks' y |
| `waist_width` | min mask width between shoulder y + 55 % of torso length and hip y |
| `shoulder_hip_ratio` | `shoulder_width / hip_width` |
| `waist_definition` | `1 − waist_width / max(shoulder_width, hip_width)` |
| `torso_leg_ratio` | (shoulder-mid → hip-mid length) / (hip-mid → ankle-mid length) |
| `*_cm` | normalised value × `height_cm` (only reported when height is given; confidence capped at 0.6) |

Silhouette rules (v1, thresholds tuned in the benchmark):
- `inverted_triangle`: shoulder_hip_ratio ≥ 1.10
- `triangle`: shoulder_hip_ratio ≤ 0.92
- `hourglass`: 0.92 < ratio < 1.10 and waist_definition ≥ 0.22
- `oval`: waist_width ≥ 0.98 × max(shoulder, hip)
- `rectangle`: otherwise

Confidence = mean landmark visibility × quality penalty (looseness, crop, angle).

## 9. Test plan (Iteration 1)

- **Unit (pytest):** feature extraction on synthetic masks with known widths; silhouette rules at threshold boundaries; quality-gate error codes; serializer validation (height range, ≤ 3 styles); photo is not persisted (temp dir empty after request).
- **Integration:** `POST analyze` → `PUT confirm` → `GET` round trip with a sample image. `DELETE` removes the profile.
- **UI (Compose):** state transitions `Idle → PhotoSelected → Analyzing → Review → Saved`; 4th style chip disabled; error state shows the hint text.
- **Smoke:** Galaxy S23 against a dev server.

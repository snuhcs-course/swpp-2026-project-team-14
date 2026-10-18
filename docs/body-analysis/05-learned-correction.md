# Body Analysis — Learned Measurement Correction (Option 2)

> Owner: Dongkun Moon · 2026-09-24 · Model: `ridge-1` (`backend/body_analysis/models/measurement_corrector.json`)
> **Update 2026-09-28:** the shipped model is now `ridge-2`, retrained without the clothing input because clothing is detected automatically. It is skipped per region when loose clothing is detected. Results and the cost (≤ 0.2 cm) are in `06-clothing-detection.md` §5; the tables below are for `ridge-1`.
> Scripts: `backend/scripts/generate_dataset.py`, `backend/scripts/train_corrector.py` · Wiki target: *Testing Documentation → AI Module Testing* and *Design Documentation → AI model*

## 1. Why

- The geometric baseline turns silhouette widths and depths into measurements with fixed rules plus one correction factor per measurement, fitted on 8 bodies.
- With a realistic range of **camera distance, height, lens and small body rotation**, its errors grow. For example, on held-out bodies in underwear the hip error was 4.7 cm and the waist error 3.6 cm.
- The course's technical-depth route #2 asks for a *meaningful, measured improvement* of the AI component. This document is that improvement.

## 2. Data

| | |
|---|---|
| Bodies | 240 random adult Anny bodies (NAVER, Apache 2.0): gender, age 0.65–0.95, height, weight, muscle, proportions. Heights 123–202 cm |
| Clothing | underwear, tight (0.3–0.8 cm shell), loose (top 2–6 cm, trousers 0.5–6 cm, independent) |
| Camera (random per sample) | distance 2.0–3.2 m, height 0.8–1.4 m, vertical FOV 50–70°, body yaw ±6° (front) / ±8° (side) |
| Ground truth | measured on the unclothed mesh (definitions in `04-synthetic-benchmark.md` §3) |
| Weight | mesh volume × 1010 kg/m³ |
| Samples | 720 generated. 563 usable: 157 were rejected by the quality gate as `body_cropped`. Rejected photos had a median frame margin of −0.01 m (head or feet at or past the edge), while accepted ones had +0.47 m. The rejections are correct and follow from the deliberately extreme camera range |

## 3. Model

- **Features (85):**
  - the 16 uncalibrated geometric estimates
  - front-width and side-depth profiles at 23 heights (30–85 % of body height)
  - landmark heights, the waist/crotch/chest/hip rows, shoulder and hip spread
  - whether the arms were found
  - all of the above divided by body height, so they are scale-free
  - the user inputs: height, BMI and whether weight was given, gender, clothing
- **Target:** measurement ÷ height.
- **Model:** one ridge regression per measurement, with standardised features and missing values imputed by the training mean. Exported as plain JSON, so it runs with numpy only.
- **Weight robustness:** every training sample is used twice, with and without the weight, so the model works whether or not the user enters it.
- **Validation protocol:**
  - Every 5th body (48 bodies) is a **held-out test body**, never used for fitting or model selection.
  - Ridge strength (α) and "use the model at all?" are chosen by 5-fold cross-validation **grouped by body** on the 191 training bodies.
  - A measurement uses the model only if it beats the geometric baseline in cross-validation. All 14 evaluated measurements did.
- **Where it applies:** trained and applied for **underwear and tight clothing only**. Loose clothing keeps the geometric values (§6).
- **Comparison model:** gradient boosting (sklearn `HistGradientBoostingRegressor`). Ridge was better or equal on 13/14 measurements and needs no ML runtime, so ridge ships.

## 4. Results — 48 held-out bodies (MAE, cm; weight not given)

| Measurement | Underwear: geometry | Underwear: **ridge** | Underwear: GBM | Tight: geometry | Tight: **ridge** |
|---|---|---|---|---|---|
| shoulder width | 0.95 | **0.41** | 0.65 | 1.42 | **0.41** |
| sleeve | 2.65 | **0.25** | 0.24 | 2.72 | **0.21** |
| torso length | 2.73 | **0.54** | 0.59 | 2.80 | **0.59** |
| rise | 2.21 | **0.56** | 0.62 | 2.68 | **0.58** |
| inseam | 1.34 | **0.38** | 0.46 | 2.41 | **0.38** |
| outseam | 2.53 | **0.57** | 0.69 | 2.70 | **0.64** |
| neck | 1.56 | **0.72** | 0.77 | 1.93 | **0.72** |
| chest | 2.08 | **1.23** | 1.72 | 4.36 | **1.25** |
| waist | 3.64 | **0.81** | 1.34 | 6.21 | **1.05** |
| hip | 4.69 | **0.58** | 1.09 | 4.18 | **0.72** |
| bicep | 1.46 | **0.61** | 0.94 | 3.50 | **0.61** |
| wrist | 1.74 | **0.32** | 0.48 | 1.35 | **0.37** |
| thigh | 2.46 | **0.62** | 0.83 | 3.82 | **0.78** |
| calf | 2.71 | **0.48** | 0.58 | 3.30 | **0.64** |

**Every measurement improves:** error falls by 40–90 %, and tight clothing ends up almost as accurate as underwear.

**Independent check on the 8 hand-picked benchmark bodies** (`04` §2; not part of the random training set; fixed camera):
- The learned model is better on 13/14 measurements, in both underwear and tight clothing. For example:
  - underwear waist 1.75 → 0.47 cm
  - tight chest 3.27 → 0.72 cm
- The exception is underwear thigh (0.36 → 0.81). That comparison favours geometry, because the geometric correction factors were fitted on these same 8 bodies.
- Insight agreement improved: more correct insights shown, still **0 wrong on screen**.

## 5. Input ablation (technical-depth route #1: which inputs matter?)

Same protocol, held-out underwear bodies, MAE in cm:

| Features the model may use | Chest | Waist | Hip | Thigh | Inseam |
|---|---|---|---|---|---|
| Geometric baseline (no learning) | 2.08 | 3.64 | 4.69 | 2.46 | 1.34 |
| User inputs only, **no photo** (height, gender, weight) | 2.08* | 2.50 / 4.01† | 2.49 / 3.85† | 1.64 / 2.78† | 0.75 / 0.81† |
| Photo geometry only | 1.38 | 1.21 | 0.78 | 0.82 | 0.44 |
| **Photo + user inputs** | **1.23** | **0.81** | **0.58** | **0.62** | **0.38** |

\* no improvement over the baseline, so the baseline is kept. † with / without weight.

**Findings:**
- **The photos carry most of the information.** A model without the photo is far worse, so the learned correction is not just guessing from height and gender.
- **Weight matters without photos but hardly at all with them** (≤ 0.02 cm difference). The side and front silhouettes already encode what weight would add. Keeping weight **optional** in the app is therefore justified.

## 6. What was fixed along the way

| Finding | Evidence | Fix |
|---|---|---|
| Arm measurements almost never produced | Arms found for 15 % of underwear and 1 % of tight samples. The armpit often sits past the 45 % row the bicep was measured on | Bicep = widest arm row among rows clear of the torso, 40–80 % down the upper arm → arms found for **100 %** of underwear and tight samples |
| Wrong retake hint for a side photo uploaded as the front | Returned `body_cropped` (the hidden side has low landmark visibility) | Orientation is checked before visibility → `not_frontal` |

## 7. Loose clothing (not deployed)

- Gradient boosting trained on underwear and tight samples also lowers loose-clothing errors on the test bodies (e.g. inseam 9.8 → 0.7 cm, waist 24.8 → 17.3 cm). But that only reflects our uniform garment shells, not real clothes.
- The model is therefore **not applied to loose clothing**. The app keeps its loose-clothing warnings and hides proportion insights (`04` §8).
- Correcting loose clothing properly needs real clothed photos with tape measurements.

## 8. Limitations

1. **Synthetic only.**
   - Training and test bodies come from the same body model (Anny), whose shape space is smoother and lower-dimensional than real people.
   - The renders are clean: flat shading, a plain background, perfect segmentation.
   - Real-photo errors will be larger, and the gain from the learned model may shrink.
   - **Validation on real photos with tape measurements is the next required step** (`03-benchmark-plan.md`).
2. **Ground-truth definitions** for shoulder width, sleeve and torso length are approximations (`04` §3). The model learns *our* definitions.
3. **Confidence badges were not re-tuned.** The per-measurement confidence levels are still the design-time guesses. They should be updated from real-photo errors.
4. **Retraining is required when features change.** The test `test_shipped_model_matches_the_pipeline_features` fails if the measurer stops producing a feature the model uses.

## 9. Reproduce

```bash
cd backend
.venv\Scripts\python -m pip install -r requirements-benchmark.txt
.venv\Scripts\python scripts/generate_dataset.py --bodies 240          # ~13 min, private/dataset/samples.csv
.venv\Scripts\python scripts/train_corrector.py --export               # report + model JSON
.venv\Scripts\python scripts/train_corrector.py --features inputs      # ablation
.venv\Scripts\python scripts/train_corrector.py --features geometry    # ablation
.venv\Scripts\python scripts/synthetic_benchmark.py                    # 8 fixed bodies, geometry vs learned
```

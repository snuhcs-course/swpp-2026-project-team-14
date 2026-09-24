# Body Analysis — Synthetic Benchmark (Iteration 1)

> Owner: Dongkun Moon · Run: 2026-09-24 · Pipeline: `baseline-geometric-1.0`
> Script: `backend/scripts/synthetic_benchmark.py` · Wiki target: *Testing Documentation → AI Module Testing*

## 1. Why a synthetic benchmark

- We have no real body photos with tape measurements yet. Collecting them needs volunteers, consent and a capture protocol (see `03-benchmark-plan.md`).
- To evaluate the measurement logic now, without anyone's photos, we render **3D bodies whose true measurements are known**. We then measure the rendered "photos" with the same pipeline the app will use.

## 2. Method

| Step | Detail |
|---|---|
| Bodies | 8 adults from **Anny** (NAVER, Apache 2.0 parametric body model): 4 female, 4 male; slim/average/heavy/muscular; 133–196 cm |
| Pose | Anny rest pose with the upper arms rotated into the frontal plane → A-pose (arms about 40° from the body) |
| Rendering | Flat-shaded mesh, pinhole camera 2.5 m away at 1.0 m height, 55° vertical FOV, 960×1280 portrait, plain background. Front view and left-side view |
| Pipeline input | rendered front + side images, true height, gender; clothing = underwear; weight not given |
| Modes | `front+side`. A `front_only` mode was run on 2026-09-24 and then removed together with front-only support (see the note after §4) |
| Ground truth | measured on the 3D mesh (§3) |
| Calibration | per-measurement multiplier `k` (least squares), evaluated by **2-fold cross-validation over bodies**: fit on 4 bodies, test on the other 4, and swap. Reported calibrated errors are never measured on the bodies used for fitting |

## 3. Ground-truth definitions on the mesh

- "Hull perimeter" = perimeter of the convex hull of a horizontal mesh slice. This is what a tape measure follows, since the tape bridges concave areas.
- Joint positions come from Anny's skeleton.

| Measurement | Definition | Confidence in the definition |
|---|---|---|
| Waist | minimum torso hull perimeter between 25 % and 70 % of the hip-joint → shoulder-joint span | good |
| Chest | maximum torso hull perimeter between 15 % and 45 % below the shoulder joints, skipping slices where an arm merges with the torso | good |
| Hip | maximum torso hull perimeter between the crotch and 12 cm above the hip joints | good |
| Neck | minimum hull perimeter between the neck-base bone and the head bone | good |
| Thigh | mean of both legs' hull perimeters 3 cm below the crotch | good |
| Calf | maximum mean leg hull perimeter in the upper 60 % of the knee → ankle span | good |
| Bicep / wrist | hull perimeter of a slice perpendicular to the upper arm (45 %) / forearm (90 %) | medium |
| Crotch height | highest slice where the lower torso splits into two legs | good |
| Inseam / outseam / rise | crotch → floor / waist → floor / waist → crotch (vertical) | good |
| Torso length | neck-base bone → waist height (vertical, not along the back surface) | medium |
| Shoulder width | x-extent of the torso slice 4 cm above the shoulder joints (across the shoulder caps) | **weak**: between biacromial and bideltoid breadth |
| Sleeve | shoulder-cap point → elbow joint → wrist joint | **weak**: inherits the shoulder definition |
| Underbust, armhole | not evaluated. No reliable mesh definition yet | — |

## 4. Results (8 bodies)

### front + side

| Measurement | Mean truth (cm) | MAE raw (cm) | Mean error raw (cm) | MAE calibrated, 2-fold CV (cm) | Multiplier (all bodies) |
|---|---|---|---|---|---|
| shoulder_width | 41.0 | 6.0 | -6.0 | 0.8 | 1.175 |
| sleeve_length | 50.0 | 2.8 | -2.8 | 1.0 | 1.062 |
| torso_length | 36.6 | 2.2 | -1.8 | 2.0 | 1.056 |
| rise | 27.8 | 1.6 | -0.1 | 1.6 | 1.005 |
| inseam | 79.1 | 2.1 | +2.1 | 0.6 | 0.974 |
| outseam | 106.9 | 2.1 | +2.0 | 1.9 | 0.981 |
| neck | 36.1 | 1.3 | -0.1 | 1.4 | 1.001 |
| chest | 93.0 | 3.1 | -3.1 | 1.7 | 1.032 |
| waist | 74.3 | 2.2 | -1.2 | 1.7 | 1.019 |
| hip | 98.2 | 4.0 | +4.0 | 2.0 | 0.961 |
| bicep | 26.0 | 2.2 | -2.2 | 1.6 | 1.089 |
| wrist | 15.3 | 3.3 | -3.3 | 0.8 | 1.271 |
| thigh | 53.7 | 0.7 | -0.7 | 0.5 | 1.013 |
| calf | 38.4 | 1.4 | +0.2 | 1.4 | 0.992 |

### front only (historical: this mode no longer exists)

| Measurement | MAE raw (cm) | Mean error raw (cm) | MAE calibrated, 2-fold CV (cm) |
|---|---|---|---|
| torso_length | 5.8 | -5.8 | 2.3 |
| outseam | 4.7 | +4.7 | 2.1 |
| neck | 3.9 | -3.9 | 2.1 |
| chest | 12.3 | -12.3 | 1.7 |
| waist | 1.2 | -0.9 | 0.7 |
| hip | 6.4 | +6.4 | 2.1 |
| thigh | 2.7 | -2.7 | 1.1 |
| calf | 1.9 | -1.4 | 1.8 |

Front-only lengths that do not use depth (shoulder, sleeve, inseam, bicep, wrist) were identical to front + side.

**Decision (2026-09-24):** the side photo is now required in the app and the pipeline. Front-only circumferences depended on a guessed depth/width ratio (chest −12.3 cm raw), and that ratio would not generalise to real bodies (§6.3). The front-only path and its `DEPTH_RATIO` constants were removed.

Against the targets in `02-design.md` NFR-B3 (lengths ≤ 2.5 cm, chest/waist/hip ≤ 4 cm):
- **Uncalibrated, front + side:** shoulder width (6.0) and sleeve (2.8) miss the length target. Chest/waist/hip pass.
- **Calibrated:** every evaluated measurement passes on synthetic bodies.

## 5. What the benchmark changed in the pipeline

Each finding below was measured on the first 2-body run, before the change, and fixed:

| Finding | Evidence | Change |
|---|---|---|
| Neck measured across the trapezius | neck MAE +26.7 cm; the overlay showed the line on the shoulder slope | neck row = narrowest row between chin and shoulders → MAE 1.3 cm |
| Waist row chosen too high | front width is nearly constant from 103 to 117 cm; the true minimum circumference (105 cm) is set by **depth** | with a side photo, waist/chest/hip rows are chosen by *estimated circumference*, not front width → outseam 7.8 → 2.1 cm, rise 6.4 → 1.6 cm |
| Neck base (C7) too low | torso length −9.2 cm | neck base moved to 40 % of shoulder → mouth |
| Arms merged into the chest slice | synthetic mannequin chest +12 cm | chest search skips rows where an upper arm touches the torso |
| Benchmark bug | Anny `gender` 0 = male (visually verified), `age` 0.8 = adult | fixed body specs |

**Tried and rejected:** clipping the side-view silhouette at the wrist, to remove the hanging hand that overlaps the hips in profile.
- The idea was that the hand makes the body look deeper at hip height.
- Result: waist MAE 2.2 → 7.7 cm and hip +4.0 → −6.5 cm. The clip cut into the body, likely because MediaPipe also places the hidden far-side wrist.
- Reverted. Hand overlap stays a known failure pattern (§6).

## 6. Known failure patterns and limitations

1. **Hand overlap in the side photo.** Hands at hip level widen hip depth (hip raw error +4 cm).
   - Mitigation candidates: capture guidance for the side photo, for example arms slightly forward/bent, or a hand-segmentation step.
2. **The domain gap is the biggest caveat.** The renders are ideal:
   - perfect lighting, no clothing, no background clutter
   - a smooth, fairly homogeneous shape space
   - the exact same camera for both views, and a known true height

   Real photos add segmentation noise, clothing, posture and camera tilt. **Real-photo errors will be larger. These numbers are a lower bound.**
3. **Front-only looked deceptively good after calibration.** Anny bodies have similar depth/width ratios, so a single ratio fits them. Real bodies vary more in depth. This is one reason the side photo became required.
4. **Shoulder and sleeve ground truth are weakly defined.** Their large multipliers (1.175, 1.062) partly reflect our definition, not only pipeline error.
5. **Wrist multiplier 1.27.** The wrist is small, and 1–2 px of mask error is about 10 %. Wrist stays low confidence.
6. **8 bodies is small.** Multipliers can shift with more bodies. Extending the body set is cheap (a list in the script).

## 7. Decisions and next steps

- **Applied (2026-09-24):** `CALIBRATION` in `backend/body_analysis/measurer.py` now includes the multipliers fitted on all 8 bodies (front + side), for the measurements whose ground truth is well defined:
  - neck, chest, waist, hip, thigh, calf
  - inseam, outseam, rise, torso length
  - bicep

  Shoulder width was also fitted (2026-09-24, user decision): 1.12 → 1.316. Its mesh ground truth is weakly defined (§3, §6.4), so it must be re-checked against real tape measurements.
  Sleeve and wrist keep their initial guesses until real tape-measure data exists. Re-running the benchmark gives a mean error of about 0 cm and an MAE of 0.3–2.1 cm for the calibrated items. That is in-sample; the §4 cross-validated numbers are the honest estimate.
- Expand to 30+ bodies with random phenotypes, and add camera tilt, distance and height noise, to test robustness.
- Validate on real photos with tape measurements (volunteer protocol in `03-benchmark-plan.md`), and compare real vs synthetic errors to size the domain gap.
- Add the side-photo capture guidance for hand placement to the Android capture guide and re-measure hip error.

## 8. Clothing robustness (added 2026-09-24)

**Question:** the app recommends underwear or tight clothing, but does it still work with clothes on, and can its body-proportion insights be trusted?

**Method:**
- Same 8 bodies, rendered again in simulated clothing.
- Each vertex is pushed outward along its normal:
  - **top:** torso from the neck to the hips, plus sleeves to the elbow
  - **trousers:** from the pelvis to the ankles
- Thickness is 0.5 cm for **tight** clothing and 4 cm for **loose** clothing. Loose is a rough stand-in for an oversized T-shirt and wide trousers; real fabric also drapes and folds.
- Ground truth stays the **unclothed** body.
- The pipeline receives the matching clothing flag.

### Mean error (cm, current calibration)

| Measurement | Underwear | Tight | Loose |
|---|---|---|---|
| shoulder_width | +0.1 | +0.7 | +3.8 |
| sleeve_length | −2.8 | −3.2 | −2.6 |
| torso_length | +0.1 | +0.2 | +2.6 |
| rise | −0.1 | +1.4 | **+12.4** |
| inseam | −0.1 | −1.5 | **−13.1** |
| outseam | −0.2 | −0.1 | −1.2 |
| neck | +0.0 | −0.1 | +7.3 |
| chest | −0.0 | +3.3 | **+19.4** |
| waist | +0.2 | +3.0 | **+24.5** |
| hip | +0.0 | +0.8 | +7.0 |
| thigh | −0.1 | +1.4 | +10.8 |
| calf | −0.1 | +1.1 | +12.9 |

- In loose clothing, bicep and wrist were not produced for any body: the sleeves merge with the torso, so the pipeline reports `arms_touching_body`.

### Findings

1. **Tight clothing is close to underwear.** Circumferences run about 1–3 cm high (the fabric thickness), and lengths are unchanged. This validates the capture guidance.
2. **Loose clothing inflates every circumference,** by 7–25 cm. The inflation is uneven: waist +24.5 vs. hip +7.0.
3. **Loose trousers break the leg lengths.** The gap between the legs disappears, so the crotch is detected far too low:
   - inseam −13.1 cm
   - rise +12.4 cm

   Before this benchmark we assumed length-based values would survive clothing. That is **wrong for inseam and rise**. Lengths from joint positions (sleeve, shoulder, outseam) do survive.
4. **Insight agreement with the true body:**

| Condition | Leg proportion | Broad shoulders | Lower-body volume | Defined waist |
|---|---|---|---|---|
| Underwear | 8/8 | 8/8 | 8/8 | 8/8 |
| Tight | 7/8 | 7/8 | 8/8 | 8/8 |
| Loose | **2/8** | **4/8** | **4/8** | **4/8** |

- After the shoulder calibration, a loose top inflates shoulder width by about 4 cm, so "broad shoulders" also becomes unreliable in loose clothing.
- In tight clothing, leg proportion and broad shoulders each flip for one body that sits right at the rule's threshold.

### Change made

- In loose clothing, the app now **hides all proportion insights**: leg proportion, broad shoulders, lower-body volume and defined waist.
- An insight comes back if the user typed the underlying values in themselves.
- Instead, the app shows a note suggesting a retake in tight clothing (`buildInsights` in `frontend/.../data/BodyAnalyzer.kt`, covered by `InsightsTest`).

With the rule applied, the benchmark counts **0 wrong insights on screen** for underwear and loose clothing. Tight clothing has 2 (both threshold cases).

Without the rule, loose clothing would have shown:
- a wrong leg-proportion sentence for 6/8 bodies
- a wrong broad-shoulders sentence for 4/8 bodies

A possible next step is a margin around each threshold: show an insight only when the ratio is clearly past it.

### Limits
- Simulated garments are uniform shells; real clothes drape, fold and vary by garment type.
- Skirts and dresses are not simulated. They hide the crotch entirely, so they should behave like or worse than loose trousers for inseam/rise.
- Real-photo validation is still needed.

## 9. Insight threshold margins (added 2026-09-24)

**Problem:** in tight clothing, two insights flipped for bodies sitting right at a rule's threshold. A ratio error of about 0.01 was enough to show the wrong sentence.

**Change:** each insight is shown only when its ratio is past the threshold by a margin. The margins match the largest ratio error for underwear photos in this benchmark (`InsightThresholds` in `frontend/.../data/BodyAnalyzer.kt`; mirrored by `InsightRule` in the benchmark script).

| Insight | Ratio | Threshold | Margin | Largest underwear error | Shown when |
|---|---|---|---|---|---|
| Leg proportion (two-sided) | inseam / height | 0.46 | 0.01 | 0.007 | ≥ 0.47 "다리가 긴 편", ≤ 0.45 "상체가 긴 편", otherwise nothing |
| Broad shoulders | shoulder width / height | 0.255 | 0.01 | 0.009 | ≥ 0.265 |
| Lower-body volume | hip / chest | 1.05 | 0.05 | 0.045 | ≥ 1.10 |
| Defined waist | waist / hip | 0.75 | 0.05 | 0.051 | ≤ 0.70 |

**Result (8 bodies):**
- *Correct* = shown and true.
- *Wrong* = shown and contradicts the body.
- *Missed* = the body qualifies but nothing is shown.

| Condition | Leg proportion | Broad shoulders | Lower-body volume | Defined waist |
|---|---|---|---|---|
| Underwear | 5 ✓ / 0 ✗ / 3 missed | 0 / 0 / 2 | 4 / 0 / 0 | 3 / 0 / 1 |
| Tight | 5 / 0 / 3 | 0 / 0 / 2 | 4 / 0 / 0 | 2 / 0 / 2 |
| Loose (hidden) | 0 / 0 / 8 | 0 / 0 / 2 | 0 / 0 / 4 | 0 / 0 / 4 |

- **Wrong insights on screen: 0 in every condition** (before: 2 in tight).
- **Cost:** missed insights. The two bodies that truly have broad shoulders have ratios of 0.259 and 0.268. Both are too close to 0.255 for their measured value to clear the 0.265 bar.
- Several Anny bodies sit close to the leg threshold (true ratios 0.459–0.472), which inflates the missed count on this small set.

**Trade-off chosen:** for a styling app, a missing hint is better than a wrong statement about someone's body.
- Re-tune the margins once real-photo errors are known. They should track the real measurement error, which will be larger.

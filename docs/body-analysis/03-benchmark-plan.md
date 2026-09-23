# Body Analysis — Benchmark Plan

> Owner: Dongkun Moon · Status: plan (2026-09-23), results to be filled in Iterations 2–3
> Wiki target: *Testing Documentation → AI Module Testing* (course requirement from Iteration 3)

## 1. Questions the benchmark must answer

1. **Input ablation (technical-depth route #1).** Which inputs are worth asking the user for?
   Compare photo only, + height, + weight, + gender, + side photo.
2. **Model comparison.** Does the hybrid pipeline beat the baseline enough to justify it? Is a VLM or HMR model better or worse?
3. **Clothing robustness.** How much does loose clothing degrade each approach, and does the looseness flag catch it?
4. **Usefulness.** Do the resulting insights and recommendations feel right to users, and how often do they correct the AI?

## 2. Test sets

| Set | Source | Size | Ground truth | Use |
|---|---|---|---|---|
| **T1 — Volunteer set** | team + 5–8 recruited students (the proposal already plans 5–8 testers), written consent, photos deleted after the study | ~10 people × 6 photos (front/side × tight / everyday / loose outfit) | tape measurements: height, shoulder breadth, chest, waist, hip, inseam; self-reported weight | main metric set, clothing robustness |
| **T2 — Re-shoot set** | same volunteers, second session on another day | ~10 × 2 front photos | — | test-retest consistency |
| **T3 — BodyM Test-A / Test-B** | public silhouettes + measurements (CC BY-NC 4.0) | 1,000+ subjects | scan-derived measurements | large-scale ratio accuracy of the silhouette features and regressor; not usable for pose/parsing (silhouettes only) |
| **T4 — Style judgement set** | T1 profiles → generated insights | ~10 × 3 arms | blind 1–5 ratings by 3 raters (usefulness, correctness, tone) | usefulness of styling output |

Silhouette labels for T1 are derived from the tape measurements with the same rules as §8 of the design doc. Two team members label the borderline cases independently, and we report agreement.

## 3. Arms

| Arm | Pipeline |
|---|---|
| A0 | Manual only (user types values): reference for correction rate |
| A1 | Baseline: MediaPipe pose + mask + rules (Option 1) |
| A2 | A1 + SCHP parsing + looseness flag |
| A3 | A2 + BodyM-trained regressor (Option 2 core) |
| A4 | VLM API: photo + height → JSON directly |
| A5 | HMR reference: CameraHMR or SAM 3D Body → mesh → ratios (offline, GPU, research-only) |
| A6 | Option 3: fine-tuned compact model (only if A3 misses the targets) |

Each photo-based arm is run with the input ablations `{photo}`, `{photo, height}`, `{photo, height, weight}`, `{photo, height, weight, gender}`, `{front+side, height, weight}` where the arm supports them.

## 4. Metrics

| Metric | Definition | Target (from NFRs) |
|---|---|---|
| Ratio MAE | shoulder-hip and torso-leg ratio vs. tape-derived ratio | ≤ 0.08 |
| Measurement MAE (cm) | shoulder breadth, chest, waist, hip (only arms with height) | report; lab SOTA is ~1–3 cm, expect 3–6 cm in the wild |
| Silhouette agreement | accuracy and Cohen's κ vs. tape-derived class | κ ≥ 0.6 |
| Size hit rate | top/bottom size exact-or-adjacent vs. the user's real size | ≥ 90 % |
| Test-retest consistency | same class across T2 pairs; mean abs ratio difference | ≥ 80 %; ≤ 0.05 |
| Clothing sensitivity | ratio error (loose) − ratio error (tight) | smaller is better; looseness-flag recall ≥ 0.8 |
| Failure handling | % of invalid photos rejected with the correct reason | ≥ 90 % |
| Latency | p50 / p95 server time on the deployment instance | p95 ≤ 1 s inference |
| Cost | $ per analysis | A1–A3: $0 |
| Style usefulness | mean blind rating (T4) | ≥ 3.5 / 5 and ≥ A4 |
| User correction rate | fields edited before confirming (beta telemetry, Iteration 5) | < 40 % |

## 5. Procedure

1. The harness `scripts/benchmark_body_analysis.py` loads a manifest (image path, inputs, ground truth), runs each arm through the same `body_analysis` interfaces, and writes `results/*.csv`.
2. Thresholds of the rule classifier are tuned on half of T1 + T3-train and reported on the other half (no tuning on the test split).
3. Report tables per arm × input combination, and a failure gallery (landmark overlays, *no raw photos in the wiki*, only masks/skeletons) with recurring failure patterns and mitigations. This matches the course's Iteration 3 requirement.
4. Decision rule: adopt the cheapest arm that meets the targets. If no arm meets the ratio target on everyday clothing, go to Option 3 with the smallest fine-tuning target: a feature → ratio/silhouette regressor trained on BodyM with clothing-dilation augmentation.

## 6. Ethics and privacy

- Written consent. Photos stay on a team member's machine and are deleted after the benchmark. Only derived numbers and masks go in the repo.
- No body-weight or health judgement in any output. Silhouette words are styling vocabulary only.
- BodyM data is used under CC BY-NC 4.0 for course research only.

## 7. Schedule

| When | Step |
|---|---|
| Iteration 1 (→ 10/09) | A1 in production path; harness skeleton; T1 protocol + consent form |
| Iteration 2 (→ 10/23) | Collect T1/T2; run A1, A4; implement A2 |
| Iteration 3 (→ 11/06) | A3 (+ A5 reference); full ablation; results + decision documented (course AI-evaluation deliverable) |
| Iteration 5 | User correction rate from beta telemetry |

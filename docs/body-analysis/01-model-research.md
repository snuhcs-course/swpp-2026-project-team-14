# Body Analysis — Model Research

> Owner: Dongkun Moon (P9, P10) · Status: Iteration 1 draft (2026-09-23) · Wiki target: *Design Documentation → Body Analysis*

## 1. Problem statement

StyleMate needs a **body profile** that the outfit recommender and the conversational editor can reuse.
The professor's feedback asks us to define exactly what "body characteristics" are and how recommendation uses them.

Design principle: **a single phone photo cannot give medically accurate or exact clothing-size measurements.**
The system therefore produces *approximate, fashion-oriented features* (relative proportions, silhouette category, confidence) and the user always reviews, edits and confirms them.

**Rev. 2 (2026-09-24): the target changed to garment measurements.**
The team decided the app should estimate as many garment-relevant body measurements as possible:
- **lengths:** shoulder width, sleeve, torso, rise, inseam, outseam
- **circumferences:** neck, chest, underbust, natural waist, hip, armhole, bicep, wrist, thigh, calf

The full table is in `02-design.md` §2. Users are guided to take a **front and a side photo in underwear or tight clothing**. Loose clothing is accepted, but confidence drops and a warning is shown.

This moves the problem from "classify a body shape" to **photo-based anthropometry**. Families C (3D mesh) and D (measurement regressors) below become more central, and the **side photo + height/weight** inputs are strongly supported by the BodyM ablation.

What the downstream components need:

| Feature | Used by | Why |
|---|---|---|
| Garment measurements (lengths, circumferences) with confidence | size matching, product recs, fit checks | the core output; user-editable |
| Derived ratios (shoulder/hip, waist/hip, torso/leg) | outfit recommender, insights | proportion-aware styling |
| Confidence per measurement | recommender, UI | low-confidence values are down-weighted and flagged |
| Preferred fit and styles (user input) | recommender, chat editor | not estimated — entered by the user |

## 2. Candidate families

### A. 2D pose / landmark estimation

| Model | Input | Reliable output | License | Deployment |
|---|---|---|---|---|
| **MediaPipe Pose Landmarker** (BlazePose GHUM) | 1 RGB image | 33 landmarks (2D + rough 3D "world" coords), optional person segmentation mask | Apache 2.0 (code), model card: BlazePose GHUM 3D | `pip install mediapipe`, CPU, tens of ms; **also runs on Android** (on-device option) |
| **RTMPose / RTMW** (MMPose, via `rtmlib`) | 1 RGB image | 17 body / 133 whole-body keypoints, more accurate than BlazePose on COCO | Apache 2.0 | ONNX Runtime on CPU, RTMPose-m reported 90+ FPS on i7 |
| ViTPose / Sapiens-pose | 1 RGB image | high-accuracy keypoints (Sapiens: 308 incl. face/hands) | ViTPose Apache 2.0; Sapiens CC BY-NC 4.0 | GPU recommended for large variants |

**Key limitation for our task:** keypoints mark *joint centres*, not body *contours*.
The two hip keypoints are hip joints, so their distance is not hip width. Shoulder keypoints are closer to real shoulder breadth but are shifted by shoulder pads/hoods.
Pose alone gives good **length ratios** (torso vs. leg, arm length) and only weak **width ratios**.

### B. Silhouette / segmentation / human parsing

| Model | Output | License | Notes |
|---|---|---|---|
| MediaPipe segmentation mask (from Pose Landmarker) | person vs. background | Apache 2.0 | free by-product of A |
| **SCHP** (Self-Correction Human Parsing, LIP/ATR/Pascal checkpoints) | 18–20 part labels: face, arms, legs, upper-clothes, pants, skirt, dress, coat… | MIT (code) | ResNet-101, CPU feasible (~1 s); community CPU forks and HF checkpoints exist |
| Sapiens-seg | 28 body-part classes at 1K resolution | CC BY-NC 4.0 (Sapiens 1); Sapiens2 has its own license | GPU needed for larger variants |

Silhouette widths measured at landmark-defined heights (shoulder, waist, hip) give the **width ratios** that pose alone cannot.
**Main failure mode: clothing.** A hoodie or wide trousers inflate the silhouette.
Human parsing lets us tell skin/body parts from garment regions (e.g. measure waist on a tucked-in shirt vs. a coat) and **flag** loose clothing instead of silently producing wrong numbers.

### C. Monocular 3D human mesh recovery (HMR)

| Model | Output | License | Notes |
|---|---|---|---|
| HMR 2.0 (4DHumans) | SMPL pose + 10 shape params | code open; **needs SMPL model → non-commercial research license, registration** | GPU; ViT-H backbone |
| Multi-HMR, SMPLer-X / SMPLest-X, CameraHMR | SMPL-X meshes; CameraHMR improves shape for heavier bodies via camera-FoV estimation | research licenses + SMPL-X license | GPU |
| **SAM 3D Body** (Meta, Nov 2025) | MHR mesh (skeleton decoupled from surface shape), keypoints | SAM License (permissive, commercial OK) | 631M–840M params, GPU, gated HF checkpoints |
| **SHAPY** (CVPR 2022) | SMPL-X shape + height/weight/chest/waist/hip estimates, "shape ↔ linguistic attribute" models | non-commercial research | directly relevant: maps shape to words like "pear-shaped", "broad shoulders" |
| Anny (NAVER, 2025) | scan-free parametric body model, SMPL-X conversion | Apache 2.0 | a license-friendly body model, not an image regressor by itself |

Known limitations:
- **Scale ambiguity:** without height, absolute size is not recoverable.
- **Regression to the mean:** mesh shape collapses towards an average body. This is documented for SAM 3D Body ([Investigating Anthropometric Fidelity in SAM 3D Body](https://arxiv.org/html/2601.06035v1)) and for HMR methods in general. Loose clothing makes it worse (most training sets have tight clothing).
- **Cost:** 0.6–0.8B-parameter models need a GPU server. That is heavy for a class project backend and costly on free-tier cloud.

Verdict: HMR is valuable as a **reference/teacher in the benchmark**, not as the production path for Iteration 1–3.

### D. Measurement regressors trained on body datasets

- **BodyM** (Amazon, CC BY-NC 4.0): 2,505 subjects with frontal and side silhouettes, height, weight, gender, and 14 scan-derived measurements (chest, waist, hip, shoulder breadth, leg length, …). Public S3 bucket, no AWS account needed.
- The BodyM paper's ablation (BMnet, errors in mm on Test-A, lab capture, tight clothing, A-pose) quantifies the value of extra inputs:

  | Inputs | Chest MAE | Hip MAE | Waist MAE |
  |---|---|---|---|
  | Front silhouette only | 34.0 | 31.0 | 31.9 |
  | Front + side | 28.7 | 28.3 | 27.3 |
  | Front + side + height | 19.4 | 16.0 | 18.7 |
  | Front + side + weight | 15.2 | 10.5 | 13.7 |
  | Front + side + height + weight | 15.9 | 9.7 | 15.4 |

  Takeaway: **height/weight metadata roughly halves the error**, and the second view adds a smaller gain. Errors grow sharply with high BMI. In-the-wild photos with normal clothes will be worse than these lab numbers.
- **Size Korea** (national 3D anthropometric survey): a 2026 study on 815 Korean women classifies Top / Regular / Bottom hourglass from chest-to-hip and hip-to-waist ratios with ~91–94 % accuracy (CART / discriminant analysis). It shows that **simple ratios are enough for shape classification** when the ratios are measured well. The hard part is measuring them from a photo.

### E. Vision-language models (VLM / LLM API)

- **Input:** photo + text. **Output:** free text or JSON (size guess, shape words, styling advice).
- **Strengths:** excellent at producing *insight text* and style reasoning. Zero training.
- **Weaknesses:**
  - No peer-reviewed accuracy for body measurement.
  - Outputs are not metrically grounded and can be inconsistent across calls.
  - The photo leaves our server.
  - Per-call cost.
- **Role in our design:** generate *explanations and style tips* from **structured numeric features** (no photo needed), and serve as one baseline arm in the benchmark (photo → JSON directly).

### F. Fashion-oriented body-aware recommendation

- **ViBE** (Hsiao & Grauman, CVPR 2020) learns a body-aware embedding from catalog images of models with different shapes and shows body-aware beats body-agnostic recommendation.
  Relevant as evidence that body shape *matters* for recommendation, and as a design reference for how the profile feeds the recommender.
- FFIT-style shape categories (rectangle, pear/triangle, apple/oval, inverted triangle, hourglass) are the common vocabulary. We use a **gender-neutral 5-class version** with rules defined on our ratios.

## 3. Evaluation against the six criteria

| Candidate | 1. Inputs | 2. Reliable outputs | 3. Accuracy / limits | 4. Weights + license | 5. Hardware / privacy / deploy | 6. Fine-tunable? |
|---|---|---|---|---|---|---|
| MediaPipe Pose + mask | 1 front photo (+ height for cm) | landmarks, length ratios, coarse silhouette | good landmarks on full-body upright photos; widths hurt by clothing | yes, Apache 2.0 | CPU ms-level; can run **on-device on Android** → photo never uploaded | no (not needed) |
| RTMPose (rtmlib) | 1 photo | more accurate keypoints | better on hard poses; same contour limitation | yes, Apache 2.0 | CPU ONNX | possible, not needed |
| SCHP human parsing | 1 photo | garment vs. body regions | trained on LIP/ATR street images; boundary noise | yes, MIT | CPU ~1 s, 250 MB | yes, but not needed |
| HMR 2.0 / CameraHMR | 1 photo (+ height) | SMPL(-X) mesh, measurements from mesh | regression to mean, clothing bias | yes, **SMPL non-commercial** | GPU | research-grade effort |
| SAM 3D Body | 1 photo (+ prompts) | MHR mesh | SOTA pose; shape still averages | yes, SAM License (gated) | GPU, large | no (too large) |
| SHAPY | 1 photo | SMPL-X shape + attributes | best shape-attribute link | non-commercial | GPU | no |
| BodyM-trained regressor | front (+ side) silhouette + height (+ weight) | 14 measurements, ratios | ~1–3 cm MAE in lab | data CC BY-NC; we train ourselves | CPU, tiny model | **yes — smallest realistic target** |
| VLM API | photo or features | text, style tips, rough guesses | unvalidated numerically, variable | API ToS | network + cost; photo leaves device if sent | prompt engineering only |

All licenses above allow our **non-commercial course project**. SMPL, SHAPY, Sapiens-1 and BodyM would block a commercial launch. That is recorded as a constraint, not a blocker.

## 4. Implementation options

> **Rev. 2 note.** With measurements as the target and underwear/tight clothing as the default capture condition, the options become:
> - **Option 1 (baseline):** MediaPipe landmarks + person masks on **both** photos. Lengths come from landmark distances scaled by height. Circumferences come from front width × side depth at landmark-defined heights, with an ellipse model.
> - **Option 2 (hybrid):** Option 1 plus a **regressor trained on BodyM** (front + side silhouettes + height/weight → 14 measurements, the closest public match to our task). SCHP parsing detects and down-weights loose garments.
> - **Option 3 (fine-tuning):** fine-tune a compact silhouette → measurement network on BodyM with clothing augmentation. HMR models (CameraHMR, SAM 3D Body) are used offline as reference measurements from a mesh.
>
> The original option text below still applies for the styling features derived from the measurements.

### Option 1 — MVP baseline (Iteration 1)
Front photo + height (required) + weight, gender/fit category (optional)
→ **MediaPipe Pose Landmarker (+ segmentation mask)** on the Django server
→ feature extractor: widths at shoulder/waist/hip rows of the mask, torso/leg lengths from landmarks, normalised by height
→ **rule-based** silhouette classifier and size lookup table
→ quality gate (full body visible, frontal, landmark visibility) → structured profile draft with per-feature confidence
→ user edits and confirms → photo deleted.
Insight text comes from templates keyed on the silhouette and ratios.
Cost: free, CPU-only, ~100 ms. Risk: clothing inflates widths.

### Option 2 — Hybrid pipeline (recommended target, Iterations 2–3)
Option 1 plus:
- **SCHP human parsing** to mask out loose garments and pick measurement rows on body regions, with a *clothing looseness flag* that lowers confidence and asks for a re-shoot or a tighter outfit.
- **Optional side photo** (BodyM shows the second view reduces error).
- A **small learned regressor** (gradient boosting / MLP) from our geometric features + height/weight/gender → ratios and measurements, trained on BodyM silhouettes.
- **LLM insight generation from the structured features only** (no photo sent), with the output schema enforced and wording constrained to fashion-neutral, non-judgemental language.
- Optional on-device variant: run MediaPipe inside the Compose app so the photo never leaves the phone. That is a privacy-driven technical constraint the course explicitly counts as depth.

### Option 3 — Ambitious fine-tuning
- **Target (smallest realistic):** fine-tune a compact CNN (MobileNetV3 / EfficientNet-B0) or the regressor above on **BodyM silhouettes + height/weight** to predict proportions and the 5-class silhouette.
- **Domain adaptation to clothed photos:** synthetic clothing-dilation augmentation of silhouettes, plus a small set of our own labelled volunteer photos.
- **Optional:** use HMR 2.0 / SAM 3D Body offline as a *teacher* to pseudo-label our volunteer photos (distillation). No large model is ever served.
- **Not attempted:** training a 3D body reconstruction model from scratch.

## 5. Recommendation

Build **Option 1 in Iteration 1** (fits the 12 h budget for P9 + P10 and gives the runnable end-to-end flow the course requires).
In parallel, build the **benchmark harness** (see `03-benchmark-plan.md`), then move to **Option 2 in Iterations 2–3**. Iteration 3 is the course's "AI Feature Evaluation" iteration, and the input ablation (photo only vs. + height vs. + weight vs. + side view vs. clothing type) is exactly the course's technical-depth route #1 ("exploring and validating appropriate input information").
Escalate to Option 3 only if the benchmark shows the rule/regressor path failing the success criteria.

## Sources

- MediaPipe Pose Landmarker — https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker
- RTMPose — https://arxiv.org/html/2303.07399v2 · rtmlib — https://github.com/Tau-J/rtmlib · MMPose — https://github.com/open-mmlab/mmpose
- SCHP — https://github.com/GoGoDuck912/Self-Correction-Human-Parsing
- Sapiens — https://github.com/facebookresearch/sapiens
- 4DHumans / HMR 2.0 — https://github.com/shubham-goel/4D-Humans
- CameraHMR — https://arxiv.org/pdf/2411.08128 · Multi-HMR — https://europe.naverlabs.com/research/publications/multi-hmr-multi-person-whole-body-human-mesh-recovery-in-a-single-shot/ · SMPLest-X — https://arxiv.org/pdf/2501.09782
- SAM 3D Body — https://github.com/facebookresearch/sam-3d-body · Anthropometric fidelity study — https://arxiv.org/html/2601.06035v1
- SHAPY — https://github.com/muelea/shapy
- Anny — https://github.com/naver/anny
- SMPL / SMPL-X licenses — https://smpl.is.tue.mpg.de/modellicense.html · https://smpl-x.is.tue.mpg.de/modellicense.html
- BodyM — https://registry.opendata.aws/bodym/ · paper https://arxiv.org/pdf/2210.05667
- Size Korea shape classification — https://pmc.ncbi.nlm.nih.gov/articles/PMC13392141/
- ViBE — https://openaccess.thecvf.com/content_CVPR_2020/html/Hsiao_ViBE_Dressing_for_Diverse_Body_Shapes_CVPR_2020_paper.html
- Clothing and shape estimation — ClothHMR https://arxiv.org/html/2512.17545 · ShapeBoost https://arxiv.org/pdf/2403.01345

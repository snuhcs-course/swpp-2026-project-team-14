# Automatic Loose-Clothing Detection

> Owner: Dongkun Moon · 2026-09-28 · Model: `clothing-1` (`backend/body_analysis/models/clothing_detector.json`)

## 1. Why

Until rev. 3 the app asked users what they wore (underwear / tight / loose). Trying the prototype showed two problems:
- It is an extra question that users answer carelessly, and a wrong answer changes the confidence, the learned correction and the insights.
- A single answer cannot say *which* part is loose. A loose T-shirt with leggings distorts the chest and waist but not the inseam.

So the question was removed. The server now decides, separately for the **top** and the **bottom**, whether the clothing is loose. The app tells users that underwear or tight clothing gives the best result, and warns only when loose clothing is detected.

## 2. Method

- **Features:** the same scale-free silhouette features the learned correction uses (`measurer._features`: front widths and side depths at fixed height fractions, divided by height), plus the gender one-hot. No extra model or image pass is needed, so detection adds well under 1 ms.
- **Model:** two logistic regressions (top loose, bottom loose), with standardised features and missing values imputed by the training mean. Exported as plain JSON and run with numpy only (`body_analysis/clothing.py`), like the corrector.
- **Labels:** the synthetic dataset (`05` §2) records the garment shell thickness. A region is "loose" when the shell is ≥ 2 cm (`cloth_top`, `cloth_pants`). Underwear and tight samples are "fitted".
- **Selection:** the regularisation strength C and the decision threshold are chosen by grouped 5-fold cross-validation on the training bodies (balanced accuracy). The same 48 bodies as in `05` are held out for testing.
- **Train:** `scripts/train_clothing_detector.py --export`.

## 3. Results — 48 held-out bodies (112 samples)

| Region | C | Threshold | Loose detected (recall) | False alarms on fitted clothing |
|---|---|---|---|---|
| Top | 1.0 | 0.13 | 36/36 = **1.00** | 0/76 = **0 %** |
| Bottom | 0.3 | 0.33 | 23/24 = **0.96** | 1/88 = **1.1 %** |

- In the bottom region, only 24 of the 36 "loose" samples have a pants shell ≥ 2 cm. The other 12 are labelled fitted, and none of them was flagged.
- **Synthetic benchmark** (`04`, 8 fixed bodies × 3 conditions, full pipeline): the top and bottom were flagged for 8/8 loose bodies and 0/16 underwear or tight bodies. The insight table still shows **0 wrong insights on screen**.

## 4. What changes when loose clothing is detected

| | Top loose | Bottom loose |
|---|---|---|
| Warning | `loose_top`: "상의가 헐렁한 것 같아요. 가슴·허리 등 상체 둘레가 정확하지 않을 수 있어요." | `loose_bottom`: "하의가 헐렁한 것 같아요. 엉덩이·허벅지·다리 길이가 정확하지 않을 수 있어요." |
| Confidence lowered one level | neck, shoulder width, chest, underbust, waist, armhole, bicep, torso length | hip, thigh, calf, inseam, rise |
| Learned correction | skipped for those measurements (geometry kept) | skipped for those measurements |
| Insights hidden (unless the user edited the values) | body type, shoulders | body type, leg proportion |

Both warnings end with "속옷이나 몸에 붙는 옷으로 다시 찍으면 더 정확해요." The mapping is `LOOSE_AFFECTS` in `body_analysis/types.py`, mirrored in the app's `data/BodyMeasurements.kt`.

## 5. Cost: the correction model no longer knows the clothing

Without the clothing answer, the corrector (`ridge-2`) loses the clothing one-hot input. Held-out MAE (cm, weight not given) versus `ridge-1` from `05` §4:

| Measurement | Underwear: ridge-1 | Underwear: **ridge-2** | Tight: ridge-1 | Tight: **ridge-2** |
|---|---|---|---|---|
| chest | 1.23 | 1.31 | 1.25 | 1.33 |
| waist | 0.81 | 1.00 | 1.05 | 1.27 |
| hip | 0.58 | 0.67 | 0.72 | 0.81 |
| thigh | 0.62 | 0.71 | 0.78 | 0.84 |
| bicep | 0.61 | 0.79 | 0.61 | 0.69 |
| lengths (shoulder … outseam) | 0.25–0.57 | 0.24–0.57 | 0.21–0.64 | 0.24–0.65 |

The loss is at most about 0.2 cm, far below the geometric error (2–6 cm) the corrector removes. We accept it in exchange for one fewer question and per-region handling.

## 6. Limitations

1. **Synthetic clothing is easy.** The garment shells are uniform offsets of the body surface. Real clothes wrinkle, hang and have hems and patterns, so real-photo recall and false-alarm rates will be worse. Validating on real clothed photos is part of the real-photo benchmark (`03`).
2. **Tight vs. underwear is not distinguished.** Both count as fitted; the corrector was trained on both.
3. **The threshold decides the trade-off.** A false alarm only lowers confidence and shows a warning. A miss applies the correction to a distorted silhouette. The CV-chosen thresholds are low (0.13 / 0.33), which favours flagging.

## 7. Reproduce

```bash
cd backend
.venv\Scripts\python scripts/train_clothing_detector.py            # report on held-out bodies
.venv\Scripts\python scripts/train_clothing_detector.py --export   # write models/clothing_detector.json
.venv\Scripts\python scripts/train_corrector.py --export           # ridge-2 without the clothing input
.venv\Scripts\python scripts/synthetic_benchmark.py                # detection table + insights
```

# AI-generated with Claude Code (Claude Opus 5.5), 2026-09-28, reviewed by Dongkun Moon
"""Train and evaluate the loose-clothing detector on the synthetic dataset.

    python scripts/train_clothing_detector.py [--data private/dataset/samples.csv] [--export]

Two binary logistic regressions on the silhouette features (+ gender):
- top loose:    simulated top garment ≥ 2 cm thick   (dataset column cloth_top)
- bottom loose: simulated trousers ≥ 2 cm thick      (dataset column cloth_pants)
Underwear and tight samples (≤ 0.8 cm) are "fitted".

Same protocol as train_corrector.py: every 5th body is a held-out TEST body; the regularisation
strength C and the decision threshold are chosen by 5-fold cross-validation grouped by body on
the training bodies only (threshold = best balanced accuracy on out-of-fold predictions).
--export writes body_analysis/models/clothing_detector.json.
"""

from __future__ import annotations

import argparse
import json
import sys
import warnings
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from train_corrector import feature_names, load  # noqa: E402

from body_analysis.clothing import DEFAULT_MODEL_PATH  # noqa: E402
from body_analysis.types import Gender  # noqa: E402

LOOSE_THICKNESS_M = 0.02
REGIONS = {"top": "cloth_top", "bottom": "cloth_pants"}
C_GRID = [0.01, 0.03, 0.1, 0.3, 1.0]


def names_for(rows: list[dict]) -> list[str]:
    return feature_names(rows, "geometry") + [f"in_gender_{g.value}" for g in Gender]


def matrix(rows: list[dict], names: list[str]) -> np.ndarray:
    out = np.full((len(rows), len(names)), np.nan)
    for i, r in enumerate(rows):
        values = {**r, **{f"in_gender_{g.value}": float(r["gender"] == g.value) for g in Gender}}
        for j, name in enumerate(names):
            v = values.get(name)
            if v not in (None, ""):
                out[i, j] = float(v)
    return out


class Standardiser:
    """Mean-impute + standardise, identical to body_analysis.regressor._Linear at inference time."""

    def fit(self, x: np.ndarray) -> "Standardiser":
        self.mean = np.nan_to_num(np.nanmean(x, axis=0))
        std = np.nanstd(x, axis=0)
        self.scale = np.where((std == 0) | np.isnan(std), 1.0, std)
        return self

    def __call__(self, x: np.ndarray) -> np.ndarray:
        return (np.where(np.isnan(x), self.mean, x) - self.mean) / self.scale


def fit_logistic(x: np.ndarray, y: np.ndarray, c: float):
    from sklearn.linear_model import LogisticRegression

    std = Standardiser().fit(x)
    model = LogisticRegression(C=c, max_iter=5000).fit(std(x), y)
    return std, model


def best_threshold(p: np.ndarray, y: np.ndarray) -> tuple[float, float]:
    """Threshold with the best balanced accuracy (mean of loose recall and fitted recall)."""
    best = (0.5, 0.0)
    for t in np.linspace(0.05, 0.95, 91):
        pred = p >= t
        bal = 0.5 * ((pred[y == 1]).mean() + (~pred[y == 0]).mean())
        if bal > best[1]:
            best = (float(t), float(bal))
    return best


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    warnings.filterwarnings("ignore", category=RuntimeWarning)
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, default=Path("private/dataset/samples.csv"))
    parser.add_argument("--export", action="store_true")
    parser.add_argument("--report", type=Path, default=Path("private/dataset/clothing_report.md"))
    args = parser.parse_args()

    rows = load(args.data)
    bodies = sorted({r["body"] for r in rows})
    test_bodies = set(bodies[::5])
    train = [r for r in rows if r["body"] not in test_bodies]
    test = [r for r in rows if r["body"] in test_bodies]
    names = names_for(rows)
    x_train, x_test = matrix(train, names), matrix(test, names)
    fold_of = {b: i % 5 for i, b in enumerate(sorted({r["body"] for r in train}))}
    folds = np.array([fold_of[r["body"]] for r in train])
    print(f"{len(rows)} samples ({len(train)} train / {len(test)} test), {len(names)} features")

    report = ["# Loose-clothing detector — held-out test bodies", "",
              f"{len(test)} test samples from {len(test_bodies)} bodies never used for training or model selection.", ""]
    exported = {}
    for region, column in REGIONS.items():
        y_train = np.array([float(r[column]) >= LOOSE_THICKNESS_M for r in train], int)
        y_test = np.array([float(r[column]) >= LOOSE_THICKNESS_M for r in test], int)

        # --- choose C and threshold with grouped CV on the training bodies ---------------------
        best = None
        for c in C_GRID:
            oof = np.zeros(len(train))
            for k in range(5):
                tr, va = folds != k, folds == k
                std, model = fit_logistic(x_train[tr], y_train[tr], c)
                oof[va] = model.predict_proba(std(x_train[va]))[:, 1]
            threshold, bal = best_threshold(oof, y_train)
            if best is None or bal > best[2]:
                best = (c, threshold, bal)
        c, threshold, cv_bal = best
        std, model = fit_logistic(x_train, y_train, c)

        # --- held-out evaluation -----------------------------------------------------------------
        p = model.predict_proba(std(x_test))[:, 1]
        pred = p >= threshold
        tp, fp = int((pred & (y_test == 1)).sum()), int((pred & (y_test == 0)).sum())
        fn, tn = int((~pred & (y_test == 1)).sum()), int((~pred & (y_test == 0)).sum())
        recall = tp / max(tp + fn, 1)
        false_alarm = fp / max(fp + tn, 1)
        report += [
            f"## {region}", "",
            f"- C = {c}, threshold = {threshold:.2f} (CV balanced accuracy {cv_bal:.3f})",
            f"- accuracy {(tp + tn) / len(y_test):.3f}, balanced accuracy {0.5 * (recall + 1 - false_alarm):.3f}",
            f"- loose detected (recall) {tp}/{tp + fn} = {recall:.3f}; false alarms on fitted clothing {fp}/{fp + tn} = {false_alarm:.3f}",
            "",
            "| Simulated condition | n | flagged loose |",
            "|---|---|---|",
        ]
        for condition in ("underwear", "tight", "loose"):
            mask = np.array([r["condition"] == condition for r in test])
            report.append(f"| {condition} | {mask.sum()} | {int(pred[mask].sum())} ({pred[mask].mean():.0%}) |")
        report.append("")
        exported[region] = {
            "features": names,
            "mean": std.mean.round(6).tolist(),
            "scale": std.scale.round(6).tolist(),
            "coef": model.coef_[0].round(6).tolist(),
            "intercept": round(float(model.intercept_[0]), 6),
            "threshold": round(threshold, 3),
            "C": c,
            "test": {"recall": round(recall, 3), "false_alarm_rate": round(false_alarm, 3)},
        }

    args.report.write_text("\n".join(report) + "\n", encoding="utf-8")
    print("\n".join(report))
    if args.export:
        payload = {
            "version": "clothing-1",
            "trained_on": f"{len(bodies) - len(test_bodies)} synthetic Anny bodies; loose = garment >= {LOOSE_THICKNESS_M * 100:.0f} cm",
            "models": exported,
        }
        DEFAULT_MODEL_PATH.write_text(json.dumps(payload), encoding="utf-8")
        print(f"exported → {DEFAULT_MODEL_PATH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

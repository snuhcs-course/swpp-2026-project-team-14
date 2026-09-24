"""Train and evaluate the learned measurement correction on the synthetic dataset.

    python scripts/train_corrector.py [--data private/dataset/samples.csv] [--export]

- Bodies are split once: every 5th body is a held-out TEST body, never used for fitting or model
  selection. Model choices (ridge alpha, whether to use the model at all) are made with 5-fold
  cross-validation grouped by body on the training bodies only.
- Target: measurement / height. Features: scale-free geometric features + user inputs
  (body_analysis.regressor.input_features). Each training row is used twice, with and without the
  weight, so the model works whether or not the user enters it.
- Compared on the test bodies: geometric baseline (current pipeline), ridge regression,
  gradient boosting (sklearn, comparison only).
- --export writes body_analysis/models/measurement_corrector.json with the ridge models for the
  measurements where ridge beat the baseline in cross-validation.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
import warnings
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from body_analysis.regressor import DEFAULT_MODEL_PATH, input_features  # noqa: E402
from body_analysis.types import AnalysisInput, Clothing, Gender, MeasurementType as M  # noqa: E402

EVALUATED = [
    M.SHOULDER_WIDTH, M.SLEEVE_LENGTH, M.TORSO_LENGTH, M.RISE, M.INSEAM, M.OUTSEAM,
    M.NECK, M.CHEST, M.WAIST, M.HIP, M.BICEP, M.WRIST, M.THIGH, M.CALF,
]
DEPLOY_CONDITIONS = ("underwear", "tight")  # loose-clothing corrections would learn our simulated garments
ALPHAS = [0.1, 1.0, 3.0, 10.0, 30.0, 100.0]


def load(path: Path) -> list[dict]:
    with open(path, encoding="utf-8") as fh:
        rows = [r for r in csv.DictReader(fh) if not r["error"]]
    return rows


def feature_names(rows: list[dict], feature_set: str = "all") -> list[str]:
    """feature_set: "all", "geometry" (photo only, no user inputs) or "inputs" (no photo)."""
    prefixes = ("geo_", "w_", "d_", "lm_", "row_")
    extra = {"shoulder_spread", "hip_joint_spread", "arms_found"}
    geometry = [k for k in rows[0] if k.startswith(prefixes) or k in extra]
    inputs = list(input_features(AnalysisInput(height_cm=170)))
    return {"all": geometry + inputs, "geometry": geometry, "inputs": inputs}[feature_set]


def to_matrix(rows: list[dict], names: list[str], with_weight: bool) -> np.ndarray:
    out = np.full((len(rows), len(names)), np.nan)
    for i, r in enumerate(rows):
        inp = AnalysisInput(
            height_cm=float(r["height_cm"]),
            weight_kg=float(r["weight_kg"]) if with_weight else None,
            gender=Gender(r["gender"]),
            clothing=Clothing(r["condition"]),
        )
        values = {**{k: r.get(k) for k in names}, **input_features(inp)}
        for j, name in enumerate(names):
            v = values.get(name)
            if v not in (None, ""):
                out[i, j] = float(v)
    return out


def target(rows: list[dict], type_: M) -> np.ndarray:
    return np.array([float(r[f"truth_{type_.value}"]) / float(r["height_cm"]) for r in rows])


def baseline(rows: list[dict], type_: M) -> np.ndarray:
    """Current geometric pipeline prediction (cm); NaN where it produced nothing."""
    return np.array([float(r[f"pred_{type_.value}"]) if r.get(f"pred_{type_.value}") else np.nan for r in rows])


class RidgeModel:
    """Standardise → impute missing with mean (0 after scaling) → ridge. Mirrors regressor._Linear."""

    def __init__(self, alpha: float):
        self.alpha = alpha

    def fit(self, x: np.ndarray, y: np.ndarray) -> "RidgeModel":
        self.mean = np.nanmean(x, axis=0)
        self.mean = np.where(np.isnan(self.mean), 0.0, self.mean)
        std = np.nanstd(x, axis=0)
        self.scale = np.where((std == 0) | np.isnan(std), 1.0, std)
        z = self._z(x)
        self.intercept = float(y.mean())
        a = z.T @ z + self.alpha * np.eye(z.shape[1])
        self.coef = np.linalg.solve(a, z.T @ (y - self.intercept))
        return self

    def _z(self, x: np.ndarray) -> np.ndarray:
        x = np.where(np.isnan(x), self.mean, x)
        return (x - self.mean) / self.scale

    def predict(self, x: np.ndarray) -> np.ndarray:
        return self._z(x) @ self.coef + self.intercept


def gbm():
    from sklearn.ensemble import HistGradientBoostingRegressor

    return HistGradientBoostingRegressor(max_iter=400, learning_rate=0.05, max_leaf_nodes=15, l2_regularization=1.0)


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")  # Windows console default is cp949
    warnings.filterwarnings("ignore", category=RuntimeWarning)  # empty subsets on small datasets
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, default=Path("private/dataset/samples.csv"))
    parser.add_argument("--export", action="store_true")
    parser.add_argument("--report", type=Path, default=Path("private/dataset/training_report.md"))
    parser.add_argument("--features", choices=["all", "geometry", "inputs"], default="all",
                        help="ablation: which feature groups the model may use")
    args = parser.parse_args()

    rows = load(args.data)
    bodies = sorted({r["body"] for r in rows})
    test_bodies = set(bodies[::5])
    names = feature_names(rows, args.features)
    print(f"{len(rows)} samples, {len(bodies)} bodies ({len(test_bodies)} held out), {len(names)} features")

    train_all = [r for r in rows if r["body"] not in test_bodies]
    test_all = [r for r in rows if r["body"] in test_bodies]
    train = [r for r in train_all if r["condition"] in DEPLOY_CONDITIONS]
    # every training row twice: with and without the weight
    x_train = np.vstack([to_matrix(train, names, True), to_matrix(train, names, False)])
    groups = np.array([r["body"] for r in train] * 2)
    fold_of = {b: i % 5 for i, b in enumerate(sorted(set(groups)))}
    folds = np.array([fold_of[g] for g in groups])

    exported, lines = {}, []
    header = "| Measurement | Condition | Weight | Baseline MAE | Ridge MAE | GBM MAE | n |"
    for type_ in EVALUATED:
        y_train = np.concatenate([target(train, type_)] * 2)
        base_train = np.concatenate([baseline(train, type_)] * 2) / np.array([float(r["height_cm"]) for r in train] * 2)
        ok = ~np.isnan(y_train)

        # --- model selection by grouped CV on training bodies only -------------------------
        cv = {}
        for alpha in ALPHAS:
            err = []
            for k in range(5):
                tr, va = ok & (folds != k), ok & (folds == k)
                model = RidgeModel(alpha).fit(x_train[tr], y_train[tr])
                err.append(np.abs(model.predict(x_train[va]) - y_train[va]))
            cv[alpha] = float(np.mean(np.concatenate(err)))
        best_alpha = min(cv, key=cv.get)
        has_base = ok & ~np.isnan(base_train)
        base_cv = float(np.mean(np.abs(base_train[has_base] - y_train[has_base])))
        use_ridge = cv[best_alpha] < base_cv

        ridge = RidgeModel(best_alpha).fit(x_train[ok], y_train[ok])
        boost = gbm().fit(x_train[ok], y_train[ok])
        if use_ridge:
            exported[type_.value] = {
                "features": names,
                "mean": ridge.mean.round(6).tolist(),
                "scale": ridge.scale.round(6).tolist(),
                "coef": ridge.coef.round(6).tolist(),
                "intercept": round(ridge.intercept, 6),
                "alpha": best_alpha,
                "cv_mae_ratio": {"ridge": round(cv[best_alpha], 5), "baseline": round(base_cv, 5)},
            }

        # --- held-out test bodies, every condition, with and without weight ------------------
        for condition in ("underwear", "tight", "loose"):
            subset = [r for r in test_all if r["condition"] == condition]
            if not subset:
                continue
            heights = np.array([float(r["height_cm"]) for r in subset])
            truth_cm = target(subset, type_) * heights
            base_cm = baseline(subset, type_)
            for with_weight in (True, False):
                x = to_matrix(subset, names, with_weight)
                deployable = condition in DEPLOY_CONDITIONS and use_ridge
                ridge_cm = ridge.predict(x) * heights if deployable else base_cm
                boost_cm = boost.predict(x) * heights
                m = ~np.isnan(base_cm) & ~np.isnan(truth_cm)
                lines.append(
                    f"| {type_.value} | {condition} | {'yes' if with_weight else 'no'} | "
                    f"{np.mean(np.abs(base_cm[m] - truth_cm[m])):.2f} | "
                    f"{np.mean(np.abs(ridge_cm[m] - truth_cm[m])):.2f}{'' if deployable else ' (not applied)'} | "
                    f"{np.mean(np.abs(boost_cm[m] - truth_cm[m])):.2f} | {m.sum()} |"
                )
        print(f"{type_.value}: alpha {best_alpha}, CV ratio-MAE ridge {cv[best_alpha]:.4f} vs baseline {base_cv:.4f}"
              f" → {'ridge' if use_ridge else 'keep geometric'}", flush=True)

    report = ["# Learned correction — held-out test bodies", "",
              f"{len(rows)} samples, {len(bodies)} bodies, {len(test_bodies)} test bodies, trained on {DEPLOY_CONDITIONS}.",
              "GBM is trained on the same conditions, shown for comparison only (not deployed).", "",
              header, "|---|---|---|---|---|---|---|", *lines]
    args.report.write_text("\n".join(report) + "\n", encoding="utf-8")
    print("\n".join(report))

    if args.export and args.features != "all":
        raise SystemExit("export only the full-feature model")
    if args.export:
        DEFAULT_MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "version": "ridge-1",
            "trained_on": f"{len(bodies) - len(test_bodies)} synthetic Anny bodies, conditions {list(DEPLOY_CONDITIONS)}",
            "conditions": list(DEPLOY_CONDITIONS),
            "target": "measurement_cm / height_cm",
            "measurements": exported,
        }
        DEFAULT_MODEL_PATH.write_text(json.dumps(payload), encoding="utf-8")
        print(f"exported {len(exported)} measurement models → {DEFAULT_MODEL_PATH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

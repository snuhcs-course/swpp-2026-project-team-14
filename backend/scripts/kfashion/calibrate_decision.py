# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-11, reviewed by Dongkun Moon
"""Calibrate the local decider's temperature so its probabilities mean what they say.

    python scripts/kfashion/calibrate_decision.py [--n 3000]

On held-out validation outfits (FITB: the real garment + 3 random ones of the same slot), the
stylist's fit-in and colour scores are computed as in a real decision; the softmax temperature
that minimises the negative log-likelihood of the real garment is kept. Reported: accuracy, NLL
and expected calibration error (ECE, 10 bins) before and after. Writes
<catalog>/decision_calibration.json, read by recommendation.decision.LocalDecider.
"""

from __future__ import annotations

import argparse
import json
import math
import random
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from labels import CATALOG_DIR  # noqa: E402
from train_masked import load  # noqa: E402

from recommendation.color import outfit_color  # noqa: E402
from recommendation.compat import CompatModel, values  # noqa: E402
from recommendation.decision import DEFAULT_TEMPERATURE  # noqa: E402
from recommendation.masked import MaskedModel  # noqa: E402
from recommendation.stylist import W  # noqa: E402
from recommendation.vocab import SLOTS  # noqa: E402


def softmax(x: np.ndarray, t: float) -> np.ndarray:
    z = (x - x.max(axis=1, keepdims=True)) / t
    e = np.exp(z)
    return e / e.sum(axis=1, keepdims=True)


def ece(probs: np.ndarray, bins: int = 10) -> float:
    """Expected calibration error of the top choice (label = column 0)."""
    conf = probs.max(axis=1)
    correct = probs.argmax(axis=1) == 0
    total = 0.0
    for lo in np.linspace(0, 1, bins, endpoint=False):
        m = (conf > lo) & (conf <= lo + 1 / bins)
        if m.any():
            total += m.mean() * abs(conf[m].mean() - correct[m].mean())
    return float(total)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=3000)
    ap.add_argument("--seed", type=int, default=9)
    args = ap.parse_args()
    rng = random.Random(args.seed)
    compat = CompatModel.from_json(json.loads((CATALOG_DIR / "stats.json").read_text(encoding="utf-8")))
    masked = MaskedModel.load(CATALOG_DIR)
    outfits = [o for o in load("val") if len(o["garments"]) >= 2]
    pools = {s: [o["garments"][s] for o in outfits if s in o["garments"]] for s in SLOTS}
    rng.shuffle(outfits)
    rows = []
    for o in outfits[: args.n]:
        slot = rng.choice(list(o["garments"]))
        cands = [o["garments"][slot]] + rng.sample(pools[slot], 3)
        context = {s: a for s, a in o["garments"].items() if s != slot}
        lp = masked.slot_logprobs(context, o["style"], slot) if masked else None  # the app passes the preferred style
        raw, col = [], []
        for c in cands:
            pmi = np.mean([compat.pair_score(slot, values(c), s, values(a)) for s, a in context.items()])
            raw.append((masked.score(lp, c) if lp is not None else 0.0) + 0.5 * float(pmi))
            col.append(outfit_color({**context, slot: c}, compat).score)
        raw = np.array(raw)
        fitin = (raw - raw.min()) / max(1e-9, raw.max() - raw.min())
        rows.append(W["fitin"] * fitin + W["color"] * np.array(col))
    x = np.array(rows)
    grid = np.exp(np.linspace(np.log(0.01), np.log(1.0), 120))
    nll = [float(-np.log(softmax(x, t)[:, 0] + 1e-12).mean()) for t in grid]
    best = float(grid[int(np.argmin(nll))])
    before, after = softmax(x, DEFAULT_TEMPERATURE), softmax(x, best)
    report = {
        "temperature": round(best, 4),
        "n": len(x),
        "accuracy": round(float((x.argmax(axis=1) == 0).mean()), 4),
        "nll": {"default": round(float(-np.log(before[:, 0] + 1e-12).mean()), 4), "calibrated": round(min(nll), 4),
                "uniform": round(math.log(4), 4)},
        "ece": {"default": round(ece(before), 4), "calibrated": round(ece(after), 4)},
        "masked_model": masked is not None,
    }
    (CATALOG_DIR / "decision_calibration.json").write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()

# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-08, reviewed by Dongkun Moon
"""Learn outfit compatibility from the K-Fashion training labels.

    python scripts/kfashion/build_stats.py [--out <catalog>/stats.json]

1. Outfits = training photos with two or more labelled garments. Several shots of one product
   would over-count its outfit (and inflate same-colour set-ups), so each (product stem, outfit
   signature) counts once.
2. 85 % of the outfits give the attribute co-occurrence PMI (recommendation.compat.PairCounter).
3. The other 15 % train a logistic regression: real outfit vs. the same outfit with one garment
   swapped for a random garment of the same slot. Validation photos are left for eval_fitb.py.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import time
from collections import Counter
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from labels import CATALOG_DIR, photos  # noqa: E402

from recommendation.compat import FEATURE_TYPES, CompatModel, PairCounter, sigmoid  # noqa: E402
from recommendation.vocab import SLOTS  # noqa: E402

REQUIRED = ("카테고리", "색상")


def load_outfits(split: str) -> tuple[list[dict], Counter]:
    seen, outfits, templates = set(), [], Counter()
    for p in photos(split):
        garments = {s: g.attrs for s, g in p.garments.items() if all(g.attrs.get(k) for k in REQUIRED)}
        if not garments:
            continue
        signature = tuple((s, garments[s].get("카테고리"), garments[s].get("색상"), garments[s].get("핏")) for s in SLOTS if s in garments)
        key = (p.stem, signature)
        if key in seen:
            continue
        seen.add(key)
        templates["+".join(s for s in SLOTS if s in garments)] += 1
        if len(garments) >= 2:
            outfits.append(garments)
    return outfits, templates


def swapped(outfit: dict, pools: dict[str, list[dict]], rng: random.Random) -> dict:
    slot = rng.choice(list(outfit))
    fake = dict(outfit)
    fake[slot] = rng.choice(pools[slot])
    return fake


def train_logistic(x: np.ndarray, y: np.ndarray, l2: float = 1e-3, iters: int = 400) -> tuple[np.ndarray, float]:
    w, b = np.zeros(x.shape[1]), 0.0
    lr = 1.0
    for _ in range(iters):
        p = sigmoid(x @ w + b)
        grad_w = x.T @ (p - y) / len(y) + l2 * w
        grad_b = float(np.mean(p - y))
        w -= lr * grad_w
        b -= lr * grad_b
    return w, b


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, default=CATALOG_DIR / "stats.json")
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()
    rng = random.Random(args.seed)
    t0 = time.time()

    outfits, templates = load_outfits("train")
    rng.shuffle(outfits)
    cut = int(len(outfits) * 0.85)
    count_set, fit_set = outfits[:cut], outfits[cut:]
    print(f"{len(outfits)} deduplicated outfits ({time.time() - t0:.0f}s)", flush=True)

    counter = PairCounter()
    for o in count_set:
        counter.add(o)
    pmi = counter.pmi()
    print(f"{len(pmi)} PMI entries ({time.time() - t0:.0f}s)", flush=True)

    model = CompatModel(pmi, np.zeros(len(FEATURE_TYPES)), 0.0)
    pools = {s: [o[s] for o in count_set if s in o] for s in SLOTS}
    fit_set = fit_set[:60000]
    pos = np.array([model.outfit_features(o) for o in fit_set])
    neg = np.array([model.outfit_features(swapped(o, pools, rng)) for o in fit_set])
    x = np.vstack([pos, neg])
    y = np.concatenate([np.ones(len(pos)), np.zeros(len(neg))])
    order = np.arange(len(y))
    np.random.default_rng(args.seed).shuffle(order)
    split = int(len(order) * 0.8)
    w, b = train_logistic(x[order[:split]], y[order[:split]])
    test = order[split:]
    acc = float(np.mean((sigmoid(x[test] @ w + b) > 0.5) == y[test]))
    # pairwise: does the real outfit beat its own swapped version?
    pair_acc = float(np.mean((pos @ w) > (neg @ w)))
    print(f"logistic: held-out accuracy {acc:.3f}, real-beats-swap {pair_acc:.3f} ({time.time() - t0:.0f}s)", flush=True)

    real_logits = pos @ w + b
    fake_logits = neg @ w + b
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps({
        "feature_types": FEATURE_TYPES,
        "weights": [round(float(v), 5) for v in w],
        "bias": round(b, 5),
        # logit percentiles of real and swapped outfits, to map a logit to a 0-100 display score
        "calibration": {
            "real": [round(float(v), 4) for v in np.percentile(real_logits, [5, 25, 50, 75, 95])],
            "swapped": [round(float(v), 4) for v in np.percentile(fake_logits, [5, 25, 50, 75, 95])],
        },
        "templates": dict(templates.most_common()),
        "meta": {
            "outfits": len(outfits), "pmi_outfits": len(count_set), "fit_outfits": len(fit_set),
            "heldout_accuracy": round(acc, 4), "real_beats_swap": round(pair_acc, 4),
        },
        "pmi": pmi,
    }, ensure_ascii=False), encoding="utf-8")
    print(f"wrote {args.out} ({args.out.stat().st_size // 1024} KB, {time.time() - t0:.0f}s)")


if __name__ == "__main__":
    main()

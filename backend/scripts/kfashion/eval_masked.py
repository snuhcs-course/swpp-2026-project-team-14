# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
"""Compare masked models on top+bottom outfits and calibrate the pair score the app uses.

    python scripts/kfashion/eval_masked.py --models DIR [DIR ...] [--n 5000] [--calibrate DIR]

The wardrobe recommendation always builds 상의+하의 (+아우터) outfits, so the comparison uses
validation outfits that have both, hides the 상의 or the 하의, and asks each model to pick it back
among 4 candidates (real + 3 random, the same samples for every model; chance 25 %).

  masked          context = the other garments + the photo's style (train_masked.py protocol)
  pair            what the app computes: 0.5 * (bottom given top + top given bottom), style context
  pair + pmi      the app's compatibility: mean of both scores as percentiles of real outfits

--calibrate DIR writes those percentiles ("pair_calibration": quantiles of the pair score on real
validation outfits, per slot pair) into DIR/masked_vocab.json. Calibration and FITB use disjoint
outfits.
"""

from __future__ import annotations

import argparse
import json
import math
import random
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from train_masked import load  # noqa: E402

from recommendation.catalog import MODEL_DIR  # noqa: E402
from recommendation.compat import QUANTILES, CompatModel, percentile, values  # noqa: E402
from recommendation.masked import MaskedModel  # noqa: E402
from recommendation.vocab import SLOTS  # noqa: E402

PAIRS = (("상의", "하의"), ("아우터", "상의"), ("아우터", "하의"))


def pair_key(a: str, b: str) -> str:
    return "|".join(sorted((a, b), key=SLOTS.index))


def calibrate(model: MaskedModel, outfits: list[dict]) -> dict[str, list[float]]:
    scores: dict[str, list[float]] = {pair_key(*p): [] for p in PAIRS}
    for o in outfits:
        g = o["garments"]
        for a, b in PAIRS:
            if a in g and b in g:
                scores[pair_key(a, b)].append(float(model.pair_scores(a, [g[a]], b, [g[b]], o["style"])[0, 0]))
    return {k: [round(float(q), 4) for q in np.quantile(v, QUANTILES)] for k, v in scores.items() if len(v) >= 200}


def fitb(model: MaskedModel, compat: CompatModel, samples: list[tuple], calibration: dict, pmi_points: list) -> dict:
    hits = {"masked": 0, "pair": 0, "pmi": 0, "masked + pmi (log)": 0, "pair + pmi (app)": 0}
    for o, slot, cands, order in samples:
        g = o["garments"]
        other = "하의" if slot == "상의" else "상의"
        context = {s: a for s, a in g.items() if s != slot}
        lp = model.slot_logprobs(context, o["style"], slot)
        masked = [model.score(lp, c) for c in cands]
        pair = list(model.pair_scores(other, [g[other]], slot, cands, o["style"])[0])
        logit = [compat.bias + _pair_logit(compat, other, g[other], slot, c) for c in cands]  # search.py, 2 slots
        pmi = [compat.probability({**context, slot: c}) for c in cands]
        both_log = [m + 3 * math.log(max(1e-6, p)) for m, p in zip(masked, pmi)]
        key = pair_key(other, slot)
        app = [0.5 * float(percentile(pmi_points, lg)) + 0.5 * float(percentile(calibration[key], pr))
               for lg, pr in zip(logit, pair)]
        for name, scores in (("masked", masked), ("pair", pair), ("pmi", pmi), ("masked + pmi (log)", both_log),
                             ("pair + pmi (app)", app)):
            if max(order, key=lambda i: scores[i]) == 0:
                hits[name] += 1
    return {k: round(v / len(samples), 4) for k, v in hits.items()}


def _pair_logit(compat: CompatModel, slot_a: str, a: dict, slot_b: str, b: dict) -> float:
    (sa, va), (sb, vb) = sorted(((slot_a, a), (slot_b, b)), key=lambda t: SLOTS.index(t[0]))
    return compat.pair_score(sa, values(va), sb, values(vb))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", type=Path, nargs="+", required=True)
    ap.add_argument("--n", type=int, default=5000)
    ap.add_argument("--calib-n", type=int, default=4000)
    ap.add_argument("--calibrate", type=Path, help="model dir whose masked_vocab.json gets pair_calibration")
    ap.add_argument("--seed", type=int, default=3)
    args = ap.parse_args()
    t0 = time.time()
    rng = random.Random(args.seed)
    stats = json.loads((MODEL_DIR / "stats.json").read_text(encoding="utf-8"))
    compat = CompatModel.from_json(stats)
    val = [o for o in load("val") if {"상의", "하의"} <= set(o["garments"])]
    rng.shuffle(val)
    calib_set, test_set = val[: args.calib_n], val[args.calib_n : args.calib_n + args.n]
    pools = {s: [o["garments"][s] for o in val[args.calib_n :]] for s in ("상의", "하의")}
    samples = []
    for o in test_set:
        slot = rng.choice(("상의", "하의"))
        order = list(range(4))
        rng.shuffle(order)  # ties must not favour the real garment
        samples.append((o, slot, [o["garments"][slot]] + rng.sample(pools[slot], 3), order))
    print(f"top+bottom validation outfits: {len(val)}; calibration {len(calib_set)}, FITB {len(samples)} "
          f"({time.time() - t0:.0f}s)", flush=True)
    for directory in args.models:
        model = MaskedModel(directory)
        calibration = calibrate(model, calib_set)
        result = fitb(model, compat, samples, calibration, stats["calibration"]["real"])
        print(f"{directory}: {result} ({time.time() - t0:.0f}s)", flush=True)
        print(f"  pair calibration: {calibration}", flush=True)
        if args.calibrate and directory.resolve() == args.calibrate.resolve():
            path = directory / "masked_vocab.json"
            vocab = json.loads(path.read_text(encoding="utf-8"))
            vocab["pair_calibration"] = calibration
            vocab["fitb_top_bottom"] = result | {"n": len(samples)}
            path.write_text(json.dumps(vocab, ensure_ascii=False), encoding="utf-8")
            print(f"  wrote pair_calibration to {path}", flush=True)


if __name__ == "__main__":
    main()

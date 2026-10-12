# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-08, reviewed by Dongkun Moon
"""Fill-in-the-blank (FITB) check of the compatibility score on validation outfits.

    python scripts/kfashion/eval_fitb.py [--n 5000]

For a held-out outfit (validation photos, never used by build_stats.py), one garment is hidden
and must be picked back among 4 candidates of the same slot (the real one + 3 random). Chance is
25 %. Compares the colour rules, the learned compatibility, and both together.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from build_stats import load_outfits  # noqa: E402
from labels import CATALOG_DIR  # noqa: E402

from recommendation.color import outfit_color  # noqa: E402
from recommendation.compat import CompatModel  # noqa: E402
from recommendation.vocab import SLOTS  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=5000)
    ap.add_argument("--seed", type=int, default=3)
    args = ap.parse_args()
    rng = random.Random(args.seed)
    model = CompatModel.from_json(json.loads((CATALOG_DIR / "stats.json").read_text(encoding="utf-8")))
    outfits, _ = load_outfits("val")
    pools = {s: [o[s] for o in outfits if s in o] for s in SLOTS}
    rng.shuffle(outfits)
    scorers = {
        "random": lambda o: rng.random(),
        "colour rules": lambda o: outfit_color(o).score,
        "compat (learned)": lambda o: model.probability(o),
        "compat + colour": lambda o: model.probability(o) + 0.5 * outfit_color(o, model).score,
    }
    hits = {k: 0 for k in scorers}
    by_slot = {s: [0, 0] for s in SLOTS}
    n = 0
    for outfit in outfits[: args.n]:
        slot = rng.choice(list(outfit))
        cands = [outfit[slot]] + rng.sample(pools[slot], 3)
        order = list(range(4))
        rng.shuffle(order)  # ties must not favour the real garment
        for name, score in scorers.items():
            scores = {i: score({**outfit, slot: cands[i]}) for i in order}
            if max(order, key=scores.__getitem__) == 0:
                hits[name] += 1
                if name == "compat (learned)":
                    by_slot[slot][0] += 1
        by_slot[slot][1] += 1
        n += 1
    print(f"FITB on {n} validation outfits (4 candidates, chance 25 %):")
    for name, h in hits.items():
        print(f"  {name:18s} {h / n:.1%}")
    print("  compat by hidden slot:", {s: f"{h / t:.1%} (n={t})" for s, (h, t) in by_slot.items() if t})


if __name__ == "__main__":
    main()

# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-11, reviewed by Dongkun Moon
"""Masked attribute transformer over K-Fashion outfits (the teammate's design), and its FITB check.

    python scripts/kfashion/train_masked.py [--epochs 6] [--export]

Design (as proposed in the team):
1. Clean each label: drop empty slots, unnest lists, add a rule-based season token per garment.
2. Tokens from 스타일 and the garments: [스타일, 레트로], [아우터, 기장, 하프], [아우터, 색상, 그레이], ...
3. Each token is a 128-d vector = slot embedding + key embedding + value embedding.
4. Masking with a learned [MASK] value vector, then self-attention (Transformer encoder, no
   positions: an outfit is a set). Training mixes
   A) 15 % of the value tokens masked (relations inside and between garments) and
   B) one whole slot masked (the real recommendation case: fill the empty slot).
   The head predicts each masked value among the values of that token's key.
5. FITB on held-out validation outfits: a candidate garment for the hidden slot is scored by the
   mean log-probability of its attributes; compared with the PMI compatibility on the same items.
--export writes <catalog>/masked_model.onnx and masked_vocab.json (inference: recommendation/masked.py).
Needs PyTorch (requirements-ml.txt); the server only needs onnxruntime.
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
import torch
from torch import nn

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from labels import CATALOG_DIR, photos  # noqa: E402

from recommendation.compat import CompatModel  # noqa: E402
from recommendation.masked import (  # noqa: E402
    QUERY_KEYS,
    SEQ_LEN,
    SLOT_TOKENS,
    STYLE_SLOT,
    garment_tokens,
)
from recommendation.vocab import SLOTS  # noqa: E402

D_MODEL = 128
HEADS = 4
LAYERS = 2
MAX_TOKENS = 48


# ---------------------------------------------------------------- data
def load(split: str) -> list[dict]:
    """De-duplicated photos: {style, garments{slot: attrs}} (same rule as build_stats.py)."""
    seen, out = set(), []
    for p in photos(split):
        garments = {s: g.attrs for s, g in p.garments.items() if g.attrs.get("카테고리") and g.attrs.get("색상")}
        if not garments:
            continue
        key = (p.stem, tuple((s, garments[s].get("카테고리"), garments[s].get("색상"), garments[s].get("핏")) for s in SLOTS if s in garments))
        if key in seen:
            continue
        seen.add(key)
        out.append({"style": p.style, "garments": garments})
    return out


class Vocab:
    def __init__(self) -> None:
        self.slots = list(SLOT_TOKENS)
        self.keys: list[str] = []
        self.values: list[tuple[str, str]] = [("", "<pad>"), ("", "<mask>")]
        self.key_of_value: list[int] = [0, 0]

    def key_id(self, k: str) -> int:
        if k not in self.keys:
            self.keys.append(k)
        return self.keys.index(k)

    def build(self, outfits: list[dict], min_count: int = 20) -> None:
        counts: dict[tuple[str, str], int] = {}
        for o in outfits:
            for _, k, v in outfit_tokens(o):
                counts[(k, v)] = counts.get((k, v), 0) + 1
        for (k, v), c in sorted(counts.items()):
            if c >= min_count:
                self.key_id(k)
                self.values.append((k, v))
        self.index = {kv: i for i, kv in enumerate(self.values)}
        self.key_of_value = [self.keys.index(k) if k else 0 for k, _ in self.values]

    def to_json(self) -> dict:
        return {"slots": self.slots, "keys": self.keys, "values": [list(v) for v in self.values]}


def outfit_tokens(o: dict) -> list[tuple[str, str, str]]:
    toks = [(STYLE_SLOT, "스타일", o["style"])] if o.get("style") else []
    for slot in SLOTS:
        if slot in o["garments"]:
            toks += garment_tokens(slot, o["garments"][slot])
    return toks[:MAX_TOKENS]


def encode(vocab: Vocab, toks: list[tuple[str, str, str]]) -> tuple[list[int], list[int], list[int]]:
    s, k, v = [], [], []
    for slot, key, value in toks:
        if (key, value) not in vocab.index:
            continue
        s.append(vocab.slots.index(slot))
        k.append(vocab.keys.index(key))
        v.append(vocab.index[(key, value)])
    return s, k, v


# ---------------------------------------------------------------- model
class MaskedOutfitModel(nn.Module):
    def __init__(self, n_slots: int, n_keys: int, n_values: int, key_of_value: list[int]) -> None:
        super().__init__()
        self.slot = nn.Embedding(n_slots, D_MODEL)
        self.key = nn.Embedding(n_keys, D_MODEL)
        self.value = nn.Embedding(n_values, D_MODEL, padding_idx=0)  # id 1 = learned [MASK] vector
        layer = nn.TransformerEncoderLayer(D_MODEL, HEADS, dim_feedforward=4 * D_MODEL, dropout=0.1, batch_first=True)
        self.encoder = nn.TransformerEncoder(layer, LAYERS, enable_nested_tensor=False)
        self.norm = nn.LayerNorm(D_MODEL)
        self.head = nn.Linear(D_MODEL, n_values)
        # allowed[k, v] = 0 if value v belongs to key k else -inf: predictions stay inside the key
        allowed = torch.full((n_keys, n_values), float("-inf"))
        for v, k in enumerate(key_of_value):
            if v >= 2:
                allowed[k, v] = 0.0
        self.register_buffer("allowed", allowed)

    def forward(self, slot_ids, key_ids, value_ids, pad):
        x = self.slot(slot_ids) + self.key(key_ids) + self.value(value_ids)
        h = self.norm(self.encoder(x, src_key_padding_mask=pad))
        return self.head(h) + self.allowed[key_ids]


def batches(samples: list[tuple], batch_size: int, rng: random.Random, train: bool):
    order = list(range(len(samples)))
    if train:
        rng.shuffle(order)
    for i in range(0, len(order), batch_size):
        chunk = [samples[j] for j in order[i : i + batch_size]]
        t = max(len(c[0]) for c in chunk)
        s = torch.zeros(len(chunk), t, dtype=torch.long)
        k = torch.zeros_like(s)
        v = torch.zeros_like(s)
        target = torch.full_like(s, -100)
        for b, (ss, kk, vv, slot_of_tok) in enumerate(chunk):
            n = len(ss)
            s[b, :n], k[b, :n] = torch.tensor(ss), torch.tensor(kk)
            masked = set()
            garment_slots = sorted({x for x in slot_of_tok if x != STYLE_SLOT})
            if train and len(garment_slots) >= 2 and rng.random() < 0.5:  # B: one whole slot
                hide = rng.choice(garment_slots)
                masked = {i2 for i2, x in enumerate(slot_of_tok) if x == hide}
            elif train:  # A: 15 % of the tokens (at least one)
                masked = {i2 for i2 in range(n) if rng.random() < 0.15} or {rng.randrange(n)}
            vals = list(vv)
            for i2 in masked:
                target[b, i2] = vals[i2]
                vals[i2] = 1
            v[b, :n] = torch.tensor(vals)
        pad = v.eq(0)  # value id 0 only appears in padding
        yield s, k, v, pad, target


# ---------------------------------------------------------------- FITB
def fitb(model, vocab, outfits, compat: CompatModel, n: int, seed: int) -> dict:
    """Same protocol as eval_fitb.py: hide one garment, pick it back among 4 (real + 3 random)."""
    rng = random.Random(seed)
    pools = {s: [o["garments"][s] for o in outfits if s in o["garments"]] for s in SLOTS}
    multi = [o for o in outfits if len(o["garments"]) >= 2]
    rng.shuffle(multi)
    hits = {"masked": 0, "masked (no style)": 0, "pmi": 0, "masked + pmi": 0}
    total = 0
    model.eval()
    for o in multi[:n]:
        slot = rng.choice(list(o["garments"]))
        cands = [o["garments"][slot]] + rng.sample(pools[slot], 3)
        order = list(range(4))
        rng.shuffle(order)
        context = {s: a for s, a in o["garments"].items() if s != slot}
        lp_style = masked_scores(model, vocab, context, o["style"], slot, cands)
        lp_plain = masked_scores(model, vocab, context, None, slot, cands)
        pmi = [compat.probability({**context, slot: c}) for c in cands]
        both = [a + 3 * math.log(max(1e-6, b)) for a, b in zip(lp_style, pmi)]
        for name, scores in (("masked", lp_style), ("masked (no style)", lp_plain), ("pmi", pmi), ("masked + pmi", both)):
            if max(order, key=lambda i: scores[i]) == 0:
                hits[name] += 1
        total += 1
    return {k: round(v / total, 4) for k, v in hits.items()} | {"n": total}


@torch.no_grad()
def masked_scores(model, vocab, context: dict, style: str | None, slot: str, candidates: list[dict]) -> list[float]:
    toks = ([(STYLE_SLOT, "스타일", style)] if style else []) + [t for s in SLOTS if s in context for t in garment_tokens(s, context[s])]
    s, k, v = encode(vocab, toks)
    queries = [key for key in QUERY_KEYS if key in vocab.keys]
    s += [vocab.slots.index(slot)] * len(queries)
    k += [vocab.keys.index(q) for q in queries]
    v += [1] * len(queries)
    logits = model(torch.tensor([s]), torch.tensor([k]), torch.tensor([v]), torch.zeros(1, len(s), dtype=torch.bool))[0]
    logp = torch.log_softmax(logits[-len(queries):], dim=-1)
    out = []
    for c in candidates:
        vals = [torch.tensor(0.0)]
        for qi, key in enumerate(queries):
            raw = c.get(key)
            value = raw[0] if isinstance(raw, list) and raw else raw
            if value and (key, str(value)) in vocab.index:
                vals.append(logp[qi, vocab.index[(key, str(value))]])
        out.append(float(torch.stack(vals[1:]).mean()) if len(vals) > 1 else -20.0)
    return out


# ---------------------------------------------------------------- main
def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--epochs", type=int, default=6)
    ap.add_argument("--batch", type=int, default=256)
    ap.add_argument("--fitb", type=int, default=5000)
    ap.add_argument("--export", action="store_true")
    ap.add_argument("--seed", type=int, default=5)
    ap.add_argument("--require", default="", help="comma-separated slots every training outfit must have, e.g. 상의,하의")
    ap.add_argument("--out", type=Path, default=CATALOG_DIR, help="where the exported model files go")
    args = ap.parse_args()
    torch.manual_seed(args.seed)
    torch.set_num_threads(8)
    rng = random.Random(args.seed)
    t0 = time.time()

    required = {s for s in args.require.split(",") if s}
    train = [o for o in load("train") if required <= set(o["garments"])]
    val = load("val")
    args.out.mkdir(parents=True, exist_ok=True)
    print(f"required slots: {sorted(required) or '-'}", flush=True)
    print(f"outfits: train {len(train)}, val {len(val)} ({time.time() - t0:.0f}s)", flush=True)
    vocab = Vocab()
    vocab.build(train)
    print(f"vocab: {len(vocab.slots)} slots, {len(vocab.keys)} keys, {len(vocab.values)} values", flush=True)

    def to_samples(outfits):
        out = []
        for o in outfits:
            toks = [t for t in outfit_tokens(o) if (t[1], t[2]) in vocab.index]
            if len(toks) >= 2:
                s, k, v = encode(vocab, toks)
                out.append((s, k, v, [t[0] for t in toks]))
        return out

    train_samples = to_samples(train)
    holdout = to_samples(val[:20000])
    model = MaskedOutfitModel(len(vocab.slots), len(vocab.keys), len(vocab.values), vocab.key_of_value)
    opt = torch.optim.AdamW(model.parameters(), lr=1e-3, weight_decay=0.01)
    steps = args.epochs * math.ceil(len(train_samples) / args.batch)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=1e-3, total_steps=steps, pct_start=0.1)
    loss_fn = nn.CrossEntropyLoss(ignore_index=-100)
    for epoch in range(args.epochs):
        model.train()
        total, count = 0.0, 0
        for s, k, v, pad, target in batches(train_samples, args.batch, rng, train=True):
            logits = model(s, k, v, pad)
            loss = loss_fn(logits.reshape(-1, logits.shape[-1]), target.reshape(-1))
            opt.zero_grad()
            loss.backward()
            opt.step()
            sched.step()
            total += float(loss) * s.shape[0]
            count += s.shape[0]
        model.eval()
        with torch.no_grad():
            vt, vc = 0.0, 0
            for s, k, v, pad, target in batches(holdout, 512, random.Random(1), train=True):  # same masking mix
                logits = model(s, k, v, pad)
                vt += float(loss_fn(logits.reshape(-1, logits.shape[-1]), target.reshape(-1))) * s.shape[0]
                vc += s.shape[0]
        print(f"epoch {epoch + 1}: train loss {total / count:.3f}, val loss {vt / vc:.3f} ({time.time() - t0:.0f}s)", flush=True)

    compat = CompatModel.from_json(json.loads((CATALOG_DIR / "stats.json").read_text(encoding="utf-8")))
    result = fitb(model, vocab, val, compat, args.fitb, seed=3)
    print("FITB (4 candidates, chance 25 %):", result, flush=True)

    if args.export:
        model.eval()
        torch.save(model.state_dict(), args.out / "masked_model.pt")
        # Fixed length: the traced attention reshape does not stay dynamic, so inference pads to
        # SEQ_LEN with pad=True (recommendation/masked.py)
        n = SEQ_LEN
        dummy = (torch.zeros(1, n, dtype=torch.long), torch.zeros(1, n, dtype=torch.long),
                 torch.ones(1, n, dtype=torch.long), torch.zeros(1, n, dtype=torch.bool))
        path = args.out / "masked_model.onnx"
        torch.onnx.export(model, dummy, str(path), input_names=["slot_ids", "key_ids", "value_ids", "pad"],
                          output_names=["logits"], opset_version=17, dynamo=False)
        (args.out / "masked_vocab.json").write_text(json.dumps({**vocab.to_json(), "fitb": result}, ensure_ascii=False),
                                                       encoding="utf-8")
        print(f"exported {path} ({path.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()

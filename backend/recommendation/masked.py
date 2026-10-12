# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-11, reviewed by Dongkun Moon
"""Masked attribute transformer (teammate's design): what should the empty slot look like?

Tokens are [slot, key, value]; an outfit is the set of its garments' tokens plus [스타일, value].
The model (trained by scripts/kfashion/train_masked.py, run here with onnxruntime) fills masked
values. To recommend a garment for a slot, the slot's attributes are masked, the other garments
are the context, and each candidate is scored by the mean log-probability of its attributes.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from recommendation import season
from recommendation.vocab import SLOTS, as_list

STYLE_SLOT = "스타일"
SLOT_TOKENS = (STYLE_SLOT, *SLOTS)
SCALAR_KEYS = ("카테고리", "색상", "서브색상", "핏", "기장", "소매기장", "넥라인", "옷깃")
LIST_KEYS = {"소재": 2, "프린트": 2, "디테일": 3}
QUERY_KEYS = ("카테고리", "색상", "핏", "기장", "소재", "프린트", "소매기장")
WINTER_WARMTH = 2.8
SEQ_LEN = 64  # the ONNX graph has a fixed length; shorter inputs are padded (pad=True)


def season_token(slot: str, attrs: dict) -> str:
    """Rule-based season of one garment (the team's cleaning step)."""
    materials = set(as_list(attrs.get("소재")))
    warmth = season.item_warmth(slot, attrs)
    if materials & season.WINTER_MATERIALS or warmth >= WINTER_WARMTH:
        return "겨울"
    if materials & season.SUMMER_MATERIALS or attrs.get("소매기장") in {"민소매", "없음", "캡", "반팔"}:
        return "여름"
    return "간절기"


def garment_tokens(slot: str, attrs: dict) -> list[tuple[str, str, str]]:
    toks = [(slot, k, str(attrs[k])) for k in SCALAR_KEYS if attrs.get(k)]
    for k, limit in LIST_KEYS.items():
        toks += [(slot, k, str(v)) for v in as_list(attrs.get(k))[:limit]]
    toks.append((slot, "계절", season_token(slot, attrs)))
    return toks


class MaskedModel:
    def __init__(self, directory: Path) -> None:
        import onnxruntime as ort

        vocab = json.loads((directory / "masked_vocab.json").read_text(encoding="utf-8"))
        self.slots = vocab["slots"]
        self.keys = vocab["keys"]
        self.values = [tuple(v) for v in vocab["values"]]
        self.index = {v: i for i, v in enumerate(self.values)}
        self.fitb = vocab.get("fitb", {})
        # "상의|하의" -> 5/25/50/75/95 % quantiles of pair_score on real K-Fashion outfits
        # (scripts/kfashion/eval_masked.py); without it the recommender uses PMI only.
        self.pair_calibration = vocab.get("pair_calibration", {})
        self.fitb_top_bottom = vocab.get("fitb_top_bottom", {})
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 2
        self.session = ort.InferenceSession(str(directory / "masked_model.onnx"), opts, providers=["CPUExecutionProvider"])

    @classmethod
    def load(cls, directory: Path) -> "MaskedModel | None":
        if (directory / "masked_model.onnx").exists() and (directory / "masked_vocab.json").exists():
            return cls(directory)
        return None

    def slot_logprobs(self, context: dict[str, dict], style: str | None, slot: str) -> dict[str, np.ndarray]:
        """key -> log-probabilities over the value vocabulary for the masked `slot`."""
        toks = [(STYLE_SLOT, "스타일", style)] if style else []
        for s in SLOTS:
            if s in context and s != slot:
                toks += garment_tokens(s, context[s])
        s_ids, k_ids, v_ids = [], [], []
        for sl, k, v in toks:
            if (k, v) in self.index:
                s_ids.append(self.slots.index(sl))
                k_ids.append(self.keys.index(k))
                v_ids.append(self.index[(k, v)])
        queries = [q for q in QUERY_KEYS if q in self.keys]
        keep = SEQ_LEN - len(queries)
        s_ids, k_ids, v_ids = s_ids[:keep], k_ids[:keep], v_ids[:keep]
        s_ids += [self.slots.index(slot)] * len(queries)
        k_ids += [self.keys.index(q) for q in queries]
        v_ids += [1] * len(queries)  # 1 = [MASK]
        n = len(s_ids)
        feed = {name: np.zeros((1, SEQ_LEN), dtype=np.int64) for name in ("slot_ids", "key_ids", "value_ids")}
        feed["slot_ids"][0, :n], feed["key_ids"][0, :n], feed["value_ids"][0, :n] = s_ids, k_ids, v_ids
        feed["pad"] = np.ones((1, SEQ_LEN), dtype=bool)
        feed["pad"][0, :n] = False
        logits = self.session.run(["logits"], feed)[0][0, n - len(queries):n]
        logits = logits - logits.max(axis=1, keepdims=True)
        logp = logits - np.log(np.exp(logits).sum(axis=1, keepdims=True))
        return dict(zip(queries, logp))

    def score(self, logprobs: dict[str, np.ndarray], attrs: dict) -> float:
        """Mean log-probability of the candidate's attributes (higher = fits the context better)."""
        vals = []
        for key, lp in logprobs.items():
            raw = attrs.get(key)
            value = raw[0] if isinstance(raw, list) and raw else raw
            i = self.index.get((key, str(value))) if value else None
            if i is not None:
                vals.append(float(lp[i]))
        return float(np.mean(vals)) if vals else -20.0

    def pair_scores(self, slot_a: str, attrs_a: list[dict], slot_b: str, attrs_b: list[dict], style: str | None,
                    cache: dict | None = None) -> np.ndarray:
        """[len(a), len(b)] symmetric pair scores: mean of "b given a" and "a given b" (one inference
        per garment and target slot; `cache` keeps them across calls)."""
        cache = {} if cache is None else cache

        def logp(attrs: dict, slot: str, target: str) -> dict[str, np.ndarray]:
            key = (slot, json.dumps(attrs, sort_keys=True, ensure_ascii=False), target, style)
            if key not in cache:
                cache[key] = self.slot_logprobs({slot: attrs}, style, target)
            return cache[key]

        fwd = np.array([[self.score(logp(a, slot_a, slot_b), b) for b in attrs_b] for a in attrs_a])
        bwd = np.array([[self.score(logp(b, slot_b, slot_a), a) for b in attrs_b] for a in attrs_a])
        return 0.5 * (fwd + bwd)

    def predicted(self, logprobs: dict[str, np.ndarray], top: int = 2) -> dict[str, list[tuple[str, float]]]:
        """Most likely values per key (for explanations: '하의는 와이드 블랙 팬츠가 어울려요')."""
        out = {}
        for key, lp in logprobs.items():
            idx = np.argsort(-lp)[:top]
            out[key] = [(self.values[i][1], float(np.exp(lp[i]))) for i in idx]
        return out

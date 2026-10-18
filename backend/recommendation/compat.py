# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-08, reviewed by Dongkun Moon
"""Outfit compatibility learned from K-Fashion outfits.

Each K-Fashion photo is one outfit styled by a shopping mall, so co-occurrence across ~500k
outfits says which garments are worn together (the Style2Vec idea, on attributes instead of
photos). For every slot pair and attribute pair we keep a shrunk PMI; a logistic regression over
the averaged pair features (trained on real outfits vs. outfits with one garment swapped) turns an
outfit into the probability that it is a "real" outfit.
"""

from __future__ import annotations

import math
from collections import defaultdict
from dataclasses import dataclass

import numpy as np

from recommendation.vocab import SLOTS, as_list, normalize_value

KEYS = ("카테고리", "색상", "핏", "소재", "기장", "프린트", "소매기장")
FEATURE_TYPES: tuple[tuple[str, str], ...] = tuple(
    [(k, k) for k in KEYS] + [("카테고리", k) for k in KEYS[1:]] + [(k, "카테고리") for k in KEYS[1:]]
)
MAX_VALUES = 2  # first values of list attributes (소재, 프린트)
PMI_CLIP = 2.0
# Malls sell matching sets (same colour/fabric top and bottom), which makes "same value" pairs look
# far more compatible than styling choices are. Cap them so set-ups do not crowd out everything else.
SETUP_KEYS = ("색상", "소재", "프린트")
SETUP_CAP = 0.3


def ordered(slot_a: str, slot_b: str) -> bool:
    return SLOTS.index(slot_a) < SLOTS.index(slot_b)


def values(attrs: dict) -> dict[str, list[str]]:
    return {k: [str(v) for v in as_list(attrs.get(k))[:MAX_VALUES]] for k in KEYS}


class PairCounter:
    """Counts attribute co-occurrences between the garments of outfits."""

    def __init__(self) -> None:
        self.n: dict[tuple, float] = defaultdict(float)
        self.a: dict[tuple, float] = defaultdict(float)
        self.b: dict[tuple, float] = defaultdict(float)
        self.joint: dict[tuple, float] = defaultdict(float)

    def add(self, outfit: dict[str, dict]) -> None:
        slots = [s for s in SLOTS if s in outfit]
        vals = {s: values(outfit[s]) for s in slots}
        for i, sa in enumerate(slots):
            for sb in slots[i + 1 :]:
                for ka, kb in FEATURE_TYPES:
                    va, vb = vals[sa][ka], vals[sb][kb]
                    if not va or not vb:
                        continue
                    key = (sa, sb, ka, kb)
                    self.n[key] += 1
                    wa, wb = 1 / len(va), 1 / len(vb)
                    for x in va:
                        self.a[key + (x,)] += wa
                    for y in vb:
                        self.b[key + (y,)] += wb
                    for x in va:
                        for y in vb:
                            self.joint[key + (x, y)] += wa * wb

    def pmi(self, shrink: float = 20.0, min_count: float = 5.0) -> dict[str, tuple[float, int]]:
        """`slotA|slotB|keyA|keyB|valueA|valueB` -> (shrunk PMI, joint count)."""
        out = {}
        for (sa, sb, ka, kb, x, y), j in self.joint.items():
            if j < min_count:
                continue
            key = (sa, sb, ka, kb)
            n = self.n[key]
            raw = math.log(j * n / (self.a[key + (x,)] * self.b[key + (y,)]))
            value = max(-PMI_CLIP, min(PMI_CLIP, raw * j / (j + shrink)))
            if ka == kb and x == y and ka in SETUP_KEYS:
                value = min(value, SETUP_CAP)
            out["|".join((sa, sb, ka, kb, x, y))] = (round(value, 4), int(round(j)))
        return out


@dataclass
class Reason:
    slot_a: str
    slot_b: str
    key_a: str
    key_b: str
    value_a: str
    value_b: str
    pmi: float
    count: int

    @property
    def lift(self) -> float:
        return math.exp(self.pmi)


class CompatModel:
    def __init__(self, pmi: dict[str, list | tuple], weights: list[float], bias: float) -> None:
        self.pmi = {normalize_value(k): (float(v[0]), int(v[1])) for k, v in pmi.items()}
        self.weights = np.asarray(weights, dtype=float)
        self.bias = float(bias)

    @classmethod
    def from_json(cls, data: dict) -> "CompatModel":
        return cls(data["pmi"], data["weights"], data["bias"])

    def lookup(self, sa: str, sb: str, ka: str, kb: str, x: str, y: str) -> tuple[float, int]:
        return self.pmi.get(normalize_value("|".join((sa, sb, ka, kb, x, y))), (0.0, 0))

    def pair_features(self, slot_a: str, vals_a: dict, slot_b: str, vals_b: dict) -> np.ndarray:
        if not ordered(slot_a, slot_b):
            slot_a, vals_a, slot_b, vals_b = slot_b, vals_b, slot_a, vals_a
        f = np.zeros(len(FEATURE_TYPES))
        for i, (ka, kb) in enumerate(FEATURE_TYPES):
            va, vb = vals_a[ka], vals_b[kb]
            if va and vb:
                f[i] = sum(self.lookup(slot_a, slot_b, ka, kb, x, y)[0] for x in va for y in vb) / (len(va) * len(vb))
        return f

    def pair_score(self, slot_a: str, vals_a: dict, slot_b: str, vals_b: dict) -> float:
        """The pair's contribution to the outfit logit (before averaging over pairs)."""
        return float(self.weights @ self.pair_features(slot_a, vals_a, slot_b, vals_b))

    def outfit_features(self, outfit: dict[str, dict]) -> np.ndarray:
        slots = [s for s in SLOTS if s in outfit]
        vals = {s: values(outfit[s]) for s in slots}
        pairs = [(a, b) for i, a in enumerate(slots) for b in slots[i + 1 :]]
        if not pairs:
            return np.zeros(len(FEATURE_TYPES))
        return np.mean([self.pair_features(a, vals[a], b, vals[b]) for a, b in pairs], axis=0)

    def probability(self, outfit: dict[str, dict]) -> float:
        return sigmoid(self.bias + float(self.weights @ self.outfit_features(outfit)))

    def best_reason(self, outfit: dict[str, dict]) -> Reason | None:
        """The attribute pair that adds most to the score, for the explanation."""
        slots = [s for s in SLOTS if s in outfit]
        vals = {s: values(outfit[s]) for s in slots}
        best, best_gain = None, 0.0
        for i, sa in enumerate(slots):
            for sb in slots[i + 1 :]:
                for w, (ka, kb) in zip(self.weights, FEATURE_TYPES):
                    for x in vals[sa][ka]:
                        for y in vals[sb][kb]:
                            pmi, count = self.lookup(sa, sb, ka, kb, x, y)
                            gain = w * pmi
                            if pmi > 0 and count >= 30 and gain > best_gain:
                                best, best_gain = Reason(sa, sb, ka, kb, x, y, pmi, count), gain
        return best


def sigmoid(x: float | np.ndarray) -> float | np.ndarray:
    return 1 / (1 + np.exp(-x))


QUANTILES = (0.05, 0.25, 0.5, 0.75, 0.95)


def percentile(points: list[float], x: float | np.ndarray) -> np.ndarray:
    """Score -> share of real K-Fashion outfits it beats (0..1), from the 5/25/50/75/95 % quantiles
    of real outfits; linear in between, gently clipped outside."""
    xs, qs = np.asarray(points, dtype=float), np.asarray(QUANTILES)
    x = np.asarray(x, dtype=float)
    inside = np.interp(x, xs, qs)
    low = np.clip(0.05 - 0.05 * (xs[0] - x), 0, 0.05)
    high = np.clip(0.95 + 0.05 * (x - xs[-1]), 0.95, 1.0)
    return np.where(x < xs[0], low, np.where(x > xs[-1], high, inside))

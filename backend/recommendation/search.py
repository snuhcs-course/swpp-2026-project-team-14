# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-08, reviewed by Dongkun Moon
"""Turn a request (body, preferred fit and styles, weather, wardrobe, taste) into outfits.

Modes: "wardrobe" builds outfits only from the user's own garments (the app); "new" and "anchor"
pick K-Fashion catalog garments (test page).

1. Hard filters per garment: too small for the body, gender (men: unisex catalog pieces), weather
   band, opposite fit (catalog only), "never show". Own garments that fail the weather are used
   with a penalty when nothing else is left for a slot.
2. Per garment: body/fit score (preferred-fit match + silhouette rules) and taste (learned weights +
   preferred styles); minus penalties for near-duplicates of owned garments (mode "new") and for
   garments already shown.
3. For each outfit template of the weather band, the top garments per slot (+ a few random ones
   for exploration) are combined exhaustively. Compatibility = PMI pair scores (compat.py) and the
   masked transformer's pair scores (masked.py), each as the share of real K-Fashion outfits it
   beats, averaged.
4. Colour rules on the best combinations, a penalty for outfits already shown, then MMR picks
   outfits that differ from each other.
Total = 0.30 compat + 0.25 body/fit + 0.15 colour + 0.15 season + 0.15 taste.
"""

from __future__ import annotations

import itertools
import random
from collections import Counter
from dataclasses import dataclass, field

import numpy as np

from recommendation import explain, fit, preference, season, weather
from recommendation.body import Body
from recommendation.catalog import Catalog, Item
from recommendation.color import outfit_color
from recommendation.compat import percentile
from recommendation.vocab import PROFILE_STYLES, SLOTS, josa
from recommendation.wardrobe import check_fit, similar

WEIGHTS = {"compat": 0.30, "body": 0.25, "color": 0.15, "season": 0.15, "taste": 0.15}
TOP_K = 36
EXPLORE = 8
COLOR_RERANK = 150
NOVELTY_PENALTY = 0.12
SEEN_PENALTY = 0.15
SEEN_OUTFIT_PENALTY = 0.5
WEATHER_RELAX_PENALTY = 0.25
MASKED_SHARE = 0.5
MMR_LAMBDA = 0.5


@dataclass
class Request:
    body: Body
    pref_fit: str = "regular"
    bone: str | None = None  # optional 골격 type: 스트레이트 | 웨이브 | 내추럴
    styles: list[str] = field(default_factory=list)  # profile chips: 미니멀, 캐주얼, ...
    temp: float = 17.0
    wet: str | None = None  # "비" | "눈": rain rules (weather.wet_penalty)
    mode: str = "new"  # wardrobe: own garments only | new: catalog garments | anchor: start from an owned garment
    anchor: str | None = None
    wardrobe: list[Item] = field(default_factory=list)
    preference: dict = field(default_factory=preference.empty)
    seen: list[str] = field(default_factory=list)  # garment ids already shown
    seen_outfits: list[str] = field(default_factory=list)  # outfit keys already shown ("id+id")
    count: int = 3
    seed: int = 0
    fixed: dict[str, str] = field(default_factory=dict)  # chat: slot -> garment that must remain
    directions: dict[str, list[str]] = field(default_factory=dict)  # chat: requested changes by slot


@dataclass
class _Cand:
    item: Item
    body: float
    fit: float
    shape_reasons: list[str]
    shape: float
    taste: float
    style_hit: bool
    penalty: float

    @property
    def unary(self) -> float:
        return 0.5 * self.body + 0.5 * self.taste - self.penalty


class RecommendError(Exception):
    def __init__(self, message: str, code: str = "NO_MATCH") -> None:
        super().__init__(message)
        self.code = code


def outfit_key(items: list[Item]) -> str:
    return "+".join(sorted(i.id for i in items))


def recommend(catalog: Catalog, req: Request) -> dict:
    rng = random.Random(req.seed)
    band = season.band(req.temp)
    wardrobe_only = req.mode == "wardrobe"
    style_order = [s for chip in req.styles for s in PROFILE_STYLES.get(chip, ())]
    kf_styles = set(style_order)
    context_style = style_order[0] if style_order else None
    excluded = set(req.preference.get("excluded", []))
    seen, seen_outfits = set(req.seen), set(req.seen_outfits)
    filtered: Counter = Counter()
    notes = [explain.MALE_NOTE] if req.body.male and not wardrobe_only else []

    for w in req.wardrobe:
        check_fit(w, req.body)
    owned = [w for w in req.wardrobe if not w.too_small]
    for w in req.wardrobe:
        if w.too_small:
            notes.append(f"내 옷 '{w.name}'{josa(w.name, '은', '는')} {w.too_small}. 추천에서 뺐어요.")

    anchor = None
    if req.mode == "anchor" or (wardrobe_only and req.anchor):
        anchor = next((w for w in owned if w.id == req.anchor), None)
        if anchor is None:
            small = any(w.id == req.anchor for w in req.wardrobe)
            raise RecommendError("기준으로 고른 옷이 몸보다 작아요." if small else "기준이 될 내 옷을 골라 주세요.",
                                 "ANCHOR_TOO_SMALL" if small else "ANCHOR_NOT_FOUND")
    if wardrobe_only and not all(any(w.slot == s for w in owned) for s in ("상의", "하의")):
        raise RecommendError("코디를 만들려면 몸에 맞는 상의와 하의가 한 벌씩은 있어야 해요.", "WARDROBE_TOO_SMALL")

    def candidate(item: Item, relax_weather: bool = False) -> _Cand | None:
        own = item.source == "wardrobe"
        if item.id in excluded:
            filtered["다시 보지 않기"] += 1
            return None
        if req.body.male and not item.unisex and not own:
            filtered["성별"] += 1
            return None
        penalty = 0.0
        if req.directions.get(item.slot):
            from recommendation.stylist import _want_score
            penalty -= 0.3 * float(np.mean([_want_score(w, item, None) for w in req.directions[item.slot]
                                          if w not in ('warmer', 'cooler')] or [0.0]))
        if season.allowed(band, req.temp, item.slot, item.attrs, item.warmth):
            if not relax_weather:
                filtered["날씨"] += 1
                return None
            penalty += WEATHER_RELAX_PENALTY
        fm = fit.fit_match(req.pref_fit, item.slot, item.attrs)
        if fm <= 0 and not own:  # own garments stay; the fit only lowers their score
            filtered["선호 핏과 반대"] += 1
            return None
        shape = fit.shape_score(req.body, item.slot, item.attrs, req.bone)
        style_hit = bool(kf_styles & set(item.styles))
        style_part = (1.0 if style_hit else 0.3) if kf_styles else 0.5
        taste = 0.65 * preference.score(req.preference, item.slot, item.attrs, item.style) + 0.35 * style_part
        if req.mode == "new" and any(similar(item, w) for w in owned):
            penalty += NOVELTY_PENALTY
        if item.id in seen:
            penalty += SEEN_PENALTY
        penalty -= weather.wet_penalty(req.wet, item.slot, item.attrs)[0]
        return _Cand(item, 0.6 * fm + 0.4 * shape.score, fm, shape.reasons, shape.score, taste, style_hit, penalty)

    source = {s: [w for w in owned if w.slot == s] for s in SLOTS} if wardrobe_only else catalog.by_slot
    pools: dict[str, list[_Cand]] = {}
    for slot in SLOTS:
        available = [i for i in source.get(slot, []) if not req.fixed.get(slot) or i.id == req.fixed[slot]]
        cands = [c for c in (candidate(i, relax_weather=slot in req.fixed) for i in available) if c]
        if not cands and wardrobe_only and slot in ("상의", "하의"):
            cands = [c for c in (candidate(i, relax_weather=True) for i in available) if c]
            if cands:
                notes.append(f"오늘 날씨에 맞는 {slot}{josa(slot, '이', '가')} 없어서 가장 가까운 옷으로 골랐어요.")
        cands.sort(key=lambda c: -c.unary)
        rest = cands[TOP_K:]
        pools[slot] = cands[:TOP_K] + (rng.sample(rest, min(EXPLORE, len(rest))) if rest else [])
    if anchor is not None:
        a = _Cand(anchor, 0.6 * fit.fit_match(req.pref_fit, anchor.slot, anchor.attrs)
                  + 0.4 * fit.shape_score(req.body, anchor.slot, anchor.attrs, req.bone).score, 1.0, [], 0.5, 0.5, False, 0.0)
        pools[anchor.slot] = [a]

    templates = [(t, w) for t, w in band.templates if not (req.body.male and "원피스" in t)]
    if req.fixed:
        if any(not pools.get(s) for s in req.fixed):
            raise RecommendError('유지할 옷으로 코디를 만들 수 없어요. 잠금을 풀거나 옷장 정보를 확인해 주세요.')
        templates = [(t, w) for t, w in templates if set(req.fixed) <= set(t)]
        if not templates:
            templates = [((('아우터',) if '아우터' in req.fixed else ()) + ('상의', '하의'), 1.0)]
    if anchor is not None:
        templates = [(t, w) for t, w in templates if anchor.slot in t] or [((anchor.slot,), 1.0)]
    cache: dict = {}
    scored = []
    for slots, t_weight in templates:
        if any(not pools.get(s) for s in slots):
            continue
        scored += _score_template(catalog, band, slots, t_weight, [pools[s] for s in slots], context_style, cache)
    if not scored and wardrobe_only and pools.get("상의") and pools.get("하의") and '아우터' not in req.fixed:
        scored = _score_template(catalog, band, ("상의", "하의"), 1.0, [pools["상의"], pools["하의"]], context_style, cache)
        if band.outer in {"recommended", "required"}:
            notes.append("오늘은 아우터가 필요한 날씨인데 맞는 아우터가 없어 상의와 하의만 골랐어요.")
    if not scored:
        raise RecommendError("조건에 맞는 옷이 없어요. 기온이나 선호 핏을 바꿔 보세요.")

    scored.sort(key=lambda o: -o["prelim"])
    finals = []
    for o in scored[: COLOR_RERANK * 3]:  # several templates compete; keep enough for MMR to diversify
        col = outfit_color({c.item.slot: c.item.attrs for c in o["cands"]}, catalog.compat)
        o["color"], o["color_reason"] = col.score, col.reason
        o["key"] = outfit_key([c.item for c in o["cands"]])
        o["seen"] = o["key"] in seen_outfits
        o["total"] = o["prelim"] + WEIGHTS["color"] * (col.score - 0.75) - SEEN_OUTFIT_PENALTY * o["seen"]
        finals.append(o)
    picked = _mmr(finals, req.count)
    has_feedback = bool(req.preference.get("weights"))
    outfits = [_present(catalog, req, band, o, has_feedback) for o in picked]
    return {
        "outfits": outfits,
        "notes": notes,
        "band": {"label": band.label, "outer": band.outer},
        "debug": {
            "filtered": dict(filtered),
            "pool": {s: len(p) for s, p in pools.items()},
            "combinations": len(scored),
            "too_small": [w.id for w in req.wardrobe if w.too_small],
            "masked": _uses_masked(catalog),
        },
    }


def _uses_masked(catalog: Catalog) -> bool:
    return bool(catalog.masked and catalog.masked.pair_calibration)


def _score_template(catalog: Catalog, band: season.Band, slots: tuple[str, ...], t_weight: float,
                    lists: list[list[_Cand]], style: str | None = None, cache: dict | None = None) -> list[dict]:
    model = catalog.compat
    masked = catalog.masked if _uses_masked(catalog) else None
    shape = tuple(len(lst) for lst in lists)
    n_pairs = max(1, len(slots) * (len(slots) - 1) // 2)

    def expand(mat: np.ndarray, i: int, j: int) -> np.ndarray:
        dims = [1] * len(slots)
        dims[i], dims[j] = mat.shape
        return mat.reshape(dims)

    logit = np.full(shape, model.bias)
    masked_q = np.zeros(shape)
    for i, j in itertools.combinations(range(len(slots)), 2):
        pmi = np.array([[model.pair_score(slots[i], a.item.vals, slots[j], b.item.vals)
                         for b in lists[j]] for a in lists[i]])
        logit = logit + expand(pmi, i, j) / n_pairs
        if masked is not None:
            points = masked.pair_calibration.get("|".join(sorted((slots[i], slots[j]), key=SLOTS.index)))
            pair = masked.pair_scores(slots[i], [c.item.attrs for c in lists[i]], slots[j],
                                      [c.item.attrs for c in lists[j]], style, cache)
            masked_q = masked_q + expand(percentile(points, pair) if points else np.full(pair.shape, 0.5), i, j) / n_pairs
    compat = _calibrate(catalog, logit)
    if masked is not None and len(slots) > 1:
        compat = (1 - MASKED_SHARE) * compat + MASKED_SHARE * masked_q

    def broadcast(values: list[float], axis: int) -> np.ndarray:
        dims = [1] * len(slots)
        dims[axis] = len(values)
        return np.asarray(values, dtype=float).reshape(dims)

    body = sum(broadcast([c.body for c in lst], k) for k, lst in enumerate(lists)) / len(slots)
    taste = sum(broadcast([c.taste for c in lst], k) for k, lst in enumerate(lists)) / len(slots)
    penalty = sum(broadcast([c.penalty for c in lst], k) for k, lst in enumerate(lists))
    warmth = sum(broadcast([c.item.warmth for c in lst], k) for k, lst in enumerate(lists))
    seas = np.exp(-((warmth - band.target) / 1.1) ** 2)
    prelim = (WEIGHTS["compat"] * compat + WEIGHTS["body"] * body + WEIGHTS["season"] * seas
              + WEIGHTS["taste"] * taste + WEIGHTS["color"] * 0.75 - penalty + 0.03 * t_weight)
    prelim = np.broadcast_to(prelim, shape)
    flat = prelim.ravel()
    top = np.argsort(-flat)[:COLOR_RERANK]

    def at(arr: np.ndarray, pos: tuple) -> float:
        return float(np.broadcast_to(arr, shape)[pos])

    out = []
    for idx in top:
        pos = np.unravel_index(idx, shape)
        out.append({
            "slots": slots, "cands": [lists[k][p] for k, p in enumerate(pos)], "prelim": float(flat[idx]),
            "compat": at(compat, pos), "logit": at(logit, pos), "body": at(body, pos),
            "taste": at(taste, pos), "season": at(seas, pos), "warmth": at(warmth, pos),
            "penalty": at(penalty, pos),
        })
    return out


def _calibrate(catalog: Catalog, logit: np.ndarray) -> np.ndarray:
    pts = catalog.calibration.get("real")
    if not pts:
        return 1 / (1 + np.exp(-logit))
    return percentile(pts, logit)


def _similarity(a: dict, b: dict) -> float:
    """0 = nothing in common; shared garments count fully, the same kind of garment (slot + category,
    or slot + colour) partly, and the same template a little."""
    def keys(o: dict, f) -> set:
        return {f(c.item) for c in o["cands"]}

    n = max(len(a["cands"]), len(b["cands"]))
    same_items = len(keys(a, lambda i: i.id) & keys(b, lambda i: i.id))
    same_kind = len(keys(a, lambda i: (i.slot, i.attrs.get("카테고리"), i.attrs.get("기장"))) & keys(b, lambda i: (i.slot, i.attrs.get("카테고리"), i.attrs.get("기장"))))
    same_color = len(keys(a, lambda i: (i.slot, i.attrs.get("색상"))) & keys(b, lambda i: (i.slot, i.attrs.get("색상"))))
    return (same_items + 0.35 * same_kind + 0.2 * same_color) / n + 0.15 * (a["slots"] == b["slots"])


def _mmr(outfits: list[dict], count: int) -> list[dict]:
    pool = sorted(outfits, key=lambda o: -o["total"])
    picked: list[dict] = []
    while pool and len(picked) < count:
        best = max(pool, key=lambda o: o["total"] - MMR_LAMBDA * max((_similarity(o, p) for p in picked), default=0.0))
        picked.append(best)
        pool.remove(best)
    return picked


def _present(catalog: Catalog, req: Request, band: season.Band, o: dict, has_feedback: bool) -> dict:
    items = [c.item for c in o["cands"]]
    components = {"compat": o["compat"], "body": o["body"], "color": o["color"], "season": o["season"], "taste": o["taste"]}
    total = sum(WEIGHTS[k] * v for k, v in components.items()) - o["penalty"]
    reasons = [r for r in (
        explain.compat_reason(catalog, items),
        explain.body_reason(req.body, req.pref_fit, float(np.mean([c.fit for c in o["cands"]])),
                            [r for c in sorted(o["cands"], key=lambda c: -c.shape) for r in c.shape_reasons]),
        {"kind": "color", "text": o["color_reason"] + ".", "sub": "배색 규칙 + K-Fashion 색 조합"},
        explain.season_reason(req.temp, band, o["warmth"], "아우터" in o["slots"]),
        explain.taste_reason(o["taste"], [chip for c in o["cands"] if c.style_hit for chip in req.styles
                                          if set(c.item.styles) & set(PROFILE_STYLES.get(chip, ()))], has_feedback),
    ) if r]
    out_items = []
    for c in o["cands"]:
        it = c.item
        entry = it.public()
        if it.source == "wardrobe":
            entry["size"] = f"내 옷 · 내 몸 기준 {it.fit_note}" if it.fit_note else "내 옷"
        else:
            entry["size"] = fit.size_advice(req.body, it.slot, it.attrs)
        out_items.append(entry)
    return {
        "key": o["key"],
        "items": out_items,
        "template": "+".join(o["slots"]),
        "total": round(100 * max(0.0, min(1.0, total))),
        "components": {k: round(100 * v) for k, v in components.items()},
        "reasons": reasons,
        "seen": o["seen"],
    }

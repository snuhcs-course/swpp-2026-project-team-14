# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-08, reviewed by Dongkun Moon
"""The user's taste, learned from likes, dislikes and chat feedback.

The preference document lives on the device and is sent with every request; the server is
stateless. Instead of one score per catalog garment it keeps weights per attribute value
("색상:블랙", "상의:카테고리:니트웨어", "스타일:모던", ...). A dislike lowers the weights of the
disliked garment's attributes, so every similar garment drops with it; an aspect ("색이 별로")
moves only that attribute.
"""

from __future__ import annotations

import math
import time

from recommendation.vocab import DARK_COLORS, BRIGHT_COLORS, as_list

ASPECTS = ("color", "fit", "style", "material", "category", "length", "print")
STEP_ASPECT = 0.6
STEP_ALL = 0.3
DECAY = 0.97
CLIP = 2.0


def empty() -> dict:
    return {"weights": {}, "excluded": [], "liked": [], "updated_at": None}


def features(slot: str, attrs: dict, style: str | None) -> dict[str, list[str]]:
    """Attribute features of a garment, grouped by aspect."""
    return {
        "color": [f"색상:{attrs['색상']}"] if attrs.get("색상") else [],
        "fit": [f"{slot}:핏:{attrs['핏']}"] if attrs.get("핏") else [],
        "style": [f"스타일:{style}"] if style else [],
        "material": [f"소재:{m}" for m in as_list(attrs.get("소재"))[:2]],
        "category": [f"{slot}:카테고리:{attrs['카테고리']}"] if attrs.get("카테고리") else [],
        "length": [f"{slot}:기장:{attrs['기장']}"] if attrs.get("기장") else [],
        "print": [f"프린트:{p}" for p in as_list(attrs.get("프린트"))[:1] if p != "무지"],
    }


def raw_score(pref: dict, slot: str, attrs: dict, style: str | None) -> float:
    w = pref.get("weights", {})
    return sum(w.get(f, 0.0) for group in features(slot, attrs, style).values() for f in group)


def score(pref: dict, slot: str, attrs: dict, style: str | None) -> float:
    """0..1, 0.5 when nothing is known."""
    return 1 / (1 + math.exp(-raw_score(pref, slot, attrs, style)))


def apply_feedback(pref: dict | None, events: list[dict], garments: dict[str, dict]) -> dict:
    """New preference after `events`.

    event: {"item_id": str | None, "polarity": +1 | -1, "aspect": one of ASPECTS | None,
            "value": "dark" | "bright" | None, "exclude": bool}
    `garments`: item_id -> {"slot", "attrs", "style"} for the garments the events refer to.
    """
    pref = {**empty(), **(pref or {})}
    weights = {k: v * DECAY for k, v in pref.get("weights", {}).items()}
    excluded, liked = list(pref.get("excluded", [])), list(pref.get("liked", []))
    for e in events:
        sign = 1.0 if e.get("polarity", 0) > 0 else -1.0
        aspect = e.get("aspect") if e.get("aspect") in ASPECTS else None
        targets: list[str] = []
        if e.get("value") in {"dark", "bright"} and aspect in (None, "color"):
            names = DARK_COLORS if e["value"] == "dark" else BRIGHT_COLORS
            targets = [f"색상:{c}" for c in names]
            step = STEP_ASPECT
        elif e.get("item_id") in garments:
            g = garments[e["item_id"]]
            feats = features(g["slot"], g["attrs"], g.get("style"))
            targets = feats[aspect] if aspect else [f for group in feats.values() for f in group]
            step = STEP_ASPECT if aspect else STEP_ALL
            if e.get("exclude") and e["item_id"] not in excluded:
                excluded.append(e["item_id"])
            if sign > 0 and e["item_id"] not in liked:
                liked.append(e["item_id"])
        for f in targets:
            weights[f] = max(-CLIP, min(CLIP, weights.get(f, 0.0) + sign * step))
    weights = {k: round(v, 3) for k, v in weights.items() if abs(v) >= 0.02}
    return {"weights": weights, "excluded": excluded[-500:], "liked": liked[-200:], "updated_at": int(time.time())}


def top_weights(pref: dict, n: int = 8) -> list[tuple[str, float]]:
    return sorted(pref.get("weights", {}).items(), key=lambda kv: -abs(kv[1]))[:n]

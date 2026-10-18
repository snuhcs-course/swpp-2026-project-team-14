# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-11, reviewed by Dongkun Moon
"""Table-driven body-shape rules (rules/body_shapes.json): which garments flatter which body.

Rules are data, not code, so the team can read, cite and edit them. A rule matches a garment when
its slot matches and every attribute condition holds; "@bright", "@dark" and "@pattern" are colour
and print groups. The result keeps the fired rules with their sources for the explanation.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from recommendation.body import Body
from recommendation.vocab import BRIGHT_COLORS, DARK_COLORS, as_list

RULES_PATH = Path(__file__).with_name("rules") / "body_shapes.json"
BONE_TYPES = ("스트레이트", "웨이브", "내추럴")


@dataclass(frozen=True)
class Fired:
    id: str
    weight: float
    reason: str
    source: str
    url: str


@lru_cache(maxsize=1)
def table() -> dict:
    return json.loads(RULES_PATH.read_text(encoding="utf-8"))


def _matches(rule: dict, slot: str, attrs: dict) -> bool:
    slots = rule["slot"]
    if slots != "*" and slot not in slots.split("|"):
        return False
    for key, want in rule["when"].items():
        have = set(as_list(attrs.get(key)))
        if want == "@bright":
            ok = bool(have & BRIGHT_COLORS)
        elif want == "@dark":
            ok = bool(have & DARK_COLORS)
        elif want == "@pattern":
            ok = bool(have - {"무지"})
        else:
            ok = bool(have & set(want))
        if not ok:
            return False
    return True


def rule_sets(body: Body, bone: str | None) -> list[list[dict]]:
    t = table()
    sets = []
    if body.shape is not None:
        sets.append(t["shapes"].get(body.shape.value, []))
    if bone in BONE_TYPES:
        sets.append(t["bone_types"][bone])
    if body.short_legs:
        sets.append(t["proportions"]["short_legs"])
    if body.broad_shoulders:
        sets.append(t["proportions"]["broad_shoulders"])
    return sets


def apply(body: Body, bone: str | None, slot: str, attrs: dict) -> tuple[float, list[Fired]]:
    """(score 0..1 with 0.5 neutral, fired rules)."""
    sources = table()["sources"]
    fired = []
    for rules in rule_sets(body, bone):
        for r in rules:
            if _matches(r, slot, attrs):
                src = sources.get(r["source"], {})
                fired.append(Fired(r["id"], r["weight"], r["reason"], src.get("title", r["source"]), src.get("url", "")))
    score = 0.5 + sum(f.weight for f in fired)
    return max(0.0, min(1.0, score)), fired

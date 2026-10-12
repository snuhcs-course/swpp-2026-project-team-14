# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-11, reviewed by Dongkun Moon
"""One turn of the AI stylist, done the way the Jev wardrobe demos do it.

state  = what the user just said + what they are wearing (+ body, weather, occasion)
questions (all slots in parallel) = "which garment for this slot?", options = my wardrobe +
          the best catalog garments + "keep what I'm wearing" (+ "take the outer off")
answer = a choice with a probability per option and a confidence; applied above a threshold.

Option scores (used by the local decider, and shown as the breakdown) combine:
  body   - cited body-shape rules (body_rules) and the preferred fit
  fit-in - teammate's masked transformer: how likely this garment is, given the other garments
           (+ PMI pair compatibility)
  colour - colour rules with the rest of the outfit
  season - outfit warmth vs. the weather band (live weather), rain rules
  taste  - learned likes/dislikes and preferred styles
  ask    - how well it moves towards what was asked ("밝게", "따뜻하게", "출근용")
  keep   - a strong bias to keep garments the user did not ask to change
"""

from __future__ import annotations

import random
import re
import time
from dataclasses import replace
from dataclasses import dataclass, field

import numpy as np

from recommendation import decision, explain, fit, intent as intent_mod, preference, season
from recommendation.body import Body
from recommendation.catalog import Catalog, Item
from recommendation.color import outfit_color
from recommendation.compat import values
from recommendation.masked import MaskedModel
from recommendation.vocab import BRIGHT_COLORS, DARK_COLORS, NEUTRAL_COLORS, PROFILE_STYLES, SLOTS, as_list
from recommendation.weather import Weather, rain_penalty

W = {"body": 0.20, "fitin": 0.22, "color": 0.14, "season": 0.12, "taste": 0.10, "ask": 0.22}
SHIFT_C = 5.0  # "따뜻하게" = dress as if it were 5 °C colder
KEEP_UNASKED = 0.45  # bias for "keep" on a slot the user did not ask to change
KEEP_ASKED = -0.08  # the user asked to change this slot
OWNED_BONUS = 0.03  # wardrobe-first: wear what you own when it is as good
NAMED_BONUS = 0.3  # "내 청바지로": the user named one of their own garments
RAIN_WORDS = re.compile(r"비\s?(가|와|오|온|올)|우산|장마|소나기")
SNOW_WORDS = re.compile(r"눈\s?(이|와|오|온|올)|폭설")
MINE_WORDS = ("내 ", "내옷", "제 ", "가진", "갖고 있는", "옷장")


def named_owned(text: str, item: Item) -> bool:
    """Did the user point at this garment of theirs ("하의는 내 청바지로")?"""
    if item.source != "wardrobe" or not any(w in text for w in MINE_WORDS):
        return False
    words = [w for w in re.split(r"\s+", item.name) if len(w) >= 2]
    category = item.attrs.get("카테고리", "")
    return any(w in text for w in words) or (len(category) >= 2 and category in text)
SHORTLIST = 10
EXPLORE = 2
KEEP, REMOVE = "keep", "remove"

FORMAL_CATS = {"셔츠", "블라우스", "재킷", "코트", "팬츠", "스커트"}
CASUAL_CATS = {"티셔츠", "후드티", "청바지", "조거팬츠", "짚업", "점퍼", "래깅스"}
FORMAL_STYLES = {"클래식", "모던", "소피스트케이티드", "매니시"}
CASUAL_STYLES = {"스트리트", "스포티", "컨트리", "힙합"}
LOOSE_FITS = {"루즈", "오버사이즈", "와이드"}
TIGHT_FITS = {"타이트", "스키니"}


@dataclass
class StylistRequest:
    body: Body
    weather: Weather
    text: str = ""
    pref_fit: str = "regular"
    bone: str | None = None
    styles: list[str] = field(default_factory=list)
    occasion: str | None = None
    current: dict[str, str] = field(default_factory=dict)  # slot -> item id
    wardrobe: list[Item] = field(default_factory=list)
    preference: dict = field(default_factory=preference.empty)
    seed: int = 0
    dress_temp: float | None = None  # set by turn(): the temperature to dress for after "따뜻하게"/"시원하게"


@dataclass
class Option:
    oid: str  # neutral id for the decider
    kind: str  # keep | remove | item
    item: Item | None
    parts: dict[str, float]
    notes: list[str]
    score: float = 0.0


def _want_score(want: str, item: Item, current: Item | None) -> float:
    """-1..1: how much choosing `item` moves towards `want`."""
    a = item.attrs
    color = a.get("색상")
    if want == "brighter":
        return 1.0 if color in BRIGHT_COLORS else (-1.0 if color in DARK_COLORS else 0.0)
    if want == "darker":
        return 1.0 if color in DARK_COLORS else (-1.0 if color in BRIGHT_COLORS else 0.0)
    if want in ("warmer", "cooler"):
        base = current.warmth if current else 0.0
        delta = (item.warmth - base) * (1 if want == "warmer" else -1)
        # a step warmer, not a jump to winter: reward up to ~1.2 layers, penalise much more
        return float(np.clip(delta / 1.2, -1, 1) - max(0.0, delta - 1.8))
    if want == "formal":
        return (0.6 if a.get("카테고리") in FORMAL_CATS else -0.6 if a.get("카테고리") in CASUAL_CATS else 0) + \
               (0.4 if item.style in FORMAL_STYLES else -0.3 if item.style in CASUAL_STYLES else 0)
    if want == "casual":
        return (0.6 if a.get("카테고리") in CASUAL_CATS else -0.4 if a.get("카테고리") in FORMAL_CATS else 0) + \
               (0.4 if item.style in CASUAL_STYLES else 0)
    if want == "looser":
        return 1.0 if a.get("핏") in LOOSE_FITS else (-1.0 if a.get("핏") in TIGHT_FITS else 0.0)
    if want == "fitted":
        return 1.0 if a.get("핏") in TIGHT_FITS | {"노멀"} else (-1.0 if a.get("핏") in LOOSE_FITS else 0.0)
    if want == "colorful":
        return 1.0 if color not in NEUTRAL_COLORS else -0.5
    if want == "simpler":
        plain = set(as_list(a.get("프린트"))) <= {"무지"} and not as_list(a.get("디테일"))
        return 1.0 if plain else -0.5
    return 0.0


class Stylist:
    def __init__(self, catalog: Catalog, masked: MaskedModel | None, local: decision.LocalDecider,
                 jev: decision.JevDecider | None) -> None:
        self.catalog, self.masked, self.local, self.jev = catalog, masked, local, jev

    # ------------------------------------------------------------------ main
    def turn(self, req: StylistRequest) -> dict:
        t0 = time.perf_counter()
        rng = random.Random(req.seed)
        it = intent_mod.parse(req.text) if req.text.strip() else intent_mod.Intent(type="whole")
        if req.occasion and not it.occasion:
            it.occasion = req.occasion
            it = intent_mod._complete(it)
        t_intent = (time.perf_counter() - t0) * 1000
        if RAIN_WORDS.search(req.text) and not req.weather.rain:
            req = replace(req, weather=replace(req.weather, rain=True, label=req.weather.label + " · 비 (요청)"))
        if SNOW_WORDS.search(req.text) and not req.weather.snow:
            req = replace(req, weather=replace(req.weather, snow=True, label=req.weather.label + " · 눈 (요청)"))
        wants_all = {w["want"] for w in it.wants}
        shift = SHIFT_C if "warmer" in wants_all else (-SHIFT_C if "cooler" in wants_all else 0.0)
        req = replace(req, dress_temp=req.weather.apparent - shift)
        band = season.band(req.dress_temp)  # the band the user wants to be dressed for
        if shift:
            notes_shift = [f"요청에 맞춰 체감 {req.weather.apparent - shift:.0f}° 기준으로 골랐어요."]
        else:
            notes_shift = []
        owned = {w.id: w for w in req.wardrobe if not fit.owned_fit(req.body, w.slot, w.dims).too_small}
        current = {s: self._item(i, owned) for s, i in req.current.items()}
        current = {s: i for s, i in current.items() if i is not None}
        slots, open_slots, notes = self._slots(req, it, band, current)
        notes = notes_shift + notes

        kf_styles = {s for chip in req.styles for s in PROFILE_STYLES.get(chip, ())}
        excluded = set(req.preference.get("excluded", []))
        args = (req, it, band, owned, kf_styles, excluded, rng)
        state = self._state(req, it, band, current)
        options_by_slot = {slot: self._options(*args[:3], slot, slot in open_slots, current, *args[3:]) for slot in slots}
        answers, t_decide, jev_error = decision.decide(state, {s: self._question(s, o, current) for s, o in options_by_slot.items()},
                                                       self.local, self.jev)
        if jev_error:
            notes.append(jev_error)
        new, decisions = {}, {}
        for slot in slots:
            decisions[slot] = self._apply(slot, slot in open_slots, answers[slot], options_by_slot[slot], current, new)

        # The slots were decided in parallel against the old outfit (as in the Jev demos). When several
        # changed, re-decide each of them once against the new outfit so the changes fit each other.
        changed = [s for s in slots if decisions[s]["status"] in ("applied", "confirm") and s in new
                   and (s not in current or new[s].id != current[s].id)]
        if len(changed) >= 2:
            for slot in sorted(changed, key=lambda s: decisions[s]["confidence"]):
                context = {k: v for k, v in new.items()}
                opts = self._options(*args[:3], slot, True, context, *args[3:], keep_bias=0.0)
                ans, t2, _ = decision.decide(state, {slot: self._question(slot, opts, context)}, self.local, self.jev)
                t_decide += t2
                first = decisions[slot]["status"]
                decisions[slot] = self._apply(slot, True, ans[slot], opts, context, new) | {"refined": True}
                if decisions[slot]["status"] == "kept":  # kept its first-pass pick: still a change from the old outfit
                    decisions[slot]["status"] = first
                    decisions[slot]["choice"] = self._label(Option("", "item", new[slot], {}, []))
        decisions = [decisions[s] for s in slots]
        outfit = self._present(req, band, new, current)
        return {
            "intent": it.public(), "weather": req.weather.public(), "band": {"label": band.label, "outer": band.outer},
            "decisions": decisions, "outfit": outfit, "notes": notes, "state": state,
            "latency_ms": {"intent": round(t_intent), "decide": round(t_decide), "total": round((time.perf_counter() - t0) * 1000)},
            "decider": self.jev.name if self.jev and not jev_error else "local",
        }

    # ------------------------------------------------------------------ pieces
    def _item(self, iid: str, owned: dict[str, Item]) -> Item | None:
        return owned.get(iid) or self.catalog.by_id.get(iid)

    def _slots(self, req, it, band, current) -> tuple[list[str], set[str], list[str]]:
        notes = []
        has_dress = "원피스" in current
        if it.type == "whole" or not current:
            male = req.body.male
            templates = [t for t, _ in band.templates if not (male and "원피스" in t)]
            slots = list(templates[0])
            return slots, set(slots), notes
        slots = [s for s in SLOTS if s in current]
        open_slots = set(it.slots) if it.type == "item" else set(slots)
        if it.type == "style":
            targeted = {w["slot"] for w in it.wants if w["slot"]}
            open_slots = targeted or set(slots)
        warmer = any(w["want"] == "warmer" for w in it.wants)
        if "아우터" not in current and (band.outer == "required" or (band.outer == "recommended" and warmer)):
            slots.insert(0, "아우터")
            open_slots.add("아우터")
            notes.append("따뜻하게 입도록 아우터를 더했어요." if warmer else f"체감 {req.weather.apparent:.0f}°라 아우터를 더했어요.")
        if "아우터" in it.slots and "아우터" not in slots:
            slots.insert(0, "아우터")
        if has_dress:
            slots = [s for s in slots if s not in {"상의", "하의"}]
        return slots, open_slots, notes

    def _question(self, slot: str, opts: list[Option], current: dict[str, Item]) -> decision.ChoiceQuestion:
        return decision.ChoiceQuestion(
            instructions=f"사용자의 요청과 상황에 가장 잘 맞는 {slot}을(를) 고르세요. 요청하지 않은 부분은 지금 옷을 유지하세요.",
            options={o.oid: self._definition(o, current.get(slot)) for o in opts},
            scores={o.oid: o.score for o in opts},
        )

    def _apply(self, slot: str, asked: bool, ans: decision.ChoiceAnswer, options: list[Option],
               current: dict[str, Item], new: dict[str, Item]) -> dict:
        """Apply one answer (above the threshold, or because the user asked) and describe it."""
        opts = {o.oid: o for o in options}
        chosen = opts[ans.choice]
        p = ans.probabilities.get(ans.choice, 0.0)
        if chosen.kind == KEEP:
            status = "kept"
        elif asked or p >= decision.SURE_AT:
            status = "applied" if (p >= decision.APPLY_AT or ans.confidence >= 0.5) else "confirm"
        else:
            status, chosen = "kept", next((o for o in options if o.kind == KEEP), chosen)
        new.pop(slot, None)
        if chosen.kind in ("item", KEEP) and chosen.item is not None:
            new[slot] = chosen.item
        ranked = sorted(options, key=lambda o: -ans.probabilities.get(o.oid, 0.0))[:4]
        return {
            "slot": slot, "asked": asked, "status": status, "confidence": ans.confidence,
            "choice": self._label(chosen), "decider": ans.decider,
            "options": [{"label": self._label(o), "kind": o.kind, "prob": ans.probabilities.get(o.oid, 0.0),
                         "item": o.item.public() if o.item else None,
                         "parts": {k: round(v, 3) for k, v in o.parts.items()}, "notes": o.notes} for o in ranked],
        }

    def _options(self, req, it, band, slot, asked, current, owned, kf_styles, excluded, rng,
                 keep_bias: float | None = None) -> list[Option]:
        context = {s: i for s, i in current.items() if s != slot}
        wants = it.wants_for(slot)
        style = next(iter(sorted(kf_styles)), None)  # the teammate model reads one 스타일 token (45 % vs 39 % FITB with it)
        logp = self.masked.slot_logprobs({s: i.attrs for s, i in context.items()}, style, slot) if self.masked else None
        cur = current.get(slot)

        def parts_for(item: Item) -> tuple[dict[str, float], list[str]]:
            notes = []
            fm = fit.fit_match(req.pref_fit, slot, item.attrs)
            shape = fit.shape_score(req.body, slot, item.attrs, req.bone)
            body = 0.55 * fm + 0.45 * shape.score
            notes += [f"{f.reason} ({f.source})" for f in shape.rules if f.weight > 0][:2]
            outfit = {s: i.attrs for s, i in context.items()} | {slot: item.attrs}
            col = outfit_color(outfit, self.catalog.compat).score
            warmth = sum(i.warmth for i in context.values()) + item.warmth
            seas = season.outfit_score(band, warmth)
            pen, why = rain_penalty(req.weather, slot, item.attrs)
            if why:
                notes.append(why)
            taste = 0.65 * preference.score(req.preference, slot, item.attrs, item.style) + \
                0.35 * ((1.0 if item.style in kf_styles else 0.3) if kf_styles else 0.5)
            # "따뜻하게/시원하게" is met when the whole outfit fits the shifted weather band (no per-item
            # warmth race, which overshoots when every slot gets warmer at once)
            signals = [_want_score(w, item, cur) for w in wants if w not in ("warmer", "cooler")]
            if any(w in ("warmer", "cooler") for w in wants):
                signals.append(2 * seas - 1)
            ask = 0.5 + 0.5 * float(np.mean(signals)) if signals else 0.5
            named = named_owned(req.text, item)
            if named:
                ask = 1.0
                notes.insert(0, "말씀하신 내 옷이에요")
            pmi = np.mean([self.catalog.compat.pair_score(slot, item.vals, s, values(i.attrs)) for s, i in context.items()]) if context else 0.0
            fitin_raw = (self.masked.score(logp, item.attrs) if logp is not None else 0.0) + 0.5 * float(pmi)
            return {"body": body, "fitin_raw": fitin_raw, "color": col, "season": seas + pen, "taste": taste, "ask": ask,
                    "named": 1.0 if named else 0.0}, notes

        cands = []
        for item in self.catalog.by_slot.get(slot, []):
            if item.id in excluded or (req.body.male and not item.unisex) or (cur is not None and item.name == cur.name):
                continue
            if season.allowed(band, req.dress_temp, slot, item.attrs, item.warmth):
                continue
            if fit.fit_match(req.pref_fit, slot, item.attrs) <= 0:
                continue
            item_wants = [w for w in wants if w not in ("warmer", "cooler")]
            quick = 0.5 * fit.fit_match(req.pref_fit, slot, item.attrs) + \
                0.5 * (0.5 + 0.5 * float(np.mean([_want_score(w, item, cur) for w in item_wants])) if item_wants else 0.5)
            quick += 0.3 * (preference.score(req.preference, slot, item.attrs, item.style) - 0.5)
            cands.append((quick, item))
        cands.sort(key=lambda x: -x[0])
        pool = [i for _, i in cands[:60]]
        rest = [i for _, i in cands[60:]]
        pool += rng.sample(rest, min(EXPLORE * 5, len(rest)))
        scored = []
        for item in pool:
            parts, notes = parts_for(item)
            scored.append(Option("", "item", item, parts, notes))
        unique, seen_kind = [], set()
        for o in self._finish(scored):  # near-identical garments (same name, other photos) once
            kind = o.item.name
            if kind not in seen_kind:
                seen_kind.add(kind)
                unique.append(o)
        scored = unique[:SHORTLIST]
        owned_opts = []
        for w in owned.values():
            if w.slot == slot and (cur is None or w.id != cur.id):
                parts, notes = parts_for(w)
                owned_opts.append(Option("", "item", w, parts, notes))
        options = scored + self._finish(owned_opts)
        if cur is not None:
            parts, notes = parts_for(cur)
            options.append(Option("", KEEP, cur, parts, notes))
        if slot == "아우터" and band.outer in ("none", "optional") and cur is not None:
            options.append(Option("", REMOVE, None, {"body": 0.5, "fitin_raw": 0.0, "color": 0.75,
                                                       "season": season.outfit_score(band, sum(i.warmth for i in context.values())),
                                                       "taste": 0.5, "ask": 0.5}, ["아우터 없이 입기"]))
        bias = keep_bias if keep_bias is not None else (KEEP_ASKED if asked else KEEP_UNASKED)
        options = self._finish(options, keep_bias=bias)
        for k, o in enumerate(options):
            o.oid = f"A{k + 1}"
        return options

    def _finish(self, options: list[Option], keep_bias: float = 0.0) -> list[Option]:
        """Scale fit-in within the question, compute the total, sort."""
        if not options:
            return options
        raw = np.array([o.parts["fitin_raw"] for o in options])
        lo, hi = float(raw.min()), float(raw.max())
        for o in options:
            o.parts["fitin"] = 0.5 if hi - lo < 1e-9 else (o.parts["fitin_raw"] - lo) / (hi - lo)
            o.score = sum(W[k] * o.parts[k] for k in W)
            if o.kind == KEEP:
                o.score += keep_bias
            if o.item is not None and o.item.source == "wardrobe":
                o.score += OWNED_BONUS
            o.score += NAMED_BONUS * o.parts.get("named", 0.0)
        return sorted(options, key=lambda o: -o.score)

    def _label(self, o: Option) -> str:
        if o.kind == REMOVE:
            return "아우터 벗기"
        name = o.item.name if o.item else ""
        if o.kind == KEEP:
            return f"그대로 ({name})"
        return f"{name} · 내 옷" if o.item.source == "wardrobe" else name

    def _definition(self, o: Option, cur: Item | None) -> str:
        if o.kind == KEEP:
            return f"지금 입은 {o.item.name}을(를) 그대로 입는다"
        if o.kind == REMOVE:
            return "아우터를 벗고 안의 옷만 입는다"
        a = o.item.attrs
        bits = [o.item.name, a.get("핏"), ",".join(as_list(a.get("소재"))[:1]), a.get("기장"), o.item.style]
        owned = " (사용자가 가진 옷)" if o.item.source == "wardrobe" else ""
        return " · ".join(b for b in bits if b) + owned

    def _state(self, req, it, band, current) -> str:
        wearing = ", ".join(f"{s}: {i.name}" for s, i in current.items()) or "없음"
        shape = req.body.shape.value if req.body.shape else "판정 보류"
        return (f"요청: {req.text or '오늘 코디 추천'}\n지금 입은 옷: {wearing}\n"
                f"체형: {shape}{', 골격 ' + req.bone if req.bone else ''}, 선호 핏 {req.pref_fit}\n"
                f"날씨: 체감 {req.weather.apparent:.0f}° {req.weather.label} ({band.label})\n상황: {it.occasion or '일상'}")

    def _present(self, req: StylistRequest, band, new: dict[str, Item], before: dict[str, Item]) -> dict:
        items = [new[s] for s in SLOTS if s in new]
        outfit = {i.slot: i.attrs for i in items}
        logit = self.catalog.compat.bias + float(self.catalog.compat.weights @ self.catalog.compat.outfit_features(outfit))
        compat = self.catalog.compat_display(logit)
        col = outfit_color(outfit, self.catalog.compat)
        warmth = sum(i.warmth for i in items)
        rules = {}
        for i in items:
            for f in fit.shape_score(req.body, i.slot, i.attrs, req.bone).rules:
                rules[f.id] = {"slot": i.slot, "reason": f.reason, "weight": f.weight, "source": f.source, "url": f.url}
        body = float(np.mean([0.55 * fit.fit_match(req.pref_fit, i.slot, i.attrs)
                              + 0.45 * fit.shape_score(req.body, i.slot, i.attrs, req.bone).score for i in items])) if items else 0.0
        reasons = [r for r in (explain.compat_reason(self.catalog, items),
                               {"kind": "color", "text": col.reason + "."},
                               explain.season_reason(req.weather.apparent, band, warmth, "아우터" in new)) if r]
        predicted = {}
        if self.masked:
            for s in new:
                style = next(iter(sorted({x for c in req.styles for x in PROFILE_STYLES.get(c, ())})), None)
                lp = self.masked.slot_logprobs({k: v.attrs for k, v in new.items() if k != s}, style, s)
                predicted[s] = self.masked.predicted(lp, top=2)
        out_items = []
        for i in items:
            e = i.public()
            e["changed"] = before.get(i.slot) is None or before[i.slot].id != i.id
            e["size"] = fit.size_advice(req.body, i.slot, i.attrs) if i.source != "wardrobe" else "내 옷"
            out_items.append(e)
        return {
            "items": out_items, "template": "+".join(i.slot for i in items),
            "components": {"compat": round(100 * compat), "body": round(100 * body), "color": round(100 * col.score),
                           "season": round(100 * season.outfit_score(band, warmth))},
            "reasons": reasons, "rules": list(rules.values()), "predicted": predicted,
        }

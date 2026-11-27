"""Why each garment changed in a chat edit: one line per changed slot, tied to what was asked.

The reply already says which garments changed ("상의를 바꿨어요"); these lines say why, with the
attribute that moved (colour, fit, category or warmth), so a result can be judged without guessing.
Pure functions over recommender Items; the chat service passes what it parsed from the request.
"""
from __future__ import annotations

from recommendation.catalog import Item
from recommendation.season import item_warmth

SLOT_CODE = {"상의": "top", "하의": "bottom", "아우터": "outerwear"}
SLOT_WORD = {"top": "상의", "bottom": "하의", "outerwear": "아우터"}

# want → (what the user asked for, which attribute shows it)
WANTS = {
    "brighter": ("더 밝은 색", "색상"), "darker": ("더 어두운 색", "색상"), "colorful": ("더 화사한 색", "색상"),
    "warmer": ("더 따뜻한 옷", "warmth"), "cooler": ("더 가벼운 옷", "warmth"),
    "formal": ("더 단정한 옷", "카테고리"), "casual": ("더 편안한 옷", "카테고리"),
    "looser": ("더 여유 있는 핏", "핏"), "fitted": ("더 몸에 맞는 핏", "핏"),
    "simpler": ("더 단순한 디자인", "카테고리"),
}


def _josa(word: str, with_final: str, without: str) -> str:
    code = ord(word[-1]) - 0xAC00 if word else -1
    return with_final if 0 <= code < 11172 and code % 28 else without


def _evidence(before: Item | None, after: Item, attr: str) -> str:
    if before is None:
        return ""
    if attr == "warmth":
        attr = "카테고리"  # what got warmer is easier to read as the kind of garment than as a number
    old, new = before.attrs.get(attr), after.attrs.get(attr)
    return f" ({old} → {new})" if old and new and old != new else ""


def _moved(before: Item | None, after: Item, want: str) -> bool:
    """Whether this garment itself moved the way asked. Warmth is asked for the whole outfit, so one garment
    can get lighter while a thicker outer makes the outfit warmer; such a garment is not called warmer."""
    if before is None or want not in ("warmer", "cooler"):
        return True
    a, b = item_warmth(before.slot, before.attrs), item_warmth(after.slot, after.attrs)
    return b > a if want == "warmer" else b < a


def reasons(before: dict[str, Item], after: list[Item], wants: list[dict], *, target_colors: list[str] = (),
            avoid_colors: list[str] = (), targets: dict[str, str] | None = None, requested: set[str] = frozenset(),
            removed_outerwear: bool = False, plan_label: str | None = None) -> list[dict]:
    """[{slot, from, to, reason}] for every garment that changed, in outfit order.

    `before` and slots use the app's codes (top, bottom, outerwear); `wants` are the parsed directions
    ({slot or None, want}); `targets` maps a slot to a garment the request named; `requested` are the
    slots the request asked to change; `plan_label` is the day and place a forecast was used for."""
    targets = targets or {}
    before_ids = {i.id for i in before.values()}
    warmth_before = sum(item_warmth(i.slot, i.attrs) for i in before.values())
    warmth_after = sum(item_warmth(i.slot, i.attrs) for i in after)
    out = []
    changed_slots = {SLOT_CODE[i.slot] for i in after if i.id not in before_ids}
    for item in after:
        if item.id in before_ids:
            continue
        slot = SLOT_CODE[item.slot]
        old = before.get(slot)
        color = item.attrs.get("색상")
        slot_wants = [w["want"] for w in wants if w.get("slot") in (None, slot) and w["want"] in WANTS]
        if slot in targets and targets[slot] == item.id[2:]:
            reason = "말씀하신 옷으로 바꿨어요."
        elif old is None:
            reason = f"요청하신 대로 {SLOT_WORD[slot]}{_josa(SLOT_WORD[slot], '을', '를')} 더했어요."
        elif color and color in target_colors:
            reason = f"요청하신 {color} 색으로 골랐어요."
        elif old.attrs.get("색상") in avoid_colors and color:
            reason = f"피하고 싶다고 하신 {old.attrs.get('색상')} 대신 {color}{_josa(color, '으로', '로')} 바꿨어요."
        elif slot_wants and _moved(old, item, slot_wants[0]):
            words, attr = WANTS[slot_wants[0]]
            reason = f"요청하신 {words}{_josa(words, '으로', '로')} 바꿨어요{_evidence(old, item, attr)}."
        elif slot_wants and slot_wants[0] in ("warmer", "cooler"):
            warmer = slot_wants[0] == "warmer"
            word = "따뜻해" if warmer else "가벼워"
            if (warmth_after > warmth_before) == warmer and warmth_after != warmth_before:
                reason = f"코디 전체가 더 {word}지도록 함께 바꿨어요."
            else:  # the warmest (or lightest) outfit was already shown: say so rather than claim it
                reason = f"옷장에서 더 {word}지는 조합을 찾지 못해 비슷한 두께의 다른 옷으로 바꿨어요."
        elif plan_label:
            reason = f"{plan_label} 날씨에 맞춰 바꿨어요{_evidence(old, item, 'warmth')}."
        elif slot not in requested and changed_slots - {slot}:
            others = " · ".join(SLOT_WORD[s] for s in sorted(changed_slots - {slot}, key=list(SLOT_WORD).index))
            reason = f"바뀐 {others}{_josa(others, '과', '와')} 어울리도록 함께 바꿨어요."
        else:
            reason = "지금 옷장에서 요청에 가장 잘 맞는 옷으로 바꿨어요."
        out.append({"slot": slot, "from": old.name if old else None, "to": item.name, "reason": reason})
    if removed_outerwear and "outerwear" in before and not any(SLOT_CODE[i.slot] == "outerwear" for i in after):
        out.append({"slot": "outerwear", "from": before["outerwear"].name, "to": None,
                    "reason": "요청하신 대로 아우터를 뺐어요."})
    order = list(SLOT_WORD)
    return sorted(out, key=lambda r: order.index(r["slot"]))

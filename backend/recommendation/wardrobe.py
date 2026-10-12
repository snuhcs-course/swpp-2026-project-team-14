# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-08, reviewed by Dongkun Moon
"""The user's own garments (wardrobe app schema) in K-Fashion terms."""

from __future__ import annotations

from recommendation import fit
from recommendation.body import Body
from recommendation.catalog import Item
from recommendation.labeling import nearest_color
from recommendation.vocab import (
    COLORS,
    WARDROBE_CATEGORY,
    WARDROBE_COLOR,
    WARDROBE_DEFAULT_CATEGORY,
    WARDROBE_LEG_SHAPE,
    WARDROBE_STYLES,
    WARDROBE_SUBCATEGORY,
    WARDROBE_UPPER_FIT,
)


def to_item(g: dict) -> Item | None:
    """A wardrobe garment ({id, attributes{category, subcategory, colors, fit_type, leg_shape, name, styles},
    dimensions, labels, image}) as an Item. Body-based labels (labeling.py) win over the plain attributes.
    Shoes are not used yet (K-Fashion has none)."""
    a = g.get("attributes") or {}
    slot = WARDROBE_CATEGORY.get(a.get("category"))
    if not slot:
        return None
    labels = g.get("labels") or {}
    attrs = _labeled_attrs(slot, labels, _plain_attrs(slot, a)) if labels else _plain_attrs(slot, a)
    styles = tuple(dict.fromkeys(s for code in a.get("styles") or [] for s in WARDROBE_STYLES.get(code, ())))
    item = Item(id=f"w:{g.get('id')}", slot=slot, attrs={k: v for k, v in attrs.items() if v}, source="wardrobe",
                dims=g.get("dimensions"), name=a.get("name") or "", image=g.get("image"),
                style=styles[0] if styles else None, styles=styles, labeled=bool(labels))
    if labels:
        item.too_small = labels.get("too_small_reason") if labels.get("too_small") else None
        if labels.get("fit"):
            item.fit_note = labels["fit"] + (f" ({labels['fit_basis']})" if labels.get("fit_basis") else "")
    return item


def _labeled_attrs(slot: str, labels: dict, plain: dict) -> dict:
    details = ["드롭숄더"] if labels.get("drop_shoulder") else []
    return {
        "카테고리": labels.get("kf_category") or plain["카테고리"],
        "색상": (labels.get("kf_colors") or [plain["색상"]])[0],
        "핏": labels.get("fit") or "노멀",
        "기장": labels.get("length"),
        "소매기장": labels.get("sleeve") if slot != "하의" else None,
        "소재": labels.get("materials") or [],
        "프린트": labels.get("prints") or [],
        "넥라인": labels.get("neckline"),
        "옷깃": labels.get("collar"),
        "디테일": details,
    }


def _plain_attrs(slot: str, a: dict) -> dict:
    """Garments saved before labels existed: a rough mapping of the wardrobe attributes."""
    sub = a.get("subcategory") or ""
    colors = a.get("colors") or []
    color = colors[0] if colors else None
    if color and color not in COLORS:
        color = WARDROBE_COLOR.get(str(color).upper()) or nearest_color(str(color))
    if slot == "하의":
        fit_label = "노멀" if sub == "skirt" else WARDROBE_LEG_SHAPE.get(a.get("leg_shape") or "", "노멀")
    else:
        fit_label = WARDROBE_UPPER_FIT.get(a.get("fit_type") or "", "노멀")
    material = {"fleece": "플리스", "puffer": "패딩", "knitwear": "니트", "jeans": "데님"}.get(sub)
    return {
        "카테고리": WARDROBE_SUBCATEGORY.get(sub, WARDROBE_DEFAULT_CATEGORY[slot]),
        "색상": color,
        "핏": fit_label,
        "기장": "미니" if sub == "shorts" else "발목" if slot == "하의" else None,
        "소재": [material] if material else [],
        "소매기장": "긴팔" if slot != "하의" else None,
    }


def check_fit(item: Item, body: Body) -> None:
    """Fill too_small / fit_note of an unlabeled garment from its dimensions on this body."""
    if item.labeled:
        return
    of = fit.owned_fit(body, item.slot, item.dims)
    item.too_small = of.detail if of.too_small else None
    item.fit_note = f"{of.label} ({of.detail})" if of.label else None


def similar(a: Item, b: Item) -> bool:
    """Same kind of garment: owning `b` makes recommending `a` pointless."""
    return a.slot == b.slot and a.attrs.get("카테고리") == b.attrs.get("카테고리") and a.attrs.get("색상") == b.attrs.get("색상")

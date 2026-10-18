# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-08, reviewed by Dongkun Moon
"""Garments the recommender can use: the K-Fashion catalog and the user's own wardrobe."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

from recommendation import season
from recommendation.compat import CompatModel, values
from recommendation.masked import MaskedModel
from recommendation.vocab import SLOTS, is_unisex

# Model files trained on K-Fashion (AI Hub): aggregated statistics and weights only, no images or labels.
MODEL_DIR = Path(__file__).resolve().parent / "models"

CATEGORY_WORD = {"니트웨어": "니트", "짚업": "집업", "래깅스": "레깅스", "점프수트": "점프슈트"}
FIT_WORD = {"루즈": "루즈", "오버사이즈": "오버핏", "타이트": "슬림", "스키니": "스키니", "와이드": "와이드", "벨보텀": "부츠컷"}


@dataclass
class Item:
    id: str
    slot: str
    attrs: dict
    style: str | None = None
    source: str = "catalog"  # catalog | wardrobe
    image: str | None = None
    dims: dict | None = None
    name: str = ""
    styles: tuple = ()  # K-Fashion styles; a catalog photo has one, a wardrobe garment may have several
    labeled: bool = False  # wardrobe garment with body-based labels (recommendation/labeling.py)
    too_small: str | None = None  # why the garment does not fit this body
    fit_note: str | None = None  # "루즈 (가슴 여유 16 cm)": fit of an owned garment on this body
    unisex: bool = True
    vals: dict = field(default_factory=dict)
    warmth: float = 1.0

    def __post_init__(self) -> None:
        if not self.styles and self.style:
            self.styles = (self.style,)
        self.vals = values(self.attrs)
        self.warmth = season.item_warmth(self.slot, self.attrs)
        self.unisex = is_unisex(self.slot, self.attrs)
        if not self.name:
            self.name = display_name(self.slot, self.attrs)

    def public(self) -> dict:
        return {"id": self.id, "slot": self.slot, "name": self.name, "image": self.image, "source": self.source,
                "style": self.style, "attrs": self.attrs}


def display_name(slot: str, attrs: dict) -> str:
    cat = attrs.get("카테고리") or slot
    if slot == "하의" and cat == "팬츠" and attrs.get("기장") == "미니":
        cat = "반바지"
    words = [attrs.get("색상") or ""]
    fit = attrs.get("핏")
    if fit in FIT_WORD:
        words.append(FIT_WORD[fit])
    if slot == "아우터" and attrs.get("기장") in {"롱", "하프", "크롭"}:
        words.append({"롱": "롱", "하프": "하프", "크롭": "크롭"}[attrs["기장"]])
    words.append(CATEGORY_WORD.get(cat, cat))
    return " ".join(w for w in words if w)


class Catalog:
    """Garments to pick from plus the compatibility models. With no items it only scores outfits
    (the wardrobe recommendation passes the user's garments with the request)."""

    def __init__(self, items: list[Item], compat: CompatModel, stats: dict, masked: MaskedModel | None = None) -> None:
        self.items = items
        self.by_id = {i.id: i for i in items}
        self.by_slot = {s: [i for i in items if i.slot == s] for s in SLOTS}
        self.compat = compat
        self.masked = masked
        self.stats = stats
        self.calibration = stats.get("calibration", {})
        self.templates = stats.get("templates", {})
        self.meta = stats.get("meta", {})

    @classmethod
    def load_models(cls, model_dir: Path = MODEL_DIR) -> "Catalog":
        stats = json.loads((model_dir / "stats.json").read_text(encoding="utf-8"))
        return cls([], CompatModel.from_json(stats), stats, MaskedModel.load(model_dir))

    @classmethod
    def load(cls, directory: Path, model_dir: Path = MODEL_DIR) -> "Catalog":
        """The K-Fashion catalog (test page): garments from `directory`/catalog.json."""
        models = cls.load_models(model_dir)
        data = json.loads((directory / "catalog.json").read_text(encoding="utf-8"))
        items = [Item(id=x["id"], slot=x["slot"], attrs=x["attrs"], style=x.get("style"),
                      image=f"/cutouts/{x['id']}.webp") for x in data["items"]]
        return cls(items, models.compat, models.stats, models.masked)

    def compat_display(self, logit: float) -> float:
        """Logit -> share of real K-Fashion outfits this outfit beats (0..1)."""
        pts = self.calibration.get("real")
        if not pts:
            return float(1 / (1 + 2.718281828 ** -logit))
        qs = (0.05, 0.25, 0.5, 0.75, 0.95)
        if logit <= pts[0]:
            return max(0.0, 0.05 - 0.05 * (pts[0] - logit))
        if logit >= pts[-1]:
            return min(1.0, 0.95 + 0.05 * (logit - pts[-1]))
        for (x0, q0), (x1, q1) in zip(zip(pts, qs), zip(pts[1:], qs[1:])):
            if x0 <= logit <= x1:
                return q0 + (q1 - q0) * (logit - x0) / max(1e-9, x1 - x0)
        return 0.5

"""Builds the fixed cases of the recommendation evaluation (RUBRIC.md). Run once; the output is committed.

    python eval/recommendation/make_cases.py            # writes cases.json (needs kfashion_dataset/ for part B)

Part A: 40 synthetic wardrobes in the app's garment format (attributes + measured dimensions), so the
runner labels them with `recommendation.labeling.label` exactly as registration does. No K-Fashion data.
Part B: pairs of a real K-Fashion validation outfit and the same outfit with one rule broken. Only the
record's line number in `val_preprocessed.jsonl.gz` is stored, never its labels or image.
"""
from __future__ import annotations

import gzip
import json
import random
from pathlib import Path

HERE = Path(__file__).resolve().parent
VAL = HERE.parents[2] / "kfashion_dataset" / "preprocessed" / "val_preprocessed.jsonl.gz"
SEED = 2026

BODIES = [
    {"gender": "female", "height": 160, "chest": 86, "waist": 66, "hip": 92, "shoulder": 37, "inseam": 72,
     "torso_length": 42, "sleeve_length": 55, "outseam": 98},
    {"gender": "female", "height": 168, "chest": 90, "waist": 72, "hip": 98, "shoulder": 39, "inseam": 78,
     "torso_length": 45, "sleeve_length": 58, "outseam": 104},
    {"gender": "male", "height": 174, "chest": 96, "waist": 80, "hip": 96, "shoulder": 44, "inseam": 79,
     "torso_length": 48, "sleeve_length": 61, "outseam": 104},
    {"gender": "male", "height": 182, "chest": 102, "waist": 86, "hip": 100, "shoulder": 47, "inseam": 84,
     "torso_length": 50, "sleeve_length": 64, "outseam": 110},
]
TEMPS = [-3, 2, 6, 10, 14, 18, 21, 25, 30]
FITS = ["regular", "loose", "slim", "regular"]
STYLES = [[], ["미니멀"], ["캐주얼", "스트릿"], ["클래식"]]
COLORS = ["#202020", "#FFFFFF", "#1F2A44", "#D6BE9A", "#8C8C8C", "#3975C6", "#795548", "#6B7B3A", "#B03A2E"]
STYLE_CODES = ["minimal", "casual", "street", "classic", "sporty", "formal"]

# (subcategory, name, sleeve as a share of the arm, materials from the photo analysis)
TOPS = [("tshirt", "반팔 티셔츠", 0.36, []), ("tshirt", "긴팔 티셔츠", 1.0, []), ("shirt", "셔츠", 1.0, []),
        ("blouse", "민소매 블라우스", 0.03, []), ("blouse", "린넨 블라우스", 0.36, ["린넨"]),
        ("knitwear", "니트", 1.0, []), ("hoodie", "후드티", 1.0, []), ("sweatshirt", "맨투맨", 1.0, [])]
# (subcategory, name, inseam as a share of the leg or None for a skirt, skirt length as a share of the outseam)
BOTTOMS = [("jeans", "청바지", 1.0, None), ("slacks", "슬랙스", 1.0, None), ("pants", "면바지", 0.95, None),
           ("shorts", "반바지", 0.18, None), ("skirt", "미니스커트", None, 0.38), ("skirt", "롱스커트", None, 0.75),
           ("active_pants", "트레이닝바지", 1.0, None)]
# (subcategory, name, total length as a share of the height, materials)
OUTERS = [("cardigan", "카디건", 0.36, []), ("jacket", "재킷", 0.38, []), ("coat", "코트", 0.56, ["울/캐시미어"]),
          ("puffer", "패딩", 0.45, []), ("fleece", "플리스", 0.36, []), ("hooded_jacket", "후드집업", 0.38, []),
          ("vest", "조끼", 0.36, [])]


def dims(**values: float) -> dict:
    """The wardrobe's dimension records, as AR measurement saves them."""
    return {"unit": "cm", **{k: {"value": round(v, 1), "source": "arcore_manual", "method": "unspecified",
                                 "reference": None} for k, v in values.items()}}


def attributes(name: str, category: str, sub: str, color: str, style: str) -> dict:
    return {"name": name, "category": category, "subcategory": sub, "colors": [color], "styles": [style],
            "fit_type": None, "leg_shape": None, "rise_type": None, "skirt_shape": None}


def garment(rng: random.Random, gid: str, kind: str, spec: tuple, body: dict, small: bool) -> dict:
    sub, name, a, b = spec
    color, style = rng.choice(COLORS), rng.choice(STYLE_CODES)
    ease = -6.0 if small else rng.uniform(6, 18)  # a too-small garment is 6 cm under the body
    if kind == "top":
        d = dims(chest_width_half=(body["chest"] + ease) / 2, total_length=body["torso_length"] * 1.4,
                 sleeve_length=body["sleeve_length"] * a)
        details = {"materials": b}
    elif kind == "outerwear":
        d = dims(chest_width_half=(body["chest"] + ease + 6) / 2, total_length=body["height"] * a,
                 sleeve_length=body["sleeve_length"] * 1.02)
        details = {"materials": b}
    else:
        hip = (body["hip"] + (ease if small else rng.uniform(4, 12))) / 2
        waist = (body["waist"] + (-6 if small else rng.uniform(0, 3))) / 2
        if a is not None:
            d = dims(hip_width_half=hip, waist_width_half=waist, thigh_width_half=30, hem_opening=20,
                     inseam=body["inseam"] * a)
        else:
            d = dims(hip_width_half=hip, waist_width_half=waist, total_length=body["outseam"] * b)
        details = {}
    label = ("작은 " if small else "") + name
    return {"id": gid, "attributes": attributes(label, kind, sub, color, style), "dimensions": d, "details": details}


def wardrobes() -> list[dict]:
    rng = random.Random(SEED)
    cases = []
    for i in range(40):
        body = BODIES[i % 4]
        out = []
        for kind, pool, low, high in (("top", TOPS, 5, 8), ("bottom", BOTTOMS, 3, 6), ("outerwear", OUTERS, 0, 5)):
            for j in range(rng.randint(low, high)):
                out.append(garment(rng, f"a{i:02d}-{kind}-{j}", kind, rng.choice(pool), body, False))
        out.append(garment(rng, f"a{i:02d}-top-small", "top", rng.choice(TOPS), body, True))
        out.append(garment(rng, f"a{i:02d}-bottom-small", "bottom", rng.choice(BOTTOMS), body, True))
        tops = [g["id"] for g in out if g["attributes"]["category"] == "top" and "small" not in g["id"]]
        cases.append({"id": f"A{i + 1:02d}", "body": body, "temp": TEMPS[i % len(TEMPS)], "pref_fit": FITS[i % 4],
                      "styles": STYLES[(i // 4) % 4], "lock": rng.choice(tops), "seed": i, "garments": out})
    return cases


def _records():
    with gzip.open(VAL, "rt", encoding="utf-8") as f:
        for index, line in enumerate(f):
            yield index, json.loads(line)


def pairs() -> list[dict]:
    """25 pairs per break type, from records chosen with a fixed seed."""
    cold, summer, formal, sporty_bottoms = [], [], [], []
    for index, r in _records():
        g = r["garments"]
        seasons = {x.get("계절") for x in g.values()} - {None}
        if set(g) == {"아우터", "상의", "하의"}:
            o = g["아우터"]
            if (o.get("계절") == "겨울" or o.get("카테고리") in ("코트", "패딩")) and "여름" not in seasons \
                    and g["상의"].get("소매기장") == "긴팔" and g["하의"].get("기장") != "미니" \
                    and g["하의"].get("카테고리") != "스커트":
                cold.append(index)
        if set(g) == {"상의", "하의"} and seasons == {"여름"}:
            summer.append(index)
        if set(g) <= {"아우터", "상의", "하의"} and {"상의", "하의"} <= set(g) \
                and r["style"] in ("클래식", "소피스트케이티드") and g["하의"].get("카테고리") in ("팬츠", "스커트") \
                and "겨울" not in seasons:
            formal.append(index)
        if r["style"] == "스포티" and "하의" in g and g["하의"].get("카테고리") in ("조거팬츠", "래깅스"):
            sporty_bottoms.append(index)
    rng = random.Random(SEED)
    out = []
    cold_picks = rng.sample(cold, 50)
    for n, index in enumerate(cold_picks[:25]):
        out.append({"id": f"B-shorts-{n + 1:02d}", "type": "winter_shorts", "record": index, "temp": 2})
    for n, index in enumerate(cold_picks[25:]):
        out.append({"id": f"B-sleeveless-{n + 1:02d}", "type": "winter_sleeveless", "record": index, "temp": 2})
    for n, index in enumerate(rng.sample(summer, 25)):
        out.append({"id": f"B-padding-{n + 1:02d}", "type": "summer_padding", "record": index, "temp": 29})
    for n, (index, other) in enumerate(zip(rng.sample(formal, 25), rng.sample(sporty_bottoms, 25))):
        out.append({"id": f"B-style-{n + 1:02d}", "type": "style_clash", "record": index, "bottom_from": other,
                    "temp": None})  # temp from the record's season (RUBRIC.md)
    return out


if __name__ == "__main__":
    data = {"seed": SEED, "rules": wardrobes(), "bad_outfits": pairs()}
    (HERE / "cases.json").write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    print(len(data["rules"]), "wardrobes,", len(data["bad_outfits"]), "pairs")

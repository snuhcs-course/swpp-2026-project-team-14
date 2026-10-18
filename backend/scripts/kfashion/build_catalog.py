# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-08, reviewed by Dongkun Moon
"""Pick a clean, de-duplicated garment catalog from K-Fashion and cut the garments out.

    python scripts/kfashion/build_catalog.py [--size 20000] [--workers 6]

Selection (labels only):
- category, colour and fit labelled; polygon with >= 15 points; garment box big enough
- not covered by another garment in the same photo (top under an outer or one-piece, bottom under
  a one-piece, one-piece under an outer)
- one garment per (product stem, slot, category, colour, fit): shots of the same product collapse
- per slot quota; inside a slot, styles are sampled ~ sqrt(frequency) so street (46 %) does not
  crowd out the rest, and 40 % of the top/bottom/outer quota is kept for unisex pieces (men only
  see those)

Cut-out: the label polygon is coarse (hair, skin and bags leak in), so the mask is
polygon AND the "clothes" class of MediaPipe's selfie_multiclass_256x256 segmenter. Garments
whose clothes pixels cover < 60 % of the polygon are dropped. Output (outside the repo):
<catalog>/cutouts/<id>.webp and <catalog>/catalog.json.
"""

from __future__ import annotations

import argparse
import io
import json
import math
import random
import sys
import time
from collections import defaultdict
from multiprocessing import Pool
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from labels import CATALOG_DIR, KFASHION_DIR, SPLITS, open_zip, parse, photos  # noqa: E402

from recommendation.vocab import is_unisex  # noqa: E402

QUOTA = {"상의": 0.35, "하의": 0.30, "아우터": 0.20, "원피스": 0.15}
SLOT_CODE = {"아우터": "o", "상의": "t", "하의": "b", "원피스": "d"}
UNISEX_SHARE = 0.4
OVERSAMPLE = 1.35
MIN_POINTS = 15
MIN_COVERAGE = 0.6
CLOTHES = 4  # selfie_multiclass_256x256: background, hair, body-skin, face-skin, clothes, others
MAX_SIDE = 360
SEG_WIDTH = 320
SEGMENTER = CATALOG_DIR / "models" / "selfie_multiclass_256x256.tflite"


def covered(slot: str, present: set[str]) -> bool:
    return (
        (slot == "상의" and present & {"아우터", "원피스"})
        or (slot == "하의" and "원피스" in present)
        or (slot == "원피스" and "아우터" in present)
    )


def candidates() -> list[dict]:
    best: dict[tuple, dict] = {}
    for split in SPLITS:
        for p in photos(split):
            present = set(p.garments)
            for slot, g in p.garments.items():
                a = g.attrs
                if not (a.get("카테고리") and a.get("색상") and a.get("핏")) or not p.style:
                    continue
                if len(g.polygon) < MIN_POINTS or not g.rect or covered(slot, present):
                    continue
                w, h = float(g.rect.get("가로", 0)), float(g.rect.get("세로", 0))
                if h < 220 or w * h < 0.06 * p.width * p.height:
                    continue
                key = (p.stem, slot, a["카테고리"], a["색상"], a["핏"])
                if key not in best or w * h > best[key]["area"]:
                    best[key] = {
                        "id": f"{split[0]}{p.image_id}{SLOT_CODE[slot]}", "split": split, "member": p.member,
                        "slot": slot, "attrs": a, "style": p.style, "substyle": p.substyle, "area": w * h,
                        "unisex": is_unisex(slot, a),
                    }
    return list(best.values())


def sample(cands: list[dict], size: int, rng: random.Random) -> list[dict]:
    out = []
    for slot, share in QUOTA.items():
        pool = [c for c in cands if c["slot"] == slot]
        quota = int(size * share * OVERSAMPLE)
        groups = [[c for c in pool if c["unisex"]], [c for c in pool if not c["unisex"]]]
        unisex_quota = 0 if slot == "원피스" else int(quota * UNISEX_SHARE)
        for group, q in ((groups[0], unisex_quota), (groups[1], quota - unisex_quota)):
            out += by_style(group, q, rng)
    return out


def by_style(pool: list[dict], quota: int, rng: random.Random) -> list[dict]:
    styles = defaultdict(list)
    for c in pool:
        styles[c["style"]].append(c)
    weights = {s: math.sqrt(len(v)) for s, v in styles.items()}
    total = sum(weights.values()) or 1
    picked = []
    for s, items in styles.items():
        rng.shuffle(items)
        picked += items[: int(round(quota * weights[s] / total))]
    return picked


# ---------- cut-out workers
_state: dict = {}


def _init(model_bytes: bytes) -> None:
    from mediapipe.tasks.python import BaseOptions, vision

    _state["segmenter"] = vision.ImageSegmenter.create_from_options(vision.ImageSegmenterOptions(
        base_options=BaseOptions(model_asset_buffer=model_bytes),
        running_mode=vision.RunningMode.IMAGE,
        output_category_mask=True,
    ))
    _state["zips"] = {}


def _zip(path: str):
    if path not in _state["zips"]:
        _state["zips"][path] = open_zip(Path(path))
    return _state["zips"][path]


def _main_parts(mask: np.ndarray, keep: float = 0.08) -> np.ndarray:
    """Drop fragments: keep connected parts at least `keep` x the largest part (a garment can be
    split in two, e.g. an open jacket, so not only the largest)."""
    import cv2

    n, labels, stats, _ = cv2.connectedComponentsWithStats(mask.astype(np.uint8), connectivity=8)
    if n <= 2:
        return mask.astype(np.uint8) * 255
    areas = stats[1:, cv2.CC_STAT_AREA]
    good = 1 + np.flatnonzero(areas >= keep * areas.max())
    return np.isin(labels, good).astype(np.uint8) * 255


def cut(task: dict) -> dict | None:
    import mediapipe as mp

    try:
        photo = parse(task["split"], task["member"], _zip(task["label_zip"]).read(task["member"]))
        garment = photo.garments[task["slot"]]
        image_name = task["member"][: -len(".json")] + ".jpg"
        img = Image.open(io.BytesIO(_zip(task["image_zip"]).read(image_name))).convert("RGB")
        w, h = img.size
        # the model works at 256 px; segmenting a small copy is ~10x faster and the mask is the same
        small_w = SEG_WIDTH
        small_h = max(4, round(h * SEG_WIDTH / w))
        rgb = np.asarray(img.resize((small_w, small_h), Image.BILINEAR))  # width is a multiple of 4 (pose.py)
        result = _state["segmenter"].segment(mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(rgb)))
        small_mask = Image.fromarray((result.category_mask.numpy_view().squeeze() == CLOTHES).astype(np.uint8) * 255)
        clothes = np.asarray(small_mask.resize((w, h), Image.BILINEAR)) > 127

        poly = Image.new("L", (w, h), 0)
        ImageDraw.Draw(poly).polygon(garment.polygon, fill=255)
        poly_mask = np.asarray(poly) > 0
        mask = poly_mask & clothes
        coverage = float(mask.sum() / max(1, poly_mask.sum()))
        if coverage < MIN_COVERAGE:
            return None
        m = Image.fromarray((mask * 255).astype(np.uint8))
        m = m.filter(ImageFilter.MinFilter(3)).filter(ImageFilter.MaxFilter(3))  # drop specks
        m = m.filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.MinFilter(5))  # close small holes
        m = Image.fromarray(_main_parts(np.asarray(m) > 127))
        m = m.filter(ImageFilter.GaussianBlur(0.8))
        box = m.getbbox()
        if not box:
            return None
        rgba = img.convert("RGBA")
        rgba.putalpha(m)
        piece = rgba.crop(box)
        piece.thumbnail((MAX_SIDE, MAX_SIDE))
        piece.save(Path(task["out_dir"]) / f"{task['id']}.webp", "WEBP", quality=82, method=4)
        return {**{k: task[k] for k in ("id", "split", "member", "slot", "attrs", "style", "substyle", "unisex")},
                "coverage": round(coverage, 3), "size": piece.size}
    except Exception as e:  # noqa: BLE001 - one broken photo must not stop the build
        return {"error": f"{task['id']}: {type(e).__name__}: {e}"}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--size", type=int, default=20000)
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--seed", type=int, default=11)
    args = ap.parse_args()
    t0 = time.time()
    rng = random.Random(args.seed)

    cands = candidates()
    print(f"{len(cands)} candidates after filters and de-duplication ({time.time() - t0:.0f}s)", flush=True)
    picked = sample(cands, args.size, rng)
    print(f"{len(picked)} sampled for cut-out", flush=True)

    image_zip_of = {}
    for split, folder in SPLITS.items():
        for zp in sorted((KFASHION_DIR / folder).glob("원천데이터*.zip")):
            for name in open_zip(zp).namelist():
                image_zip_of[(split, name)] = str(zp)
    label_zips = {s: str(KFASHION_DIR / f / "라벨링데이터.zip") for s, f in SPLITS.items()}
    out_dir = CATALOG_DIR / "cutouts"
    out_dir.mkdir(parents=True, exist_ok=True)
    tasks = [{**c, "label_zip": label_zips[c["split"]],
              "image_zip": image_zip_of[(c["split"], c["member"][: -len(".json")] + ".jpg")],
              "out_dir": str(out_dir)} for c in picked]
    tasks.sort(key=lambda t: t["image_zip"])

    items, errors, dropped = [], [], 0
    with Pool(args.workers, initializer=_init, initargs=(SEGMENTER.read_bytes(),)) as pool:
        for i, r in enumerate(pool.imap_unordered(cut, tasks, chunksize=16)):
            if r is None:
                dropped += 1
            elif "error" in r:
                errors.append(r["error"])
            else:
                items.append(r)
            if (i + 1) % 2000 == 0:
                print(f"{i + 1}/{len(tasks)} done, {len(items)} kept ({time.time() - t0:.0f}s)", flush=True)

    # trim to the slot quota, keeping the best-covered garments
    final = []
    for slot, share in QUOTA.items():
        slot_items = sorted((x for x in items if x["slot"] == slot), key=lambda x: -x["coverage"])
        final += slot_items[: int(args.size * share)]
    keep = {x["id"] for x in final}
    for x in items:
        if x["id"] not in keep:
            (out_dir / f"{x['id']}.webp").unlink(missing_ok=True)
    (CATALOG_DIR / "catalog.json").write_text(json.dumps({
        "items": final,
        "meta": {"candidates": len(cands), "sampled": len(tasks), "low_coverage": dropped, "errors": len(errors)},
    }, ensure_ascii=False), encoding="utf-8")
    print(f"catalog: {len(final)} garments, dropped {dropped} (coverage), {len(errors)} errors ({time.time() - t0:.0f}s)")
    for e in errors[:10]:
        print("  ", e)


if __name__ == "__main__":
    main()

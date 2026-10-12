# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-08, reviewed by Dongkun Moon
"""Read K-Fashion (AI Hub) labels and photos straight from the downloaded zips.

The dataset is never copied into the repository (AI Hub forbids redistribution and the repo is
public). Set KFASHION_DIR to the folder that holds Training/ and Validation/; the default is the
download location used during development.
"""

from __future__ import annotations

import json
import os
import re
import zipfile
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path

KFASHION_DIR = Path(os.environ.get("KFASHION_DIR", Path(__file__).resolve().parents[3] / "kfashion_dataset" / "K-Fashion 이미지"))
CATALOG_DIR = Path(os.environ.get("RECO_CATALOG_DIR", KFASHION_DIR / "catalog"))
SLOTS = ("아우터", "상의", "하의", "원피스")
SPLITS = {"train": "Training", "val": "Validation"}


def open_zip(path: Path) -> zipfile.ZipFile:
    # file names inside the zips are cp949 without the UTF-8 flag
    return zipfile.ZipFile(path, metadata_encoding="cp949")


def label_zip(split: str) -> zipfile.ZipFile:
    return open_zip(KFASHION_DIR / SPLITS[split] / "라벨링데이터.zip")


def image_zips(split: str) -> list[zipfile.ZipFile]:
    folder = KFASHION_DIR / SPLITS[split]
    return [open_zip(p) for p in sorted(folder.glob("원천데이터*.zip"))]


class ImageStore:
    """Finds a photo by its label member name (`style/123.json` -> `style/123.jpg`)."""

    def __init__(self, split: str) -> None:
        self._zips = image_zips(split)
        self._where: dict[str, zipfile.ZipFile] = {}
        for z in self._zips:
            for name in z.namelist():
                if name.endswith(".jpg"):
                    self._where[name] = z

    def read(self, member: str) -> bytes:
        name = member[: -len(".json")] + ".jpg"
        return self._where[name].read(name)


@dataclass
class Garment:
    slot: str
    attrs: dict
    rect: dict | None
    polygon: list[tuple[float, float]]


@dataclass
class Photo:
    split: str
    member: str
    image_id: int
    file: str
    width: int
    height: int
    style: str | None
    substyle: str | None
    garments: dict[str, Garment]

    @property
    def stem(self) -> str:
        return product_stem(self.file) or self.member


def product_stem(file_name: str) -> str | None:
    """Shopping-mall file name without the shot index, so shots of one product group together.

    None for generic camera/upload names ("1.jpg", "IMG_0412.jpg", "없음"), which say nothing
    about the product and would merge unrelated photos.
    """
    s = re.sub(r"\.(jpe?g|png|gif)$", "", file_name, flags=re.I)
    s = re.sub(r"[_\-\s(]*\d{1,3}\)?$", "", s).lower()
    letters = re.sub(r"[^a-z가-힣]", "", s)
    if len(s) < 5 or len(letters) < 3 or letters in {"img", "dsc", "dscf", "image", "photo", "kakaotalk", "없음"}:
        return None
    return s


def polygon_points(poly: dict) -> list[tuple[float, float]]:
    idx = sorted(int(m.group(1)) for k in poly if (m := re.fullmatch(r"X좌표(\d+)", k)))
    return [(float(poly[f"X좌표{i}"]), float(poly[f"Y좌표{i}"])) for i in idx if f"Y좌표{i}" in poly]


def parse(split: str, member: str, raw: bytes) -> Photo:
    doc = json.loads(raw.decode("utf-8"))
    img = doc.get("이미지 정보", {})
    detail = doc.get("데이터셋 정보", {}).get("데이터셋 상세설명", {})
    label = detail.get("라벨링", {})
    rects = detail.get("렉트좌표", {})
    polys = detail.get("폴리곤좌표", {})
    styles = [s for s in label.get("스타일", []) if s]
    garments = {}
    for slot in SLOTS:
        items = [it for it in label.get(slot, []) if it]
        if not items:
            continue
        r = [x for x in rects.get(slot, []) if x]
        p = [x for x in polys.get(slot, []) if x]
        garments[slot] = Garment(slot, items[0], r[0] if r else None, polygon_points(p[0]) if p else [])
    return Photo(
        split=split,
        member=member,
        image_id=int(img.get("이미지 식별자", 0) or 0),
        file=str(img.get("이미지 파일명", "")),
        width=int(img.get("이미지 너비", 0) or 0),
        height=int(img.get("이미지 높이", 0) or 0),
        style=styles[0].get("스타일") if styles else None,
        substyle=styles[0].get("서브스타일") if styles else None,
        garments=garments,
    )


def photos(split: str) -> Iterator[Photo]:
    with label_zip(split) as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            yield parse(split, info.filename, z.read(info))

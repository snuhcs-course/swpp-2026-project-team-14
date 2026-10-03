"""Recognises our benchmark photos so the app can show real accuracy against known measurements.

The 8 synthetic benchmark bodies (`scripts/synthetic_benchmark.py`) have exact measurements taken
from their 3D mesh. `scripts/export_benchmark_reference.py` renders them as demo photos with a small
marker in the top-left corner: a magenta start block, 5 black/white bits (body number and view) and
a parity block, plus a "BENCHMARK <body>" label. When both uploaded photos carry markers of the same
body (front + side), the result includes that body's true measurements and the app shows
"정확도 98%" per measurement. Any other photo is unchanged: we never show an accuracy we did not
measure.

Why a marker and not image matching: some benchmark bodies differ by only a few centimetres
(m_avg vs m_heavy: waist 80 vs 85 cm at the same height), and after the app's JPEG re-encoding and
downscaling their thumbnails or silhouettes are closer than two copies of the same photo. The marker
blocks are large (5 % of the image width), so they survive JPEG and resizing to under half size.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from .types import MeasurementType

DEFAULT_REFERENCE_PATH = Path(__file__).resolve().parent / "models" / "benchmark_reference.json"

BLOCK = 0.05  # block size as a fraction of the image width
DATA_BITS = 5  # bit 0: view (0 front, 1 side); bits 1–4: body index
_MAGENTA = (255, 0, 255)


def _block_box(i: int, w: int) -> tuple[int, int, int, int]:
    size = round(BLOCK * w)
    return i * size, 0, (i + 1) * size, size  # x0, y0, x1, y1


def _bits(index: int, view: str) -> list[int]:
    value = (index << 1) | (view == "side")
    return [(value >> b) & 1 for b in range(DATA_BITS)]


def draw_marker(image_rgb: np.ndarray, index: int, view: str, label: str) -> np.ndarray:
    """Returns a copy of the photo with the benchmark marker in its top-left corner."""
    import cv2

    out = image_rgb.copy()
    w = out.shape[1]
    bits = _bits(index, view)
    colours = [_MAGENTA] + [(255, 255, 255) if b else (0, 0, 0) for b in bits]
    colours.append((255, 255, 255) if sum(bits) % 2 else (0, 0, 0))  # parity
    for i, colour in enumerate(colours):
        x0, y0, x1, y1 = _block_box(i, w)
        out[y0:y1, x0:x1] = colour
    size = round(BLOCK * w)
    cv2.putText(out, f"BENCHMARK {label}", (4, size + round(0.6 * size)), cv2.FONT_HERSHEY_SIMPLEX,
                size / 80, (90, 90, 90), max(1, size // 30), cv2.LINE_AA)
    return out


def read_marker(image_rgb: np.ndarray) -> tuple[int, str] | None:
    """(body index, view) if the photo carries a valid benchmark marker, else None."""
    h, w = image_rgb.shape[:2]
    if w < 100 or round(BLOCK * w) * (DATA_BITS + 2) > w:
        return None

    def colour(i: int) -> np.ndarray:
        x0, y0, x1, y1 = _block_box(i, w)
        q = (x1 - x0) // 4  # centre half of the block: away from JPEG ringing at the edges
        return image_rgb[y0 + q:y1 - q, x0 + q:x1 - q].reshape(-1, 3).mean(0)

    r, g, b = colour(0)
    if not (r > 200 and g < 70 and b > 200):
        return None
    bits = []
    for i in range(1, DATA_BITS + 2):
        c = colour(i)
        if c.min() > 190 and np.ptp(c) < 40:
            bits.append(1)
        elif c.max() < 65:
            bits.append(0)
        else:
            return None
    data, parity = bits[:DATA_BITS], bits[DATA_BITS]
    if sum(data) % 2 != parity:
        return None
    value = sum(bit << i for i, bit in enumerate(data))
    return value >> 1, "side" if value & 1 else "front"


def erase_marker(image_rgb: np.ndarray) -> np.ndarray:
    """The photo with its marker and label painted over in the background colour (unchanged if none).

    The high-contrast blocks otherwise shift MediaPipe's landmarks by up to ~16 px and the measurements
    by up to 2 cm, so the analysis would no longer be that of the plain render.
    """
    if read_marker(image_rgb) is None:
        return image_rgb
    h, w = image_rgb.shape[:2]
    size = round(BLOCK * w)
    bottom, right = min(h, round(1.9 * size)), min(w, (DATA_BITS + 2) * size)
    below = image_rgb[bottom:min(h, bottom + max(2, size // 4)), :right].reshape(-1, 3)
    out = image_rgb.copy()
    out[:bottom, :right] = np.median(below, 0).astype(image_rgb.dtype)
    return out


@dataclass(frozen=True)
class Reference:
    body: str
    height_cm: float
    measurements: dict[MeasurementType, float]

    def to_dict(self) -> dict:
        return {
            "body": self.body,
            "height_cm": self.height_cm,
            "measurements": {t.value: v for t, v in self.measurements.items()},
        }


class ReferenceSet:
    def __init__(self, references: list[Reference]):
        self._references = references  # list position = marker body index

    @classmethod
    def load(cls, path: Path = DEFAULT_REFERENCE_PATH) -> ReferenceSet | None:
        if not path.exists():
            return None
        data = json.loads(path.read_text(encoding="utf-8"))
        return cls([
            Reference(
                body=body["body"],
                height_cm=body["height_cm"],
                measurements={MeasurementType(k): v for k, v in body["measurements"].items()},
            )
            for body in data["bodies"]
        ])

    def match(self, front_rgb: np.ndarray, side_rgb: np.ndarray) -> Reference | None:
        """The benchmark body shown in both photos, or None. Front and side must be the same body."""
        front, side = read_marker(front_rgb), read_marker(side_rgb)
        if front is None or side is None or front[1] != "front" or side[1] != "side" or front[0] != side[0]:
            return None
        index = front[0]
        return self._references[index] if index < len(self._references) else None

"""Pure geometry helpers on person masks. No ML dependencies, fully unit-testable."""

from __future__ import annotations

import math

import numpy as np


def largest_component(mask: np.ndarray) -> np.ndarray:
    """Keeps only the largest connected blob (removes segmentation noise)."""
    import cv2

    count, labels, stats, _ = cv2.connectedComponentsWithStats(mask.astype(np.uint8), connectivity=8)
    if count <= 1:
        return mask.astype(bool)
    biggest = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    return labels == biggest


def vertical_extent(mask: np.ndarray) -> tuple[int, int]:
    """(top_row, bottom_row) of the mask, inclusive."""
    rows = np.flatnonzero(mask.any(axis=1))
    if rows.size == 0:
        raise ValueError("empty mask")
    return int(rows[0]), int(rows[-1])


def runs(row: np.ndarray) -> list[tuple[int, int]]:
    """Contiguous True segments of a 1-D bool array as (start, end_exclusive)."""
    padded = np.concatenate(([False], row.astype(bool), [False]))
    edges = np.flatnonzero(padded[1:] != padded[:-1])
    return [(int(edges[i]), int(edges[i + 1])) for i in range(0, len(edges), 2)]


def run_at(mask: np.ndarray, y: float, x: float) -> tuple[int, int] | None:
    """The mask segment on row `y` that contains column `x`, or the nearest one."""
    yi = int(round(min(max(y, 0), mask.shape[0] - 1)))
    segments = runs(mask[yi])
    if not segments:
        return None

    def distance(seg: tuple[int, int]) -> float:
        start, end = seg
        return 0.0 if start <= x < end else min(abs(x - start), abs(x - (end - 1)))

    return min(segments, key=distance)


def width_at(
    mask: np.ndarray,
    y: float,
    x: float,
    clamp: tuple[float, float] | None = None,
) -> float:
    """Width (px) of the segment containing `x` on row `y`, optionally clipped to [left, right]."""
    seg = run_at(mask, y, x)
    if seg is None:
        return 0.0
    start, end = seg
    if clamp is not None:
        start, end = max(start, clamp[0]), min(end, clamp[1])
    return float(max(end - start, 0))


def contains(mask: np.ndarray, y: float, x: float) -> bool:
    yi, xi = int(round(y)), int(round(x))
    return 0 <= yi < mask.shape[0] and 0 <= xi < mask.shape[1] and bool(mask[yi, xi])


def ellipse_perimeter(a: float, b: float) -> float:
    """Ramanujan's second approximation for an ellipse with semi-axes a, b."""
    if a <= 0 or b <= 0:
        return 0.0
    h = ((a - b) / (a + b)) ** 2
    return math.pi * (a + b) * (1 + 3 * h / (10 + math.sqrt(4 - 3 * h)))


def distance(p: tuple[float, float], q: tuple[float, float]) -> float:
    return math.hypot(p[0] - q[0], p[1] - q[1])


def cos_from_vertical(p: tuple[float, float], q: tuple[float, float]) -> float:
    """cos of the angle between segment p→q and the vertical axis (1 = vertical limb)."""
    length = distance(p, q)
    return 1.0 if length == 0 else abs(q[1] - p[1]) / length


def lerp(a: float, b: float, t: float) -> float:
    return a + (b - a) * t

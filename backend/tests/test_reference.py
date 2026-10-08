# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-02, reviewed by Dongkun Moon
"""Benchmark photo recognition: accuracy is shown only for our benchmark bodies (body_analysis/reference.py)."""

import cv2
import numpy as np
import pytest

from body_analysis import AnalysisInput, BodyAnalysisPipeline, MeasurementType
from body_analysis.reference import DEFAULT_REFERENCE_PATH, Reference, ReferenceSet, draw_marker, erase_marker, read_marker

from . import synthetic as syn
from .test_pipeline import FakeEstimator


def photo(w=960, h=1280):
    return np.full((h, w, 3), (236, 234, 230), np.uint8)


def through_app(image, w, h, quality=85):
    """Resize + JPEG round trip, like the app's downscaling and re-encoding."""
    resized = cv2.resize(image, (w, h), interpolation=cv2.INTER_AREA if w < image.shape[1] else cv2.INTER_CUBIC)
    ok, data = cv2.imencode(".jpg", resized, [cv2.IMWRITE_JPEG_QUALITY, quality])
    return cv2.imdecode(data, cv2.IMREAD_COLOR)


@pytest.mark.parametrize("index", range(8))
@pytest.mark.parametrize("view", ["front", "side"])
def test_marker_round_trip(index, view):
    assert read_marker(draw_marker(photo(), index, view, "body")) == (index, view)


@pytest.mark.parametrize("size", [(960, 1280), (481, 641), (480, 640), (963, 1284), (3840, 5120), (300, 400)])
def test_marker_survives_resizing_and_jpeg(size):
    marked = draw_marker(photo(), 6, "side", "m_heavy")
    assert read_marker(through_app(marked, *size)) == (6, "side")


def test_unmarked_and_damaged_photos_have_no_marker():
    assert read_marker(photo()) is None
    rng = np.random.default_rng(0)
    assert read_marker(rng.integers(0, 256, (1280, 960, 3), dtype=np.uint8)) is None
    damaged = draw_marker(photo(), 3, "front", "f_short")
    damaged[0:48, 48:96] = 255 - damaged[0:48, 48:96]  # flip one data bit → parity fails
    assert read_marker(damaged) is None


def references():
    def ref(name):
        return Reference(name, 170.0, {MeasurementType.WAIST: 70.0})
    return ReferenceSet([ref("a"), ref("b")])


def test_match_needs_front_and_side_of_the_same_body():
    front_a, side_a = draw_marker(photo(), 0, "front", "a"), draw_marker(photo(), 0, "side", "a")
    side_b = draw_marker(photo(), 1, "side", "b")
    refs = references()
    assert refs.match(front_a, side_a).body == "a"
    assert refs.match(front_a, side_b) is None  # different bodies
    assert refs.match(side_a, front_a) is None  # views swapped
    assert refs.match(front_a, photo()) is None  # side not a benchmark photo
    assert refs.match(draw_marker(photo(), 5, "front", "x"), draw_marker(photo(), 5, "side", "x")) is None  # unknown index


def test_pipeline_reports_reference_only_for_benchmark_photos():
    def analyze(front, side):  # FakeEstimator hands out each pose once, so one pipeline per analysis
        pipeline = BodyAnalysisPipeline(FakeEstimator(syn.front_pose(), syn.side_pose()), references=references())
        return pipeline.analyze_images(front, side, AnalysisInput(height_cm=170))[0]

    marked = analyze(draw_marker(photo(), 1, "front", "b"), draw_marker(photo(), 1, "side", "b"))
    plain = analyze(photo(), photo())
    assert marked.to_dict()["reference"] == {"body": "b", "height_cm": 170.0, "measurements": {"waist": 70.0}}
    assert plain.to_dict()["reference"] is None


def test_shipped_reference_file():
    refs = ReferenceSet.load()
    assert refs is not None, f"{DEFAULT_REFERENCE_PATH} is missing"
    assert len(refs._references) == 8
    for ref in refs._references:
        assert 120 < ref.height_cm < 210
        assert MeasurementType.WAIST in ref.measurements and len(ref.measurements) >= 12


def test_erase_marker_restores_the_background():
    plain = photo()
    plain[600:, 400:560] = (222, 184, 158)  # something that is not background, below the marker
    erased = erase_marker(draw_marker(plain, 2, "front", "f_heavy"))
    assert np.array_equal(erased, plain)
    assert erase_marker(plain) is plain  # no marker → untouched

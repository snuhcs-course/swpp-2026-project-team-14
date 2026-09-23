import math

import numpy as np
import pytest

from body_analysis.geometry import ellipse_perimeter, runs, width_at
from body_analysis.measurer import CALIBRATION, DEPTH_RATIO, measure
from body_analysis.types import MeasurementType as M

from . import synthetic as syn


# --- geometry -----------------------------------------------------------------------------

def test_runs_finds_segments():
    row = np.array([0, 1, 1, 0, 0, 1, 0, 1, 1, 1], bool)
    assert runs(row) == [(1, 3), (5, 6), (7, 10)]


def test_width_at_picks_segment_under_point_and_clamps():
    mask = np.zeros((3, 20), bool)
    mask[1, 2:6] = True
    mask[1, 10:18] = True
    assert width_at(mask, 1, 12) == 8
    assert width_at(mask, 1, 3) == 4
    assert width_at(mask, 1, 12, clamp=(11, 15)) == 4


def test_ellipse_perimeter_matches_circle_and_known_value():
    assert ellipse_perimeter(5, 5) == pytest.approx(2 * math.pi * 5)
    assert ellipse_perimeter(16, 12) == pytest.approx(88.41, abs=0.01)
    assert ellipse_perimeter(0, 5) == 0.0


# --- measurer on the synthetic mannequin ---------------------------------------------------

def expected_torso_circ(type_: M, y: float, with_side: bool) -> float:
    width = syn.profile(syn.TORSO_WIDTH, y)
    depth = syn.profile(syn.TORSO_DEPTH, y) if with_side else width * DEPTH_RATIO[type_]
    return ellipse_perimeter(width / 2, depth / 2) * CALIBRATION[type_]


@pytest.fixture(scope="module")
def both():
    return measure(syn.front_pose(), syn.side_pose(), syn.HEIGHT_CM)


@pytest.fixture(scope="module")
def front_only():
    return measure(syn.front_pose(), None, syn.HEIGHT_CM)


def test_lengths_match_mannequin(both):
    v = both.values
    assert v[M.SHOULDER_WIDTH] == pytest.approx(2 * syn.SHOULDER_X * CALIBRATION[M.SHOULDER_WIDTH], abs=0.5)
    assert v[M.INSEAM] == pytest.approx(syn.FLOOR_Y - syn.CROTCH_Y, abs=1.0)
    # the mannequin's waist is flat within 1 px from 56 to 58 cm, so the waist row is ±1.5 cm
    assert v[M.OUTSEAM] == pytest.approx(syn.FLOOR_Y - syn.WAIST_Y, abs=1.5)
    assert v[M.RISE] == pytest.approx(syn.CROTCH_Y - syn.WAIST_Y, abs=1.5)
    neck_base = syn.SHOULDER_Y - 0.1 * (syn.SHOULDER_Y - syn.MOUTH_Y)
    assert v[M.TORSO_LENGTH] == pytest.approx(syn.WAIST_Y - neck_base, abs=1.5)
    upper = math.dist((18, 28), (27, 56))
    lower = math.dist((27, 56), (33, 82))
    assert v[M.SLEEVE_LENGTH] == pytest.approx((upper + lower) * CALIBRATION[M.SLEEVE_LENGTH], abs=0.5)


def test_torso_circumferences_use_side_depth(both):
    v = both.values
    assert v[M.CHEST] == pytest.approx(expected_torso_circ(M.CHEST, syn.CHEST_Y, True), rel=0.03)
    assert v[M.WAIST] == pytest.approx(expected_torso_circ(M.WAIST, syn.WAIST_Y, True), rel=0.03)
    assert v[M.HIP] == pytest.approx(expected_torso_circ(M.HIP, syn.HIP_Y, True), rel=0.03)
    neck = ellipse_perimeter(syn.NECK["width"] / 2, syn.NECK["depth"] / 2)
    assert v[M.NECK] == pytest.approx(neck, rel=0.05)


def test_front_only_falls_back_to_depth_ratio(front_only):
    v = front_only.values
    assert v[M.WAIST] == pytest.approx(expected_torso_circ(M.WAIST, syn.WAIST_Y, False), rel=0.03)
    assert v[M.HIP] == pytest.approx(expected_torso_circ(M.HIP, syn.HIP_Y, False), rel=0.03)


def test_leg_circumferences(both):
    thigh_y = syn.CROTCH_Y + 0.04 * (syn.FLOOR_Y - syn.CROTCH_Y)
    thigh = ellipse_perimeter(syn.profile(syn.LEG_WIDTH, thigh_y) / 2, syn.profile(syn.LEG_DEPTH, thigh_y) / 2)
    assert both.values[M.THIGH] == pytest.approx(thigh, rel=0.06)
    # calf row = widest point between knee and ankle → 12 cm wide, 12.5 cm deep
    assert both.values[M.CALF] == pytest.approx(ellipse_perimeter(6, 6.25), rel=0.08)


def test_arm_measurements_found_when_arms_are_clear(both):
    v = both.values
    assert v[M.BICEP] == pytest.approx(ellipse_perimeter(4.5, 0.95 * 4.5), rel=0.1)
    assert M.WRIST in v and M.ARMHOLE in v
    assert "arms_touching_body" not in both.warnings


def test_derived_ratios(both):
    d = both.derived
    assert d["waist_hip_ratio"] == pytest.approx(both.values[M.WAIST] / both.values[M.HIP], abs=0.001)
    assert 0.3 < d["torso_leg_ratio"] < 0.5
    assert d["inseam_height_ratio"] == pytest.approx(90 / 170, abs=0.01)

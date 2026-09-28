import json

import pytest

from body_analysis import AnalysisInput, BodyAnalysisPipeline, Gender, MeasurementType as M
from body_analysis.clothing import ClothingDetector, detector_features
from body_analysis.regressor import MeasurementCorrector, input_features
from body_analysis.types import ClothingAssessment

from . import synthetic as syn
from .test_pipeline import IMAGE, FakeEstimator


def write_model(tmp_path):
    """A tiny hand-made model: waist/height = 0.5 + 0.1 * standardized(in_bmi)."""
    model = {
        "version": "test-1",
        "measurements": {
            "waist": {"features": ["in_bmi"], "mean": [20.0], "scale": [2.0], "coef": [0.1], "intercept": 0.5},
        },
    }
    path = tmp_path / "model.json"
    path.write_text(json.dumps(model), encoding="utf-8")
    return MeasurementCorrector.load(path)


def test_input_features_encode_user_inputs():
    feats = input_features(AnalysisInput(height_cm=160, weight_kg=64, gender=Gender.FEMALE))
    assert feats["in_bmi"] == pytest.approx(25.0)
    assert feats["in_weight_known"] == 1.0
    assert feats["in_gender_female"] == 1.0 and feats["in_gender_male"] == 0.0
    assert not any(k.startswith("in_clothing") for k in feats)  # clothing is detected, not entered
    assert input_features(AnalysisInput(height_cm=160))["in_weight_known"] == 0.0


def test_correct_replaces_modelled_measurements_only(tmp_path):
    corrector = write_model(tmp_path)
    input_ = AnalysisInput(height_cm=100, weight_kg=22)  # bmi 22 → standardized 1.0
    out = corrector.correct({M.WAIST: 70.0, M.HIP: 90.0}, {}, input_)
    assert out[M.WAIST] == pytest.approx((0.5 + 0.1) * 100)
    assert out[M.HIP] == 90.0


def test_missing_feature_uses_training_mean(tmp_path):
    corrector = write_model(tmp_path)
    out = corrector.correct({M.WAIST: 70.0}, {}, AnalysisInput(height_cm=100))  # bmi unknown → 0 ≠ mean
    assert out[M.WAIST] == pytest.approx((0.5 + 0.1 * (0 - 20) / 2) * 100)


def test_measurements_in_loose_regions_keep_geometry(tmp_path):
    corrector = write_model(tmp_path)
    input_ = AnalysisInput(height_cm=100, weight_kg=22)
    assert corrector.correct({M.WAIST: 70.0}, {}, input_, skip={M.WAIST}) == {M.WAIST: 70.0}


def test_pipeline_uses_corrector_and_reports_version(tmp_path):
    pipeline = BodyAnalysisPipeline(FakeEstimator(syn.front_pose(), syn.side_pose()), write_model(tmp_path))
    result, raw = pipeline.analyze_images(IMAGE, IMAGE, AnalysisInput(height_cm=syn.HEIGHT_CM, weight_kg=63.6))
    assert result.pipeline_version.endswith("+test-1")
    assert result.value(M.WAIST) == pytest.approx(round((0.5 + 0.1 * (63.6 / 1.7**2 - 20) / 2) * 170 * 2) / 2)
    assert result.derived["waist_hip_ratio"] == pytest.approx(raw.values[M.WAIST] / raw.values[M.HIP], abs=1e-3)


def test_missing_model_file_means_no_corrector(tmp_path):
    assert MeasurementCorrector.load(tmp_path / "nope.json") is None


def test_shipped_model_matches_the_pipeline_features():
    """Guards against the model and the measurer drifting apart (retrain after changing features)."""
    from body_analysis.measurer import measure

    corrector = MeasurementCorrector.load()
    assert corrector is not None, "body_analysis/models/measurement_corrector.json is missing"
    raw = measure(syn.front_pose(), syn.side_pose(), syn.HEIGHT_CM)
    produced = set(raw.features) | set(input_features(AnalysisInput(height_cm=170)))
    for type_, model in corrector.models.items():
        missing = set(model.features) - produced
        assert not missing, f"{type_.value}: model uses features the pipeline no longer produces: {missing}"


def test_shipped_model_gives_plausible_values_on_the_mannequin():
    corrector = MeasurementCorrector.load()
    pipeline = BodyAnalysisPipeline(FakeEstimator(syn.front_pose(), syn.side_pose()), corrector)
    result, _ = pipeline.analyze_images(IMAGE, IMAGE, AnalysisInput(height_cm=syn.HEIGHT_CM, gender=Gender.MALE))
    for m in result.measurements:
        assert 0.05 * syn.HEIGHT_CM < m.value_cm < 0.8 * syn.HEIGHT_CM, m


# --- clothing detector ----------------------------------------------------------------------------

def write_detector(tmp_path):
    """Top loose when feature f > 0 (threshold 0.5); bottom never loose."""
    linear = {"features": ["f"], "mean": [0.0], "scale": [1.0], "coef": [10.0], "intercept": 0.0}
    never = {"features": ["f"], "mean": [0.0], "scale": [1.0], "coef": [0.0], "intercept": -10.0}
    data = {"version": "clothing-test", "models": {"top": {**linear, "threshold": 0.5}, "bottom": {**never, "threshold": 0.5}}}
    path = tmp_path / "clothing.json"
    path.write_text(json.dumps(data), encoding="utf-8")
    return ClothingDetector.load(path)


def test_detector_thresholds_probabilities(tmp_path):
    detector = write_detector(tmp_path)
    loose = detector.assess({"f": 1.0}, Gender.FEMALE)
    fitted = detector.assess({"f": -1.0}, Gender.FEMALE)
    assert loose.top_loose and not loose.bottom_loose and loose.top_probability > 0.99
    assert not fitted.top_loose and fitted.top_probability < 0.01
    assert ClothingDetector.load(tmp_path / "missing.json") is None


def test_detector_features_add_gender():
    feats = detector_features({"w_0.500": 0.2}, Gender.MALE)
    assert feats["in_gender_male"] == 1.0 and feats["w_0.500"] == 0.2


def test_shipped_detector_matches_the_pipeline_features():
    from body_analysis.measurer import measure

    detector = ClothingDetector.load()
    assert detector is not None, "body_analysis/models/clothing_detector.json is missing"
    raw = measure(syn.front_pose(), syn.side_pose(), syn.HEIGHT_CM)
    produced = set(detector_features(raw.features, Gender.MALE))
    for region, (model, threshold) in detector.models.items():
        assert not set(model.features) - produced, region
        assert 0 < threshold < 1


def test_shipped_detector_calls_the_mannequin_fitted():
    """The mannequin is drawn without clothing, so neither region should be flagged."""
    from body_analysis.measurer import measure

    raw = measure(syn.front_pose(), syn.side_pose(), syn.HEIGHT_CM)
    assessment = ClothingDetector.load().assess(raw.features, Gender.MALE)
    assert assessment.loose_regions == set(), assessment

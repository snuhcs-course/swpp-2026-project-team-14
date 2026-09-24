import json

import pytest

from body_analysis import AnalysisInput, BodyAnalysisPipeline, Clothing, Gender, MeasurementType as M
from body_analysis.regressor import MeasurementCorrector, input_features

from . import synthetic as syn
from .test_pipeline import IMAGE, FakeEstimator


def write_model(tmp_path, conditions=("underwear", "tight")):
    """A tiny hand-made model: waist/height = 0.5 + 0.1 * standardized(in_bmi)."""
    model = {
        "version": "test-1",
        "conditions": list(conditions),
        "measurements": {
            "waist": {"features": ["in_bmi"], "mean": [20.0], "scale": [2.0], "coef": [0.1], "intercept": 0.5},
        },
    }
    path = tmp_path / "model.json"
    path.write_text(json.dumps(model), encoding="utf-8")
    return MeasurementCorrector.load(path)


def test_input_features_encode_user_inputs():
    feats = input_features(AnalysisInput(height_cm=160, weight_kg=64, gender=Gender.FEMALE, clothing=Clothing.TIGHT))
    assert feats["in_bmi"] == pytest.approx(25.0)
    assert feats["in_weight_known"] == 1.0
    assert feats["in_gender_female"] == 1.0 and feats["in_gender_male"] == 0.0
    assert feats["in_clothing_tight"] == 1.0
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


def test_not_applied_to_untrained_clothing(tmp_path):
    corrector = write_model(tmp_path)
    loose = AnalysisInput(height_cm=100, weight_kg=22, clothing=Clothing.LOOSE)
    assert corrector.correct({M.WAIST: 70.0}, {}, loose) == {M.WAIST: 70.0}


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
    assert corrector.conditions == {Clothing.UNDERWEAR, Clothing.TIGHT}
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

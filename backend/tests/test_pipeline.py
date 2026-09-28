import numpy as np
import pytest

from body_analysis import AnalysisError, AnalysisInput, BodyAnalysisPipeline, Confidence, Gender, MeasurementType
from body_analysis.types import ClothingAssessment
from body_analysis.pipeline import decode_image

from . import synthetic as syn


class FakeEstimator:
    """Returns prepared poses in order, ignoring the pixels."""

    def __init__(self, *poses):
        self.poses = list(poses)

    def estimate(self, image_rgb):
        return self.poses.pop(0)


IMAGE = np.zeros((10, 10, 3), np.uint8)


class FakeDetector:
    """Reports a fixed clothing assessment."""

    version = "fake-clothing"

    def __init__(self, assessment: ClothingAssessment):
        self.assessment = assessment

    def assess(self, features, gender):
        return self.assessment


def run(front, side=None, detector=None, **input_kwargs):
    """Runs the pipeline with fake poses. `side` defaults to the mannequin's side view."""
    side = side if side is not None else syn.side_pose()
    pipeline = BodyAnalysisPipeline(FakeEstimator(front, side), detector=detector)
    input_ = AnalysisInput(height_cm=syn.HEIGHT_CM, **input_kwargs)
    result, _ = pipeline.analyze_images(IMAGE, IMAGE, input_)
    return result


def confidence(result, type_):
    return next(m.confidence for m in result.measurements if m.type is type_)


def test_ideal_input_keeps_base_confidence_and_no_warnings():
    result = run(syn.front_pose(), syn.side_pose(), weight_kg=65)
    assert result.warnings == []
    for m in result.measurements:
        assert m.confidence is m.type.base_confidence
        assert m.value_cm * 2 == int(m.value_cm * 2)  # 0.5 cm steps


def test_detected_loose_top_warns_and_lowers_only_upper_body():
    detector = FakeDetector(ClothingAssessment(top_loose=True, top_probability=0.9, bottom_probability=0.1))
    result = run(syn.front_pose(), weight_kg=65, detector=detector)
    assert result.warnings == ["loose_top"]
    assert confidence(result, MeasurementType.CHEST) is Confidence.LOW  # medium → low
    assert confidence(result, MeasurementType.SHOULDER_WIDTH) is Confidence.MEDIUM  # high → medium
    assert confidence(result, MeasurementType.INSEAM) is Confidence.HIGH  # legs untouched
    assert result.clothing.to_dict()["top"] == "loose"
    assert result.pipeline_version.endswith("+fake-clothing")


def test_detected_loose_bottom_lowers_legs():
    result = run(syn.front_pose(), weight_kg=65, detector=FakeDetector(ClothingAssessment(bottom_loose=True)))
    assert result.warnings == ["loose_bottom"]
    assert confidence(result, MeasurementType.INSEAM) is Confidence.MEDIUM
    assert confidence(result, MeasurementType.HIP) is Confidence.LOW
    assert confidence(result, MeasurementType.CHEST) is Confidence.MEDIUM


def test_without_detector_clothing_is_assumed_fitted():
    result = run(syn.front_pose(), weight_kg=65)
    assert result.clothing == ClothingAssessment()
    assert result.to_dict()["clothing"] == {
        "top": "fitted", "bottom": "fitted", "top_loose_probability": None, "bottom_loose_probability": None,
    }


def test_side_photo_is_required():
    pipeline = BodyAnalysisPipeline(FakeEstimator(syn.front_pose()))
    with pytest.raises(AnalysisError) as error:
        pipeline.analyze_images(IMAGE, None, AnalysisInput(height_cm=syn.HEIGHT_CM))
    assert (error.value.code, error.value.photo) == ("side_photo_required", "side")
    with pytest.raises(AnalysisError) as error:
        pipeline.analyze(b"front bytes", b"", AnalysisInput(height_cm=syn.HEIGHT_CM))
    assert error.value.code == "side_photo_required"


def test_underbust_only_for_female():
    male = {m.type for m in run(syn.front_pose(), gender=Gender.MALE).measurements}
    female = {m.type for m in run(syn.front_pose(), gender=Gender.FEMALE).measurements}
    assert MeasurementType.UNDERBUST not in male
    assert MeasurementType.UNDERBUST in female


def test_result_serialises_to_api_shape():
    data = run(syn.front_pose()).to_dict()
    assert data["pipeline_version"]
    assert {"type", "value_cm", "confidence"} <= set(data["measurements"][0])


@pytest.mark.parametrize(
    "front, code",
    [
        (None, "no_person"),
        (syn.front_pose(num_people=2), "multiple_people"),
        (syn.front_pose(crop_feet=True), "body_cropped"),
        (syn.side_pose(), "not_frontal"),
    ],
)
def test_quality_gate_rejects_bad_front_photos(front, code):
    with pytest.raises(AnalysisError) as error:
        run(front)
    assert error.value.code == code
    assert error.value.hint


def test_quality_gate_rejects_front_photo_given_as_side():
    with pytest.raises(AnalysisError) as error:
        run(syn.front_pose(), syn.front_pose())
    assert (error.value.code, error.value.photo) == ("not_side_view", "side")


def test_decode_rejects_non_images():
    with pytest.raises(AnalysisError) as error:
        decode_image(b"not an image")
    assert error.value.code == "invalid_image"


def test_decode_downscales_large_images():
    import cv2

    ok, png = cv2.imencode(".png", np.zeros((3000, 1500, 3), np.uint8))
    assert ok
    assert max(decode_image(png.tobytes()).shape[:2]) == 1280

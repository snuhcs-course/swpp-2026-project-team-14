import cv2
import numpy as np
import pytest
from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import Client

from body_analysis import BodyAnalysisPipeline
from body_profiles import services

from . import synthetic as syn
from .test_pipeline import FakeEstimator

URL = "/api/body-profile/analyze/"


def png(name: str) -> SimpleUploadedFile:
    ok, data = cv2.imencode(".png", np.zeros((64, 48, 3), np.uint8))
    assert ok
    return SimpleUploadedFile(name, data.tobytes(), content_type="image/png")


@pytest.fixture
def client():
    services.set_pipeline(BodyAnalysisPipeline(FakeEstimator(syn.front_pose(), syn.side_pose())))
    yield Client()
    services.set_pipeline(None)


def post(client, **overrides):
    data = {"front_photo": png("front.png"), "side_photo": png("side.png"), "height_cm": "170"}
    data.update(overrides)
    return client.post(URL, {k: v for k, v in data.items() if v is not None})


def test_analyze_returns_measurements(client):
    response = post(client, weight_kg="65", gender="female", clothing="tight")
    assert response.status_code == 200
    body = response.json()
    assert body["inputs"] == {"height_cm": 170.0, "weight_kg": 65.0, "gender": "female", "clothing": "tight"}
    types = {m["type"] for m in body["measurements"]}
    assert {"chest", "waist", "hip", "inseam", "underbust"} <= types
    assert body["pipeline_version"] and body["analysis_id"]


def test_missing_side_photo_is_422_with_hint(client):
    response = post(client, side_photo=None)
    assert response.status_code == 422
    assert response.json()["error"] == "side_photo_required"
    assert response.json()["hint"]


def test_quality_gate_errors_are_422(client):
    services.set_pipeline(BodyAnalysisPipeline(FakeEstimator(None)))
    response = post(client)
    assert response.status_code == 422
    body = response.json()
    assert (body["error"], body["photo"]) == ("no_person", "front")
    assert body["hint"]


@pytest.mark.parametrize("field, value", [("height_cm", "90"), ("height_cm", "abc"), ("weight_kg", "500"), ("clothing", "coat")])
def test_invalid_fields_are_400(client, field, value):
    response = post(client, **{field: value})
    assert response.status_code == 400


def test_get_is_not_allowed(client):
    assert client.get(URL).status_code == 405

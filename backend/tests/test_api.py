# AI-generated with Claude Code (Claude Opus 5.5), 2026-09-24, reviewed by Dongkun Moon
import cv2
import numpy as np
import pytest
from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import Client, override_settings
from django.test.utils import setup_databases, teardown_databases
from apps.users.auth import issue_token
from apps.users.models import User

from body_analysis import BodyAnalysisPipeline
from apps.body_profiles import services

from . import synthetic as syn
from .test_pipeline import FakeEstimator

URL = "/api/body-profile/analyze/"


def png(name: str) -> SimpleUploadedFile:
    ok, data = cv2.imencode(".png", np.zeros((64, 48, 3), np.uint8))
    assert ok
    return SimpleUploadedFile(name, data.tobytes(), content_type="image/png")


@pytest.fixture(scope='module')
def authenticated_database():
    old_config = setup_databases(verbosity=0, interactive=False)
    user = User.objects.create(username='body_owner')
    _, token = issue_token(user)
    yield token
    teardown_databases(old_config, verbosity=0)


@pytest.fixture
def client(authenticated_database):
    services.set_pipeline(BodyAnalysisPipeline(FakeEstimator(syn.front_pose(), syn.side_pose())))
    yield Client(HTTP_AUTHORIZATION=f'Bearer {authenticated_database}')
    services.set_pipeline(None)


def post(client, **overrides):
    data = {"front_photo": png("front.png"), "side_photo": png("side.png"), "height_cm": "170"}
    data.update(overrides)
    return client.post(URL, {k: v for k, v in data.items() if v is not None})


def test_analyze_returns_measurements(client):
    response = post(client, weight_kg="65", gender="female", clothing="tight")  # clothing: ignored (old clients)
    assert response.status_code == 200
    body = response.json()
    assert body["inputs"] == {"height_cm": 170.0, "weight_kg": 65.0, "gender": "female"}
    assert body["clothing"]["top"] in ("fitted", "loose")
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


@pytest.mark.parametrize("field, value", [("height_cm", "90"), ("height_cm", "abc"), ("weight_kg", "500"), ("gender", "robot")])
def test_invalid_fields_are_400(client, field, value):
    response = post(client, **{field: value})
    assert response.status_code == 400


def test_get_is_not_allowed(client):
    assert client.get(URL).status_code == 405


def test_empty_photo_file_is_a_retake_hint_not_a_server_error(client):
    response = post(client, front_photo=SimpleUploadedFile("front.jpg", b"", content_type="image/jpeg"))
    assert response.status_code == 422
    assert response.json()["error"] == "invalid_image"


def test_empty_gender_means_unspecified(client):
    response = post(client, gender="")
    assert response.status_code == 200
    assert response.json()["inputs"]["gender"] == "unspecified"


def test_two_large_photos_are_not_silently_dropped(client):
    """Two 6 MB photos (each under the 8 MB limit) used to exceed the 10 MB in-memory upload limit,
    so Django dropped both files and the API answered front_photo_required."""
    big = b"\xff\xd8" + b"0" * (6 * 1024 * 1024)
    response = post(
        client,
        front_photo=SimpleUploadedFile("front.jpg", big, content_type="image/jpeg"),
        side_photo=SimpleUploadedFile("side.jpg", big, content_type="image/jpeg"),
    )
    assert response.json()["error"] not in ("front_photo_required", "side_photo_required")


def test_request_too_large_for_memory_is_413(client):
    with override_settings(FILE_UPLOAD_MAX_MEMORY_SIZE=1024):
        response = post(client, front_photo=SimpleUploadedFile("front.jpg", b"0" * 4096, content_type="image/jpeg"))
    assert response.status_code == 413
    assert response.json()["error"] == "photo_too_large"

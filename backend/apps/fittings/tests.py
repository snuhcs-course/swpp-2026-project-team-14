import base64
import io
import json
import uuid
from unittest.mock import Mock, patch
from concurrent.futures import ThreadPoolExecutor

from django.test import SimpleTestCase, TestCase, override_settings
from PIL import Image
from apps.users.auth import issue_token
from apps.users.models import User
from .generation import GeneratedImage, MODEL
from .jobs import Jobs
from .views import get_jobs


@override_settings(OPENROUTER_API_KEY="test-only", PASSWORD_HASHERS=["django.contrib.auth.hashers.MD5PasswordHasher"])
class FittingAccountTests(TestCase):
    def setUp(self):
        self.user = User.objects.create_user("fitting_01", "test-password")
        self.other = User.objects.create_user("fitting_02", "test-password")
        _, self.token = issue_token(self.user)
        _, self.other_token = issue_token(self.other)
        output = io.BytesIO()
        Image.new("RGB", (20, 30), "navy").save(output, "JPEG")
        self.photo = output.getvalue()
        encoded = base64.b64encode(self.photo).decode()
        self.payload = {"consent": True, "person": encoded,
                        "garments": [{"category": "tops", "image": encoded}]}
        self.jobs = Jobs(lambda *args: GeneratedImage(self.photo, "0.06"), start=False)
        mocked = patch("apps.fittings.views.get_jobs", return_value=self.jobs)
        mocked.start()
        self.addCleanup(mocked.stop)
        self.addCleanup(self.jobs.close)

    def upload(self, payload=None, **headers):
        return self.client.post("/api/fittings/jobs/", json.dumps(self.payload if payload is None else payload),
            content_type="application/json", HTTP_AUTHORIZATION="Bearer " + self.token, **headers)

    def test_real_account_job_lifecycle_and_cross_account_isolation(self):
        uploaded = self.upload(HTTP_X_FITTING_OWNER=str(self.other.pk))
        self.assertEqual(uploaded.status_code, 202)
        data = uploaded.json()
        job = self.jobs.jobs[data["id"]]
        self.assertEqual(job.owner, str(self.user.pk))
        path = f'/api/fittings/jobs/{data["id"]}/'
        headers = {"HTTP_AUTHORIZATION": "Bearer " + self.token, "HTTP_X_FITTING_TOKEN": data["token"]}
        for token, capability in ((self.other_token, data["token"]), (self.token, "wrong")):
            denied = {"HTTP_AUTHORIZATION": "Bearer " + token, "HTTP_X_FITTING_TOKEN": capability}
            self.assertEqual(self.client.get(path, **denied).status_code, 404)
            self.assertEqual(self.client.get(path + "image/", **denied).status_code, 404)
            self.assertEqual(self.client.delete(path, **denied).status_code, 404)
        self.assertEqual(self.client.get(path + "image/", **headers).status_code, 409)
        self.jobs.run_next()
        state = self.client.get(path, **headers)
        self.assertTrue(state.json()["inputs_deleted"])
        self.assertEqual(state.json()["cost_usd"], "0.06")
        result = self.client.get(path + "image/", **headers)
        self.assertEqual(result.content, self.photo)
        self.assertEqual(result["Cache-Control"], "no-store")
        self.assertEqual(self.client.delete(path, **headers).status_code, 204)
        self.assertFalse(self.jobs.jobs)

    def test_failed_generation_erases_inputs_and_never_exposes_an_image(self):
        from .generation import GenerationError
        def fail(*args):
            raise GenerationError("FITTING_RATE_LIMITED")
        self.jobs.runner = fail
        uploaded = self.upload().json()
        self.jobs.run_next()
        path = f'/api/fittings/jobs/{uploaded["id"]}/'
        headers = {"HTTP_AUTHORIZATION": "Bearer " + self.token,
                   "HTTP_X_FITTING_TOKEN": uploaded["token"]}
        state = self.client.get(path, **headers).json()
        self.assertEqual(state["status"], "failed")
        self.assertEqual(state["error"], "FITTING_RATE_LIMITED")
        self.assertTrue(state["inputs_deleted"])
        self.assertEqual(self.client.get(path + "image/", **headers).status_code, 409)
        self.assertEqual(self.client.delete(path, **headers).status_code, 204)

    def test_authentication_and_revocation_are_checked_before_any_generation(self):
        self.user.auth_tokens.all().delete()
        for path in ("/api/fittings/health/", "/api/fittings/jobs/", f"/api/fittings/jobs/{uuid.uuid4()}/"):
            self.assertEqual(self.client.get(path, HTTP_AUTHORIZATION="Bearer " + self.token).status_code,
                             405 if path.endswith("jobs/") else 401)
        self.assertEqual(self.upload().status_code, 401)
        self.assertFalse(self.jobs.jobs)

    def test_consent_and_image_validation_prevent_paid_calls(self):
        for value in (False, None, "true"):
            payload = {**self.payload, "consent": value}
            self.assertEqual(self.upload(payload).status_code, 400)
        for value in ("bad", "", base64.b64encode(b"not an image").decode()):
            self.assertEqual(self.upload({**self.payload, "person": value}).status_code, 400)
        self.assertFalse(self.jobs.jobs)

    def test_gpu_modes_and_invalid_outfit_metadata_are_not_supported(self):
        self.assertEqual(self.upload({**self.payload, "mode": "quality"}).status_code, 400)
        for garment in ({"category": "outerwear", "image": self.payload["person"]},
                        {"category": "tops", "image": self.payload["person"], "photo_type": "invalid"}):
            self.assertEqual(self.upload({**self.payload, "garments": [garment]}).status_code, 400)
        self.assertFalse(self.jobs.jobs)

    def test_malformed_and_excessively_nested_json_are_rejected_without_starting_jobs(self):
        depth = 10_000
        for raw in (b'{"consent":', b'[' * depth + b'0' + b']' * depth):
            result = self.client.post("/api/fittings/jobs/", raw, content_type="application/json",
                                      HTTP_AUTHORIZATION="Bearer " + self.token)
            self.assertEqual(result.status_code, 400)
            self.assertEqual(result.json()["error"], "INVALID_FITTING_REQUEST")
        self.assertFalse(self.jobs.jobs)

    def test_health_reports_nano_banana_without_gpu_or_weight_configuration(self):
        result = self.client.get("/api/fittings/health/", HTTP_AUTHORIZATION="Bearer " + self.token)
        self.assertEqual(result.status_code, 200)
        self.assertEqual(result.json()["model"], MODEL)
        self.assertEqual(result.json()["resolution"], "2K")
        self.assertNotIn("steps", result.json())

    @override_settings(OPENROUTER_API_KEY="")
    def test_missing_key_is_reported_without_creating_jobs(self):
        self.assertEqual(self.upload().status_code, 503)
        self.assertFalse(self.jobs.jobs)

    def test_queue_full_returns_retry_after_and_keeps_existing_jobs(self):
        self.jobs.capacity = 1
        self.assertEqual(self.upload().status_code, 202)
        result = self.upload()
        self.assertEqual(result.status_code, 429)
        self.assertEqual(result["Retry-After"], "5")
        self.assertEqual(len(self.jobs.jobs), 1)

    def test_all_supported_outfits_preserve_original_photos_and_outerwear_metadata(self):
        received = []
        def run(person, garments):
            received.append((person, list(garments)))
            return GeneratedImage(self.photo)
        self.jobs.runner = run
        for categories in (("tops",), ("bottoms",), ("tops", "bottoms"), ("tops", "bottoms", "outerwear")):
            with self.subTest(categories=categories):
                garments = [{"category": category, "image": self.payload["person"],
                             "photo_type": "model", "open_front": False} for category in categories]
                uploaded = self.upload({**self.payload, "garments": garments})
                self.assertEqual(uploaded.status_code, 202)
                self.jobs.run_next()
                self.assertEqual(received[-1], (self.photo, [(category, self.photo, "model", False) for category in categories]))

    def test_invalid_body_and_image_boundaries_never_enqueue_paid_work(self):
        bad_photos = [None, 10, "x" * (4 * 1024 * 1024 * 4 // 3 + 9)]
        output = io.BytesIO()
        Image.new("RGB", (2, 2)).save(output, "BMP")
        bad_photos.append(base64.b64encode(output.getvalue()).decode())
        for photo in bad_photos:
            self.assertEqual(self.upload({**self.payload, "person": photo}).status_code, 400)
        for garments in ([], {}, [None], [{"category": "tops", "image": self.payload["person"], "unexpected": True}],
                         [{"category": "tops", "image": self.payload["person"], "open_front": "true"}]):
            self.assertEqual(self.upload({**self.payload, "garments": garments}).status_code, 400)
        self.assertEqual(self.client.post("/api/fittings/jobs/", b"image", content_type="image/jpeg",
                        HTTP_AUTHORIZATION="Bearer " + self.token).status_code, 415)
        with patch("apps.fittings.views.MAX_REQUEST_BYTES", 8):
            self.assertEqual(self.upload().status_code, 413)
        self.assertFalse(self.jobs.jobs)

    def test_deleted_or_expired_jobs_are_not_retrievable_and_queued_deletion_prevents_generation(self):
        runner = self.jobs.runner = Mock(return_value=GeneratedImage(self.photo))
        for expired in (False, True):
            uploaded = self.upload().json()
            path = f'/api/fittings/jobs/{uploaded["id"]}/'
            headers = {"HTTP_AUTHORIZATION": "Bearer " + self.token, "HTTP_X_FITTING_TOKEN": uploaded["token"]}
            if expired:
                self.jobs.jobs[uploaded["id"]].created -= 1000
            else:
                self.assertEqual(self.client.delete(path, **headers).status_code, 204)
            for operation, suffix in ((self.client.get, ""), (self.client.get, "image/"), (self.client.delete, "")):
                self.assertEqual(operation(path + suffix, **headers).status_code, 404)
        self.assertFalse(self.jobs.run_next())
        runner.assert_not_called()


@override_settings(OPENROUTER_API_KEY="synthetic-only", FITTING_CONCURRENCY=2)
class FittingQueueInitializationTests(SimpleTestCase):
    def test_concurrent_requests_share_the_same_bounded_queue(self):
        with patch("apps.fittings.views._jobs", None), patch("apps.fittings.views.NanoBanana") as provider:
            provider.return_value = lambda *args: GeneratedImage(b"synthetic-result")
            with ThreadPoolExecutor(max_workers=10) as pool:
                queues = list(pool.map(lambda _: get_jobs(), range(10)))
            try:
                self.assertTrue(all(queue is queues[0] for queue in queues))
                provider.assert_called_once_with("synthetic-only")
                self.assertEqual(queues[0].concurrency, 2)
            finally:
                queues[0].close()

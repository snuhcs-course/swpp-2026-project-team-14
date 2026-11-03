"""Authenticated external-search API regression tests; the recommendation model is a test double."""

import json
import uuid
from types import SimpleNamespace
from unittest.mock import Mock, patch

from django.test import TestCase
from onnxruntime.capi.onnxruntime_pybind11_state import Fail, InvalidGraph, InvalidProtobuf, NoSuchFile, RuntimeException

from apps.users.auth import issue_token
from apps.users.models import User
from .models import Outfit
from .tests import garment


class ExternalMatchApiTests(TestCase):
    path = "/api/outfits/match-external/"

    def setUp(self):
        self.user = User.objects.create_user("external_owner", "test-password", preferred_styles=["minimal"])
        _, token = issue_token(self.user)
        self.client.defaults["HTTP_AUTHORIZATION"] = f"Bearer {token}"
        self.bottom = garment(self.user, "검정 바지", "bottom", "pants", "#202020")
        self.top = garment(self.user, "흰 셔츠", "top", "shirt", "#FFFFFF")
        self.outer = garment(self.user, "코트", "outerwear", "coat", "#795548")
        self.shoes = garment(self.user, "운동화", "shoes", "sneakers", "#FFFFFF")
        self.model = Mock()
        self.model.values = [("카테고리", "셔츠"), ("카테고리", "팬츠"), ("카테고리", "코트"),
                             ("색상", "블랙"), ("핏", "오버사이즈"), ("핏", "와이드")]
        self.model.slot_logprobs.return_value = {
            "카테고리": [4, 3, 9, float("-inf"), float("-inf"), float("-inf")],
            "색상": [float("-inf"), float("-inf"), float("-inf"), 1, float("-inf"), float("-inf")],
            "핏": [float("-inf"), float("-inf"), float("-inf"), float("-inf"), 9, 4],
        }
        self.models_patch = patch("apps.outfits.views.models", return_value=SimpleNamespace(masked=self.model))
        self.models = self.models_patch.start()
        self.addCleanup(self.models_patch.stop)

    def post(self, garment_id=None, **extra):
        return self.client.post(self.path, json.dumps({"garment_id": garment_id or str(self.bottom.id), **extra}),
                                content_type="application/json")

    def test_one_saved_item_is_enough_and_recommendation_does_not_save_an_outfit(self):
        self.top.delete()
        self.outer.delete()
        self.shoes.delete()
        response = self.post()
        self.assertEqual(response.status_code, 200, response.content)
        result = response.json()
        self.assertEqual(result["source_garment_id"], str(self.bottom.id))
        self.assertEqual(result["target_slot"], "top")
        self.assertEqual(result["attributes"], {"category": "셔츠", "color": "블랙", "fit": "오버사이즈", "pattern": "무지"})
        self.assertEqual(result["title"], "블랙 오버핏 셔츠")
        self.assertEqual(result["wardrobe_matches"], [])
        self.assertEqual(self.model.slot_logprobs.call_args.args[1], "모던")
        self.assertEqual(response["Cache-Control"], "no-store")
        self.assertFalse(Outfit.objects.exists())
        self.bottom.refresh_from_db()
        self.assertIsNone(self.bottom.labels)

    def test_top_and_outerwear_choose_the_expected_opposite_slot(self):
        for source, target in ((self.top, "bottom"), (self.outer, "top")):
            with self.subTest(source=source.attributes["category"]):
                response = self.post(str(source.id))
                self.assertEqual(response.status_code, 200, response.content)
                self.assertEqual(response.json()["target_slot"], target)

    def test_missing_other_account_and_draft_items_are_hidden_before_model_call(self):
        other = User.objects.create_user("external_other", "test-password")
        hidden = garment(other, "다른 계정 바지", "bottom", "pants", "#202020")
        draft = garment(self.user, "초안", "top", "shirt", "#FFFFFF", saved=False)
        for garment_id in (str(uuid.uuid4()), str(hidden.id), str(draft.id)):
            with self.subTest(garment_id=garment_id):
                response = self.post(garment_id)
                self.assertEqual(response.status_code, 404)
                self.assertEqual(response.json()["error"], "EXTERNAL_GARMENT_NOT_FOUND")
        self.models.assert_not_called()

    def test_shoes_are_rejected_without_loading_model(self):
        response = self.post(str(self.shoes.id))
        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["error"], "UNSUPPORTED_EXTERNAL_CATEGORY")
        self.models.assert_not_called()

    def test_authentication_and_method_are_required(self):
        self.client.defaults.pop("HTTP_AUTHORIZATION")
        self.assertEqual(self.post().status_code, 401)
        self.assertEqual(self.client.get(self.path).status_code, 405)
        self.models.assert_not_called()

    def test_bad_json_and_garment_identifiers_are_rejected(self):
        for value in ("not-a-uuid", 12, True, [], {}):
            with self.subTest(value=value):
                response = self.client.post(self.path, json.dumps({"garment_id": value}), content_type="application/json")
                self.assertEqual(response.status_code, 400)
        self.assertEqual(self.client.post(self.path, "{}", content_type="application/json").status_code, 400)
        self.assertEqual(self.client.post(self.path, "[", content_type="application/json").status_code, 400)
        self.assertEqual(self.client.post(self.path, "{}", content_type="text/plain").status_code, 415)
        self.models.assert_not_called()

    def test_missing_or_failed_model_is_retryable_without_database_changes(self):
        for failure in ("missing", "load", "inference"):
            with self.subTest(failure=failure), self.assertLogs("apps.outfits.views", level="ERROR"):
                self.models.side_effect = FileNotFoundError("unavailable") if failure == "load" else None
                self.models.return_value = SimpleNamespace(masked=None if failure == "missing" else self.model)
                self.model.slot_logprobs.side_effect = Fail("unavailable") if failure == "inference" else None
                response = self.post()
                self.assertEqual(response.status_code, 503)
                self.assertEqual(response.json()["error"], "EXTERNAL_MODEL_UNAVAILABLE")
                self.assertIn("다시 시도", response.json()["hint"])
        self.assertFalse(Outfit.objects.exists())

    def test_corrupt_model_files_are_retryable(self):
        errors = [json.JSONDecodeError("invalid model metadata", "{", 0),
                  InvalidGraph("invalid graph"), InvalidProtobuf("invalid protobuf"), NoSuchFile("missing model")]
        for error in errors:
            with self.subTest(error=type(error).__name__), self.assertLogs("apps.outfits.views", level="ERROR"):
                self.models.side_effect = error
                self.assertEqual(self.post().status_code, 503)
        self.assertFalse(Outfit.objects.exists())

    def test_expected_runtime_failure_is_retryable(self):
        self.model.slot_logprobs.side_effect = RuntimeException("execution failed")
        with self.assertLogs("apps.outfits.views", level="ERROR"):
            self.assertEqual(self.post().status_code, 503)

    def test_unexpected_loading_and_inference_bugs_are_not_hidden_as_503(self):
        for error in (RuntimeError("code bug"), KeyError("missing variable"), AttributeError("code bug")):
            with self.subTest(stage="loading", error=type(error).__name__):
                self.models.side_effect = error
                with self.assertRaises(type(error)):
                    self.post()
            self.models.side_effect = None
            with self.subTest(stage="inference", error=type(error).__name__):
                self.model.slot_logprobs.side_effect = error
                with self.assertRaises(type(error)):
                    self.post()
            self.model.slot_logprobs.side_effect = None

    def test_wardrobe_matches_include_only_own_saved_target_slot_garments(self):
        other = User.objects.create_user("external_other_owner", "test-password")
        garment(other, "남의 셔츠", "top", "shirt", "#202020")
        garment(self.user, "초안 셔츠", "top", "shirt", "#202020", saved=False)
        response = self.post()
        self.assertEqual(response.status_code, 200, response.content)
        ids = [match["garment_id"] for match in response.json()["wardrobe_matches"]]
        self.assertEqual(ids, [str(self.top.id)])

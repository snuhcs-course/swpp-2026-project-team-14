# AI-generated with Claude Code (Claude Opus 5.5), 2026-11-12, reviewed by Kihwan Kim
"""옷장 분석 API 테스트. 추천 모델은 테스트 대역(Mock)이라 집계·권한·오류 처리만 본다."""

import json
from types import SimpleNamespace
from unittest.mock import Mock, patch

from django.test import TestCase
from onnxruntime.capi.onnxruntime_pybind11_state import Fail, InvalidGraph, InvalidProtobuf, NoSuchFile, RuntimeException

from apps.users.auth import issue_token
from apps.users.models import User
from .models import Outfit
from .tests import garment

NEG = float("-inf")


class WardrobeGapsApiTests(TestCase):
    path = "/api/outfits/wardrobe-gaps/"

    def setUp(self):
        self.user = User.objects.create_user("gaps_owner", "test-password", preferred_styles=["minimal"])
        _, token = issue_token(self.user)
        self.client.defaults["HTTP_AUTHORIZATION"] = f"Bearer {token}"
        self.shirt = garment(self.user, "흰 셔츠", "top", "shirt", "#FFFFFF")
        self.tee = garment(self.user, "검정 티", "top", "tshirt", "#202020")
        self.slacks = garment(self.user, "검정 바지", "bottom", "pants", "#202020")
        self.jeans = garment(self.user, "청바지", "bottom", "jeans", "#3975C6")
        self.coat = garment(self.user, "코트", "outerwear", "coat", "#795548")
        self.shoes = garment(self.user, "운동화", "shoes", "sneakers", "#FFFFFF")
        # 모든 옷이 같은 예측: 상의 대상이면 그레이 니트, 하의 대상이면 그레이 팬츠 (허용 후보로 갈림)
        self.model = Mock()
        self.model.values = [("카테고리", "니트웨어"), ("카테고리", "팬츠"), ("색상", "그레이"),
                             ("핏", "노멀"), ("핏", "와이드")]
        self.model.slot_logprobs.return_value = {
            "카테고리": [3, 3, NEG, NEG, NEG],
            "색상": [NEG, NEG, 1, NEG, NEG],
            "핏": [NEG, NEG, NEG, 5, 4],
        }
        self.models_patch = patch("apps.outfits.views.models", return_value=SimpleNamespace(masked=self.model))
        self.models = self.models_patch.start()
        self.addCleanup(self.models_patch.stop)

    def get(self):
        return self.client.get(self.path)

    def test_whole_wardrobe_is_analysed_without_saving_anything(self):
        response = self.get()
        self.assertEqual(response.status_code, 200, response.content)
        result = response.json()
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["garment_count"], 6)  # 신발도 옷장 벌 수에는 들어간다
        self.assertEqual(result["slot_counts"], {"top": 2, "bottom": 2, "outerwear": 1})
        self.assertEqual([(gap["title"], gap["slot"], gap["vote_count"]) for gap in result["gaps"]],
                         [("그레이 레귤러핏 니트", "top", 3), ("그레이 레귤러핏 팬츠", "bottom", 2)])
        knit = result["gaps"][0]
        self.assertEqual(knit["attributes"], {"category": "니트웨어", "color": "그레이", "fit": "노멀", "pattern": "무지"})
        self.assertEqual(knit["reason"], "내 옷 3벌과 잘 어울리는데, 비슷한 옷이 옷장에 없어요.")
        self.assertEqual({match["garment_id"] for match in knit["matched_garments"]},
                         {str(self.slacks.id), str(self.jeans.id), str(self.coat.id)})
        self.assertIn({"garment_id": str(self.coat.id), "name": "코트",
                       "image_url": f"/api/wardrobe/items/{self.coat.id}/image/"}, knit["matched_garments"])
        self.assertTrue(knit["search_url"].startswith("https://www.musinsa.com/search/goods?keyword="))
        self.assertEqual({call.args[1] for call in self.model.slot_logprobs.call_args_list}, {"모던"})
        self.assertEqual(self.model.slot_logprobs.call_count, 5)  # 신발은 예측하지 않는다
        self.assertEqual(response["Cache-Control"], "no-store")
        self.assertFalse(Outfit.objects.exists())
        self.slacks.refresh_from_db()
        self.assertIsNone(self.slacks.labels)

    def test_other_accounts_and_drafts_are_not_part_of_the_wardrobe(self):
        other = User.objects.create_user("gaps_other", "test-password")
        hidden = garment(other, "남의 바지", "bottom", "pants", "#202020")
        draft = garment(self.user, "초안 바지", "bottom", "slacks", "#24344B", saved=False)
        result = self.get().json()
        self.assertEqual(result["garment_count"], 6)
        ids = {match["garment_id"] for gap in result["gaps"] for match in gap["matched_garments"]}
        self.assertNotIn(str(hidden.id), ids)
        self.assertNotIn(str(draft.id), ids)

    def test_owned_kind_is_not_recommended_and_nothing_to_buy_is_reported(self):
        garment(self.user, "회색 니트", "top", "knitwear", "#808080")
        garment(self.user, "회색 바지", "bottom", "pants", "#808080")
        result = self.get().json()
        self.assertEqual(result["status"], "enough")
        self.assertEqual(result["gaps"], [])

    def test_small_wardrobe_is_reported_without_loading_the_model(self):
        for item in (self.tee, self.jeans, self.coat):
            item.delete()
        response = self.get()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"garment_count": 3, "status": "too_few",
                                           "slot_counts": {"top": 1, "bottom": 1, "outerwear": 0}, "gaps": []})
        self.models.assert_not_called()

    def test_authentication_and_method_are_required(self):
        self.assertEqual(self.client.post(self.path).status_code, 405)
        self.client.defaults.pop("HTTP_AUTHORIZATION")
        self.assertEqual(self.get().status_code, 401)
        self.models.assert_not_called()

    def test_missing_or_failed_model_is_retryable_without_database_changes(self):
        for failure in ("missing", "load", "inference", "runtime"):
            with self.subTest(failure=failure), self.assertLogs("apps.outfits.views", level="ERROR"):
                self.models.side_effect = FileNotFoundError("unavailable") if failure == "load" else None
                self.models.return_value = SimpleNamespace(masked=None if failure == "missing" else self.model)
                self.model.slot_logprobs.side_effect = {"inference": Fail("x"), "runtime": RuntimeException("x")}.get(failure)
                response = self.get()
                self.assertEqual(response.status_code, 503)
                self.assertEqual(response.json()["error"], "WARDROBE_GAPS_UNAVAILABLE")
                self.assertIn("다시 시도", response.json()["hint"])
        self.assertFalse(Outfit.objects.exists())

    def test_corrupt_model_files_are_retryable(self):
        errors = [json.JSONDecodeError("invalid model metadata", "{", 0),
                  InvalidGraph("invalid graph"), InvalidProtobuf("invalid protobuf"), NoSuchFile("missing model")]
        for error in errors:
            with self.subTest(error=type(error).__name__), self.assertLogs("apps.outfits.views", level="ERROR"):
                self.models.side_effect = error
                self.assertEqual(self.get().status_code, 503)

    def test_unexpected_bugs_are_not_hidden_as_503(self):
        self.model.slot_logprobs.side_effect = KeyError("code bug")
        with self.assertRaises(KeyError):
            self.get()

"""채팅 추천 만족도(좋아요/별로예요) API와 만족도 집계 명령 테스트."""

import json
from io import StringIO

from django.core.management import call_command
from django.test import TestCase

from apps.users.auth import issue_token
from apps.users.models import User
from .management.commands.chat_satisfaction import satisfaction
from .models import Outfit
from .tests import garment
from .views import _create, _members


class ChatFeedbackApiTests(TestCase):
    path = "/api/outfits/chat/feedback/"

    def setUp(self):
        self.user = User.objects.create_user("chat_feedback_owner", "test-password")
        _, token = issue_token(self.user)
        self.client.defaults["HTTP_AUTHORIZATION"] = f"Bearer {token}"
        self.tee = garment(self.user, "흰 티", "top", "tshirt", "#FFFFFF")
        self.knit = garment(self.user, "베이지 니트", "top", "knitwear", "#D6BE9A")
        self.jeans = garment(self.user, "청바지", "bottom", "jeans", "#3975C6")
        self.coat = garment(self.user, "갈색 코트", "outerwear", "coat", "#795548")
        self.ids = [str(self.tee.id), str(self.jeans.id)]

    def post(self, feedback, ids=None, preference=None, **extra):
        body = {"garment_ids": self.ids if ids is None else ids, "feedback": feedback, "preference": preference, **extra}
        return self.client.post(self.path, json.dumps(body), content_type="application/json")

    def chat_rows(self):
        return Outfit.objects.filter(user=self.user, source=Outfit.Source.CHAT)

    def test_like_stores_an_unsaved_chat_outfit_and_raises_taste_weights(self):
        response = self.post("liked")
        self.assertEqual(response.status_code, 200, response.content)
        result = response.json()
        self.assertEqual(result["outfit"]["feedback"], "liked")
        self.assertEqual(result["outfit"]["source"], "chat")
        self.assertFalse(result["outfit"]["saved"])  # 코디북에는 들어가지 않음
        self.assertEqual({i["garment_id"] for i in result["outfit"]["items"]}, set(self.ids))
        weights = result["preference"]["weights"]
        self.assertTrue(weights and all(v > 0 for v in weights.values()))
        self.assertEqual(self.client.get("/api/outfits/").json()["outfits"], [])

    def test_same_combination_reuses_one_row_regardless_of_id_order(self):
        first = self.post("liked").json()
        second = self.post("liked", ids=list(reversed(self.ids)), preference=first["preference"]).json()
        self.assertEqual(self.chat_rows().count(), 1)
        self.assertEqual(first["outfit"]["id"], second["outfit"]["id"])
        # 같은 값을 다시 보내면 가중치가 그대로 (홈 좋아요와 같은 규칙)
        self.assertEqual(second["preference"]["weights"], first["preference"]["weights"])

    def test_dislike_after_like_lowers_weights_of_the_same_garments(self):
        liked = self.post("liked").json()
        disliked = self.post("disliked", preference=liked["preference"]).json()
        self.assertEqual(disliked["outfit"]["feedback"], "disliked")
        self.assertTrue(all(disliked["preference"]["weights"].get(k, 0) < v
                            for k, v in liked["preference"]["weights"].items()))

    def test_cancel_clears_feedback_without_moving_weights(self):
        liked = self.post("liked").json()
        cleared = self.post(None, preference=liked["preference"]).json()
        self.assertIsNone(cleared["outfit"]["feedback"])
        self.assertEqual(cleared["preference"]["weights"], liked["preference"]["weights"])

    def test_cancel_without_previous_feedback_creates_nothing(self):
        response = self.post(None)
        self.assertEqual(response.status_code, 404)
        self.assertEqual(response.json()["error"], "CHAT_FEEDBACK_NOT_FOUND")
        self.assertFalse(self.chat_rows().exists())

    def test_feedback_attaches_to_the_chat_outfit_already_saved_in_the_codibook(self):
        saved = self.client.post("/api/outfits/", json.dumps({"garment_ids": self.ids, "source": "chat"}),
                                 content_type="application/json").json()
        result = self.post("liked").json()
        self.assertEqual(result["outfit"]["id"], saved["id"])
        self.assertEqual(self.chat_rows().count(), 1)

    def test_outerwear_combination_is_a_different_outfit(self):
        self.post("liked")
        self.post("disliked", ids=[*self.ids, str(self.coat.id)])
        self.assertEqual(self.chat_rows().count(), 2)

    def test_invalid_requests_are_rejected_without_creating_rows(self):
        other = User.objects.create_user("chat_feedback_other", "test-password")
        foreign = garment(other, "남의 바지", "bottom", "jeans", "#202020")
        draft = garment(self.user, "초안", "bottom", "jeans", "#202020", saved=False)
        cases = {
            "bad_value": ("meh", self.ids),
            "two_tops": ("liked", [str(self.tee.id), str(self.knit.id), str(self.jeans.id)]),
            "no_bottom": ("liked", [str(self.tee.id)]),
            "foreign_garment": ("liked", [str(self.tee.id), str(foreign.id)]),
            "draft_garment": ("liked", [str(self.tee.id), str(draft.id)]),
            "not_uuid": ("liked", [str(self.tee.id), "not-a-uuid"]),
        }
        for case, (value, ids) in cases.items():
            with self.subTest(case=case):
                response = self.post(value, ids=ids)
                self.assertEqual(response.status_code, 400)
                self.assertEqual(response.json()["error"], "INVALID_FEEDBACK")
        self.assertFalse(self.chat_rows().exists())
        self.assertEqual(self.client.post(self.path, "{}", content_type="text/plain").status_code, 415)
        oversized = json.dumps({"garment_ids": self.ids, "feedback": "liked", "padding": "x" * 70_000})
        self.assertEqual(self.client.post(self.path, oversized, content_type="application/json").status_code, 413)

    def test_authentication_and_method_are_required(self):
        self.assertEqual(self.client.get(self.path).status_code, 405)
        self.client.defaults.pop("HTTP_AUTHORIZATION")
        self.assertEqual(self.post("liked").status_code, 401)
        self.assertFalse(self.chat_rows().exists())


class ChatSatisfactionCommandTests(TestCase):
    def setUp(self):
        self.user = User.objects.create_user("satisfaction_owner", "test-password")
        tops = [garment(self.user, f"상의{i}", "top", "tshirt", "#FFFFFF") for i in range(4)]
        self.jeans = garment(self.user, "청바지", "bottom", "jeans", "#3975C6")
        self.tops = tops

    def outfit(self, top, source, feedback):
        outfit = _create(source, _members(self.user, [str(top.id), str(self.jeans.id)]), user=self.user)
        outfit.feedback = feedback
        outfit.save()

    def test_rate_counts_only_rated_chat_outfits(self):
        self.outfit(self.tops[0], Outfit.Source.CHAT, "liked")
        self.outfit(self.tops[1], Outfit.Source.CHAT, "disliked")
        self.outfit(self.tops[2], Outfit.Source.CHAT, None)  # 만족도를 안 남긴 채팅 코디는 제외
        self.outfit(self.tops[3], Outfit.Source.RECOMMENDED, "liked")  # 홈 추천 좋아요는 제외
        result = satisfaction()
        self.assertEqual((result["liked"], result["disliked"], result["total"]), (1, 1, 2))
        self.assertEqual(result["rate"], 0.5)
        self.assertTrue(result["passed"])  # 기준 50% "이상"

    def test_below_threshold_fails(self):
        self.outfit(self.tops[0], Outfit.Source.CHAT, "liked")
        self.outfit(self.tops[1], Outfit.Source.CHAT, "disliked")
        self.outfit(self.tops[2], Outfit.Source.CHAT, "disliked")
        result = satisfaction()
        self.assertAlmostEqual(result["rate"], 1 / 3)
        self.assertFalse(result["passed"])
        out = StringIO()
        call_command("chat_satisfaction", stdout=out)
        self.assertIn("만족도 33.3% (기준 50% 이상) → 미달", out.getvalue())

    def test_no_responses_is_reported_without_a_verdict(self):
        result = satisfaction()
        self.assertIsNone(result["rate"])
        self.assertFalse(result["passed"])
        out = StringIO()
        call_command("chat_satisfaction", stdout=out)
        self.assertIn("응답이 아직 없습니다", out.getvalue())

    def test_passing_result_is_reported(self):
        self.outfit(self.tops[0], Outfit.Source.CHAT, "liked")
        out = StringIO()
        call_command("chat_satisfaction", stdout=out)
        self.assertIn("만족도 100.0% (기준 50% 이상) → 통과", out.getvalue())

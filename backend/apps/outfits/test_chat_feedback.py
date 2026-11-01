"""채팅 추천 만족도(좋아요/별로예요) API와 만족도 집계 명령 테스트."""

import json
from datetime import timedelta
from io import StringIO

from django.core.management import call_command, CommandError
from django.test import TestCase
from django.utils import timezone

from apps.users.auth import issue_token
from apps.users.models import User
from .management.commands.chat_satisfaction import satisfaction, MIN_RESPONSES
from .models import Outfit, FeedbackRecord, FeedbackTaste
from recommendation import preference
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

    def test_cancel_reverts_the_chat_contribution(self):
        liked = self.post("liked").json()
        cleared = self.post(None, preference=liked["preference"]).json()
        self.assertIsNone(cleared["outfit"]["feedback"])
        self.assertEqual(cleared["preference"]["weights"], {})
        self.assertEqual(cleared["preference"]["liked"], [])

    def test_like_save_cancel_and_relike_use_one_outfit_and_one_rating(self):
        first = self.post("liked").json()
        payload = {"garment_ids": self.ids, "source": "chat", "context": {"chat_save_id": "save-one"}}
        saved = self.client.post("/api/outfits/", json.dumps(payload), content_type="application/json")
        self.assertEqual(saved.status_code, 201)
        self.assertEqual(saved.json()["id"], first["outfit"]["id"])
        self.assertTrue(saved.json()["saved"])
        self.assertEqual(self.chat_rows().count(), 1)
        cleared = self.post(None, preference=first["preference"]).json()
        self.assertEqual(FeedbackRecord.objects.filter(channel="chat", feedback__isnull=False).count(), 0)
        self.assertEqual(cleared["preference"]["weights"], {})
        again = self.post("liked", preference=cleared["preference"]).json()
        self.assertEqual(again["preference"]["weights"], first["preference"]["weights"])
        self.assertEqual(FeedbackRecord.objects.filter(channel="chat").count(), 1)

    def test_all_save_retry_tokens_remain_bound_to_the_original_combination(self):
        self.post("liked")
        for token in ("first", "second", "third"):
            payload = {"garment_ids": self.ids, "source": "chat", "context": {"chat_save_id": token}}
            self.assertIn(self.client.post("/api/outfits/", json.dumps(payload), content_type="application/json").status_code,
                          (200, 201))
        for token in ("first", "second", "third"):
            payload = {"garment_ids": [str(self.knit.id), str(self.jeans.id)], "source": "chat",
                       "context": {"chat_save_id": token}}
            self.assertEqual(self.client.post("/api/outfits/", json.dumps(payload), content_type="application/json").status_code, 409)
        self.assertEqual(self.chat_rows().count(), 1)

    def test_legacy_duplicate_outfits_cannot_split_the_current_chat_rating(self):
        first = self.post("liked").json()
        _create(Outfit.Source.CHAT, _members(self.user, self.ids), user=self.user, saved=True, feedback="liked")
        cleared = self.post(None, preference=first["preference"]).json()
        self.assertIsNone(cleared["outfit"]["feedback"])
        self.assertFalse(self.chat_rows().filter(feedback__isnull=False).exists())
        self.assertEqual(FeedbackRecord.objects.filter(channel="chat").count(), 1)

    def test_cancel_preserves_later_home_feedback_and_replays_clipping_and_decay(self):
        baseline = {"weights": {"색상:화이트": 1.95, "별도": 0.8}, "liked": [], "excluded": []}
        liked = self.post("liked", preference=baseline).json()
        home = _create(Outfit.Source.RECOMMENDED, _members(self.user, [str(self.knit.id), str(self.jeans.id)]), user=self.user)
        response = self.client.post(f"/api/outfits/{home.id}/feedback/",
                                    json.dumps({"feedback": "liked", "preference": liked["preference"]}),
                                    content_type="application/json").json()
        cleared = self.post(None, preference=response["preference"]).json()
        event = FeedbackRecord.objects.get(channel="outfit")
        expected = preference.apply_feedback(FeedbackTaste.objects.get(user=self.user).baseline,
            [{"item_id": gid, "polarity": 1} for gid in event.garments], event.garments)
        self.assertEqual(cleared["preference"]["weights"], expected["weights"])
        self.assertEqual(cleared["preference"]["liked"], expected["liked"])

    def test_switching_rating_replaces_its_effect_and_retry_returns_server_taste(self):
        self.post("liked")
        disliked = self.post("disliked").json()
        retry = self.post("disliked", preference=None).json()
        self.assertEqual(disliked["preference"]["weights"], retry["preference"]["weights"])
        self.assertTrue(all(v < 0 for v in retry["preference"]["weights"].values()))
        self.assertEqual(FeedbackRecord.objects.filter(channel="chat").count(), 1)

    def test_cancelling_one_chat_rating_preserves_another_chat_rating(self):
        first = self.post("liked").json()
        second_ids = [str(self.knit.id), str(self.jeans.id)]
        second = self.post("liked", ids=second_ids, preference=first["preference"]).json()
        cleared = self.post(None, preference=second["preference"]).json()
        remaining = FeedbackRecord.objects.get(channel="chat", feedback__isnull=False)
        expected = preference.apply_feedback(preference.empty(),
            [{"item_id": gid, "polarity": 1} for gid in remaining.garments], remaining.garments)
        self.assertEqual(cleared["preference"]["weights"], expected["weights"])
        self.assertEqual(cleared["preference"]["liked"], expected["liked"])

    def test_retry_does_not_move_an_old_rating_into_the_he_period(self):
        self.post("liked")
        old = timezone.now() - timedelta(days=10)
        FeedbackRecord.objects.filter(user=self.user, channel="chat").update(rated_at=old)
        self.post("liked")
        self.assertEqual(FeedbackRecord.objects.get(user=self.user, channel="chat").rated_at, old)

    def test_codibook_rating_does_not_change_chat_rating_or_its_timestamp(self):
        liked = self.post("liked").json()
        event = FeedbackRecord.objects.get(channel="chat")
        original_time = event.rated_at
        self.client.post(f"/api/outfits/{liked['outfit']['id']}/feedback/",
                         json.dumps({"feedback": "disliked", "preference": liked["preference"]}),
                         content_type="application/json")
        event.refresh_from_db()
        self.assertEqual(event.feedback, "liked")
        self.assertEqual(event.rated_at, original_time)
        self.assertEqual(self.client.post(self.path + "state/", json.dumps({"garment_ids": self.ids}),
                                         content_type="application/json").json()["feedback"], "liked")

    def test_state_lookup_is_authenticated_owner_scoped_and_has_no_side_effects(self):
        state = self.path + "state/"
        def lookup(ids):
            return self.client.post(state, json.dumps({"garment_ids": ids}), content_type="application/json")
        self.assertIsNone(lookup(self.ids).json()["feedback"])
        self.assertFalse(Outfit.objects.exists())
        self.assertFalse(FeedbackTaste.objects.exists())
        self.post("liked")
        self.assertEqual(lookup(list(reversed(self.ids))).json()["feedback"], "liked")
        other = User.objects.create_user("state_other", "test-password")
        foreign = garment(other, "남의 상의", "top", "tshirt", "#FFFFFF")
        self.assertEqual(lookup([str(foreign.id), str(self.jeans.id)]).status_code, 400)
        self.assertEqual(lookup(["bad"]).status_code, 400)
        self.assertEqual(self.client.get(state).status_code, 405)
        self.client.defaults.pop("HTTP_AUTHORIZATION")
        self.assertEqual(lookup(self.ids).status_code, 401)

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
        self.since = timezone.now() - timedelta(hours=1)

    def rating(self, feedback="liked", *, channel="chat", user=None, at=None):
        return FeedbackRecord.objects.create(user=user or self.user, channel=channel,
            key=f"combination:{FeedbackRecord.objects.count()}", feedback=feedback,
            rated_at=at or self.since + timedelta(minutes=30))

    def result(self, **options):
        return satisfaction(since=self.since, participants=[self.user.username], **options)

    def command(self, **options):
        out = StringIO()
        call_command("chat_satisfaction", since=self.since.isoformat(), participants=[self.user.username], stdout=out, **options)
        return out.getvalue()

    def test_exact_minimum_and_threshold_pass(self):
        for i in range(MIN_RESPONSES):
            self.rating("liked" if i < MIN_RESPONSES / 2 else "disliked")
        result = self.result()
        self.assertEqual(result["rate"], 0.5)
        self.assertTrue(result["passed"])
        self.assertIn("→ PASS", self.command())

    def test_below_threshold_fails(self):
        for i in range(MIN_RESPONSES):
            self.rating("liked" if i == 0 else "disliked")
        result = self.result()
        self.assertFalse(result["passed"])
        self.assertEqual(result["verdict"], "FAIL")
        self.assertIn("→ FAIL", self.command())

    def test_no_responses_is_reported_without_a_verdict(self):
        result = self.result()
        self.assertIsNone(result["rate"])
        self.assertFalse(result["passed"])
        self.assertIn("→ INCOMPLETE", self.command())

    def test_one_like_and_minimum_minus_one_are_incomplete(self):
        for _ in range(MIN_RESPONSES - 1):
            self.rating()
            self.assertEqual(self.result()["verdict"], "INCOMPLETE")
        self.assertIn("100.0%", self.command())
        self.assertIn("→ INCOMPLETE", self.command())

    def test_only_in_period_allowlisted_current_chat_ratings_count(self):
        end = timezone.now() + timedelta(minutes=1)
        self.rating(at=self.since)
        self.rating("disliked")
        self.rating(None)
        self.rating(channel="outfit")
        self.rating(at=self.since - timedelta(seconds=1))
        self.rating(at=end)
        team = User.objects.create_user("team_test", "test-password")
        self.rating(user=team)
        result = self.result(until=end)
        self.assertEqual((result["liked"], result["disliked"], result["total"]), (1, 1, 2))

    def test_command_rejects_invalid_periods_unknown_accounts_and_missing_scope(self):
        for value in ("bad", "2026-11-01T00:00:00", "2026-13-99T00:00:00+09:00"):
            with self.subTest(value=value), self.assertRaises(CommandError):
                call_command("chat_satisfaction", since=value, participants=[self.user.username])
        with self.assertRaises(CommandError):
            self.command(until=(self.since - timedelta(seconds=1)).isoformat())
        with self.assertRaises(CommandError):
            call_command("chat_satisfaction", since=self.since.isoformat(), participants=["missing_user"])
        with self.assertRaises(CommandError):
            call_command("chat_satisfaction")

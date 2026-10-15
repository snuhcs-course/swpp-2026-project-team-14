# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
"""Outfit API tests on the in-memory database with the shipped models; no network, photos or AI key."""
import json
import tempfile
from unittest.mock import patch

from django.test import SimpleTestCase, TestCase, override_settings
import uuid

from apps.wardrobe.models import Garment
from apps.users.auth import issue_token
from apps.users.models import User
from recommendation import weather

from .models import Outfit

BODY = {"height_cm": 162, "gender": "female", "preferred_fit": "loose", "preferred_styles": ["미니멀"],
        "measurements": [{"type": "chest", "value_cm": 84}, {"type": "waist", "value_cm": 68},
                         {"type": "hip", "value_cm": 94}, {"type": "shoulder_width", "value_cm": 38},
                         {"type": "torso_length", "value_cm": 44}, {"type": "sleeve_length", "value_cm": 56},
                         {"type": "inseam", "value_cm": 72}]}


def dims(**values):
    return {"unit": "cm", **{k: {"value": v, "source": "arcore_manual", "method": "unspecified", "reference": None}
                             for k, v in values.items()}}


def garment(user, name, category, sub, color, saved=True, labels=None, **measured):
    attributes = {"name": name, "category": category, "subcategory": sub, "colors": [color], "styles": ["minimal"],
                  "fit_type": None, "leg_shape": None, "rise_type": None, "skirt_shape": None}
    return Garment.objects.create(user=user, image="garments/x.jpg", attributes=attributes, dimensions=dims(**measured) if measured else None,
                                  labels=labels, saved=saved)


class OutfitApiTests(TestCase):
    def setUp(self):
        self.user = User.objects.create_user('outfit_owner', 'test-password')
        _, token = issue_token(self.user)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        setting = override_settings(MEDIA_ROOT=directory.name)
        setting.enable()
        self.addCleanup(setting.disable)
        self.knit = garment(self.user, "베이지 니트", "top", "knitwear", "#D6BE9A", chest_width_half=50, total_length=64, sleeve_length=58)
        self.tee = garment(self.user, "흰 티", "top", "tshirt", "#FFFFFF", chest_width_half=47, total_length=62, sleeve_length=20)
        self.small = garment(self.user, "작은 셔츠", "top", "shirt", "#3975C6", chest_width_half=40)
        self.slacks = garment(self.user, "검정 슬랙스", "bottom", "slacks", "#202020", hip_width_half=53, thigh_width_half=32,
                              hem_opening=29, inseam=70)
        self.jeans = garment(self.user, "청바지", "bottom", "jeans", "#3975C6", hip_width_half=48, thigh_width_half=29,
                             hem_opening=16, inseam=70)
        self.coat = garment(self.user, "갈색 코트", "outerwear", "coat", "#795548", chest_width_half=54, total_length=95, sleeve_length=60)
        self.draft = garment(self.user, "저장 안 한 옷", "top", "tshirt", "#202020", saved=False)
        self.shoes = garment(self.user, "운동화", "shoes", "sneakers", "#FFFFFF")

    def post(self, url, data):
        return self.client.post(url, json.dumps(data), content_type="application/json")

    def recommend(self, **extra):
        return self.post("/api/outfits/recommend/", {"body": BODY, "temp": 13, **extra})

    def test_recommends_outfits_from_my_saved_garments_only(self):
        other = User.objects.create_user('other_owner', 'test-password')
        garment(other, "다른 사람의 티셔츠", "top", "tshirt", "#FFFFFF", chest_width_half=50)
        garment(other, "다른 사람의 청바지", "bottom", "jeans", "#3975C6", hip_width_half=50)
        response = self.recommend()
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(len(data["outfits"]), 3)
        allowed = {str(g.id) for g in (self.knit, self.tee, self.slacks, self.jeans, self.coat)}
        for outfit in data["outfits"]:
            ids = {i["garment_id"] for i in outfit["items"]}
            self.assertLessEqual(ids, allowed)  # never the too-small shirt, a draft or shoes
            self.assertTrue({"top", "bottom"} <= {i["slot"] for i in outfit["items"]})
            self.assertEqual(outfit["source"], "recommended")
            self.assertFalse(outfit["saved"])
            self.assertTrue(0 <= outfit["score"] <= 100 and outfit["reasons"])
            self.assertTrue(all(i["image_url"].startswith("/api/wardrobe/items/") for i in outfit["items"]))
        self.assertTrue(any("작은 셔츠" in n for n in data["notes"]))
        self.assertEqual(data["context"]["pref_fit"], "loose")  # from the stored profile
        self.assertEqual(data["context"]["styles"], ["미니멀"])
        self.assertEqual(Outfit.objects.count(), 3)
        self.assertEqual(Outfit.objects.filter(user=self.user).count(), 3)
        self.assertEqual(response["Cache-Control"], "no-store")

    def test_labels_drive_the_fit_note_and_seen_outfits_rotate(self):
        labels = {"fit": "오버사이즈", "fit_basis": "직접 수정", "kf_category": "니트웨어", "kf_colors": ["베이지"],
                  "sleeve": "긴팔", "materials": ["니트"], "too_small": False, "confirmed": True}
        self.knit.labels = labels
        self.knit.save()
        first = self.recommend(count=1).json()["outfits"][0]
        notes = {i["garment_id"]: i["note"] for i in first["items"]}
        if str(self.knit.id) in notes:
            self.assertIn("오버사이즈 (직접 수정)", notes[str(self.knit.id)])
        second = self.recommend(count=1, seen_keys=[first["key"]]).json()["outfits"][0]
        self.assertNotEqual(second["key"], first["key"])

    def test_weather_object_and_anchor(self):
        response = self.recommend(temp=None, weather={"temp": 30, "apparent": 31, "rain": True, "snow": False,
                                                      "label": "비"}, anchor_id=str(self.jeans.id), count=2)
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual((data["context"]["temp"], data["context"]["wet"]), (31, "비"))
        self.assertTrue(all(str(self.jeans.id) in {i["garment_id"] for i in o["items"]} for o in data["outfits"]))
        self.assertFalse(any(i["slot"] == "outerwear" for o in data["outfits"] for i in o["items"]))

    def test_errors(self):
        self.assertEqual(self.recommend(body=None).status_code, 400)
        self.assertEqual(self.recommend(temp=99).status_code, 400)
        self.assertEqual(self.recommend(pref_fit="tight").status_code, 400)
        self.assertEqual(self.recommend(styles=["없는스타일"]).status_code, 400)
        self.assertEqual(self.recommend(preference={"weights": {"색상:블랙": "a"}}).status_code, 400)
        self.assertEqual(self.client.post("/api/outfits/recommend/", "{}", content_type="text/plain").status_code, 415)
        missing = self.recommend(anchor_id=str(self.small.id))
        self.assertEqual((missing.status_code, missing.json()["error"]), (422, "ANCHOR_TOO_SMALL"))
        Garment.objects.filter(attributes__category="bottom").update(saved=False)
        empty = self.recommend()
        self.assertEqual((empty.status_code, empty.json()["error"]), (422, "WARDROBE_TOO_SMALL"))

    def test_save_list_feedback(self):
        outfit = self.recommend(count=1).json()["outfits"][0]
        url = f"/api/outfits/{outfit['id']}/"
        response = self.client.put(url, json.dumps({"saved": True, "name": "월요일 코디"}), content_type="application/json")
        self.assertEqual((response.status_code, response.json()["saved"], response.json()["name"]), (200, True, "월요일 코디"))
        self.assertEqual([o["id"] for o in self.client.get("/api/outfits/").json()["outfits"]], [outfit["id"]])

        liked = self.post(url + "feedback/", {"feedback": "liked", "preference": None}).json()
        self.assertEqual(liked["outfit"]["feedback"], "liked")
        weights = liked["preference"]["weights"]
        self.assertTrue(weights and all(v > 0 for v in weights.values()))
        again = self.post(url + "feedback/", {"feedback": "liked", "preference": liked["preference"]}).json()
        self.assertEqual(again["preference"]["weights"], weights)  # repeating the same feedback changes nothing
        disliked = self.post(url + "feedback/", {"feedback": "disliked", "preference": liked["preference"]}).json()
        self.assertTrue(all(disliked["preference"]["weights"].get(k, 0) < v for k, v in weights.items()))
        self.assertEqual(self.post(url + "feedback/", {"feedback": "meh"}).status_code, 400)
        self.assertEqual(self.client.put(url, json.dumps({"extra": 1}), content_type="application/json").status_code, 400)

    def test_create_outfit_from_garment_ids(self):
        ids = [str(self.tee.id), str(self.jeans.id), str(self.coat.id)]
        response = self.post("/api/outfits/", {"garment_ids": ids, "source": "chat", "name": "채팅 코디"})
        self.assertEqual(response.status_code, 201)
        self.assertEqual([i["slot"] for i in response.json()["items"]], ["outerwear", "top", "bottom"])
        self.assertTrue(response.json()["saved"])
        bad = {
            "two_tops": [str(self.tee.id), str(self.knit.id), str(self.jeans.id)],
            "no_bottom": [str(self.tee.id)],
            "draft": [str(self.draft.id), str(self.jeans.id)],
            "shoes": [str(self.tee.id), str(self.jeans.id), str(self.shoes.id)],
            "unknown": [str(self.tee.id), "00000000-0000-0000-0000-000000000000"],
        }
        for case, garment_ids in bad.items():
            with self.subTest(case=case):
                self.assertEqual(self.post("/api/outfits/", {"garment_ids": garment_ids}).status_code, 400)


class WeatherTests(TestCase):
    @patch("apps.outfits.views.weather.current")
    def test_weather_and_band(self, current):
        current.return_value = weather.Weather(18.2, 17.5, 0.0, False, False, 2.0, "맑음")
        response = self.client.get("/api/weather/?lat=37.5&lon=127")
        self.assertEqual(response.status_code, 200)
        self.assertEqual((response.json()["label"], response.json()["band"]), ("맑음", "가을 간절기"))
        current.assert_called_once_with(37.5, 127.0)
        current.side_effect = weather.WeatherError("down")
        self.assertEqual(self.client.get("/api/weather/").status_code, 502)
        self.assertEqual(self.client.get("/api/weather/?lat=abc").status_code, 400)


class LocalWardrobePreviewTests(SimpleTestCase):
    def setUp(self):
        self.rows = []
        for category, subtype in [('top', 'tshirt'), ('bottom', 'jeans')]:
            self.rows.append({'id': str(uuid.uuid4()), 'attributes': {
                'name': '기기에 저장한 옷', 'category': category, 'subcategory': subtype,
                'colors': ['#FFFFFF'], 'styles': ['minimal'], 'fit_type': None, 'leg_shape': None,
                'rise_type': None, 'skirt_shape': None,
            }, 'dimensions': None, 'notes': '', 'labels': None})

    def post(self, path, data):
        return self.client.post(path, json.dumps(data), content_type='application/json')

    def test_recommendation_uses_local_records_without_any_database_queries(self):
        data = {'body': BODY, 'temp': 18, 'wardrobe': self.rows, 'count': 1}
        response = self.post('/api/outfits/preview/', data)
        self.assertEqual(response.status_code, 200, response.content)
        outfit = response.json()['outfits'][0]
        self.assertEqual({item['garment_id'] for item in outfit['items']}, {row['id'] for row in self.rows})
        self.assertTrue(all(item['image_url'].startswith('local:') for item in outfit['items']))
        repeated = self.post('/api/outfits/preview/', data).json()['outfits'][0]
        self.assertEqual(outfit['id'], repeated['id'])
        feedback = {'outfit': outfit, 'wardrobe': self.rows, 'feedback': 'liked'}
        first = self.post('/api/outfits/preview/feedback/', feedback)
        self.assertEqual(first.status_code, 200)
        feedback.update(outfit=first.json()['outfit'], preference=first.json()['preference'])
        self.assertEqual(self.post('/api/outfits/preview/feedback/', feedback).json()['preference'], first.json()['preference'])

    def test_rejects_bad_local_records(self):
        for rows in (self.rows * 2, [{'id': 'invalid'}], self.rows * 51):
            with self.subTest(count=len(rows)):
                self.assertEqual(self.post('/api/outfits/preview/', {'body': BODY, 'wardrobe': rows}).status_code, 400)

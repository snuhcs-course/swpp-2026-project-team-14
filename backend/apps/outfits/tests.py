# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
"""Outfit API tests on the in-memory database with the shipped models; no network, photos or AI key."""
import json
import base64
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
    def test_cursor_checks_owner_kind_and_invalid_positions(self):
        def cursor(position):
            return base64.urlsafe_b64encode(json.dumps(position).encode()).decode().rstrip('=')
        at = self.tee.created_at.isoformat()
        owner, pk = str(self.user.pk), str(self.tee.pk)
        for invalid in (
                cursor([str(uuid.uuid4()), 'items', at, pk]),
                cursor([owner, 'outfits', at, pk]),
                cursor([owner, 'items', '2026-10-16T00:00:00', pk]),
                cursor([owner, 'items', '9999-12-31T23:59:59-23:59', pk]),
                cursor([owner, 'items', at, 'invalid']),
                cursor([owner, 'items', at, None]),
                cursor({'not': 'a position'}), 'x' * 513, '%%%'):
            with self.subTest(cursor=invalid):
                self.assertEqual(self.client.get('/api/wardrobe/items/', {'after': invalid}).status_code, 400)

    def test_next_page_survives_cursor_outfit_edit_or_unsave(self):
        for change in ({'name': '수정'}, {'saved': False}):
            with self.subTest(change=change):
                Outfit.objects.filter(user=self.user).delete()
                for _ in range(23):
                    self.assertEqual(self.post('/api/outfits/', {'garment_ids': [str(self.tee.pk), str(self.jeans.pk)]}).status_code, 201)
                first = self.client.get('/api/outfits/').json()
                first_ids = {row['id'] for row in first['outfits']}
                remaining = {str(pk) for pk in Outfit.objects.filter(user=self.user).exclude(pk__in=first_ids).values_list('pk', flat=True)}
                anchor = first['outfits'][-1]['id']
                edited = self.client.put(f'/api/outfits/{anchor}/', json.dumps(change), content_type='application/json')
                self.assertEqual(edited.status_code, 200)
                response = self.client.get('/api/outfits/', {'after': first['next']})
                self.assertEqual(response.status_code, 200, response.content)
                second = response.json()
                self.assertEqual({row['id'] for row in second['outfits']}, remaining)
                self.assertIsNone(second['next'])

    def test_malformed_garment_ids_are_rejected_without_creating_outfits(self):
        for invalid in ('not-a-uuid', '', 'z' * 36, 123, None, {}, True):
            with self.subTest(value=invalid):
                before = Outfit.objects.count()
                response = self.post('/api/outfits/', {'garment_ids': [invalid, str(self.jeans.pk)]})
                self.assertEqual(response.status_code, 400, response.content)
                self.assertEqual(response.json()['error'], 'INVALID_OUTFIT')
                self.assertEqual(Outfit.objects.count(), before)
        missing = self.post('/api/outfits/', {'garment_ids': [str(uuid.uuid4()), str(self.jeans.pk)]})
        self.assertEqual(missing.status_code, 400)

    def test_linked_garment_category_cannot_change_but_other_edits_can(self):
        response = self.post('/api/outfits/', {'garment_ids': [str(self.tee.pk), str(self.jeans.pk)]})
        self.assertEqual(response.status_code, 201, response.content)
        # Recommended/unsaved outfits also retain their slot invariant.
        Outfit.objects.filter(pk=response.json()['id']).update(saved=False)
        payload = {'attributes': {**self.tee.attributes, 'category': 'bottom', 'subcategory': 'jeans'},
                   'dimensions': None, 'notes': 'must not persist'}
        path = f'/api/wardrobe/items/{self.tee.pk}/'
        blocked = self.client.put(path, json.dumps(payload), content_type='application/json')
        self.assertEqual(blocked.status_code, 409, blocked.content)
        self.assertEqual(blocked.json()['error'], 'GARMENT_CATEGORY_IN_USE')
        self.tee.refresh_from_db()
        self.assertEqual(self.tee.attributes['category'], 'top')
        self.assertEqual(self.tee.notes, '')
        payload['attributes'] = {**self.tee.attributes, 'name': '수정한 이름'}
        self.assertEqual(self.client.put(path, json.dumps(payload), content_type='application/json').status_code, 200)
        unlinked = f'/api/wardrobe/items/{self.small.pk}/'
        payload['attributes'] = {**self.small.attributes, 'category': 'bottom', 'subcategory': 'jeans'}
        self.assertEqual(self.client.put(unlinked, json.dumps(payload), content_type='application/json').status_code, 200)

    def test_account_lists_are_bounded_complete_and_owner_scoped(self):
        rows = [Garment(user=self.user, image='garments/x.jpg', saved=True,
                        attributes=self.tee.attributes, dimensions=self.tee.dimensions, notes='한' * 2000)
                for _ in range(122)]
        Garment.objects.bulk_create(rows)
        other = User.objects.create_user('list_other', 'test-password')
        hidden = garment(other, '비공개', 'top', 'tshirt', '#FFFFFF')
        seen, path = [], '/api/wardrobe/items/'
        while path:
            response = self.client.get(path)
            self.assertEqual(response.status_code, 200, response.content)
            self.assertLess(len(response.content), 256 * 1024)
            page = response.json()
            seen.extend(row['id'] for row in page['items'])
            path = '/api/wardrobe/items/?after=' + page['next'] if page['next'] else None
        expected = {str(pk) for pk in Garment.objects.filter(user=self.user, saved=True).values_list('pk', flat=True)}
        self.assertEqual(len(seen), len(set(seen)))
        self.assertEqual(set(seen), expected)
        for cursor in ('bad-id', str(hidden.pk), str(uuid.uuid4())):
            self.assertEqual(self.client.get('/api/wardrobe/items/', {'after': cursor}).status_code, 400)

        for _ in range(23):
            self.assertEqual(self.post('/api/outfits/', {'garment_ids': [str(self.tee.pk), str(self.jeans.pk)]}).status_code, 201)
        first = self.client.get('/api/outfits/').json()
        self.assertEqual(len(first['outfits']), 20)
        second = self.client.get('/api/outfits/', {'after': first['next']}).json()
        self.assertEqual(len(second['outfits']), 3)
        self.assertIsNone(second['next'])
        self.assertEqual(len({row['id'] for row in first['outfits'] + second['outfits']}), 23)

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
    def setUp(self):
        user = User.objects.create(username='weather_owner')
        _, token = issue_token(user)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'

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

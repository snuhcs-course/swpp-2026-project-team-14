# AI-generated with Claude Code (Claude Opus 5.5), 2026-11-27, reviewed by Dongkun Moon
from unittest.mock import patch

from django.test import SimpleTestCase, TestCase, override_settings

from apps.users.auth import issue_token
from apps.users.models import User
from recommendation import places


def result(name, kind, display, lat=37.5, lon=127.0):
    return {"name": name, "addresstype": kind, "display_name": display, "lat": str(lat), "lon": str(lon)}


class PlaceShapeTests(SimpleTestCase):
    def test_areas_and_districts_come_before_stations_and_buildings(self):
        found = places.shape([
            result("홍익대학교 서울캠퍼스", "amenity", "홍익대학교 서울캠퍼스, 94, 와우산로, 서교동, 마포구, 서울특별시, 04066, 대한민국"),
            result("홍대", "neighbourhood", "홍대, 서교동, 마포구, 서울특별시, 04039, 대한민국", 37.5532, 126.9215),
            result("홍대입구", "railway", "홍대입구, 양화로, 서교동, 마포구, 서울특별시, 대한민국"),
        ])
        self.assertEqual([p["name"] for p in found], ["홍대", "홍대입구", "홍익대학교 서울캠퍼스"])
        self.assertEqual(found[0], {"name": "홍대", "area": "서울특별시 마포구 서교동", "lat": 37.5532, "lon": 126.9215})

    def test_the_area_skips_the_name_postcode_and_country_and_duplicates_go(self):
        same = result("판교", "suburb", "판교, 분당구, 성남시, 경기도, 13525, 대한민국")
        found = places.shape([same, dict(same), result("", "suburb", "이름 없음, 대한민국")])
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0]["area"], "경기도 성남시 분당구")

    def test_a_bare_name_without_a_place_retries_as_a_city(self):
        school = result("서귀포고등학교", "amenity", "서귀포고등학교, 동홍동, 서귀포시, 제주특별자치도, 대한민국")
        city = result("서귀포시", "city", "서귀포시, 제주특별자치도, 대한민국", 33.25, 126.56)
        calls = []

        def fetch(query, timeout):
            calls.append(query)
            return [city] if query == "서귀포시" else [school]
        places._cache.clear()
        with patch.object(places, "_fetch", side_effect=fetch):
            found = places.search("서귀포")
            self.assertEqual([p["name"] for p in found], ["서귀포시", "서귀포고등학교"])
            self.assertEqual(calls, ["서귀포", "서귀포시"])
            places.search("서귀포")
            self.assertEqual(len(calls), 2, "cached for a day")


@override_settings(PASSWORD_HASHERS=["django.contrib.auth.hashers.MD5PasswordHasher"])
class PlacesViewTests(TestCase):
    def setUp(self):
        self.user = User.objects.create_user("places_01", "test-password")
        _, self.token = issue_token(self.user)

    def get(self, query, **headers):
        return self.client.get("/api/weather/places/", {"q": query}, **headers)

    def test_places_need_login_and_answer_with_coordinates(self):
        self.assertEqual(self.get("홍대").status_code, 401)
        found = [{"name": "홍대", "area": "서울특별시 마포구 서교동", "lat": 37.5532, "lon": 126.9215}]
        with patch.object(places, "search", return_value=found):
            reply = self.get("홍대", HTTP_AUTHORIZATION="Bearer " + self.token)
        self.assertEqual(reply.status_code, 200)
        self.assertEqual(reply.json(), {"places": found})

    def test_long_queries_and_provider_failures_are_reported(self):
        auth = {"HTTP_AUTHORIZATION": "Bearer " + self.token}
        self.assertEqual(self.get("가" * 31, **auth).status_code, 400)
        with patch.object(places, "search", side_effect=places.PlaceError("down")):
            self.assertEqual(self.get("홍대", **auth).status_code, 502)

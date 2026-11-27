# AI-generated with Claude Code (Claude Opus 5.5), 2026-11-27, reviewed by Dongkun Moon
"""Places in Korea by name, for choosing the weather region on 홈.

OpenStreetMap's Nominatim finds Korean districts, neighbourhoods, islands and stations by their everyday
names, with coordinates: "홍대" gives 서울특별시 마포구 서교동, "판교" gives 경기도 성남시 분당구 판교. (Open-Meteo's
GeoNames search, tried first, missed 강남구, 홍대 and 서귀포 and put villages before 잠실 and 성수.)

Nominatim's usage policy is followed: searches are made only when the person submits one (no autocomplete),
at most one request a second from this server, with the application named in the User-Agent, and results
cached for a day. The data is © OpenStreetMap contributors (ODbL); the app shows the attribution.
"""

from __future__ import annotations

import json
import threading
import time
from urllib.error import URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

URL = "https://nominatim.openstreetmap.org/search"
USER_AGENT = "StyleMate/0.2 (weather region search)"
CACHE_SECONDS = 86_400
MIN_INTERVAL = 1.1
MAX_RESULTS = 8
# Nominatim address types, most useful as a weather region first.
RANK = {"city": 0, "province": 0, "state": 0, "borough": 1, "county": 1, "town": 1, "municipality": 1,
        "city_district": 2, "district": 2, "suburb": 2, "quarter": 2, "neighbourhood": 3, "village": 3,
        "island": 3, "hamlet": 4, "railway": 5, "aeroway": 5}
SKIP = {"대한민국"}
SUFFIXES = ("시", "군", "구", "동", "읍", "면", "가", "리", "역", "도")

_cache: dict[str, tuple[float, list[dict]]] = {}
_lock = threading.Lock()
_last_call = 0.0


class PlaceError(Exception):
    pass


def rank(result: dict) -> int:
    return RANK.get(result.get("addresstype", ""), 6)


def area(result: dict) -> str:
    """Where it is, general to specific and without the name, postcode or country: 서울특별시 마포구 서교동."""
    name = result.get("name", "")
    parts = [p.strip() for p in result.get("display_name", "").split(",")]
    parts = [p for p in parts if p and p != name and p not in SKIP and not p.replace("-", "").isdigit()]
    return " ".join(list(reversed(parts))[:3])


def shape(results: list[dict]) -> list[dict]:
    seen, places = set(), []
    for result in sorted(results, key=rank):
        if not result.get("name"):
            continue
        place = {"name": result["name"], "area": area(result),
                 "lat": round(float(result["lat"]), 4), "lon": round(float(result["lon"]), 4)}
        key = (place["name"], place["area"])
        if key in seen:
            continue
        seen.add(key)
        places.append(place)
        if len(places) == MAX_RESULTS:
            break
    return places


def _fetch(query: str, timeout: float) -> list[dict]:
    global _last_call
    with _lock:
        # One request a second at most, as Nominatim asks.
        wait = _last_call + MIN_INTERVAL - time.monotonic()
        if wait > 0:
            time.sleep(wait)
        _last_call = time.monotonic()
    params = urlencode({"q": query, "countrycodes": "kr", "accept-language": "ko", "format": "jsonv2",
                        "limit": 15, "addressdetails": 0})
    try:
        with urlopen(Request(f"{URL}?{params}", headers={"User-Agent": USER_AGENT}), timeout=timeout) as r:
            data = json.load(r)
    except (URLError, TimeoutError, OSError, ValueError) as error:
        raise PlaceError(str(error)) from None
    return data if isinstance(data, list) else []


def search(query: str, timeout: float = 6.0) -> list[dict]:
    query = " ".join(query.split())[:30]
    if not query:
        return []
    with _lock:
        hit = _cache.get(query)
        if hit and time.monotonic() - hit[0] < CACHE_SECONDS:
            return hit[1]
    results = _fetch(query, timeout)
    # A bare name ("서귀포") can match schools and offices before the city itself: when nothing found is a
    # city, district or neighbourhood, the same name as a city or a district is tried and put first.
    if not any(rank(r) <= 3 for r in results) and not query.endswith(SUFFIXES):
        for suffix in ("시", "구"):
            better = [r for r in _fetch(query + suffix, timeout) if rank(r) <= 3]
            if better:
                results = better + results
                break
    places = shape(results)
    with _lock:
        if len(_cache) > 512:
            _cache.clear()
        _cache[query] = (time.monotonic(), places)
    return places

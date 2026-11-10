# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-11, reviewed by Dongkun Moon
"""Current weather from Open-Meteo (free, no key). The recommender uses the apparent temperature for
the season band and precipitation for the rain rules."""

from __future__ import annotations

import json
import socket
import time
from dataclasses import asdict, dataclass
from urllib.error import URLError
from urllib.parse import urlencode
from urllib.request import urlopen

SEOUL = (37.5665, 126.9780)
URL = "https://api.open-meteo.com/v1/forecast"
# WMO weather codes (Open-Meteo docs)
RAIN_CODES = set(range(51, 68)) | set(range(80, 83)) | {95, 96, 99}
SNOW_CODES = set(range(71, 78)) | {85, 86}
LABELS = {0: "맑음", 1: "대체로 맑음", 2: "구름 조금", 3: "흐림", 45: "안개", 48: "안개"}


@dataclass
class Weather:
    temp: float
    apparent: float
    precipitation: float
    rain: bool
    snow: bool
    wind: float
    label: str
    source: str = "open-meteo"

    def public(self) -> dict:
        return asdict(self)


def label_of(code: int) -> str:
    if code in SNOW_CODES:
        return "눈"
    if code in RAIN_CODES:
        return "비"
    return LABELS.get(code, "흐림")


def parse(data: dict) -> Weather:
    cur = data["current"]
    code = int(cur.get("weather_code", 3))
    precip = float(cur.get("precipitation", 0.0) or 0.0)
    return Weather(
        temp=float(cur["temperature_2m"]),
        apparent=float(cur.get("apparent_temperature", cur["temperature_2m"])),
        precipitation=precip,
        rain=code in RAIN_CODES or (precip > 0 and code not in SNOW_CODES),
        snow=code in SNOW_CODES,
        wind=float(cur.get("wind_speed_10m", 0.0) or 0.0),
        label=label_of(code),
    )


def current(lat: float = SEOUL[0], lon: float = SEOUL[1], timeout: int = 8) -> Weather:
    query = urlencode({
        "latitude": lat, "longitude": lon, "timezone": "Asia/Seoul",
        "current": "temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m",
    })
    try:
        with urlopen(f"{URL}?{query}", timeout=timeout) as r:
            return parse(json.loads(r.read(64 * 1024)))
    except (URLError, socket.timeout, TimeoutError, KeyError, ValueError) as e:
        raise WeatherError(str(e)) from None


class WeatherError(Exception):
    pass


FORECAST_DAYS = 16  # Open-Meteo's daily forecast horizon
_DAILY_CACHE: dict[tuple, tuple[float, "DayForecast"]] = {}


@dataclass
class DayForecast:
    """One day's range at one place, for a planned outing ("내일 부산 여행")."""
    day: str  # ISO date
    temp_min: float
    temp_max: float
    apparent_min: float
    apparent_max: float
    rain_chance: int  # %, the day's maximum
    rain: bool
    snow: bool
    label: str

    @property
    def apparent(self) -> float:
        """The season band temperature: the middle of the day's felt range."""
        return round((self.apparent_min + self.apparent_max) / 2, 1)

    @property
    def spread(self) -> float:
        return self.temp_max - self.temp_min

    def public(self) -> dict:
        return {**asdict(self), "apparent": self.apparent}


def parse_daily(data: dict) -> DayForecast:
    d = data["daily"]
    code = int(d["weather_code"][0])
    chance = int(d.get("precipitation_probability_max", [0])[0] or 0)
    t_min, t_max = float(d["temperature_2m_min"][0]), float(d["temperature_2m_max"][0])
    snow = code in SNOW_CODES
    return DayForecast(
        day=d["time"][0], temp_min=t_min, temp_max=t_max,
        apparent_min=float(d.get("apparent_temperature_min", [t_min])[0]),
        apparent_max=float(d.get("apparent_temperature_max", [t_max])[0]),
        rain_chance=chance, snow=snow, rain=not snow and (code in RAIN_CODES or chance >= 60),
        label=label_of(code),
    )


def daily(lat: float, lon: float, day: str, timeout: int = 8) -> DayForecast:
    """The forecast for `day` (ISO date) at a place, cached for 30 minutes."""
    key = (round(lat, 2), round(lon, 2), day)
    hit = _DAILY_CACHE.get(key)
    if hit and time.monotonic() - hit[0] < 1800:
        return hit[1]
    query = urlencode({
        "latitude": lat, "longitude": lon, "timezone": "auto", "start_date": day, "end_date": day,
        "daily": "temperature_2m_max,temperature_2m_min,apparent_temperature_max,apparent_temperature_min,"
                 "precipitation_probability_max,weather_code",
    })
    try:
        with urlopen(f"{URL}?{query}", timeout=timeout) as r:
            forecast = parse_daily(json.loads(r.read(64 * 1024)))
    except (URLError, socket.timeout, TimeoutError, KeyError, IndexError, TypeError, ValueError) as e:
        raise WeatherError(str(e)) from None
    if len(_DAILY_CACHE) > 256:
        _DAILY_CACHE.clear()
    _DAILY_CACHE[key] = (time.monotonic(), forecast)
    return forecast


def manual(temp: float) -> Weather:
    return Weather(temp, temp, 0.0, False, False, 0.0, "직접 입력", source="manual")


# rain: fabrics that stain or shrink and hems that drag
RAIN_AVOID_MATERIALS = {"스웨이드", "린넨", "실크", "벨벳", "시퀸/글리터", "퍼"}


def rain_penalty(w: Weather, slot: str, attrs: dict) -> tuple[float, str | None]:
    return wet_penalty("비" if w.rain else "눈" if w.snow else None, slot, attrs)


def wet_penalty(wet: str | None, slot: str, attrs: dict) -> tuple[float, str | None]:
    """`wet`: "비", "눈" or None."""
    if not wet:
        return 0.0, None
    materials = set(attrs.get("소재") or [])
    if materials & RAIN_AVOID_MATERIALS:
        return -0.15, f"{wet} 오는 날엔 {', '.join(sorted(materials & RAIN_AVOID_MATERIALS))} 소재는 피해요"
    if slot in {"하의", "원피스"} and attrs.get("기장") == "맥시":
        return -0.1, "바닥에 끌리는 기장은 젖기 쉬워요"
    if slot == "하의" and attrs.get("색상") == "화이트":
        return -0.05, "흰 하의는 빗물이 튀면 티가 나요"
    return 0.0, None

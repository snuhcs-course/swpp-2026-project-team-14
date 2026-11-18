from unittest.mock import patch

import pytest

from recommendation import weather

READING = weather.Weather(18.0, 17.0, 0.0, False, False, 2.0, "맑음")
NEWER = weather.Weather(12.0, 10.0, 0.0, True, False, 2.0, "비")


@pytest.fixture(autouse=True)
def empty_cache():
    weather._current_cache.clear()
    yield
    weather._current_cache.clear()


class Clock:
    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        return self.now


def test_fresh_reading_is_served_without_calling_open_meteo():
    clock = Clock()
    with patch("recommendation.weather.time.monotonic", clock), \
            patch("recommendation.weather.current", return_value=READING) as current:
        assert weather.cached_current(37.5665, 126.978) is READING
        clock.now += weather.FRESH_SECONDS - 1
        assert weather.cached_current(37.5665, 126.978) is READING
    assert current.call_count == 1


def test_stale_reading_is_served_at_once_and_refreshed_in_the_background():
    clock = Clock()
    with patch("recommendation.weather.time.monotonic", clock), \
            patch("recommendation.weather.current", side_effect=[READING, NEWER]) as current, \
            patch("recommendation.weather.threading.Thread") as thread:
        weather.cached_current()
        clock.now += weather.FRESH_SECONDS + 1
        assert weather.cached_current() is READING
        assert weather.cached_current() is READING  # one refresh at a time
        assert thread.call_count == 1
        target, args = thread.call_args.kwargs["target"], thread.call_args.kwargs["args"]
        target(*args)
        assert weather.cached_current() is NEWER
    assert current.call_count == 2


def test_failed_refresh_keeps_the_older_reading_and_an_hour_old_one_is_refetched():
    clock = Clock()
    with patch("recommendation.weather.time.monotonic", clock), \
            patch("recommendation.weather.current", side_effect=[READING, weather.WeatherError("down"), NEWER]):
        weather.cached_current()
        weather._refresh((37.57, 126.98), *weather.SEOUL)
        assert weather.cached_current() is READING
        clock.now += weather.STALE_SECONDS + 1
        assert weather.cached_current() is NEWER

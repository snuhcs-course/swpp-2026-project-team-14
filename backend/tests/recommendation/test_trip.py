from datetime import date, datetime

from recommendation import trip, weather

NOW = datetime(2026, 11, 10, 12, 0, tzinfo=trip.KST)  # a Tuesday
TODAY = NOW.date()


def day(text):
    return trip.parse(text, NOW).day


def test_relative_days_and_weekdays():
    assert TODAY.weekday() == 1
    assert day('오늘 뭐 입지') == TODAY
    assert day('내일 부산 여행') == date(2026, 11, 11)
    assert day('모레 데이트') == date(2026, 11, 12)
    assert day('3일 뒤에 모임') == date(2026, 11, 13)
    assert day('토요일에 데이트') == date(2026, 11, 14)
    assert day('화요일 출근') == TODAY  # today's weekday means today
    assert day('월요일 출근') == date(2026, 11, 16)
    assert day('다음 주 화요일 면접') == date(2026, 11, 17)
    assert day('이번 주말 나들이') == date(2026, 11, 14)
    assert day('다음주 주말 캠핑') == date(2026, 11, 21)


def test_calendar_dates_roll_to_next_year_once_past():
    assert day('11월 15일 제주') == date(2026, 11, 15)
    assert day('12/24 파티') == date(2026, 12, 24)
    assert day('3월 2일 개강') == date(2027, 3, 2)
    assert day('2월 30일') is None
    assert day('셔츠 말고 다른 거') is None


def test_place_and_occasion():
    plan = trip.parse('다음 주 토요일 서귀포 바닷가 가는데 뭐 입지', NOW)
    assert (plan.place, plan.occasion) == ('서귀포', '여행')
    assert plan.coords == trip.PLACES['서귀포']
    assert trip.parse('결혼식 하객룩, 부산에서', NOW).place == '부산'
    assert trip.parse('결혼식 하객룩, 부산에서', NOW).occasion == '모임'
    assert trip.parse('상의만 밝게', NOW) == trip.Trip()


def test_day_label():
    assert trip.day_label(date(2026, 11, 11), TODAY) == '내일(11/11 수)'
    assert trip.day_label(date(2026, 11, 20), TODAY) == '11/20 금'


def test_daily_forecast_parsing_uses_rain_chance():
    data = {'daily': {'time': ['2026-11-11'], 'temperature_2m_min': [6.0], 'temperature_2m_max': [17.0],
                      'apparent_temperature_min': [3.0], 'apparent_temperature_max': [15.0],
                      'precipitation_probability_max': [70], 'weather_code': [3]}}
    f = weather.parse_daily(data)
    assert (f.rain, f.snow, f.apparent, f.spread, f.label) == (True, False, 9.0, 11.0, '흐림')
    data['daily']['precipitation_probability_max'] = [20]
    assert weather.parse_daily(data).rain is False

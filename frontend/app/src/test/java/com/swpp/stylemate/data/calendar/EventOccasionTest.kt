package com.swpp.stylemate.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventOccasionTest {
    private fun event(title: String) = CalendarEvent(title, 0, 1, null, false)

    @Test fun titlesMapToTheChatOccasions() {
        assertEquals("출근", EventOccasion.of("회사 면접"))
        assertEquals("출근", EventOccasion.of("팀 미팅"))
        assertEquals("데이트", EventOccasion.of("저녁 데이트"))
        assertEquals("운동", EventOccasion.of("헬스장 PT"))
        assertEquals("모임", EventOccasion.of("친구 결혼식"))
        assertEquals("여행", EventOccasion.of("부산 여행"))
    }

    @Test fun ordinaryTitlesHaveNoOccasion() {
        assertNull(EventOccasion.of("치과 예약"))
        assertNull(EventOccasion.of(emptyList()))
    }

    @Test fun theFirstEventWithAnOccasionWins() {
        assertEquals("데이트", EventOccasion.of(listOf(event("병원"), event("소개팅"), event("회의"))))
    }
}

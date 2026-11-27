// AI-generated with Claude Code (Claude Opus 5.5), 2026-11-27, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.home

import androidx.compose.ui.geometry.Size
import com.swpp.stylemate.data.recommendation.HomeRegion
import com.swpp.stylemate.data.recommendation.Weather
import com.swpp.stylemate.ui.wardrobe.SpreadTips
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionPickerTest {
    @Test fun regionsAreFoundByNameOrByInitialConsonants() {
        assertEquals("ㄱㄹ", choseong("강릉"))
        assertTrue(matchesRegion("부", "부산"))
        assertTrue(matchesRegion("ㄷㄱ", "대구"))
        assertTrue(matchesRegion(" ㄱ ㄹ ", "강릉"))
        assertFalse(matchesRegion("ㄷㄱ", "서울"))
        assertFalse(matchesRegion("부산", "울산"))
        assertTrue("an empty search shows everything", HomeRegion.OPTIONS.all { matchesRegion("", it.name) })
    }

    @Test fun theMapKeepsKoreasLayoutInsideItsBox() {
        val size = Size(300f, 500f)
        fun at(name: String) = HomeRegion.OPTIONS.first { it.name == name }.let { projectKorea(it.lat, it.lon, size, 10f) }
        assertTrue("Seoul is north of Busan", at("서울").y < at("부산").y)
        assertTrue("Gangneung is east of Seoul", at("강릉").x > at("서울").x)
        assertTrue("Jeju is the southernmost", HomeRegion.OPTIONS.all { at(it.name).y <= at("제주").y })
        HomeRegion.OPTIONS.forEach { val p = at(it.name); assertTrue(it.name, p.x in 0f..300f && p.y in 0f..500f) }
    }

    @Test fun eachServerLabelGetsASky() {
        fun sky(label: String, rain: Boolean = false, snow: Boolean = false) =
            skyOf(Weather(10.0, 10.0, label, "", rain, snow, JSONObject()))
        assertEquals(Sky.CLEAR, sky("맑음"))
        assertEquals(Sky.PARTLY, sky("대체로 맑음"))
        assertEquals(Sky.PARTLY, sky("구름 조금"))
        assertEquals(Sky.CLOUDY, sky("흐림"))
        assertEquals(Sky.CLOUDY, sky("안개"))
        assertEquals(Sky.RAIN, sky("비", rain = true))
        assertEquals(Sky.SNOW, sky("눈", snow = true))
    }

    @Test fun theSpreadTipStopsOnceTheGestureIsKnownAndStaysRare() {
        assertTrue(SpreadTips.eligible(uses = 0, shownOnScreen = 0, alreadyShownForOutfit = false))
        assertFalse("a couple of times per screen at most", SpreadTips.eligible(0, SpreadTips.PER_SCREEN, false))
        assertFalse("once per outfit", SpreadTips.eligible(0, 0, alreadyShownForOutfit = true))
        assertFalse("never again once used", SpreadTips.eligible(SpreadTips.STOP_AFTER_USES, 0, false))
    }

    private val outline = parseOutline(java.io.File("src/main/assets/map/korea_outline.json").readText())

    private fun onLand(lat: Double, lon: Double) = outline.any { ring ->
        // Ray casting on (lon, lat).
        var inside = false
        ring.indices.forEach { i ->
            val (ay, ax) = ring[i]; val (by, bx) = ring[(i + 1) % ring.size]
            if ((ay > lat) != (by > lat) && lon < (bx - ax) * (lat - ay) / (by - ay) + ax) inside = !inside
        }
        inside
    }

    @Test fun everyListedCityFallsOnTheRealCoastline() {
        assertTrue("the mainland and the islands", outline.size >= 3 && outline[0].size > 50)
        HomeRegion.OPTIONS.forEach { assertTrue("${it.name} is on land", onLand(it.lat, it.lon)) }
        assertTrue("Ulleung is drawn too", onLand(37.50, 130.86))
        assertFalse("the Yellow Sea is not land", onLand(36.5, 125.9))
        assertFalse("nor the East Sea", onLand(37.0, 130.0))
    }

    @Test fun placesAreKeyedByWhereTheyAreNotTheirName() {
        val a = com.swpp.stylemate.data.recommendation.Region("송도", 37.3826, 126.6566, "인천광역시 연수구")
        val b = com.swpp.stylemate.data.recommendation.Region("송도", 36.0184, 129.3727, "경상북도 포항시 남구")
        assertTrue(a.key != b.key)
        assertEquals(a.key, a.copy(name = "송도국제도시").key)
    }
}

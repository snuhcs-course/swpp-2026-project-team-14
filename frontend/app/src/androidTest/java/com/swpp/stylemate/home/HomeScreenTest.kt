// AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.home

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitGarment
import com.swpp.stylemate.data.recommendation.Reason
import com.swpp.stylemate.data.recommendation.Weather
import com.swpp.stylemate.ui.home.HomeScreen
import com.swpp.stylemate.ui.home.HomeState
import com.swpp.stylemate.ui.theme.StyleMateTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule val compose = createComposeRule()

    private val outfit = Outfit(
        id = "o1", key = "w:a+w:b", score = 78,
        items = listOf(
            OutfitGarment("a", "top", "베이지 니트", "/api/wardrobe/items/a/image/", "내 옷 · 내 몸 기준 루즈 (가슴 여유 16 cm)"),
            OutfitGarment("b", "bottom", "검정 슬랙스", "/api/wardrobe/items/b/image/", "내 옷"),
        ),
        reasons = listOf(Reason("color", "베이지 · 블랙 뉴트럴 조합이라 차분해요.", "배색 규칙")),
        saved = false, name = "", feedback = null, createdAt = "2026-10-12T09:00:00",
    )
    private val weather = Weather(18.0, 17.0, "맑음", "가을 간절기", false, false, JSONObject())

    private fun show(state: HomeState, events: MutableList<String> = mutableListOf()) {
        compose.setContent {
            StyleMateTheme {
                HomeScreen(state, onRefresh = { events += "refresh" }, onNext = { events += "next" },
                    onLike = { events += "like" }, onDislike = { events += "dislike" }, onSave = { events += "save" },
                    onSetupProfile = { events += "setup" }, onOpenWardrobe = { events += "wardrobe" },
                    onMessageShown = {}, photo = { item, modifier -> Box(modifier) { Text("사진:${item.name}") } })
            }
        }
    }

    @Test fun outfitFromMyWardrobeWithWeatherReasonsAndActions() {
        val events = mutableListOf<String>()
        show(HomeState(weather = weather, outfits = listOf(outfit, outfit.copy(id = "o2", key = "k2"))), events)
        compose.onNodeWithText("오늘의 코디").assertIsDisplayed()
        compose.onNodeWithText("서울 18°C · 맑음 · 가을 간절기").assertIsDisplayed()
        compose.onNodeWithText("사진:베이지 니트").assertIsDisplayed()
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNodeWithText("내 옷장에서 100% 구성").assertIsDisplayed()
        compose.onNodeWithText("어울림 78점").assertIsDisplayed()
        compose.onNodeWithText("베이지 · 블랙 뉴트럴 조합이라 차분해요.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("베이지 니트: 내 몸 기준 루즈 (가슴 여유 16 cm)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("좋아요").performScrollTo().performClick()
        compose.onNodeWithText("별로예요").performClick()
        compose.onNodeWithText("코디북에 저장").performClick()
        compose.onNodeWithText("다른 코디 보기").performClick()
        compose.runOnIdle { assertEquals(listOf("like", "dislike", "save", "next"), events) }
    }

    @Test fun emptyStatesLeadToTheProfileOrTheWardrobe() {
        val events = mutableListOf<String>()
        show(HomeState(errorCode = HomeState.NO_PROFILE), events)
        compose.onNodeWithText("체형 정보가 필요해요").assertIsDisplayed()
        compose.onNodeWithText("체형 분석하기").performClick()
        compose.runOnIdle { assertEquals(listOf("setup"), events) }
    }

    @Test fun tooFewGarmentsAsksToRegisterMore() {
        val events = mutableListOf<String>()
        show(HomeState(errorCode = "WARDROBE_TOO_SMALL", error = "상의와 하의를 한 벌씩 등록하면 코디를 만들어 드려요."), events)
        compose.onNodeWithText("상의와 하의를 한 벌씩 등록하면 코디를 만들어 드려요.").assertIsDisplayed()
        compose.onNodeWithText("옷장으로 가기").performClick()
        compose.runOnIdle { assertEquals(listOf("wardrobe"), events) }
    }
}

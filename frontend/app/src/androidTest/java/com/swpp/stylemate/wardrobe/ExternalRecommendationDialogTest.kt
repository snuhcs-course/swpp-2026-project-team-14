package com.swpp.stylemate.wardrobe

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.swpp.stylemate.data.recommendation.ExternalAttributes
import com.swpp.stylemate.data.recommendation.ExternalRecommendation
import com.swpp.stylemate.data.recommendation.WardrobeMatch
import com.swpp.stylemate.ui.theme.StyleMateTheme
import com.swpp.stylemate.ui.wardrobe.ExternalMatchState
import com.swpp.stylemate.ui.wardrobe.ExternalRecommendationDialog
import com.swpp.stylemate.ui.wardrobe.WardrobeItem
import com.swpp.stylemate.ui.wardrobe.WardrobeItemActionsDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ExternalRecommendationDialogTest {
    @get:Rule val compose = createComposeRule()
    private val result = ExternalRecommendation("pants", "top", "블랙 오버핏 셔츠",
        "해당 옷과 가장 잘 어울리는 상의는 블랙 & 오버핏 속성이에요.",
        "선택한 옷을 기준으로 블랙·오버핏 조합의 어울림 점수가 가장 높았어요 (후보 중 색상 62% · 핏 71%).",
        ExternalAttributes("셔츠", "블랙", "오버사이즈"),
        "https://www.musinsa.com/search/goods?keyword=black+shirt&categoryCode=001",
        listOf(WardrobeMatch("shirt-1", "검정 셔츠"), WardrobeMatch("tee-2", "흰 티셔츠")))

    @Test fun recommendationActionShowsThePredictionAndOpensItsSearchLink() {
        var opened: String? = null
        compose.setContent {
            var recommending by remember { mutableStateOf(false) }
            StyleMateTheme {
                if (recommending) ExternalRecommendationDialog(ExternalMatchState("pants", result = result),
                    onRetry = {}, onOpenLink = { opened = it }, onDismiss = {})
                else WardrobeItemActionsDialog(WardrobeItem("pants", "내 바지", "bottom"),
                    onEdit = {}, onRecommend = { recommending = true }, onDismiss = {})
            }
        }
        compose.onNodeWithText("어울리는 옷 찾기").performClick()
        compose.onNodeWithText("어울리는 상의").assertIsDisplayed()
        compose.onNodeWithText(result.title).assertIsDisplayed()
        compose.onNodeWithText(result.headline).assertIsDisplayed()
        compose.onNodeWithText(result.reason).assertIsDisplayed()
        compose.onNodeWithText("무신사에서 비슷한 옷 찾기").performClick()
        compose.runOnIdle { assertEquals(result.searchUrl, opened) }
    }

    @Test fun unsupportedShoesKeepEditingAvailable() {
        var edited = false
        compose.setContent { StyleMateTheme {
            WardrobeItemActionsDialog(WardrobeItem("shoes", "운동화", "shoes"),
                onEdit = { edited = true }, onRecommend = {}, onDismiss = {})
        } }
        compose.onNodeWithText("어울리는 옷 찾기").assertIsNotEnabled()
        compose.onNodeWithText("옷 정보 수정하기").performClick()
        compose.runOnIdle { assertEquals(true, edited) }
    }

    @Test fun loadingCannotOpenAnUnfinishedRecommendation() {
        compose.setContent { StyleMateTheme {
            ExternalRecommendationDialog(ExternalMatchState("pants", loading = true), {}, {}, {})
        } }
        compose.onNodeWithText("AI가 어울림 점수 분석 중...").assertIsDisplayed()
        compose.onNodeWithText("무신사에서 비슷한 옷 찾기").assertDoesNotExist()
        compose.onNodeWithText("내 옷장에서").assertDoesNotExist()
    }

    @Test fun failureOffersRetryAndCloseWithoutASearchLink() {
        var retries = 0
        var closed = false
        compose.setContent { StyleMateTheme {
            ExternalRecommendationDialog(ExternalMatchState("pants", error = "다시 시도해 주세요."),
                onRetry = { retries++ }, onOpenLink = {}, onDismiss = { closed = true })
        } }
        compose.onNodeWithText("다시 시도해 주세요.").assertIsDisplayed()
        compose.onNodeWithText("무신사에서 비슷한 옷 찾기").assertDoesNotExist()
        compose.onNodeWithText("다시 시도").performClick()
        compose.onNodeWithText("닫기").performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(true, closed) }
    }

    @Test fun ownedMatchesAreShownRightAwayWithoutOpeningALink() {
        var opened: String? = null
        compose.setContent { StyleMateTheme {
            ExternalRecommendationDialog(ExternalMatchState("pants", result = result),
                onRetry = {}, onOpenLink = { opened = it }, onDismiss = {})
        } }
        compose.onNodeWithText("내 옷장에서").assertIsDisplayed()
        compose.onNodeWithText("1. 검정 셔츠").assertIsDisplayed()
        compose.onNodeWithText("2. 흰 티셔츠").assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, opened) }
    }

    @Test fun wardrobeSearchExplainsWhenNoGarmentMatches() {
        compose.setContent { StyleMateTheme {
            ExternalRecommendationDialog(ExternalMatchState("pants", result = result.copy(wardrobeMatches = emptyList())),
                {}, {}, {})
        } }
        compose.onNodeWithText("옷장에 어울리는 상의가 아직 없어요.").assertIsDisplayed()
    }
}

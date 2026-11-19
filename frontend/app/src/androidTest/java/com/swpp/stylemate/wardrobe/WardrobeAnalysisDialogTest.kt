// AI-generated with Claude Code (Claude Opus 5.5), 2026-11-12, reviewed by Kihwan Kim
package com.swpp.stylemate.wardrobe

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.swpp.stylemate.data.recommendation.ExternalAttributes
import com.swpp.stylemate.data.recommendation.GapGarment
import com.swpp.stylemate.data.recommendation.WardrobeAnalysis
import com.swpp.stylemate.data.recommendation.WardrobeGap
import com.swpp.stylemate.ui.theme.StyleMateTheme
import com.swpp.stylemate.ui.wardrobe.ANALYSIS_ENOUGH_TEXT
import com.swpp.stylemate.ui.wardrobe.ANALYSIS_LINK_TEXT
import com.swpp.stylemate.ui.wardrobe.ANALYSIS_LOADING_TEXT
import com.swpp.stylemate.ui.wardrobe.ANALYSIS_SECTION_TEXT
import com.swpp.stylemate.ui.wardrobe.ANALYSIS_TOO_FEW_TEXT
import com.swpp.stylemate.ui.wardrobe.ANALYZE_BUTTON_TEXT
import com.swpp.stylemate.ui.wardrobe.WardrobeAnalysisDialog
import com.swpp.stylemate.ui.wardrobe.WardrobeAnalysisState
import com.swpp.stylemate.ui.wardrobe.WardrobeItem
import com.swpp.stylemate.ui.wardrobe.WardrobeScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

// 옷장 분석 팝업의 화면 상태별 표시와 버튼 동작. 썸네일은 네트워크 없이 빈 칸으로 그린다.
class WardrobeAnalysisDialogTest {
    @get:Rule val compose = createComposeRule()

    private fun garment(id: String, name: String) = GapGarment(id, name, "/api/wardrobe/items/$id/image/")
    private val knit = WardrobeGap("그레이 루즈 니트", "top", 4, "내 옷 4벌과 잘 어울리는데, 비슷한 옷이 옷장에 없어요.",
        ExternalAttributes("니트웨어", "그레이", "루즈"),
        listOf(garment("jeans", "청바지"), garment("skirt", "와인 스커트"), garment("slacks", "검정 바지"),
            garment("coat", "코트")),
        "https://www.musinsa.com/search/goods?keyword=knit")
    private val pants = WardrobeGap("베이지 레귤러핏 팬츠", "bottom", 2, "내 옷 2벌과 잘 어울리는데, 비슷한 옷이 옷장에 없어요.",
        ExternalAttributes("팬츠", "베이지", "노멀"), listOf(garment("shirt", "흰 셔츠"), garment("tee", "검정 티")),
        "https://www.musinsa.com/search/goods?keyword=pants")
    private val counts = mapOf("top" to 9, "bottom" to 8, "outerwear" to 6)

    private fun show(state: WardrobeAnalysisState, onRetry: () -> Unit = {}, onOpenLink: (String) -> Unit = {},
                     onDismiss: () -> Unit = {}) {
        compose.setContent { StyleMateTheme {
            WardrobeAnalysisDialog(state, onRetry, onOpenLink, onDismiss, thumbnail = {})
        } }
    }

    @Test fun analyzeButtonSitsOnTheWardrobeScreen() {
        var analyzed = 0
        compose.setContent { StyleMateTheme {
            WardrobeScreen(items = listOf(WardrobeItem("a", "흰 셔츠", "top")), message = null, cameraBusy = false,
                onCapture = {}, onAnalyze = { analyzed++ })
        } }
        compose.onNodeWithText(ANALYZE_BUTTON_TEXT).performClick()
        compose.runOnIdle { assertEquals(1, analyzed) }
    }

    @Test fun analyzeButtonWaitsForTheWardrobeToLoad() {
        compose.setContent { StyleMateTheme {
            WardrobeScreen(items = emptyList(), message = null, cameraBusy = false, onCapture = {}, loading = true,
                onAnalyze = {})
        } }
        compose.onNodeWithText(ANALYZE_BUTTON_TEXT).assertIsNotEnabled()
    }

    @Test fun loadingShowsTheAnalysisMessageWithoutResults() {
        show(WardrobeAnalysisState(open = true, loading = true))
        compose.onNodeWithText(ANALYSIS_LOADING_TEXT).assertIsDisplayed()
        compose.onNodeWithText(ANALYSIS_LINK_TEXT).assertDoesNotExist()
    }

    @Test fun resultShowsSummaryCardsEvidenceAndOpensEachSearchLink() {
        val opened = mutableListOf<String>()
        show(WardrobeAnalysisState(open = true,
            result = WardrobeAnalysis(WardrobeAnalysis.OK, 23, counts, listOf(knit, pants))),
            onOpenLink = { opened += it })
        compose.onNodeWithText("옷장 분석 결과").assertIsDisplayed()
        compose.onNodeWithText("옷 23벌을 분석했어요").assertIsDisplayed()
        compose.onNodeWithText("상의 9 · 하의 8 · 아우터 6").assertIsDisplayed()
        compose.onNodeWithText(ANALYSIS_SECTION_TEXT).assertIsDisplayed()
        compose.onNodeWithText("1. 그레이 루즈 니트").assertIsDisplayed()
        compose.onNodeWithText(knit.reason).assertIsDisplayed()
        // 근거 옷은 3벌까지 이름을 보여 주고 나머지는 수로 줄인다
        compose.onNodeWithText("+1").assertIsDisplayed()
        compose.onNodeWithText("어울리는 내 옷: 청바지, 와인 스커트, 검정 바지 외 1벌").assertIsDisplayed()
        compose.onNodeWithText("2. 베이지 레귤러핏 팬츠").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("어울리는 내 옷: 흰 셔츠, 검정 티").performScrollTo().assertIsDisplayed()
        val links = compose.onAllNodesWithText(ANALYSIS_LINK_TEXT)
        links[0].performScrollTo().performClick()
        links[1].performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(knit.searchUrl, pants.searchUrl), opened) }
    }

    @Test fun nothingToBuyIsSaidPlainlyWithoutShoppingLinks() {
        show(WardrobeAnalysisState(open = true, result = WardrobeAnalysis(WardrobeAnalysis.ENOUGH, 12, counts, emptyList())))
        compose.onNodeWithText("옷 12벌을 분석했어요").assertIsDisplayed()
        compose.onNodeWithText(ANALYSIS_ENOUGH_TEXT).assertIsDisplayed()
        compose.onNodeWithText(ANALYSIS_SECTION_TEXT).assertDoesNotExist()
        compose.onNodeWithText(ANALYSIS_LINK_TEXT).assertDoesNotExist()
    }

    @Test fun smallWardrobeAsksForMoreGarments() {
        show(WardrobeAnalysisState(open = true, result = WardrobeAnalysis(WardrobeAnalysis.TOO_FEW, 2,
            mapOf("top" to 1, "bottom" to 1, "outerwear" to 0), emptyList())))
        compose.onNodeWithText(ANALYSIS_TOO_FEW_TEXT).assertIsDisplayed()
        compose.onNodeWithText("옷 2벌을 분석했어요").assertDoesNotExist()
        compose.onNodeWithText(ANALYSIS_LINK_TEXT).assertDoesNotExist()
    }

    @Test fun failureOffersRetryAndClose() {
        var retries = 0
        var closed = false
        show(WardrobeAnalysisState(open = true, error = "옷장을 분석하지 못했어요."),
            onRetry = { retries++ }, onDismiss = { closed = true })
        compose.onNodeWithText("옷장을 분석하지 못했어요.").assertIsDisplayed()
        compose.onNodeWithText(ANALYSIS_LINK_TEXT).assertDoesNotExist()
        compose.onNodeWithText("다시 시도").performClick()
        compose.onNodeWithText("닫기").performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(true, closed) }
    }
}

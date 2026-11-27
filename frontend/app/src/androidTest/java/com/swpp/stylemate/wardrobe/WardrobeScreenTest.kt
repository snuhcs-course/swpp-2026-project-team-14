// AI-generated with Codex, 2026-09-29, reviewed by Hyeon U Jeong
package com.swpp.stylemate.wardrobe

import com.swpp.stylemate.data.wardrobe.*
import com.swpp.stylemate.ui.wardrobe.*

import com.swpp.stylemate.ui.theme.StyleMateTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WardrobeScreenTest {
    @get:Rule val compose = createComposeRule()

    /** Card delete icons and the two-column layout live in 전체 보기; the tab opens on the rail. */
    private fun openFullGrid() {
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("전체 보기"))
        compose.onNodeWithText("전체 보기").performClick()
    }

    @Test fun deleteIconOpensConfirmationAndNoKeepsTheGarment() {
        var opened = 0
        var deleted = 0
        compose.setContent { StyleMateTheme {
            WardrobeScreen(listOf(WardrobeItem("one", "내 셔츠", "top")), null, false, {},
                onItemClick = { opened++ }, onDelete = { deleted++ })
        } }
        openFullGrid()
        compose.onNodeWithContentDescription("내 셔츠 삭제").performClick()
        compose.onNodeWithText("해당 의상을 삭제하시겠습니까?").assertIsDisplayed()
        compose.onNodeWithText("이 의상이 포함된 코디도 함께 삭제됩니다.").assertIsDisplayed()
        compose.onNodeWithText("아니요").performClick()
        compose.onNodeWithText("해당 의상을 삭제하시겠습니까?").assertDoesNotExist()
        compose.onNodeWithText("내 셔츠").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, opened); assertEquals(0, deleted) }
    }

    @Test fun yesDeletesTheChosenCardAndUpdatesTheCountAndEmptyState() {
        val items = mutableStateOf(listOf(WardrobeItem("one", "내 셔츠", "top")))
        compose.setContent { StyleMateTheme {
            WardrobeScreen(items.value, null, false, {}, onDelete = { selected ->
                items.value = items.value.filter { it.id != selected.id }
            })
        } }
        openFullGrid()
        compose.onNodeWithContentDescription("내 셔츠 삭제").performClick()
        compose.onNodeWithText("예").performClick()
        compose.onNodeWithText("해당 의상을 삭제하시겠습니까?").assertDoesNotExist()
        compose.onNodeWithText("총 0벌").assertIsDisplayed()
        compose.onNodeWithText("아직 등록된 옷이 없습니다").assertIsDisplayed()
    }

    @Test fun busyDeleteDisablesOtherDeleteIcons() {
        compose.setContent { StyleMateTheme {
            WardrobeScreen(listOf(WardrobeItem("one", "내 셔츠", "top")), null, false, {},
                onDelete = {}, deletingId = "one", deleteError = "삭제 오류")
        } }
        openFullGrid()
        compose.onNodeWithContentDescription("내 셔츠 삭제").assertIsNotEnabled()
    }

    @Test fun failedDeleteKeepsTheConfirmationAndCanRetry() {
        val busy = mutableStateOf<String?>(null)
        val error = mutableStateOf<String?>(null)
        var requests = 0
        compose.setContent { StyleMateTheme {
            WardrobeScreen(listOf(WardrobeItem("one", "내 셔츠", "top")), null, false, {},
                onDelete = { requests++; busy.value = it.id }, deletingId = busy.value, deleteError = error.value)
        } }
        openFullGrid()
        compose.onNodeWithContentDescription("내 셔츠 삭제").performClick()
        compose.onNodeWithText("예").performClick()
        compose.onNodeWithText("예").assertIsNotEnabled()
        compose.onNodeWithText("아니요").assertIsNotEnabled()
        compose.runOnIdle { busy.value = null; error.value = "서버에 연결하지 못했어요." }
        compose.onNodeWithText("서버에 연결하지 못했어요.").assertIsDisplayed()
        compose.onNodeWithText("예").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, requests) }
    }

    @Test fun emptyWardrobeCanStartCapture() {
        var captures = 0
        compose.setContent { StyleMateTheme { WardrobeScreen(emptyList(), null, false, { captures++ }) } }
        compose.onNodeWithText("총 0벌").assertIsDisplayed()
        compose.onNodeWithText("첫 번째 옷을 걸어보세요").assertIsDisplayed()
        compose.onNodeWithText("등록하기").performClick()
        compose.onNodeWithText("직접 촬영하기").performClick()
        compose.runOnIdle { assertEquals(1, captures) }
    }

    @Test fun categoryFiltersClothesWithoutChangingTotal() {
        val garments = listOf(WardrobeItem("1", "셔츠", "top"), WardrobeItem("2", "코트", "outerwear"))
        compose.setContent { StyleMateTheme { WardrobeScreen(garments, null, false, {}) } }
        compose.onNode(hasText("아우터") and isSelectable()).performClick()
        compose.onNodeWithText("코트").assertIsDisplayed()
        compose.onNodeWithText("셔츠").assertDoesNotExist()
        compose.onNodeWithText("총 2벌").assertIsDisplayed()
        compose.onNode(hasText("하의") and isSelectable()).performClick()
        compose.onNodeWithText("이 카테고리는 아직 비어 있어요").assertIsDisplayed()
        compose.onNode(hasText("전체") and isSelectable()).performClick()
        // The rail keeps the garment that was centred (코트); the shirt is back on the rail beside it.
        compose.onNodeWithContentDescription("셔츠").assertExists()
        compose.onNodeWithText("총 2벌").assertIsDisplayed()
    }

    @Test fun pendingCaptureDisablesDuplicateLaunchAndKeepsMessage() {
        compose.setContent { StyleMateTheme { WardrobeScreen(emptyList(), "권한을 확인해 주세요", true, {}) } }
        compose.onNodeWithText("촬영 중").assertIsNotEnabled()
        compose.onNodeWithText("권한을 확인해 주세요").assertIsDisplayed()
    }

    @Test fun savedCardOpensItsGarment() {
        var opened: String? = null
        compose.setContent { StyleMateTheme {
            WardrobeScreen(listOf(WardrobeItem("saved-id", "내 셔츠", "top")), null, false, {},
                onItemClick = { opened = it.id })
        } }
        // On the rail the centred garment opens its detail; the caption below is not a button.
        compose.onNodeWithContentDescription("내 셔츠").performClick()
        compose.runOnIdle { assertEquals("saved-id", opened) }
    }

    @Test fun choosingAGarmentOffersOutfitRecommendationsWithoutStartingUntilTapped() {
        var recommended: String? = null
        compose.setContent { StyleMateTheme {
            WardrobeScreen(listOf(WardrobeItem("shirt-id", "내 셔츠", "top")), null, false, {},
                onRecommendOutfits = { recommended = it.id }, onFindMatchingClothes = {})
        } }
        compose.onNodeWithContentDescription("내 셔츠").performClick()
        compose.onNodeWithText("이 옷을 포함한 코디 추천받기").assertIsDisplayed()
        compose.onNodeWithText("함께 입을 옷 찾기").assertIsDisplayed()
        compose.onNodeWithText("상세 정보 보기").assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, recommended) }
        compose.onNodeWithText("이 옷을 포함한 코디 추천받기").performClick()
        compose.runOnIdle { assertEquals("shirt-id", recommended) }
        compose.onNodeWithText("이 옷을 포함한 코디 추천받기").assertDoesNotExist()
    }

    @Test fun theGarmentSheetKeepsTheMatchingClothesAction() {
        var matched: String? = null
        compose.setContent { StyleMateTheme {
            WardrobeScreen(listOf(WardrobeItem("coat-id", "내 코트", "outerwear")), null, false, {},
                onRecommendOutfits = {}, onFindMatchingClothes = { matched = it.id })
        } }
        compose.onNodeWithContentDescription("내 코트").performClick()
        compose.onNodeWithText("함께 입을 옷 찾기").performClick()
        compose.runOnIdle { assertEquals("coat-id", matched) }
    }

    @Test fun theGarmentSheetCanStillOpenTheSelectedGarmentsDetails() {
        var opened: String? = null
        compose.setContent { StyleMateTheme {
            WardrobeScreen(listOf(WardrobeItem("jeans-id", "내 청바지", "bottom")), null, false, {},
                onItemClick = { opened = it.id }, onRecommendOutfits = {})
        } }
        compose.onNodeWithContentDescription("내 청바지").performClick()
        compose.onNodeWithText("상세 정보 보기").performClick()
        compose.runOnIdle { assertEquals("jeans-id", opened) }
    }

    @Test fun unsupportedShoesOpenDetailsWithoutOfferingOutfitRecommendations() {
        var opened: String? = null
        compose.setContent { StyleMateTheme {
            WardrobeScreen(listOf(WardrobeItem("shoes-id", "내 운동화", "shoes")), null, false, {},
                onItemClick = { opened = it.id }, onRecommendOutfits = {})
        } }
        compose.onNodeWithContentDescription("내 운동화").performClick()
        compose.onNodeWithText("이 옷을 포함한 코디 추천받기").assertDoesNotExist()
        compose.runOnIdle { assertEquals("shoes-id", opened) }
    }
}

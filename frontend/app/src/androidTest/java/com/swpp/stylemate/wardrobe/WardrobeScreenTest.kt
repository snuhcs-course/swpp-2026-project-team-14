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

    @Test fun deleteIconOpensConfirmationAndNoKeepsTheGarment() {
        var opened = 0
        var deleted = 0
        compose.setContent { StyleMateTheme {
            WardrobeScreen(listOf(WardrobeItem("one", "내 셔츠", "top")), null, false, {},
                onItemClick = { opened++ }, onDelete = { deleted++ })
        } }
        compose.onNodeWithContentDescription("내 셔츠 삭제").performClick()
        compose.onNodeWithText("'내 셔츠'을(를) 옷장에서 삭제할까요?").assertIsDisplayed()
        compose.onNodeWithText("이 옷이 들어간 코디북 코디도 함께 삭제돼요.").assertIsDisplayed()
        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithText("'내 셔츠'을(를) 옷장에서 삭제할까요?").assertDoesNotExist()
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
        compose.onNodeWithContentDescription("내 셔츠 삭제").performClick()
        compose.onNodeWithText("삭제").performClick()
        compose.onNodeWithText("'내 셔츠'을(를) 옷장에서 삭제할까요?").assertDoesNotExist()
        compose.onNodeWithText("총 0벌").assertIsDisplayed()
        compose.onNodeWithText("아직 등록된 옷이 없습니다").assertIsDisplayed()
    }

    @Test fun busyDeleteDisablesOtherDeleteIcons() {
        compose.setContent { StyleMateTheme {
            WardrobeScreen(listOf(WardrobeItem("one", "내 셔츠", "top")), null, false, {},
                onDelete = {}, deletingId = "one", deleteError = "삭제 오류")
        } }
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
        compose.onNodeWithContentDescription("내 셔츠 삭제").performClick()
        compose.onNodeWithText("삭제").performClick()
        compose.onNodeWithText("삭제 중…").assertIsNotEnabled()
        compose.onNodeWithText("취소").assertIsNotEnabled()
        compose.runOnIdle { busy.value = null; error.value = "서버에 연결하지 못했어요." }
        compose.onNodeWithText("서버에 연결하지 못했어요.").assertIsDisplayed()
        compose.onNodeWithText("삭제").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, requests) }
    }

    @Test fun emptyWardrobeCanStartCapture() {
        var captures = 0
        compose.setContent { StyleMateTheme { WardrobeScreen(emptyList(), null, false, { captures++ }) } }
        compose.onNodeWithText("총 0벌").assertIsDisplayed()
        compose.onNodeWithText("아직 등록된 옷이 없습니다").assertIsDisplayed()
        compose.onNodeWithText("등록하기").performClick()
        compose.onNodeWithText("직접 촬영하기").performClick()
        compose.runOnIdle { assertEquals(1, captures) }
    }

    @Test fun categoryFiltersClothesWithoutChangingTotal() {
        val garments = listOf(WardrobeItem("1", "셔츠", "top"), WardrobeItem("2", "코트", "outerwear"))
        compose.setContent { StyleMateTheme { WardrobeScreen(garments, null, false, {}) } }
        compose.onNodeWithText("아우터").performClick()
        compose.onNodeWithText("코트").assertIsDisplayed()
        compose.onNodeWithText("셔츠").assertDoesNotExist()
        compose.onNodeWithText("총 2벌").assertIsDisplayed()
        compose.onNodeWithText("하의").performClick()
        compose.onNodeWithText("이 카테고리에 등록된 옷이 없습니다").assertIsDisplayed()
        compose.onNodeWithText("전체", useUnmergedTree = true).performClick()
        compose.onNodeWithText("셔츠").assertIsDisplayed()
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
        compose.onNodeWithText("내 셔츠").performClick()
        compose.runOnIdle { assertEquals("saved-id", opened) }
    }
}

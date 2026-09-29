package com.stylemate.localdev

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WardrobeScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyWardrobeCanStartCapture() {
        var captures = 0
        compose.setContent { MaterialTheme { WardrobeScreen(emptyList(), null, false, { captures++ }, {}) } }
        compose.onNodeWithText("총 0벌").assertIsDisplayed()
        compose.onNodeWithText("아직 등록된 옷이 없습니다").assertIsDisplayed()
        compose.onNodeWithText("촬영하기").performClick()
        compose.runOnIdle { assertEquals(1, captures) }
    }

    @Test fun categoryFiltersClothesWithoutChangingTotal() {
        val garments = listOf(WardrobeItem("1", "셔츠", "top"), WardrobeItem("2", "코트", "outerwear"))
        compose.setContent { MaterialTheme { WardrobeScreen(garments, null, false, {}, {}) } }
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
        compose.setContent { MaterialTheme { WardrobeScreen(emptyList(), "권한을 확인해 주세요", true, {}, {}) } }
        compose.onNodeWithText("촬영 중").assertIsNotEnabled()
        compose.onNodeWithText("권한을 확인해 주세요").assertIsDisplayed()
    }

    @Test fun savedCardOpensItsGarment() {
        var opened: String? = null
        compose.setContent { MaterialTheme {
            WardrobeScreen(listOf(WardrobeItem("saved-id", "내 셔츠", "top")), null, false, {}, {},
                onItemClick = { opened = it.id })
        } }
        compose.onNodeWithText("내 셔츠").performClick()
        compose.runOnIdle { assertEquals("saved-id", opened) }
    }
}

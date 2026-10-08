// AI-generated with Codex, 2026-10-03, reviewed by Hyeon U Jeong
package com.swpp.stylemate.wardrobe

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.swpp.stylemate.data.FakeBodyAnalyzer
import com.swpp.stylemate.ui.StyleMateApp
import com.swpp.stylemate.ui.profile.BodyProfileViewModel
import com.swpp.stylemate.ui.theme.StyleMateTheme
import org.junit.Rule
import org.junit.Test

class AppNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onboardingOpensWardrobeAndProfileRemainsReachable() {
        val model = BodyProfileViewModel(FakeBodyAnalyzer(0))
        compose.setContent { StyleMateTheme { StyleMateApp(model) } }
        compose.onNodeWithText("건너뛰기").performClick()
        compose.onNodeWithText("내 옷장").assertIsDisplayed()
        compose.onNodeWithText("촬영하기").assertIsDisplayed()
        compose.onNodeWithText("개발용 DB 연결 확인").assertDoesNotExist()
        compose.onNodeWithText("마이프로필").performClick()
        compose.onNodeWithText("체형 분석 시작하기").assertIsDisplayed()
        compose.onNodeWithText("옷장").performClick()
        compose.onNodeWithText("촬영하기").assertIsDisplayed()
    }
}

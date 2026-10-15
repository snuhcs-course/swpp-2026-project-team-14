// AI-generated with Codex, 2026-10-03, reviewed by Hyeon U Jeong
package com.swpp.stylemate.wardrobe

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.swpp.stylemate.data.FakeBodyAnalyzer
import com.swpp.stylemate.data.StyleProfile
import com.swpp.stylemate.ui.profile.MyProfileScreen
import com.swpp.stylemate.ui.profile.AuthViewModel
import com.swpp.stylemate.data.AuthRepository
import com.swpp.stylemate.data.AuthSessionStore
import org.junit.Assert.assertEquals
import com.swpp.stylemate.ui.StyleMateApp
import com.swpp.stylemate.ui.profile.BodyProfileViewModel
import com.swpp.stylemate.ui.theme.StyleMateTheme
import org.junit.Rule
import org.junit.Test

class AppNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onboardingOpensWardrobeAndProfileRemainsReachable() {
        val model = BodyProfileViewModel(FakeBodyAnalyzer(0))
        val auth = guestAuth()
        compose.setContent { StyleMateTheme { StyleMateApp(model, auth) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("건너뛰기").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("건너뛰기").performClick()
        compose.onNodeWithText("내 옷장").assertIsDisplayed()
        compose.onNodeWithText("촬영하기").assertIsDisplayed()
        compose.onNodeWithText("개발용 DB 연결 확인").assertDoesNotExist()
        compose.onNodeWithText("마이프로필").performClick()
        compose.onNodeWithText("선호하는 핏").assertIsDisplayed()
        compose.onNodeWithText("내 체형 분석하기").performScrollTo().performClick()
        compose.onNodeWithText("체형 분석 시작하기").assertIsDisplayed()
        compose.onNodeWithText("뒤로").performClick()
        compose.onNodeWithText("옷장").performClick()
        compose.onNodeWithText("촬영하기").assertIsDisplayed()
    }

    @Test fun colorsAreOnlyAppliedAfterSavingAndCanBeCleared() {
        var saved by mutableStateOf(StyleProfile())
        compose.setContent { StyleMateTheme { MyProfileScreen(null, saved, { saved = it; null }, {}, {}) } }
        compose.onNodeWithText("팔레트 관리").performScrollTo().performClick()
        compose.onNodeWithText("네이비").performClick()
        compose.onNodeWithText("취소").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), saved.favoriteColors) }
        compose.onNodeWithText("팔레트 관리").performScrollTo().performClick()
        compose.onNodeWithText("네이비").performClick()
        compose.onNodeWithText("저장하기").performClick()
        compose.runOnIdle { assertEquals(listOf("#1F2A44"), saved.favoriteColors) }
        compose.onNodeWithText("팔레트 관리").performScrollTo().performClick()
        compose.onAllNodesWithText("네이비").onLast().performClick()
        compose.onNodeWithText("저장하기").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), saved.favoriteColors) }
    }

    @Test fun fitAndMoodCanBeSavedWithoutBodyMeasurements() {
        val model = BodyProfileViewModel(FakeBodyAnalyzer(0))
        model.skipSetup()
        val auth = guestAuth()
        compose.setContent { StyleMateTheme { StyleMateApp(model, auth) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("마이프로필").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("마이프로필").performClick()
        compose.onAllNodesWithText("수정").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("와이드").performClick()
        compose.onNodeWithText("저장하기").performClick()
        compose.runOnIdle { assertEquals(listOf("regular", "wide"), model.app.value.style.preferredFits) }
        compose.onAllNodesWithText("수정").onLast().performScrollTo().performClick()
        compose.onNodeWithText("시티보이").performClick()
        compose.onNodeWithText("저장하기").performClick()
        compose.runOnIdle { assertEquals(listOf("cityboy"), model.app.value.style.preferredStyles); assertEquals(null, model.app.value.profile) }
    }

    private fun guestAuth() = AuthViewModel(AuthRepository("http://127.0.0.1:1", object : AuthSessionStore {
        override fun read(): String? = null
        override fun write(value: String) = Unit
        override fun clear() = Unit
    }))
}

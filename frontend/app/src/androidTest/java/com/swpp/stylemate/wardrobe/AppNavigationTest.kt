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
import kotlinx.coroutines.CompletableDeferred
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

    @Test fun signedOutUsersCannotEnterOnboardingOrServiceTabs() {
        val model = BodyProfileViewModel(FakeBodyAnalyzer(0))
        val auth = guestAuth()
        compose.setContent { StyleMateTheme { StyleMateApp(model, auth) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("나에게 맞는 스타일의 시작").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("홈").assertDoesNotExist()
        compose.onNodeWithText("옷장").assertDoesNotExist()
        compose.onNodeWithText("건너뛰기").assertDoesNotExist()
        compose.onNodeWithContentDescription("닫기").assertDoesNotExist()
        compose.onNodeWithText("회원가입").performClick()
        compose.onNodeWithText("비밀번호 확인").assertExists()
        compose.onNodeWithContentDescription("뒤로").performClick()
        compose.onNodeWithText("나에게 맞는 스타일의 시작").assertIsDisplayed()
    }

    @Test fun colorsAreOnlyAppliedAfterSavingAndCanBeCleared() {
        var saved by mutableStateOf(StyleProfile())
        compose.setContent { StyleMateTheme { MyProfileScreen(null, saved, { saved = it; null }, {}, {}) } }
        compose.onNodeWithText("컬러 기록").performScrollTo().performClick()
        compose.onNodeWithText("네이비").performScrollTo().performClick()
        compose.onNodeWithText("취소").assertDoesNotExist()
        compose.onNodeWithContentDescription("뒤로").performClick()
        compose.onNodeWithText("변경 내용을 버릴까요?").assertIsDisplayed()
        compose.onNodeWithText("버리기").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), saved.favoriteColors) }
        compose.onNodeWithText("컬러 기록").performScrollTo().performClick()
        compose.onNodeWithText("네이비").performScrollTo().performClick()
        compose.onNodeWithText("저장하기").performClick()
        compose.runOnIdle { assertEquals(listOf("#1F2A44"), saved.favoriteColors) }
        compose.onNodeWithText("컬러 기록").performScrollTo().performClick()
        compose.onAllNodesWithText("네이비").onLast().performScrollTo().performClick()
        compose.onNodeWithText("저장하기").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), saved.favoriteColors) }
    }

    @Test fun profileHasLogoutAndNoGuestLoginActions() {
        var loggedOut = false
        compose.setContent { StyleMateTheme {
            MyProfileScreen(null, StyleProfile(), { null }, {}, {}, username = "member_1", onLogout = { loggedOut = true })
        } }
        compose.onNodeWithText("member_1").assertDoesNotExist()
        compose.onNodeWithText("MY").assertIsDisplayed()
        compose.onNodeWithContentDescription("이 기기의 데이터 가져오기").assertDoesNotExist()
        compose.onNodeWithText("이 기기의 기존 데이터 가져오기").assertDoesNotExist()  // login-only: no guest import (#95)
        compose.onNodeWithText("계정 설정").performScrollTo().performClick()
        compose.onNodeWithText("member_1").assertIsDisplayed()
        compose.onNodeWithText("내 정보를 계정에 보관하세요").assertDoesNotExist()
        compose.onNodeWithText("로그아웃").performClick()
        compose.onNodeWithText("로그아웃할까요?").assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertFalse(loggedOut) }
        compose.onAllNodesWithText("로그아웃").onLast().performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(loggedOut) }
    }

    @Test fun failedPreferenceSaveKeepsSelectionAndBlocksDuplicateSubmissionUntilRetry() {
        val pending = CompletableDeferred<Unit>()
        var calls = 0
        var saved by mutableStateOf(StyleProfile())
        compose.setContent { StyleMateTheme {
            MyProfileScreen(null, saved, {
                calls++
                if (calls == 1) { pending.await(); "저장 연결 오류" } else { saved = it; null }
            }, {}, {})
        } }
        compose.onNodeWithText("컬러 기록").performScrollTo().performClick()
        compose.onNodeWithText("네이비").performScrollTo().performClick()
        compose.onNodeWithText("저장하기").performClick()
        compose.onNodeWithText("저장 중…").assertIsNotEnabled()
        compose.onNodeWithContentDescription("뒤로").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls); pending.complete(Unit) }
        compose.onNodeWithText("저장 연결 오류").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<String>(), saved.favoriteColors) }
        compose.onNodeWithText("저장하기").performClick()
        compose.runOnIdle { assertEquals(2, calls); assertEquals(listOf("#1F2A44"), saved.favoriteColors) }
    }

    @Test fun fitIsEditedExplicitlyAndFailedSaveRetainsItsDraftAndOtherPreferences() {
        val pending = CompletableDeferred<Unit>()
        var calls = 0
        var saved by mutableStateOf(StyleProfile(preferredFits = listOf("regular", "slim"),
            preferredStyles = listOf("minimal"), favoriteColors = listOf("#1F2A44")))
        compose.setContent { StyleMateTheme {
            MyProfileScreen(null, saved, {
                calls++
                if (calls == 1) { pending.await(); "저장 연결 오류" } else { saved = it; null }
            }, {}, {})
        } }
        compose.onNodeWithText("와이드").assertDoesNotExist()
        compose.onNodeWithText("나의 취향").performClick()
        compose.onNodeWithText("와이드").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, calls); assertEquals(listOf("regular", "slim"), saved.preferredFits) }
        compose.onNodeWithText("저장하기").performClick()
        compose.onNodeWithText("저장 중…").assertIsNotEnabled()
        compose.onNodeWithContentDescription("뒤로").assertIsNotEnabled()
        compose.runOnIdle { pending.complete(Unit) }
        compose.onNodeWithText("저장 연결 오류").assertIsDisplayed()
        compose.onNodeWithText("와이드").assertIsSelected()
        compose.onNodeWithText("저장하기").performClick()
        compose.runOnIdle {
            assertEquals(2, calls)
            assertEquals(listOf("wide", "regular", "slim"), saved.preferredFits)
            assertEquals(listOf("minimal"), saved.preferredStyles)
            assertEquals(listOf("#1F2A44"), saved.favoriteColors)
        }
        compose.onNodeWithText("MY").assertIsDisplayed()
    }

    private fun guestAuth() = AuthViewModel(AuthRepository("http://127.0.0.1:1", object : AuthSessionStore {
        override fun read(): String? = null
        override fun write(value: String) = Unit
        override fun clear() = Unit
    }))
}

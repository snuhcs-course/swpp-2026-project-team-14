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
import com.swpp.stylemate.ui.profile.GuestImportDialog
import com.swpp.stylemate.data.GuestImportRecord
import com.swpp.stylemate.data.GuestImportSnapshot
import com.swpp.stylemate.ui.profile.GuestImportState
import com.swpp.stylemate.ui.profile.GuestImportStage
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

    @Test fun skippingOnboardingOpensBodyGuideAndWardrobeAndProfileRemainReachable() {
        val model = BodyProfileViewModel(FakeBodyAnalyzer(0))
        val auth = guestAuth()
        compose.setContent { StyleMateTheme { StyleMateApp(model, auth) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("건너뛰기").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("건너뛰기").performClick()
        compose.onNodeWithText("체형 정보가 필요해요").assertIsDisplayed()
        compose.onNodeWithText("옷장").performClick()
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
        compose.onNodeWithText("취소").assertDoesNotExist()
        compose.onNodeWithContentDescription("뒤로").performClick()
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
        compose.onNodeWithText("수정").assertDoesNotExist()
        compose.onNodeWithText("와이드").performClick()
        compose.runOnIdle { assertEquals(listOf("regular", "wide"), model.app.value.style.preferredFits) }
        compose.onNodeWithText("와이드").performClick()
        compose.runOnIdle { assertEquals(listOf("regular"), model.app.value.style.preferredFits) }
        compose.onNodeWithText("시티보이").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("cityboy"), model.app.value.style.preferredStyles); assertEquals(null, model.app.value.profile) }
        compose.onNodeWithText("시티보이").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), model.app.value.style.preferredStyles) }
        compose.onNodeWithText("여러 개 선택할 수 있어요.").assertDoesNotExist()
        compose.onNodeWithText("선택하지 않으면 레귤러로 저장돼요.").assertDoesNotExist()
        compose.onNodeWithText("내가 편하게 입는 실루엣을 기록해요.").assertDoesNotExist()
    }

    @Test fun accountImportIconOpensCountedConsentAndCancelDoesNotImport() {
        var state by mutableStateOf(GuestImportState())
        var confirmed = 0
        val snapshot = GuestImportSnapshot(listOf(GuestImportRecord("profile", "style", StyleProfile().json(), "test")))
        compose.setContent { StyleMateTheme {
            MyProfileScreen(null, StyleProfile(), { null }, {}, {}, username = "member_1", onImport = {
                state = GuestImportState(GuestImportStage.CONSENT, snapshot)
            })
            GuestImportDialog(state, { confirmed++; state = GuestImportState() }, {}, {}, { state = GuestImportState() })
        } }
        val logout = compose.onNodeWithText("로그아웃").fetchSemanticsNode().boundsInRoot
        val icon = compose.onNodeWithContentDescription("이 기기의 데이터 가져오기")
        val iconBounds = icon.fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("마이프로필").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(iconBounds.bottom <= logout.top)
        org.junit.Assert.assertTrue(kotlin.math.abs(iconBounds.center.y - title.center.y) < 1f)
        icon.performClick()
        compose.onNodeWithText("이 기기의 데이터를 가져올까요?").assertIsDisplayed()
        compose.onNodeWithText("총 1개").assertIsDisplayed()
        compose.onNodeWithText("취소").performClick()
        compose.runOnIdle { assertEquals(0, confirmed) }
        icon.performClick()
        compose.onNode(hasText("가져오기") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(1, confirmed) }
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
        compose.onNodeWithText("팔레트 관리").performScrollTo().performClick()
        compose.onNodeWithText("네이비").performClick()
        compose.onNodeWithText("저장하기").performClick()
        compose.onNodeWithText("저장 중…").assertIsNotEnabled()
        compose.onNodeWithContentDescription("뒤로").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls); pending.complete(Unit) }
        compose.onNodeWithText("저장 연결 오류").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<String>(), saved.favoriteColors) }
        compose.onNodeWithText("저장하기").performClick()
        compose.runOnIdle { assertEquals(2, calls); assertEquals(listOf("#1F2A44"), saved.favoriteColors) }
    }

    @Test fun inlineToggleRollsBackOnFailureAndPreservesOtherPreferences() {
        val pending = CompletableDeferred<Unit>()
        var calls = 0
        var saved by mutableStateOf(StyleProfile(preferredStyles = listOf("minimal"), favoriteColors = listOf("#1F2A44")))
        compose.setContent { StyleMateTheme {
            MyProfileScreen(null, saved, {
                calls++
                if (calls == 1) { pending.await(); "저장 연결 오류" } else { saved = it; null }
            }, {}, {})
        } }
        compose.onNodeWithText("와이드").performClick()
        compose.onNodeWithText("와이드").assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithText("시티보이").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("regular"), saved.preferredFits); pending.complete(Unit) }
        compose.onNodeWithText("저장 연결 오류").assertIsDisplayed()
        compose.onNodeWithText("와이드").assertIsNotSelected().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(2, calls)
            assertEquals(listOf("regular", "wide"), saved.preferredFits)
            assertEquals(listOf("minimal"), saved.preferredStyles)
            assertEquals(listOf("#1F2A44"), saved.favoriteColors)
        }
    }

    private fun guestAuth() = AuthViewModel(AuthRepository("http://127.0.0.1:1", object : AuthSessionStore {
        override fun read(): String? = null
        override fun write(value: String) = Unit
        override fun clear() = Unit
    }))
}

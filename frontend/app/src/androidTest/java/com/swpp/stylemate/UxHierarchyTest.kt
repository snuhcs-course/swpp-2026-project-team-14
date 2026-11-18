package com.swpp.stylemate

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.stylemate.data.recommendation.*
import com.swpp.stylemate.ui.chat.*
import com.swpp.stylemate.ui.home.*
import com.swpp.stylemate.ui.profile.*
import com.swpp.stylemate.ui.theme.StyleMateTheme
import com.swpp.stylemate.ui.theme.enableStyleMateEdgeToEdge
import com.swpp.stylemate.ui.wardrobe.*
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UxHierarchyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val outfit = Outfit("o1", "a+b", 78, listOf(
        OutfitGarment("a", "top", "코튼 셔츠", "", ""), OutfitGarment("b", "bottom", "데님 팬츠", "", "")),
        listOf(Reason("color", "차분한 색상끼리 함께 골랐어요.", ""), Reason("fit", "선호하는 넉넉한 핏이에요.", "")), true, "데일리 코디", null, "")
    private fun show(content: @Composable () -> Unit) {
        compose.setContent { StyleMateTheme { Box(Modifier.width(360.dp).height(700.dp)) { content() } } }
    }
    private fun capture(name: String) {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ux-hierarchy").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            compose.waitForIdle()
            checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun aLargeCodiBookUsesBoundedControlsAndConfirmsStarRemoval() {
        val removed = mutableListOf<String>()
        val rows = (1..250).map { outfit.copy(id = "o$it", key = "k$it", name = "저장 코디 $it") }
        show { CodiBookScreen(CodiBookState(outfits = rows), { removed += it.id }, {}, photo = { _, m -> Box(m.background(Color(0xFFE8E5E0))) }) }
        compose.onNodeWithText("슬라이드").assertDoesNotExist()
        compose.onNodeWithContentDescription("목록 보기").assertIsDisplayed()
        compose.onNodeWithText("1 / 250").assertIsDisplayed()
        compose.onNodeWithContentDescription("다음 코디").performClick()
        compose.onNodeWithText("2 / 250").assertIsDisplayed()
        capture("codibook")
        compose.onNodeWithContentDescription("목록 보기").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("저장 코디 2 크게 보기").performClick()
        compose.onNodeWithContentDescription("슬라이드 보기").assertIsSelected()
        compose.onNodeWithText("2 / 250").assertIsDisplayed()
        compose.onNodeWithContentDescription("코디북에서 빼기").performClick()
        compose.onNodeWithText("취소").performClick()
        compose.runOnIdle { assertTrue(removed.isEmpty()) }
        compose.onNodeWithContentDescription("코디북에서 빼기").performClick()
        compose.onNodeWithText("빼기").performClick()
        compose.runOnIdle { assertEquals(listOf("o2"), removed) }
    }

    @Test fun photoPreviewStaysInlineAndKeepsTheMessageFocused() {
        var state by mutableStateOf(ChatState(source = outfit, current = outfit))
        compose.activityRule.scenario.onActivity {
            it.enableStyleMateEdgeToEdge()
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.setContent { StyleMateTheme { Box(Modifier.width(360.dp).fillMaxHeight()) {
            val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
            ChatScreen(state, {}, { state = state.copy(draft = it) }, { id -> state = state.copy(lockedIds =
                if (id in state.lockedIds) state.lockedIds - id else state.lockedIds + id) }, {}, {}, {}, {}, {}, {}, {},
                photo = { _, m -> Box(m.background(Color(0xFFE8E5E0))) })
        } } }
        compose.onNode(hasSetTextAction()).performTextInput("하의만 바꿔줘")
        // Match MainActivity's window behavior rather than panning a height-constrained test box.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("코디 사진 보기").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("코디 사진 보기").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused().assertTextContains("하의만 바꿔줘")
        compose.onNodeWithContentDescription("오늘의 코디로 돌아가기").assertIsDisplayed()
        compose.onAllNodesWithTag("chat-keep-a").assertCountEquals(1)
        compose.onNodeWithText("그대로 입기").assertDoesNotExist()
        compose.onNodeWithTag("chat-keep-a").assertIsOff().performClick().assertIsOn()
        capture("chat-keyboard-preview")
        compose.onNodeWithText("코디 사진 접기").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test fun homeRatingStaysBelowThePhotoAndReasonsKeepTheirHierarchy() {
        show { HomeScreen(HomeState(outfits = listOf(outfit)), {}, {}, {}, {}, {}, {}, {},
            photo = { _, m -> Box(m.background(Color(0xFFE8E5E0))) }) }
        val photo = compose.onNodeWithContentDescription("코디 1 / 1").fetchSemanticsNode().boundsInRoot
        val rating = compose.onNodeWithContentDescription("추천 별점", substring = true).assertHasNoClickAction().fetchSemanticsNode().boundsInRoot
        assertTrue(rating.top >= photo.bottom)
        compose.onNodeWithText("이 코디를 고른 이유").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("색상").assertIsDisplayed()
        capture("home")
    }
}

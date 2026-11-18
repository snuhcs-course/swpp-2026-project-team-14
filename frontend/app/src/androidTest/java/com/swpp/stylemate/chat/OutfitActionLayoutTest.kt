package com.swpp.stylemate.chat

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.stylemate.data.chat.ChatRequest
import com.swpp.stylemate.data.chat.ChatResult
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitGarment
import com.swpp.stylemate.ui.chat.ChatScreen
import com.swpp.stylemate.ui.chat.ChatState
import com.swpp.stylemate.ui.chat.ChatTurn
import com.swpp.stylemate.ui.home.HomeScreen
import com.swpp.stylemate.ui.home.HomeState
import com.swpp.stylemate.ui.theme.StyleMateTheme
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OutfitActionLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val first = Outfit("first", "top+bottom", 80, listOf(
        OutfitGarment("top", "top", "아주 긴 이름의 코튼 오버핏 셔츠", "", ""),
        OutfitGarment("bottom", "bottom", "스트레이트 데님 팬츠", "", ""),
        OutfitGarment("outer", "outerwear", "울 재킷", "", "")), emptyList(), false, "", null, "")
    private val second = first.copy(id = "second", key = "new-top+bottom")
    private lateinit var state: MutableState<ChatState>
    private val savedKeys = mutableListOf<String>()

    private fun showChat(fontScale: Float = 1f, compact: Boolean = false) {
        val request = ChatRequest(first, emptySet(), emptySet(), "하의 바꿔줘", JSONObject(),
            null, null, emptyList(), emptyList())
        state = mutableStateOf(ChatState(source = first, current = second, snapshots = listOf(first),
            turns = listOf(ChatTurn("updated", request,
                ChatResult("updated", "코디를 바꿨어요.", second, emptySet(), emptySet(), emptySet(), emptyList()), loading = false)),
            feedbackLoaded = setOf(second.key)))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                StyleMateTheme {
                    Box(Modifier.width(320.dp).height(if (compact) 360.dp else 620.dp)) {
                        ChatScreen(state.value, onBack = {}, onDraft = {}, onLock = { id ->
                            state.value = state.value.copy(lockedIds = if (id in state.value.lockedIds)
                                state.value.lockedIds - id else state.value.lockedIds + id)
                        }, onDirection = {}, onSend = {}, onRetry = {}, onUnlock = {},
                            onRestore = {}, onUndo = {}, onSave = { savedKeys += it.key },
                            photo = { item, modifier -> Box(modifier.background(
                                if (item.slot == "bottom") Color(0xFF48576A) else Color(0xFFD9D5CF))) })
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "outfit-actions").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun homeSavedActionStillTogglesTheSelectedOutfit() {
        val events = mutableListOf<Boolean>()
        compose.setContent { StyleMateTheme {
            var saved by remember { mutableStateOf(true) }
            Box(Modifier.width(320.dp).height(620.dp)) {
                HomeScreen(HomeState(outfits = listOf(first.copy(saved = saved))), onRefresh = {},
                    onLike = {}, onDislike = {}, onSave = { saved = !saved; events += saved },
                    onSetupProfile = {}, onOpenWardrobe = {}, onMessageShown = {},
                    photo = { _, modifier -> Box(modifier.background(Color(0xFFD9D5CF))) })
            }
        } }
        compose.onNodeWithContentDescription("코디북에 저장됨").performScrollTo().assertIsEnabled()
        capture("home-saved")
        compose.onNodeWithContentDescription("코디북에 저장됨").performClick()
        compose.onNodeWithContentDescription("코디북에 저장").performClick()
        compose.runOnIdle { assertEquals(listOf(false, true), events) }
    }

    @Test fun chatHeaderSaveTargetsTheCurrentOutfit() {
        showChat()
        val keep = compose.onNodeWithTag("chat-keep-top")
        keep.assertHeightIsAtLeast(48.dp).assertIsOff().performClick().assertIsOn()
        val card = compose.onNodeWithTag("chat-current-outfit").fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithTag("chat-save-outfit").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val keepBounds = keep.fetchSemanticsNode().boundsInRoot
        assertTrue(save.bottom <= card.top + 1f)
        assertTrue(save.center.x > card.center.x)
        capture("chat-actions")
        compose.onNodeWithTag("chat-save-outfit").performClick()
        compose.runOnIdle { assertEquals(listOf(second.key), savedKeys) }
    }

    @Test fun chatGuardsPendingSavesAndAllowsUnsaveAndRetryForTheCurrentOutfit() {
        showChat()
        compose.runOnIdle { state.value = state.value.copy(savingKeys = setOf(second.key)) }
        compose.onNodeWithTag("chat-save-outfit").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(savingKeys = emptySet(), savedKeys = setOf(second.key)) }
        compose.onNodeWithContentDescription("코디북 저장 해제").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(second.key), savedKeys)
            savedKeys.clear()
        }
        compose.runOnIdle { state.value = state.value.copy(savedKeys = emptySet(), turns = listOf(
            ChatTurn("pending", ChatRequest(second, emptySet(), emptySet(), "하의 바꿔줘", JSONObject(),
                null, null, emptyList(), emptyList())))) }
        compose.onNodeWithTag("chat-save-outfit").assertIsNotEnabled()
        compose.onNodeWithTag("chat-keep-top").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(turns = emptyList(), saveErrors = mapOf(second.key to "연결 오류")) }
        compose.onNodeWithTag("chat-save-outfit").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(second.key), savedKeys) }
    }

    @Test fun chatActionsRemainReachableWithLargeText() {
        showChat(fontScale = 2f)
        compose.onNodeWithText("코디 사진 보기").performClick()
        compose.onNodeWithTag("chat-keep-top").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick().assertIsOn()
        compose.onNodeWithTag("chat-save-outfit").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("메시지 보내기").assertIsDisplayed()
        compose.onNodeWithTag("chat-conversation").performScrollToNode(hasContentDescription("별로예요"))
        val dislike = compose.onNodeWithContentDescription("별로예요").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
        assertTrue(dislike.getUnclippedBoundsInRoot().right <= compose.onRoot().getUnclippedBoundsInRoot().right)
        capture("chat-large-text")
        compose.onNodeWithTag("chat-keep-outer").performScrollTo().assertIsDisplayed().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(listOf(second.key), savedKeys) }
    }

    @Test fun compactChatKeepsSaveBesideItsPhotoPreview() {
        showChat(compact = true)
        compose.onNodeWithText("코디 사진 보기").assertIsDisplayed()
        compose.onNodeWithTag("chat-save-outfit").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("메시지 보내기").assertIsDisplayed()
        capture("chat-compact")
        compose.runOnIdle { assertEquals(listOf(second.key), savedKeys) }
    }
}

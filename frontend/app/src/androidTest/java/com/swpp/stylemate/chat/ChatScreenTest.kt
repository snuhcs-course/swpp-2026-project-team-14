package com.swpp.stylemate.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.data.chat.ChatRequest
import com.swpp.stylemate.data.chat.ChatResult
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitGarment
import com.swpp.stylemate.ui.chat.*
import com.swpp.stylemate.ui.theme.StyleMateTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatScreenTest {
    @get:Rule val compose = createComposeRule()
    private val top = OutfitGarment("t", "top", "검정 티", "", "")
    private val pants = OutfitGarment("p", "bottom", "청바지", "", "")
    private val skirt = OutfitGarment("s", "bottom", "회색 스커트", "", "")
    private val original = Outfit("o1", "t+p", null, listOf(top, pants), emptyList(), false, "", null, "")
    private val edited = original.copy(id = "o2", key = "t+s", items = listOf(top, skirt))
    private lateinit var state: MutableState<ChatState>
    private val events = mutableListOf<String>()

    private fun request() = ChatRequest(original, setOf("t"), setOf("brighter"), "하의만 바꿔줘", JSONObject(), null, null, emptyList(), emptyList())
    private fun updated(id: String = "turn") = ChatTurn(id, request(),
        ChatResult("updated", "하의를 바꿨어요. 잠근 옷은 그대로 유지했어요.", edited, setOf("s"), setOf("t"), emptySet(), listOf("날씨 정보가 없어요.")), loading = false)
    private fun resultState() = ChatState(source = original, current = edited, lockedIds = setOf("t"), turns = listOf(updated()),
        snapshots = listOf(original), feedbackLoaded = setOf(edited.key))

    private fun scrollToText(text: String, substring: Boolean = false): SemanticsNodeInteraction {
        compose.onNodeWithTag("chat-conversation").performScrollToNode(hasText(text, substring = substring))
        return compose.onNodeWithText(text, substring = substring)
    }

    private fun show(initial: ChatState = ChatState(source = original, current = original), height: Dp = 620.dp) {
        state = mutableStateOf(initial)
        compose.setContent { StyleMateTheme { Box(Modifier.fillMaxSize()) {
            ChatScreen(state.value, onBack = { events += "back" }, onDraft = { state.value = state.value.copy(draft = it) },
                onLock = { id -> state.value = state.value.copy(lockedIds = if (id in state.value.lockedIds) state.value.lockedIds - id else state.value.lockedIds + id) },
                onDirection = { code -> state.value = state.value.copy(directions = if (code in state.value.directions) state.value.directions - code else state.value.directions + code) },
                onSend = { events += "send:${state.value.draft}:${state.value.directions.joinToString()}" },
                onRetry = { events += "retry:$it" }, onUnlock = { events += "unlock:$it" },
                onRestore = { events += "restore:${it.key}"; state.value = state.value.copy(current = it) },
                onUndo = { events += "undo" }, onSave = { events += "save:${it.key}" },
                onFeedback = { outfit, value -> events += "feedback:${outfit.key}:$value" },
                onLoadFeedback = { events += "load:${it.key}" },
                onEditRequest = { id ->
                    events += "edit:$id"
                    val turn = state.value.turns.first { it.id == id }
                    state.value = state.value.copy(draft = turn.request.text, directions = turn.request.directions)
                }, modifier = Modifier.width(320.dp).height(height),
                photo = { _, modifier -> Box(modifier.background(Color.LightGray)) })
        } } }
    }

    @Test fun selectedConditionsStayInOneRowAndUseTheOnlySendAction() {
        show()
        val before = compose.onNodeWithTag("chat-conversation").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithContentDescription("메시지 보내기").assertIsNotEnabled()
        compose.onNodeWithContentDescription("수정 조건 선택").performClick()
        compose.onNodeWithTag("chat-direction-different").assertDoesNotExist()
        compose.onNodeWithTag("chat-direction-brighter").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("chat-direction-warmer").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertTrue(events.none { it.startsWith("send:") }) }
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithContentDescription("밝게 조건 해제").assertIsDisplayed()
        compose.onNodeWithContentDescription("메시지 보내기").assertIsEnabled()
        compose.onAllNodesWithContentDescription("메시지 보내기").assertCountEquals(1)
        compose.onNodeWithText("선택한 조건으로 수정").assertDoesNotExist()
        compose.onNodeWithTag("chat-conversation").assertHeightIsAtLeast(180.dp)
        assertTrue(compose.onNodeWithTag("chat-conversation").fetchSemanticsNode().boundsInRoot.height <= before)
        compose.onNodeWithContentDescription("메시지 보내기").performClick()
        compose.runOnIdle { assertEquals("send::brighter, warmer", events.last()) }
    }

    @Test fun removingAConditionDoesNotSendAMessage() {
        show(ChatState(source = original, current = original, directions = setOf("brighter")))
        compose.onNodeWithContentDescription("밝게 조건 해제").performClick()
        compose.onNodeWithContentDescription("메시지 보내기").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(events.none { it.startsWith("edit:") || it.startsWith("send:") }); assertTrue(state.value.directions.isEmpty()) }
    }

    @Test fun currentOutfitHasOneInteractivePreviewAndAConciseChangeReply() {
        show(resultState())
        compose.onNodeWithText("대화로 수정하기").assertIsDisplayed()
        compose.onAllNodesWithTag("chat-keep-s").assertCountEquals(1)
        compose.onNodeWithTag("chat-keep-s").assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertTrue("s" in state.value.lockedIds) }
        scrollToText("코디를 바꿨어요.\n하의: 청바지 → 회색 스커트").assertIsDisplayed()
        scrollToText("하의만 바꿔줘", substring = true).assertIsDisplayed()
        compose.onNodeWithText("유지: 검정 티", substring = true).assertDoesNotExist()
        compose.onNodeWithText("이 코디로 계속 수정 중").assertDoesNotExist()
        compose.onNodeWithText("펼치기").assertDoesNotExist()
        compose.onNodeWithContentDescription("코디북에 저장").assertIsDisplayed()
        compose.onNodeWithContentDescription("코디북에 저장").performClick()
        compose.runOnIdle { assertTrue("save:t+s" in events) }
    }

    @Test fun feedbackFailureDoesNotLookLikeAnEditFailure() {
        show(resultState().copy(feedbackLoaded = emptySet(), feedbackLoading = setOf(edited.key)))
        compose.runOnIdle { state.value = state.value.copy(feedbackLoading = emptySet(),
            feedbackMessages = mapOf(edited.key to "요청을 처리하지 못했어요.")) }
        scrollToText("평가 다시 불러오기").assertIsDisplayed()
        compose.onNodeWithText("코디 수정 실패").assertDoesNotExist()
        compose.onNodeWithContentDescription("좋아요").assertIsNotEnabled()
        compose.onNodeWithText("평가 다시 불러오기").performClick()
        compose.runOnIdle { assertEquals(2, events.count { it == "load:t+s" }) }
        compose.onNodeWithText("대화로 수정하기").assertIsDisplayed()
    }

    @Test fun failedRequestsCanBeEditedAfterLocksChangedAndExistingDraftNeedsConfirmation() {
        val failed = ChatTurn("failed", request(), error = "연결을 확인해 주세요.", loading = false)
        show(ChatState(source = original, current = original, turns = listOf(failed), lockedIds = setOf("p"), draft = "작성 중인 문장"))
        scrollToText("다시 시도").assertIsNotEnabled()
        scrollToText("요청 수정하기").performClick()
        compose.onNodeWithText("작성 중인 요청을 바꿀까요?").assertIsDisplayed()
        compose.onNodeWithText("계속 작성").performClick()
        compose.runOnIdle { assertEquals("작성 중인 문장", state.value.draft); assertTrue(events.none { it.startsWith("edit:") || it.startsWith("send:") }) }
        compose.onNodeWithText("요청 수정하기").performClick()
        compose.onNodeWithText("불러오기").performClick()
        compose.runOnIdle {
            assertEquals("하의만 바꿔줘", state.value.draft)
            assertEquals(setOf("brighter"), state.value.directions)
            assertEquals(setOf("p"), state.value.lockedIds)
            assertEquals(listOf("edit:failed"), events.filter { it.startsWith("edit:") })
        }
    }

    @Test fun compactViewportKeepsInputAccessibleAndCanOpenTheOutfit() {
        show(height = 360.dp)
        compose.onNodeWithTag("chat-keep-t").assertDoesNotExist()
        compose.onNodeWithContentDescription("메시지 보내기").assertIsDisplayed()
        compose.onNodeWithText("코디 사진 보기").performClick()
        compose.onNodeWithTag("chat-keep-t").assertIsDisplayed()
        compose.onNodeWithText("코디 사진 접기").performClick()
        compose.onNodeWithContentDescription("메시지 보내기").assertIsDisplayed()
    }

    @Test fun unchangedResultIsExplicitAndDoesNotOfferUndoAsIfItChanged() {
        val turn = ChatTurn(request = request(), loading = false,
            result = ChatResult("unchanged", "조건에 맞는 다른 코디가 없어요.", null, emptySet(), emptySet(), emptySet(), emptyList()))
        show(ChatState(source = original, current = original, turns = listOf(turn)))
        scrollToText("변경 없음").assertIsDisplayed()
        compose.onNodeWithText("조건에 맞는 다른 코디가 없어요.").assertIsDisplayed()
        compose.onNodeWithText("처음 코디").assertDoesNotExist()
        compose.onNodeWithContentDescription("수정 기록").assertIsNotEnabled()
    }

    @Test fun latestConversationActionHasItsOwnSpace() {
        show(resultState().copy(turns = (1..8).map { updated("turn-$it") }))
        compose.onNodeWithTag("chat-conversation").performTouchInput { swipeDown() }
        compose.onNodeWithText("최신 대화로").assertIsDisplayed()
        val conversation = compose.onNodeWithTag("chat-conversation").fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithText("최신 대화로").fetchSemanticsNode().boundsInRoot
        assertTrue(action.top >= conversation.bottom - 1f)
    }

    @Test fun feedbackAndSaveAlwaysTargetTheSelectedResult() {
        show(resultState())
        compose.onNodeWithTag("chat-conversation").performScrollToNode(hasContentDescription("좋아요"))
        compose.onNodeWithContentDescription("좋아요").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("별로예요").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("코디북에 저장").assertIsDisplayed()
        compose.onNodeWithContentDescription("코디북에 저장").performClick()
        compose.runOnIdle {
            assertTrue("feedback:t+s:liked" in events)
            assertTrue("feedback:t+s:disliked" in events)
            assertTrue("save:t+s" in events)
        }
    }

    @Test fun historySelectsAndRestoresEarlierOutfitsWithoutSending() {
        show(resultState())
        compose.onNodeWithText("이 코디로 돌아가기").assertDoesNotExist()
        compose.onNodeWithText("이전 코디").assertDoesNotExist()
        compose.onNodeWithContentDescription("수정 기록").performClick()
        compose.onNodeWithTag("chat-history-t+s").assertIsSelected()
        compose.onNodeWithTag("chat-history-t+p").assertIsNotSelected().performClick()
        compose.runOnIdle { assertTrue("restore:t+p" in events); assertEquals(original.key, state.value.current?.key) }
        compose.onNodeWithContentDescription("수정 기록").performClick()
        compose.onNodeWithTag("chat-history-t+p").assertIsSelected()
        compose.onNodeWithTag("chat-history-t+s").assertIsNotSelected().performClick()
        compose.runOnIdle {
            assertTrue("restore:t+s" in events)
            assertEquals(edited.key, state.value.current?.key)
            assertTrue(events.none { it.startsWith("send:") })
        }
    }
}

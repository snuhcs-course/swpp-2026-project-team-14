package com.swpp.stylemate.chat

import com.swpp.stylemate.data.AnalysisInput
import com.swpp.stylemate.data.BodyProfile
import com.swpp.stylemate.data.Gender
import com.swpp.stylemate.data.PreferredFit
import com.swpp.stylemate.data.RoutedServer
import com.swpp.stylemate.data.await
import com.swpp.stylemate.data.failure
import com.swpp.stylemate.data.json
import com.swpp.stylemate.data.outfitJson
import com.swpp.stylemate.data.chat.ChatRepository
import com.swpp.stylemate.data.recommendation.InMemoryTasteStore
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitRepository
import com.swpp.stylemate.data.testAccount
import com.swpp.stylemate.ui.chat.ChatState
import com.swpp.stylemate.ui.chat.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// 채팅 코디 수정 ViewModel의 상태 전이(세션, 입력, 잠금, 방향, 보내기/재시도, 되돌리기, 저장)를 검증한다.
// 만족도(좋아요/별로예요)는 ChatViewModelFeedbackTest에서 따로 다룬다.
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private lateinit var server: RoutedServer
    private lateinit var viewModel: ChatViewModel
    private val profile = BodyProfile(AnalysisInput(heightCm = 170, weightKg = null, gender = Gender.FEMALE),
        emptyList(), PreferredFit.REGULAR, listOf("미니멀"))
    private val start = outfit("o1", "a", "b")
    private val edited = outfitJson("chat-1", "c", "b")

    private fun outfit(id: String, top: String, bottom: String): Outfit = OutfitRepository.parseOutfit(outfitJson(id, top, bottom))

    private fun chat(status: String, message: String, outfit: JSONObject? = null, changed: List<String> = emptyList(),
                     unlock: List<String> = emptyList(), delayMs: Long = 0) =
        json(JSONObject().put("status", status).put("message", message).put("outfit", outfit ?: JSONObject.NULL)
            .put("changed_ids", JSONArray(changed)).put("locked_ids", JSONArray()).put("unlock_ids", JSONArray(unlock))
            .put("notes", JSONArray()).toString(), delayMs)

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = RoutedServer()
        viewModel = ChatViewModel(ChatRepository(server.baseUrl, testAccount(server.baseUrl)), InMemoryTasteStore())
        viewModel.open(start, profile, null)
    }

    @After fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun settled(): ChatState = viewModel.state.await { !it.busy }

    private fun send(text: String) {
        viewModel.setDraft(text)
        viewModel.send()
    }

    @Test fun openingWithoutABodyProfileDoesNothing() {
        val other = ChatViewModel(ChatRepository(server.baseUrl, testAccount(server.baseUrl)), InMemoryTasteStore())
        other.open(start, null, null)
        assertFalse(other.visible.value)
        assertNull(other.state.value.current)
    }

    @Test fun openShowsTheOutfitAndReopeningRestoresTheSession() {
        assertTrue(viewModel.visible.value)
        assertEquals("o1", viewModel.state.value.current?.id)
        viewModel.setDraft("밝게")
        viewModel.close()
        assertFalse(viewModel.visible.value)
        viewModel.open(start, profile, null)
        assertTrue(viewModel.visible.value)
        assertEquals("밝게", viewModel.state.value.draft)
    }

    @Test fun changedBodyProfileStartsANewSession() {
        viewModel.setDraft("밝게")
        viewModel.open(start, profile.copy(preferredFit = PreferredFit.LOOSE), null)
        assertEquals("", viewModel.state.value.draft)
    }

    @Test fun draftIsLimitedTo400Characters() {
        viewModel.setDraft("가".repeat(500))
        assertEquals(400, viewModel.state.value.draft.length)
    }

    @Test fun locksTogglePerGarmentAndAllLockedBlocksSending() {
        viewModel.toggleLock("a")
        assertEquals(setOf("a"), viewModel.state.value.lockedIds)
        viewModel.setDraft("밝게")
        assertTrue(viewModel.state.value.canSend)
        viewModel.toggleLock("b")
        assertTrue(viewModel.state.value.allLocked)
        assertFalse(viewModel.state.value.canSend)
        viewModel.toggleLock("a")
        assertEquals(setOf("b"), viewModel.state.value.lockedIds)
    }

    @Test fun choosingADirectionReplacesItsOpposite() {
        viewModel.toggleDirection("brighter")
        viewModel.toggleDirection("darker")
        assertEquals(setOf("darker"), viewModel.state.value.directions)
        viewModel.toggleDirection("formal")
        assertEquals(setOf("darker", "formal"), viewModel.state.value.directions)
        viewModel.toggleDirection("darker")
        assertEquals(setOf("formal"), viewModel.state.value.directions)
    }

    @Test fun sendNeedsTextOrADirection() {
        assertFalse(viewModel.state.value.canSend)
        viewModel.send()
        assertTrue(viewModel.state.value.turns.isEmpty())
        viewModel.toggleDirection("brighter")
        assertTrue(viewModel.state.value.canSend)
    }

    @Test fun successfulEditReplacesTheOutfitAndKeepsThePreviousAsASnapshot() {
        server.on("POST /api/outfits/chat/", chat("updated", "상의를 바꿨어요.", edited, changed = listOf("c")))
        viewModel.toggleLock("b")
        viewModel.toggleDirection("brighter")
        send("상의만 밝게")
        val state = settled()
        assertEquals("chat-1", state.current?.id)
        assertEquals(listOf("o1"), state.snapshots.map { it.id })
        assertEquals(setOf("b"), state.lockedIds) // 새 코디에도 있는 잠근 옷은 유지
        assertEquals("", state.draft)
        assertTrue(state.directions.isEmpty())
        assertEquals("상의를 바꿨어요.", state.turns.single().result?.message)
        val sent = server.requests("POST /api/outfits/chat/").single()
        assertEquals("상의만 밝게", sent.getString("text"))
        assertEquals("b", sent.getJSONArray("locked_ids").getString(0))
        assertEquals("brighter", sent.getJSONArray("directions").getString(0))
    }

    @Test fun locksOnGarmentsThatLeftTheOutfitAreDropped() {
        server.on("POST /api/outfits/chat/", chat("updated", "상의를 바꿨어요.", edited))
        viewModel.toggleLock("a")
        send("다른 걸로")
        assertTrue(settled().lockedIds.isEmpty())
    }

    @Test fun sendIsIgnoredWhileARequestIsRunning() {
        server.on("POST /api/outfits/chat/", chat("updated", "바꿨어요.", edited, delayMs = 300))
        send("밝게")
        assertTrue(viewModel.state.value.busy)
        send("어둡게")
        settled()
        assertEquals(1, server.requests("POST /api/outfits/chat/").size)
    }

    @Test fun undoWithoutAPreviousOutfitSaysSo() {
        server.on("POST /api/outfits/chat/", chat("undo", "이전 코디로 되돌릴게요."))
        send("아까 코디로")
        val state = settled()
        assertEquals("o1", state.current?.id)
        assertEquals("되돌릴 이전 코디가 없어요.", state.turns.single().result?.message)
    }

    @Test fun undoResultRestoresThePreviousOutfit() {
        server.on("POST /api/outfits/chat/", chat("updated", "바꿨어요.", edited), chat("undo", "이전 코디로 되돌릴게요."))
        send("밝게")
        settled()
        send("아까 코디로")
        val state = settled()
        assertEquals("o1", state.current?.id)
        assertTrue(state.snapshots.isEmpty())
        assertEquals("이전 코디로 되돌릴게요.", state.turns.last().result?.message)
    }

    @Test fun failedTurnShowsTheErrorAndRetryResendsIt() {
        server.on("POST /api/outfits/chat/", failure(503, "CHAT_MODEL_UNAVAILABLE", "잠시 후 다시 시도해 주세요."),
            chat("updated", "바꿨어요.", edited))
        send("밝게")
        val failed = settled()
        val turn = failed.turns.single()
        assertEquals("잠시 후 다시 시도해 주세요.", turn.error)
        viewModel.retry(turn.id)
        val retried = settled()
        assertNull(retried.turns.single().error)
        assertEquals("chat-1", retried.current?.id)
        assertEquals(2, server.requests("POST /api/outfits/chat/").size)
        assertEquals("밝게", server.requests("POST /api/outfits/chat/").last().getString("text"))
    }

    @Test fun retryIsRefusedAfterTheLocksChanged() {
        server.on("POST /api/outfits/chat/", failure(503, "CHAT_MODEL_UNAVAILABLE", "잠시 후 다시 시도해 주세요."))
        send("밝게")
        val turn = settled().turns.single()
        viewModel.toggleLock("a")
        viewModel.retry(turn.id)
        assertEquals(1, server.requests("POST /api/outfits/chat/").size)
        assertNotNull(viewModel.state.value.turns.single().error)
    }

    @Test fun unlockAndRetryRemovesTheRequestedLocksAndResends() {
        server.on("POST /api/outfits/chat/", chat("unlock", "바지 잠금을 풀고 바꿀까요?", unlock = listOf("b")),
            chat("updated", "바꿨어요.", outfitJson("chat-2", "a", "d")))
        viewModel.toggleLock("b")
        send("바지도 바꿔줘")
        val asked = settled()
        viewModel.unlockAndRetry(asked.turns.single().id)
        val state = settled()
        assertTrue(state.lockedIds.isEmpty())
        assertEquals("chat-2", state.current?.id)
        assertEquals(0, server.requests("POST /api/outfits/chat/").last().getJSONArray("locked_ids").length())
    }

    @Test fun restoringAnEarlierOutfitTrimsLaterSnapshotsAndKeepsValidLocks() {
        server.on("POST /api/outfits/chat/", chat("updated", "바꿨어요.", edited),
            chat("updated", "또 바꿨어요.", outfitJson("chat-2", "e", "f")))
        send("밝게")
        settled()
        viewModel.toggleLock("b")
        send("다른 걸로")
        settled()
        val second = viewModel.state.value
        assertEquals(listOf("o1", "chat-1"), second.snapshots.map { it.id })
        viewModel.toggleLock("e")
        viewModel.restore(second.snapshots[1])
        val state = viewModel.state.value
        assertEquals("chat-1", state.current?.id)
        assertEquals(listOf("o1"), state.snapshots.map { it.id })
        assertTrue(state.lockedIds.isEmpty()) // e는 되돌린 코디에 없어서 잠금이 풀림
        assertEquals("선택한 코디로 돌아왔어요. 이 코디를 기준으로 수정할게요.", state.notice)
    }

    @Test fun undoButtonGoesBackToTheLastSnapshot() {
        server.on("POST /api/outfits/chat/", chat("updated", "바꿨어요.", edited))
        send("밝게")
        settled()
        viewModel.undo()
        assertEquals("o1", viewModel.state.value.current?.id)
        assertTrue(viewModel.state.value.snapshots.isEmpty())
    }

    @Test fun savingMarksTheOutfitSaved() {
        server.on("POST /api/outfits/", json(outfitJson("saved-1", "a", "b", saved = true).toString()))
        viewModel.save(start)
        val state = viewModel.state.await { start.key !in it.savingKeys }
        assertTrue(start.key in state.savedKeys)
        val sent = server.requests("POST /api/outfits/").single()
        assertEquals("chat", sent.getString("source"))
        assertTrue(sent.getJSONObject("context").getString("chat_save_id").isNotBlank())
    }

    @Test fun failedSaveShowsTheErrorAndRetryReusesTheSameToken() {
        server.on("POST /api/outfits/", failure(500, "SERVER_ERROR", "저장 서버 오류"),
            json(outfitJson("saved-1", "a", "b", saved = true).toString()))
        viewModel.save(start)
        val failed = viewModel.state.await { start.key !in it.savingKeys }
        assertEquals("저장 서버 오류", failed.saveErrors[start.key])
        assertFalse(start.key in failed.savedKeys)
        viewModel.save(start)
        val saved = viewModel.state.await { start.key in it.savedKeys }
        assertNull(saved.saveErrors[start.key])
        val tokens = server.requests("POST /api/outfits/").map { it.getJSONObject("context").getString("chat_save_id") }
        assertEquals(2, tokens.size)
        assertEquals(tokens[0], tokens[1]) // 재시도는 같은 토큰이라 서버가 중복 저장하지 않음
    }

    @Test fun savingAnAlreadySavedOutfitDoesNothing() {
        server.on("POST /api/outfits/", json(outfitJson("saved-1", "a", "b", saved = true).toString()))
        viewModel.save(start)
        viewModel.state.await { start.key in it.savedKeys }
        viewModel.save(start)
        assertEquals(1, server.requests("POST /api/outfits/").size)
    }

    @Test fun cancellingTheLastRequestRestoresTheOutfitLocksAndText() {
        server.on("POST /api/outfits/chat/", chat("updated", "상의를 바꿨어요.", edited, changed = listOf("c")))
        viewModel.toggleLock("b")
        send("상의를 화려하게")
        settled()
        viewModel.toggleLock("c")
        assertEquals("chat-1", viewModel.state.value.current?.id)
        viewModel.cancelLast()
        val state = viewModel.state.value
        assertEquals("o1", state.current?.id)
        assertEquals(setOf("b"), state.lockedIds)
        assertTrue(state.turns.isEmpty())
        assertTrue(state.snapshots.isEmpty())
        assertEquals("상의를 화려하게", state.draft)  // the request comes back so it can be rewritten
        assertFalse(state.canCancelLast)
    }

    @Test fun aCancelledRequestIsNotSentAsHistoryAgain() {
        server.on("POST /api/outfits/chat/", chat("updated", "바꿨어요.", edited), chat("updated", "바꿨어요.", edited))
        send("빨간색으로")
        settled()
        viewModel.cancelLast()
        send("밝게")
        settled()
        val history = server.requests("POST /api/outfits/chat/").last().getJSONArray("history")
        assertEquals(0, history.length())
    }

    @Test fun cancellingARunningRequestIgnoresItsLateResult() {
        server.on("POST /api/outfits/chat/", chat("updated", "늦게 온 결과", edited, delayMs = 400))
        send("밝게")
        assertTrue(viewModel.state.value.busy)
        viewModel.cancelLast()
        assertFalse(viewModel.state.value.busy)
        Thread.sleep(700)
        assertEquals("o1", viewModel.state.value.current?.id)
        assertTrue(viewModel.state.value.turns.isEmpty())
    }

    @Test fun undoingASaveTakesTheOutfitOutOfTheCodiBookAndAllowsSavingAgain() {
        server.on("POST /api/outfits/", json(outfitJson("saved-1", "a", "b", saved = true).toString()),
            json(outfitJson("saved-1", "a", "b", saved = true).toString()))
        server.on("PUT /api/outfits/saved-1/", json(outfitJson("saved-1", "a", "b").toString()))
        viewModel.save(start)
        val saved = viewModel.state.await { it.lastSaved != null }
        assertEquals("saved-1", saved.lastSaved?.outfitId)
        viewModel.undoSave()
        val undone = viewModel.state.await { !it.undoingSave && start.key !in it.savedKeys }
        assertNull(undone.lastSaved)
        assertEquals(false, server.requests("PUT /api/outfits/saved-1/").single().getBoolean("saved"))
        viewModel.save(start)
        viewModel.state.await { start.key in it.savedKeys }
        val tokens = server.requests("POST /api/outfits/").map { it.getJSONObject("context").getString("chat_save_id") }
        assertTrue(tokens[0] != tokens[1])  // a new token, so the server saves the outfit again
    }

    @Test fun failedSaveUndoKeepsTheOutfitSaved() {
        server.on("POST /api/outfits/", json(outfitJson("saved-1", "a", "b", saved = true).toString()))
        server.on("PUT /api/outfits/saved-1/", failure(500, "SERVER_ERROR", "취소 서버 오류"))
        viewModel.save(start)
        viewModel.state.await { it.lastSaved != null }
        viewModel.undoSave()
        val state = viewModel.state.await { !it.undoingSave && it.saveErrors.isNotEmpty() }
        assertTrue(start.key in state.savedKeys)
        assertEquals("취소 서버 오류", state.saveErrors[start.key])
    }
}

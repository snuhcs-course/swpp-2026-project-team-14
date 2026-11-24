package com.swpp.stylemate.chat

import com.swpp.stylemate.data.*
import com.swpp.stylemate.data.chat.ChatRepository
import com.swpp.stylemate.data.recommendation.InMemoryTasteStore
import com.swpp.stylemate.data.recommendation.OutfitRepository
import com.swpp.stylemate.data.usage.UsageTracker
import com.swpp.stylemate.ui.chat.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class ChatUsageTest {
    private lateinit var server: RoutedServer
    private val events = CopyOnWriteArrayList<JSONObject>()
    private val terminal = CountDownLatch(1)
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()); server = RoutedServer() }
    @After fun tearDown() { server.shutdown(); Dispatchers.resetMain() }

    private fun model(client: OkHttpClient = OkHttpClient()): ChatViewModel {
        val tracker = UsageTracker(emit = { events.add(it); if (it.optString("phase") == "finished") terminal.countDown() })
        return ChatViewModel(ChatRepository(server.baseUrl, testAccount(server.baseUrl), client), InMemoryTasteStore(), tracker).also {
            it.open(OutfitRepository.parseOutfit(outfitJson("first", "a", "b")), null, null)
        }
    }
    private fun result(status: String, outfit: JSONObject? = null) = JSONObject().put("status", status)
        .put("message", "private reply").put("outfit", outfit ?: JSONObject.NULL)
        .put("changed_ids", JSONArray()).put("locked_ids", JSONArray()).put("unlock_ids", JSONArray()).put("notes", JSONArray())
    private fun send(model: ChatViewModel) { model.setDraft("private request"); model.send() }

    @Test fun updatedOutfitSucceedsWithoutRecordingTheConversation() {
        server.on("POST /api/outfits/chat/", json(result("updated", outfitJson("edited", "c", "b")).toString()))
        val model = model(); send(model); model.state.await { !it.busy }
        assertEquals("success", events.last().getString("outcome"))
        assertEquals("updated", events.last().getJSONObject("details").getString("status"))
        assertFalse(events.joinToString().contains("private"))
    }

    @Test fun noMatchingEditIsSeparateFromTechnicalErrors() {
        server.on("POST /api/outfits/chat/", json(result("unchanged").toString()))
        val model = model(); send(model); model.state.await { !it.busy }
        assertEquals("failure", events.last().getString("outcome"))
        assertEquals("OUTFIT_EDIT_FAILED", events.last().getString("error_code"))
        assertEquals("unchanged", events.last().getJSONObject("details").getString("status"))
    }

    @Test fun askingForClarificationIsASuccessfulResponse() {
        server.on("POST /api/outfits/chat/", json(result("clarify").toString()))
        val model = model(); send(model); model.state.await { !it.busy }
        assertEquals("success", events.last().getString("outcome"))
    }

    @Test fun serverFailureRetainsOnlyItsStructuredCode() {
        server.on("POST /api/outfits/chat/", failure(502, "AI_UNAVAILABLE", "private server details"))
        val model = model(); send(model); model.state.await { !it.busy }
        assertEquals("AI_UNAVAILABLE", events.last().getString("error_code"))
        assertFalse(events.joinToString().contains("private"))
    }

    @Test fun readTimeoutIsNotReportedAsAnOrdinaryNetworkFailure() {
        server.on("POST /api/outfits/chat/", json(result("clarify").toString(), delayMs = 250))
        val model = model(OkHttpClient.Builder().readTimeout(30, TimeUnit.MILLISECONDS).build())
        send(model); model.state.await { !it.busy }
        assertEquals("TIMEOUT", events.last().getString("error_code"))
    }

    @Test fun takingBackARunningRequestRecordsOneCancellation() {
        server.on("POST /api/outfits/chat/", json(result("clarify").toString(), delayMs = 250))
        val model = model(); send(model); model.cancelLast()
        assertTrue(terminal.await(3, TimeUnit.SECONDS))
        assertEquals("cancelled", events.last().getString("outcome"))
        assertEquals(1, events.count { it.getString("phase") == "finished" })
    }
}

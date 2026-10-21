package com.swpp.stylemate.chat

import com.swpp.stylemate.data.chat.ChatRepository
import com.swpp.stylemate.data.chat.ChatRequest
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitApiException
import com.swpp.stylemate.data.recommendation.OutfitGarment
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ChatRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: ChatRepository
    private val outfit = Outfit("outfit-1", "w:a+w:b", null, listOf(
        OutfitGarment("a", "top", "셔츠", "/a.png", ""),
        OutfitGarment("b", "bottom", "바지", "/b.png", "")), emptyList(), false, "", null, "")

    @Before fun setup() { server = MockWebServer(); server.start(); repository = ChatRepository(server.url("/").toString()) }
    @After fun close() { server.shutdown() }

    @Test fun freeTextAndLocksTravelTogetherAndUnlockIsExplicit() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"unlock","message":"바지 잠금을 풀까요?","outfit":null,"changed_ids":[],"locked_ids":["b"],"unlock_ids":["b"],"notes":[]}"""))
        val result = repository.edit(ChatRequest(outfit, setOf("b"), setOf("formal"), "바지도 바꿔줘",
            JSONObject().put("height_cm", 170), null, null, emptyList(), emptyList()))
        val request = server.takeRequest()
        val json = JSONObject(request.body.readUtf8())
        assertEquals("/api/outfits/chat/", request.path)
        assertEquals("바지도 바꿔줘", json.getString("text"))
        assertEquals("b", json.getJSONArray("locked_ids").getString(0))
        assertEquals("formal", json.getJSONArray("directions").getString(0))
        assertEquals(setOf("b"), result.unlockIds)
        assertNull(result.outfit)
    }

    @Test fun modelFailureKeepsTheRetryMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"CHAT_MODEL_UNAVAILABLE","hint":"잠시 후 다시 시도해 주세요."}"""))
        try {
            repository.edit(ChatRequest(outfit, emptySet(), emptySet(), "밝게", JSONObject(), null, null, emptyList(), emptyList()))
            fail("expected failure")
        } catch (e: OutfitApiException) {
            assertEquals("CHAT_MODEL_UNAVAILABLE", e.code)
            assertEquals("잠시 후 다시 시도해 주세요.", e.message)
        }
    }

    @Test fun savingUsesChatSourceAndStableRetryToken() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"saved-1","key":"w:a+w:b","score":null,"saved":true,"items":[],"reasons":[]}"""))
        val result = repository.save(outfit, "stable-token")
        val json = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("chat", json.getString("source"))
        assertEquals("stable-token", json.getJSONObject("context").getString("chat_save_id"))
        assertEquals(2, json.getJSONArray("garment_ids").length())
        assertTrue(result.saved)
    }
    @Test fun responseCannotAddLocksAfterUserUnlockedPants() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"unchanged","message":"다른 조합이 없어요","outfit":null,"changed_ids":[],"locked_ids":["a","b"],"unlock_ids":[],"notes":[]}"""))
        val request = ChatRequest(outfit, setOf("a"), emptySet(), "I don't want knit",
            JSONObject(), null, null, emptyList(), emptyList())
        val result = repository.edit(request)
        assertEquals(setOf("a"), result.lockedIds)
        val json = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(1, json.getJSONArray("locked_ids").length())
        assertEquals("a", json.getJSONArray("locked_ids").getString(0))
    }

    @Test fun conversationHistoryDoesNotTreatOldLocksAsUserInstructions() {
        val request = ChatRequest(outfit, setOf("b"), emptySet(), "I don't want knit",
            JSONObject(), null, null, emptyList(), emptyList())
        assertEquals("I don't want knit", com.swpp.stylemate.ui.chat.ChatViewModel.conversationText(request))
        assertTrue(com.swpp.stylemate.ui.chat.ChatViewModel.summary(request).contains("유지: 바지"))
    }

}

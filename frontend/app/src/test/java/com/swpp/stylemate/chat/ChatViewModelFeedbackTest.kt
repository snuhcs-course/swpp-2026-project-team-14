package com.swpp.stylemate.chat

import com.swpp.stylemate.data.AnalysisInput
import com.swpp.stylemate.data.BodyProfile
import com.swpp.stylemate.data.Gender
import com.swpp.stylemate.data.PreferredFit
import com.swpp.stylemate.data.chat.ChatRepository
import com.swpp.stylemate.data.recommendation.InMemoryTasteStore
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitGarment
import com.swpp.stylemate.data.testAccount
import com.swpp.stylemate.ui.chat.ChatState
import com.swpp.stylemate.ui.chat.ChatViewModel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// 채팅 추천 코디의 만족도(좋아요/별로예요) 상태 전이를 검증한다. 서버는 MockWebServer로 대체한다.
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelFeedbackTest {
    private lateinit var server: MockWebServer
    private lateinit var taste: InMemoryTasteStore
    private lateinit var viewModel: ChatViewModel
    private val outfit = Outfit("outfit-1", "w:a+w:b", null, listOf(
        OutfitGarment("a", "top", "셔츠", "/a.png", ""),
        OutfitGarment("b", "bottom", "바지", "/b.png", "")), emptyList(), false, "", null, "")
    private val profile = BodyProfile(AnalysisInput(heightCm = 170, weightKg = null, gender = Gender.FEMALE),
        emptyList(), PreferredFit.REGULAR, listOf("미니멀"))

    private fun response(feedback: String?, weight: Double) = MockResponse().setBody(JSONObject()
        .put("outfit", JSONObject().put("id", "chat-1").put("key", outfit.key).put("score", JSONObject.NULL)
            .put("saved", false).put("feedback", feedback ?: JSONObject.NULL)
            .put("items", org.json.JSONArray()).put("reasons", org.json.JSONArray()))
        .put("preference", JSONObject().put("weights", JSONObject().put("색상:블랙", weight))).toString())

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply { start() }
        taste = InMemoryTasteStore(JSONObject().put("weights", JSONObject().put("색상:화이트", 0.1)))
        val base = server.url("/").toString()
        viewModel = ChatViewModel(ChatRepository(base, testAccount(base)), taste)
        viewModel.open(outfit, profile, null)
    }

    @After fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun settled(): ChatState = runBlocking {
        withTimeout(5_000) { viewModel.state.first { outfit.key !in it.feedbackPending } }
    }

    private fun sent(): JSONObject = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())

    @Test fun likeIsPendingUntilTheServerAnswers() {
        server.enqueue(response("liked", 0.3).setBodyDelay(300, TimeUnit.MILLISECONDS))
        viewModel.feedback(outfit, "liked")
        assertTrue(outfit.key in viewModel.state.value.feedbackPending)
        assertNull(viewModel.state.value.feedbacks[outfit.key])
        settled()
    }

    @Test fun likeIsStoredAndTheUpdatedTasteIsSaved() {
        server.enqueue(response("liked", 0.3))
        viewModel.feedback(outfit, "liked")
        val state = settled()
        val request = sent()
        assertEquals("liked", request.getString("feedback"))
        assertEquals(0.1, request.getJSONObject("preference").getJSONObject("weights").getDouble("색상:화이트"), 0.0)
        assertEquals("liked", state.feedbacks[outfit.key])
        assertEquals("비슷한 코디를 더 자주 추천할게요.", state.feedbackMessages[outfit.key])
        assertEquals(0.3, taste.load()!!.getJSONObject("weights").getDouble("색상:블랙"), 0.0)
    }

    @Test fun tappingTheActiveButtonAgainCancels() {
        server.enqueue(response("liked", 0.3))
        viewModel.feedback(outfit, "liked")
        settled()
        sent()
        server.enqueue(response(null, 0.3))
        viewModel.feedback(outfit, "liked")
        val state = settled()
        assertTrue(sent().isNull("feedback"))
        assertNull(state.feedbacks[outfit.key])
        assertNull(state.feedbackMessages[outfit.key])
    }

    @Test fun dislikeAfterLikeSwitchesTheValue() {
        server.enqueue(response("liked", 0.3))
        viewModel.feedback(outfit, "liked")
        settled()
        sent()
        server.enqueue(response("disliked", -0.1))
        viewModel.feedback(outfit, "disliked")
        val state = settled()
        assertEquals("disliked", sent().getString("feedback"))
        assertEquals("disliked", state.feedbacks[outfit.key])
        assertEquals("비슷한 코디는 덜 추천할게요.", state.feedbackMessages[outfit.key])
    }

    @Test fun failureKeepsThePreviousValueAndTasteAndShowsTheError() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"INVALID_FEEDBACK","hint":"코디를 다시 확인해 주세요."}"""))
        viewModel.feedback(outfit, "liked")
        val state = settled()
        assertNull(state.feedbacks[outfit.key])
        assertEquals("코디를 다시 확인해 주세요.", state.feedbackMessages[outfit.key])
        assertFalse(taste.load()!!.getJSONObject("weights").has("색상:블랙"))
    }

    @Test fun secondTapWhileSavingIsIgnored() {
        server.enqueue(response("liked", 0.3).setBodyDelay(300, TimeUnit.MILLISECONDS))
        viewModel.feedback(outfit, "liked")
        viewModel.feedback(outfit, "disliked")
        val state = settled()
        assertEquals(1, server.requestCount)
        assertEquals("liked", state.feedbacks[outfit.key])
    }
}

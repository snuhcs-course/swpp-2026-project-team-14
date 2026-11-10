package com.swpp.stylemate.home

import com.swpp.stylemate.data.RoutedServer
import com.swpp.stylemate.data.await
import com.swpp.stylemate.data.failure
import com.swpp.stylemate.data.json
import com.swpp.stylemate.data.outfitJson
import com.swpp.stylemate.data.recommendation.InMemoryTasteStore
import com.swpp.stylemate.data.recommendation.OutfitRepository
import com.swpp.stylemate.data.testAccount
import com.swpp.stylemate.ui.home.CodiBookViewModel
import com.swpp.stylemate.ui.home.HomeState
import com.swpp.stylemate.ui.home.HomeViewModel
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// 홈(오늘의 코디)과 코디북 ViewModel의 상태 전이를 검증한다. 서버는 경로별 가짜 서버로 대체한다.
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private lateinit var server: RoutedServer
    private lateinit var taste: InMemoryTasteStore
    private lateinit var viewModel: HomeViewModel
    private val body = JSONObject().put("height_cm", 162).put("gender", "female")

    private val weather = """{"temp": 18.2, "apparent": 17.4, "rain": false, "snow": false, "label": "맑음", "band": "가을 간절기"}"""
    private fun recommendation(vararg outfits: JSONObject, notes: List<String> = emptyList()) =
        json(JSONObject().put("outfits", JSONArray(outfits.toList())).put("notes", JSONArray(notes)).toString())
    private fun feedbackResult(outfit: JSONObject, weight: Double) =
        json(JSONObject().put("outfit", outfit).put("preference", JSONObject().put("weights", JSONObject().put("색상:블랙", weight))).toString())

    private val first = outfitJson("o1", "a", "b")
    private val second = outfitJson("o2", "c", "d")
    private val third = outfitJson("o3", "e", "f")

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = RoutedServer()
        taste = InMemoryTasteStore(JSONObject().put("weights", JSONObject().put("색상:화이트", 0.1)))
        viewModel = HomeViewModel(OutfitRepository(testAccount(server.baseUrl)), taste)
    }

    @After fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun loaded(): HomeState = viewModel.state.await { !it.loading }

    private fun loadThreeOutfits() {
        server.on("GET /api/weather/", json(weather))
        server.on("POST /api/outfits/recommend/", recommendation(first, second, third))
        viewModel.load(body)
        loaded()
    }

    private fun idle(): HomeState = viewModel.state.await { !it.acting }

    @Test fun garmentDeletionInvalidatesOldRecommendationsOnlyOncePerRevision() {
        loadThreeOutfits()
        server.on("GET /api/weather/", json(weather))
        server.on("POST /api/outfits/recommend/", recommendation(second))
        viewModel.ensureLoaded(body, wardrobeRevision = 1)
        assertEquals(listOf("o2"), loaded().outfits.map { it.id })
        assertEquals(2, server.requests("POST /api/outfits/recommend/").size)
        viewModel.ensureLoaded(body, wardrobeRevision = 1)
        assertEquals(2, server.requests("POST /api/outfits/recommend/").size)
    }

    @Test fun missingBodyProfileAsksForProfileWithoutCallingTheServer() {
        viewModel.load(null)
        assertEquals(HomeState.NO_PROFILE, viewModel.state.value.errorCode)
        assertTrue(server.requests("POST /api/outfits/recommend/").isEmpty())
    }

    @Test fun loadShowsFirstOutfitWithWeatherAndNotes() {
        server.on("GET /api/weather/", json(weather))
        server.on("POST /api/outfits/recommend/", recommendation(first, second, third, notes = listOf("작은 셔츠는 작아요.")))
        viewModel.load(body)
        val state = loaded()
        assertEquals(listOf("o1", "o2", "o3"), state.outfits.map { it.id })
        assertEquals("o1", state.current?.id)
        assertEquals("맑음", state.weather?.label)
        assertEquals(listOf("작은 셔츠는 작아요."), state.notes)
        val sent = server.requests("POST /api/outfits/recommend/").single()
        assertEquals(HomeViewModel.CAROUSEL_SIZE, sent.getInt("count"))
        assertEquals(0.1, sent.getJSONObject("preference").getJSONObject("weights").getDouble("색상:화이트"), 0.0)
        assertEquals(0, sent.getJSONArray("seen_keys").length())
    }

    @Test fun weatherFailureStillLoadsOutfitsWithDefaultTemperature() {
        server.on("GET /api/weather/", failure(502, "WEATHER_UNAVAILABLE"))
        server.on("POST /api/outfits/recommend/", recommendation(first))
        viewModel.load(body)
        val state = loaded()
        assertNull(state.weather)
        assertEquals("o1", state.current?.id)
        assertEquals(OutfitRepository.DEFAULT_TEMP, server.requests("POST /api/outfits/recommend/").single().getDouble("temp"), 0.0)
    }

    @Test fun recommendationErrorShowsMessageAndOpeningTheTabRetries() {
        server.on("GET /api/weather/", json(weather), json(weather))
        server.on("POST /api/outfits/recommend/", failure(422, "WARDROBE_TOO_SMALL"), recommendation(first))
        viewModel.load(body)
        val failed = loaded()
        assertEquals("WARDROBE_TOO_SMALL", failed.errorCode)
        assertEquals("상의와 하의를 한 벌씩 등록하면 코디를 만들어 드려요.", failed.error)
        viewModel.ensureLoaded(body)
        val retried = loaded()
        assertNull(retried.errorCode)
        assertEquals("o1", retried.current?.id)
    }

    @Test fun ensureLoadedReloadsOnlyWhenTheBodyProfileChanges() {
        loadThreeOutfits()
        viewModel.ensureLoaded(JSONObject(body.toString()))
        assertEquals(1, server.requests("POST /api/outfits/recommend/").size)
        server.on("GET /api/weather/", json(weather))
        server.on("POST /api/outfits/recommend/", recommendation(second))
        viewModel.ensureLoaded(JSONObject(body.toString()).put("height_cm", 170))
        assertEquals("o2", loaded().current?.id)
        assertEquals(2, server.requests("POST /api/outfits/recommend/").size)
    }

    @Test fun fewerThanThreeOutfitsShowOnlyTheBestOne() {
        server.on("GET /api/weather/", json(weather))
        server.on("POST /api/outfits/recommend/", recommendation(first, second))
        viewModel.load(body)
        assertEquals(listOf("o1"), loaded().outfits.map { it.id })
        server.on("POST /api/outfits/recommend/", recommendation(third))
        viewModel.load(body)
        loaded()
        // Only the outfit that was shown counts as seen.
        val seen = server.requests("POST /api/outfits/recommend/").last().getJSONArray("seen_keys")
        assertEquals(listOf("w:a+w:b"), (0 until seen.length()).map { seen.getString(it) })
    }

    @Test fun swipingSelectsTheOutfitThatActionsUse() {
        loadThreeOutfits()
        viewModel.select(2)
        assertEquals("o3", viewModel.state.value.current?.id)
        viewModel.select(7)
        assertEquals("o3", viewModel.state.value.current?.id)
        server.on("PUT /api/outfits/o3/", json(outfitJson("o3", "e", "f", saved = true).toString()))
        viewModel.toggleSaved()
        assertTrue(idle().outfits[2].saved)
    }

    @Test fun refreshReplacesTheWholeSetAndStartsFromTheFirst() {
        loadThreeOutfits()
        viewModel.select(1)
        server.on("GET /api/weather/", json(weather))
        server.on("POST /api/outfits/recommend/", recommendation(outfitJson("o4", "g", "h"), outfitJson("o5", "i", "j"), outfitJson("o6", "k", "l")))
        viewModel.load(body)
        val state = loaded()
        assertEquals(listOf("o4", "o5", "o6"), state.outfits.map { it.id })
        assertEquals(0, state.index)
        val seen = server.requests("POST /api/outfits/recommend/").last().getJSONArray("seen_keys")
        assertEquals(listOf("w:a+w:b", "w:c+w:d", "w:e+w:f"), (0 until seen.length()).map { seen.getString(it) })
    }

    @Test fun dislikingTheLastOutfitLoadsANewSet() {
        loadThreeOutfits()
        viewModel.select(2)
        server.on("POST /api/outfits/o3/feedback/", feedbackResult(outfitJson("o3", "e", "f", feedback = "disliked"), -0.1))
        server.on("POST /api/outfits/recommend/", recommendation(outfitJson("o4", "g", "h"), outfitJson("o5", "i", "j"), outfitJson("o6", "k", "l")))
        viewModel.feedback("disliked")
        val state = viewModel.state.await { !it.loading && !it.acting && it.current?.id == "o4" }
        assertEquals(1, server.requests("GET /api/weather/").size) // 새 세트는 날씨를 다시 부르지 않음
        assertEquals(3, state.outfits.size)
    }

    @Test fun likeStoresFeedbackAndTheUpdatedTaste() {
        loadThreeOutfits()
        server.on("POST /api/outfits/o1/feedback/", feedbackResult(outfitJson("o1", "a", "b", feedback = "liked"), 0.3))
        viewModel.feedback("liked")
        val state = idle()
        assertEquals("liked", state.current?.feedback)
        assertEquals("비슷한 코디를 더 자주 추천할게요.", state.message)
        assertEquals(0.3, taste.load()!!.getJSONObject("weights").getDouble("색상:블랙"), 0.0)
        assertEquals("liked", server.requests("POST /api/outfits/o1/feedback/").single().getString("feedback"))
    }

    @Test fun tappingTheActiveFeedbackAgainCancelsIt() {
        loadThreeOutfits()
        server.on("POST /api/outfits/o1/feedback/",
            feedbackResult(outfitJson("o1", "a", "b", feedback = "liked"), 0.3),
            feedbackResult(outfitJson("o1", "a", "b"), 0.3))
        viewModel.feedback("liked")
        idle()
        viewModel.feedback("liked")
        val state = idle()
        assertTrue(server.requests("POST /api/outfits/o1/feedback/").last().isNull("feedback"))
        assertNull(state.current?.feedback)
        assertNull(state.message)
    }

    @Test fun dislikeMovesOnToTheNextOutfit() {
        loadThreeOutfits()
        server.on("POST /api/outfits/o1/feedback/", feedbackResult(outfitJson("o1", "a", "b", feedback = "disliked"), -0.1))
        viewModel.feedback("disliked")
        val state = idle()
        assertEquals("o2", state.current?.id)
        assertEquals("disliked", state.outfits.first().feedback)
        assertEquals("비슷한 코디는 덜 추천할게요.", state.message)
    }

    @Test fun feedbackFailureKeepsTheOutfitAndTasteAndShowsTheError() {
        loadThreeOutfits()
        server.on("POST /api/outfits/o1/feedback/", failure(400, "INVALID_FEEDBACK"))
        viewModel.feedback("liked")
        val state = idle()
        assertNull(state.current?.feedback)
        // 422가 아닌 오류는 앱 공통 문구로 보여준다 (AuthRepository 정책)
        assertEquals("요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.", state.message)
        assertFalse(taste.load()!!.getJSONObject("weights").has("색상:블랙"))
    }

    @Test fun actionsAreIgnoredWhileAnotherIsRunning() {
        loadThreeOutfits()
        server.on("POST /api/outfits/o1/feedback/", feedbackResult(outfitJson("o1", "a", "b", feedback = "liked"), 0.3).setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS))
        viewModel.feedback("liked")
        assertTrue(viewModel.state.value.acting)
        viewModel.toggleSaved()
        viewModel.feedback("disliked")
        idle()
        assertEquals(1, server.requests("POST /api/outfits/o1/feedback/").size)
        assertTrue(server.requests("PUT /api/outfits/o1/").isEmpty())
    }

    @Test fun toggleSavedUpdatesTheOutfitAndShowsWhereItWent() {
        loadThreeOutfits()
        server.on("PUT /api/outfits/o1/", json(outfitJson("o1", "a", "b", saved = true).toString()),
            json(outfitJson("o1", "a", "b", saved = false).toString()))
        viewModel.toggleSaved()
        val saved = idle()
        assertTrue(saved.current!!.saved)
        assertEquals("코디북에 저장했어요.", saved.message)
        assertTrue(server.requests("PUT /api/outfits/o1/").single().getBoolean("saved"))
        viewModel.toggleSaved()
        val removed = idle()
        assertFalse(removed.current!!.saved)
        assertEquals("코디북에서 뺐어요.", removed.message)
    }

    @Test fun saveFailureShowsTheErrorAndKeepsTheOutfitUnsaved() {
        loadThreeOutfits()
        // error 코드 없는 5xx는 HTTP_500으로 분류되어 코디 공통 오류 문구가 나온다
        server.on("PUT /api/outfits/o1/", okhttp3.mockwebserver.MockResponse().setResponseCode(500).setBody("{}"))
        viewModel.toggleSaved()
        val state = idle()
        assertFalse(state.current!!.saved)
        assertEquals(OutfitRepository.SERVER_ERROR, state.message)
    }

    @Test fun shownMessageIsCleared() {
        loadThreeOutfits()
        server.on("PUT /api/outfits/o1/", json(outfitJson("o1", "a", "b", saved = true).toString()))
        viewModel.toggleSaved()
        idle()
        viewModel.messageShown()
        assertNull(viewModel.state.value.message)
    }

    @Test fun calendarOccasionIsSentWithTheRecommendationButNotTheEvent() {
        viewModel.setOccasion("데이트")
        loadThreeOutfits()
        val sent = server.requests("POST /api/outfits/recommend/").single()
        assertEquals("데이트", sent.getString("occasion"))
        assertEquals(false, sent.toString().contains("성수동"))
    }

    @Test fun noCalendarOccasionSendsNoOccasionField() {
        loadThreeOutfits()
        assertEquals(false, server.requests("POST /api/outfits/recommend/").single().has("occasion"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CodiBookViewModelTest {
    private lateinit var server: RoutedServer
    private lateinit var viewModel: CodiBookViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = RoutedServer()
        viewModel = CodiBookViewModel(OutfitRepository(testAccount(server.baseUrl)))
    }

    @After fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun page(vararg outfits: JSONObject) =
        json(JSONObject().put("outfits", JSONArray(outfits.toList())).put("next", JSONObject.NULL).toString())

    @Test fun garmentDeletionClearsOldOutfitsEvenIfTheFollowingReloadFails() {
        server.on("GET /api/outfits/", page(outfitJson("o1", "a", "b", saved = true)))
        viewModel.load()
        assertEquals(1, viewModel.state.await { !it.loading }.outfits.size)
        viewModel.invalidateWardrobe(1)
        assertTrue(viewModel.state.value.outfits.isEmpty())
        server.on("GET /api/outfits/", failure(500, "SERVER_ERROR"))
        viewModel.load()
        val state = viewModel.state.await { !it.loading }
        assertTrue(state.outfits.isEmpty())
        assertEquals(true, state.error?.isNotBlank())
    }

    @Test fun loadListsSavedOutfits() {
        server.on("GET /api/outfits/", page(outfitJson("o1", "a", "b", saved = true), outfitJson("o2", "c", "d", saved = true)))
        viewModel.load()
        val state = viewModel.state.await { !it.loading }
        assertEquals(listOf("o1", "o2"), state.outfits.map { it.id })
        assertNull(state.error)
    }

    @Test fun loadFailureShowsTheError() {
        server.on("GET /api/outfits/", failure(500, "SERVER_ERROR"))
        viewModel.load()
        val state = viewModel.state.await { !it.loading }
        assertTrue(state.outfits.isEmpty())
        assertEquals(true, state.error?.isNotBlank())
    }

    @Test fun removeUnsavesAndDropsTheOutfitFromTheList() {
        server.on("GET /api/outfits/", page(outfitJson("o1", "a", "b", saved = true), outfitJson("o2", "c", "d", saved = true)))
        viewModel.load()
        val loaded = viewModel.state.await { !it.loading }
        server.on("PUT /api/outfits/o1/", json(outfitJson("o1", "a", "b").toString()))
        viewModel.remove(loaded.outfits.first())
        val state = viewModel.state.await { it.outfits.size == 1 }
        assertEquals(listOf("o2"), state.outfits.map { it.id })
        assertEquals(false, server.requests("PUT /api/outfits/o1/").single().getBoolean("saved"))
    }

    @Test fun removeFailureKeepsTheListAndShowsTheError() {
        server.on("GET /api/outfits/", page(outfitJson("o1", "a", "b", saved = true)))
        viewModel.load()
        val loaded = viewModel.state.await { !it.loading }
        server.on("PUT /api/outfits/o1/", failure(500, "SERVER_ERROR"))
        viewModel.remove(loaded.outfits.first())
        val state = viewModel.state.await { it.error != null }
        assertEquals(listOf("o1"), state.outfits.map { it.id })
    }
}

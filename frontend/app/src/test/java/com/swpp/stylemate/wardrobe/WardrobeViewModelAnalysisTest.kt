// AI-generated with Claude Code (Claude Opus 5.5), 2026-11-12, reviewed by Kihwan Kim
package com.swpp.stylemate.wardrobe

import com.swpp.stylemate.data.LocalUserStore
import com.swpp.stylemate.data.recommendation.ErrorMessages
import com.swpp.stylemate.data.recommendation.GapGarment
import com.swpp.stylemate.data.recommendation.WardrobeAnalysis
import com.swpp.stylemate.data.testAccount
import com.swpp.stylemate.ui.wardrobe.WardrobeAnalysisState
import com.swpp.stylemate.ui.wardrobe.WardrobeViewModel
import com.swpp.stylemate.ui.wardrobe.matchedNames
import com.swpp.stylemate.ui.wardrobe.slotSummary
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// 옷장 화면의 "옷장 분석" 팝업 상태(열기·로딩 → 결과/에러 → 다시 시도 → 닫기) 전이를 검증한다.
// 서버는 MockWebServer로 대체하고, 응답은 요청 순서대로 큐에서 꺼낸다.
@OptIn(ExperimentalCoroutinesApi::class)
class WardrobeViewModelAnalysisTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var viewModel: WardrobeViewModel
    private val responses = ConcurrentLinkedQueue<MockResponse>()
    private val analysisRequests = AtomicInteger()

    private fun body(status: String = "ok") = JSONObject()
        .put("garment_count", 6)
        .put("status", status)
        .put("slot_counts", JSONObject().put("top", 2).put("bottom", 3).put("outerwear", 1))
        .put("gaps", if (status != "ok") JSONArray() else JSONArray().put(JSONObject()
            .put("title", "그레이 루즈 니트").put("slot", "top").put("vote_count", 1)
            .put("reason", "내 옷 1벌과 잘 어울리는데, 비슷한 옷이 옷장에 없어요.")
            .put("attributes", JSONObject().put("category", "니트웨어").put("color", "그레이").put("fit", "루즈"))
            .put("matched_garments", JSONArray().put(JSONObject().put("garment_id", "jeans").put("name", "청바지")
                .put("image_url", "/api/wardrobe/items/jeans/image/")))
            .put("search_url", "https://www.musinsa.com/search/goods?keyword=knit")))
        .toString()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/outfits/wardrobe-gaps/" -> {
                        analysisRequests.incrementAndGet()
                        responses.poll() ?: MockResponse().setResponseCode(500)
                    }
                    // 생성 시 init { refresh() }가 부르는 옷장 API: 분석 상태와 무관하므로 빈 결과
                    else -> MockResponse().setBody("""{"items":[],"next_cursor":null}""")
                }
            }
            start()
        }
        val store = LocalUserStore.inDirectory(temporary.root, "00000000-0000-0000-0000-000000000001")
        viewModel = WardrobeViewModel(store, testAccount(server.url("/").toString()))
        runBlocking { withTimeout(5_000) { viewModel.state.first { it.catalog != null || it.error != null } } }
    }

    @After fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun settled(): WardrobeAnalysisState = runBlocking {
        withTimeout(5_000) { viewModel.wardrobeAnalysis.first { !it.loading } }
    }

    @Test fun initialStateShowsNoDialog() {
        assertEquals(WardrobeAnalysisState(), viewModel.wardrobeAnalysis.value)
    }

    @Test fun pressingTheButtonOpensTheDialogInLoadingBeforeTheServerAnswers() {
        responses.add(MockResponse().setBody(body()).setBodyDelay(500, TimeUnit.MILLISECONDS))
        viewModel.analyzeWardrobe()
        val state = viewModel.wardrobeAnalysis.value
        assertTrue(state.open)
        assertTrue(state.loading)
        assertNull(state.result)
        assertNull(state.error)
        settled() // 지연 응답까지 받고 종료 (테스트 간 코루틴 누수 방지)
    }

    @Test fun successfulAnalysisBecomesTheResultInTheSameDialog() {
        responses.add(MockResponse().setBody(body()))
        viewModel.analyzeWardrobe()
        val state = settled()
        assertTrue(state.open)
        assertNull(state.error)
        assertEquals(WardrobeAnalysis.OK, state.result?.status)
        assertEquals("그레이 루즈 니트", state.result?.gaps?.single()?.title)
    }

    @Test fun nothingToBuyAndTooFewAreResultsNotErrors() {
        for (status in listOf(WardrobeAnalysis.ENOUGH, WardrobeAnalysis.TOO_FEW)) {
            responses.add(MockResponse().setBody(body(status)))
            viewModel.analyzeWardrobe()
            val state = settled()
            assertNull(state.error)
            assertEquals(status, state.result?.status)
        }
    }

    @Test fun unavailableModelKeepsTheDialogOpenWithTheAppsUnavailableMessage() {
        responses.add(MockResponse().setResponseCode(503)
            .setBody("""{"error":"WARDROBE_GAPS_UNAVAILABLE","hint":"서버 내부 안내"}"""))
        viewModel.analyzeWardrobe()
        val state = settled()
        assertTrue(state.open)
        assertNull(state.result)
        assertEquals(ErrorMessages.UNAVAILABLE, state.error)
    }

    @Test fun retryAfterFailureCanSucceed() {
        responses.add(MockResponse().setResponseCode(503).setBody("""{"error":"WARDROBE_GAPS_UNAVAILABLE"}"""))
        viewModel.analyzeWardrobe()
        assertNotNull(settled().error)
        responses.add(MockResponse().setBody(body()))
        viewModel.analyzeWardrobe()
        val state = settled()
        assertNull(state.error)
        assertEquals(WardrobeAnalysis.OK, state.result?.status)
        assertEquals(2, analysisRequests.get())
    }

    @Test fun closingClearsTheDialogState() {
        responses.add(MockResponse().setBody(body()))
        viewModel.analyzeWardrobe()
        settled()
        viewModel.closeWardrobeAnalysis()
        assertEquals(WardrobeAnalysisState(), viewModel.wardrobeAnalysis.value)
    }

    @Test fun responseArrivingAfterCloseDoesNotReopenTheDialog() = runBlocking {
        responses.add(MockResponse().setBody(body()).setBodyDelay(300, TimeUnit.MILLISECONDS))
        viewModel.analyzeWardrobe()
        viewModel.closeWardrobeAnalysis()
        delay(1_000) // 지연된 응답이 도착할 시간을 충분히 줌
        assertEquals(WardrobeAnalysisState(), viewModel.wardrobeAnalysis.value)
    }

    @Test fun summaryTextsNameEverySlotAndShortenLongEvidence() {
        assertEquals("상의 2 · 하의 0 · 아우터 1", slotSummary(mapOf("top" to 2, "outerwear" to 1)))
        val garments = listOf("청바지", "슬랙스", "스커트", "코트", "조거").mapIndexed { i, name ->
            GapGarment("$i", name, "/api/wardrobe/items/$i/image/")
        }
        assertEquals("어울리는 내 옷: 청바지, 슬랙스", matchedNames(garments.take(2)))
        assertEquals("어울리는 내 옷: 청바지, 슬랙스, 스커트 외 2벌", matchedNames(garments))
    }

    @Test fun newerRequestWinsOverAnEarlierSlowerOne() = runBlocking {
        // 첫 요청은 느린 실패, 두 번째 요청은 빠른 성공: 늦게 온 첫 응답이 결과를 덮어쓰면 안 된다
        responses.add(MockResponse().setResponseCode(503).setBody("""{"error":"WARDROBE_GAPS_UNAVAILABLE"}""")
            .setBodyDelay(300, TimeUnit.MILLISECONDS))
        viewModel.analyzeWardrobe()
        // 첫 요청이 서버에 도착해 느린 응답을 가져간 뒤 두 번째 요청을 보낸다
        withTimeout(5_000) { while (analysisRequests.get() < 1) delay(10) }
        responses.add(MockResponse().setBody(body()))
        viewModel.analyzeWardrobe()
        assertEquals(WardrobeAnalysis.OK, settled().result?.status)
        delay(1_000)
        assertNull(viewModel.wardrobeAnalysis.value.error)
        assertEquals(WardrobeAnalysis.OK, viewModel.wardrobeAnalysis.value.result?.status)
    }
}

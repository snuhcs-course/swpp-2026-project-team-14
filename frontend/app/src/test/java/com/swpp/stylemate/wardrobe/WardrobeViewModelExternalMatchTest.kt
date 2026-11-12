package com.swpp.stylemate.wardrobe

import com.swpp.stylemate.data.LocalUserStore
import com.swpp.stylemate.data.testAccount
import com.swpp.stylemate.ui.wardrobe.ExternalMatchState
import com.swpp.stylemate.ui.wardrobe.WardrobeViewModel
import java.util.concurrent.TimeUnit
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

// 옷장 화면의 "어울리는 옷 찾기" 팝업 상태(로딩 → 결과/에러 → 닫기) 전이를 검증한다.
// 서버는 MockWebServer로 대체하고, 실제 네트워크 IO 스레드를 거치므로 상태 변화는 시간 제한을 두고 기다린다.
@OptIn(ExperimentalCoroutinesApi::class)
class WardrobeViewModelExternalMatchTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var viewModel: WardrobeViewModel

    // match-external 요청에 돌려줄 응답: 요청 본문의 garment_id별로 지정 (요청 도착 순서와 무관하게 결정적)
    private val responses = java.util.concurrent.ConcurrentHashMap<String, MockResponse>()

    private fun success(garmentId: String) = JSONObject()
        .put("source_garment_id", garmentId)
        .put("target_slot", "top")
        .put("title", "블랙 오버핏 셔츠")
        .put("headline", "해당 옷과 가장 잘 어울리는 상의는 블랙 & 오버핏 속성이에요.")
        .put("reason", "선택한 옷을 기준으로 블랙·오버핏 조합의 어울림 점수가 가장 높았어요.")
        .put("attributes", JSONObject().put("category", "셔츠").put("color", "블랙").put("fit", "오버사이즈"))
        .put("search_url", "https://www.musinsa.com/search/goods?keyword=%EB%B8%94%EB%9E%99")
        .put("wardrobe_matches", JSONArray())

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/outfits/match-external/" -> {
                        val garmentId = JSONObject(request.body.readUtf8()).getString("garment_id")
                        responses[garmentId] ?: MockResponse().setResponseCode(500)
                    }
                    // 생성 시 init { refresh() }가 부르는 옷장 API: 외부 추천 상태와 무관하므로 빈 결과
                    else -> MockResponse().setBody("""{"items":[],"next_cursor":null}""")
                }
            }
            start()
        }
        val store = LocalUserStore.inDirectory(temporary.root, "00000000-0000-0000-0000-000000000001")
        viewModel = WardrobeViewModel(store, testAccount(server.url("/").toString()))
        // init의 옷장 새로고침이 끝난 뒤 시작해야, tearDown 후 늦게 끝난 코루틴이 Main을 찾는 일이 없음
        // (refresh는 로컬 캐시 후 loading=false로 바꾸고 원격 요청을 계속하므로, 마지막 단계인 catalog 반영이나 에러까지 기다림)
        runBlocking { withTimeout(5_000) { viewModel.state.first { it.catalog != null || it.error != null } } }
    }

    @After fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun settled(): ExternalMatchState = runBlocking {
        withTimeout(5_000) { viewModel.externalMatch.first { !it.loading } }
    }

    @Test fun initialStateShowsNoDialog() {
        assertEquals(ExternalMatchState(), viewModel.externalMatch.value)
    }

    @Test fun requestEntersLoadingForTheSelectedGarmentBeforeTheServerAnswers() {
        responses["garment-1"] = MockResponse().setBody(success("garment-1").toString()).setBodyDelay(500, TimeUnit.MILLISECONDS)
        viewModel.matchExternal("garment-1")
        val state = viewModel.externalMatch.value
        assertEquals("garment-1", state.garmentId)
        assertTrue(state.loading)
        assertNull(state.result)
        assertNull(state.error)
        settled() // 지연 응답까지 받고 종료 (테스트 간 코루틴 누수 방지)
    }

    @Test fun successfulResponseBecomesTheResult() {
        responses["garment-1"] = MockResponse().setBody(success("garment-1").toString())
        viewModel.matchExternal("garment-1")
        val state = settled()
        assertEquals("garment-1", state.garmentId)
        assertNull(state.error)
        assertEquals("해당 옷과 가장 잘 어울리는 상의는 블랙 & 오버핏 속성이에요.", state.result?.headline)
        assertEquals("블랙", state.result?.attributes?.color)
    }

    @Test fun serverErrorShowsTheAppsGenericMessageAndKeepsTheGarmentForRetry() {
        // 앱은 5xx의 서버 hint 대신 원인과 다음 행동을 담은 공통 문구를 보여준다 (ErrorMessages)
        responses["garment-1"] = MockResponse().setResponseCode(503)
            .setBody("""{"error":"EXTERNAL_MODEL_UNAVAILABLE","hint":"서버 내부 안내"}""")
        viewModel.matchExternal("garment-1")
        val state = settled()
        assertEquals("garment-1", state.garmentId) // 다시 시도 버튼이 같은 옷으로 재요청할 수 있어야 함
        assertNull(state.result)
        assertEquals(com.swpp.stylemate.data.recommendation.ErrorMessages.UNAVAILABLE, state.error)
    }

    @Test fun unsupportedGarmentShowsTheServerHint() {
        // 422는 사용자가 고칠 수 있는 요청 오류라 서버 hint를 그대로 보여준다
        responses["garment-1"] = MockResponse().setResponseCode(422)
            .setBody("""{"error":"UNSUPPORTED_EXTERNAL_CATEGORY","hint":"상의, 하의, 아우터에서 어울리는 옷을 찾아보세요."}""")
        viewModel.matchExternal("garment-1")
        val state = settled()
        assertNull(state.result)
        assertEquals("상의, 하의, 아우터에서 어울리는 옷을 찾아보세요.", state.error)
    }

    @Test fun retryAfterFailureCanSucceed() {
        responses["garment-1"] = MockResponse().setResponseCode(503).setBody("""{"error":"EXTERNAL_MODEL_UNAVAILABLE"}""")
        viewModel.matchExternal("garment-1")
        assertNotNull(settled().error)
        responses["garment-1"] = MockResponse().setBody(success("garment-1").toString())
        viewModel.matchExternal("garment-1")
        val state = settled()
        assertNull(state.error)
        assertEquals("블랙 오버핏 셔츠", state.result?.title)
    }

    @Test fun closingClearsTheDialogState() {
        responses["garment-1"] = MockResponse().setBody(success("garment-1").toString())
        viewModel.matchExternal("garment-1")
        settled()
        viewModel.closeExternalMatch()
        assertEquals(ExternalMatchState(), viewModel.externalMatch.value)
    }

    @Test fun responseArrivingAfterCloseDoesNotReopenTheDialog() = runBlocking {
        responses["garment-1"] = MockResponse().setBody(success("garment-1").toString()).setBodyDelay(300, TimeUnit.MILLISECONDS)
        viewModel.matchExternal("garment-1")
        viewModel.closeExternalMatch()
        delay(1_000) // 지연된 응답이 도착할 시간을 충분히 줌
        assertEquals(ExternalMatchState(), viewModel.externalMatch.value)
    }

    @Test fun newerRequestWinsOverAnEarlierSlowerOne() = runBlocking {
        responses["garment-1"] = MockResponse().setBody(success("garment-1").toString()).setBodyDelay(300, TimeUnit.MILLISECONDS)
        viewModel.matchExternal("garment-1")
        responses["garment-2"] = MockResponse().setBody(success("garment-2").toString())
        viewModel.matchExternal("garment-2")
        assertEquals("garment-2", settled().result?.sourceGarmentId)
        delay(1_000) // 먼저 보낸 느린 요청의 응답이 덮어쓰지 않는지 확인
        assertEquals("garment-2", viewModel.externalMatch.value.result?.sourceGarmentId)
    }
}

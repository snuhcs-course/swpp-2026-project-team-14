// AI-generated with Claude Code (Claude Opus 5.5), 2026-11-12, reviewed by Kihwan Kim
package com.swpp.stylemate.data

import com.swpp.stylemate.data.recommendation.ExternalAttributes
import com.swpp.stylemate.data.recommendation.GapGarment
import com.swpp.stylemate.data.recommendation.OutfitApiException
import com.swpp.stylemate.data.recommendation.OutfitRepository
import com.swpp.stylemate.data.recommendation.WardrobeAnalysis
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// 옷장 분석 API 응답을 읽는 규칙: 세 가지 상태, 근거 옷과 표 수의 일치, 안전한 링크·사진 경로만 허용.
class WardrobeGapsRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: OutfitRepository
    private val searchUrl = "https://www.musinsa.com/search/goods?keyword=%EA%B7%B8%EB%A0%88%EC%9D%B4"

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        repository = OutfitRepository(testAccount(server.url("/").toString()))
    }

    @After fun tearDown() = server.shutdown()

    private fun garment(id: String, name: String) = JSONObject()
        .put("garment_id", id).put("name", name).put("image_url", "/api/wardrobe/items/$id/image/")

    private fun gap() = JSONObject()
        .put("title", "그레이 루즈 니트")
        .put("slot", "top")
        .put("vote_count", 2)
        .put("reason", "내 옷 2벌과 잘 어울리는데, 비슷한 옷이 옷장에 없어요.")
        .put("attributes", JSONObject().put("category", "니트웨어").put("color", "그레이").put("fit", "루즈").put("pattern", "무지"))
        .put("matched_garments", JSONArray().put(garment("jeans", "청바지")).put(garment("skirt", "와인 스커트")))
        .put("search_url", searchUrl)

    private fun response(status: String = "ok", gaps: JSONArray = JSONArray().put(gap())) = JSONObject()
        .put("garment_count", 7)
        .put("status", status)
        .put("slot_counts", JSONObject().put("top", 3).put("bottom", 3).put("outerwear", 1))
        .put("gaps", gaps)

    @Test fun wardrobeGaps_requestsTheWholeWardrobeWithAuthenticationAndReadsTheGaps() = runBlocking {
        server.enqueue(MockResponse().setBody(response().toString()))

        val result = repository.wardrobeGaps()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/outfits/wardrobe-gaps/", request.path)
        assertEquals("Bearer $TEST_TOKEN", request.getHeader("Authorization"))
        assertEquals(WardrobeAnalysis.OK, result.status)
        assertEquals(7, result.garmentCount)
        assertEquals(mapOf("top" to 3, "bottom" to 3, "outerwear" to 1), result.slotCounts)
        val gap = result.gaps.single()
        assertEquals("그레이 루즈 니트", gap.title)
        assertEquals("top", gap.slot)
        assertEquals(2, gap.voteCount)
        assertEquals("내 옷 2벌과 잘 어울리는데, 비슷한 옷이 옷장에 없어요.", gap.reason)
        assertEquals(ExternalAttributes("니트웨어", "그레이", "루즈"), gap.attributes)
        assertEquals(listOf(GapGarment("jeans", "청바지", "/api/wardrobe/items/jeans/image/"),
            GapGarment("skirt", "와인 스커트", "/api/wardrobe/items/skirt/image/")), gap.matchedGarments)
        assertEquals(searchUrl, gap.searchUrl)
    }

    @Test fun wardrobeGaps_readsTheNothingToBuyAndTooFewStates() = runBlocking {
        for (status in listOf(WardrobeAnalysis.ENOUGH, WardrobeAnalysis.TOO_FEW)) {
            server.enqueue(MockResponse().setBody(response(status, JSONArray()).toString()))
            val result = repository.wardrobeGaps()
            assertEquals(status, result.status)
            assertEquals(emptyList<Any>(), result.gaps)
        }
    }

    @Test fun wardrobeGaps_rejectsInconsistentStatesAndCounts() = runBlocking {
        for (json in listOf(
            response("ok", JSONArray()),                       // ok인데 추천 없음
            response("enough"),                                // 살 옷 없다면서 추천 있음
            response("unknown", JSONArray()),
            response().put("garment_count", -1),
            response().put("slot_counts", JSONObject().put("top", 1).put("bottom", 1)),
            response(gaps = JSONArray().put(gap().put("vote_count", 3))),          // 표 수 ≠ 근거 옷 수
            response(gaps = JSONArray().put(gap().put("vote_count", "2"))),
            response(gaps = JSONArray().put(gap().put("slot", "shoes"))),
            response(gaps = JSONArray().put(gap().put("title", " "))),
            response(gaps = JSONArray().put(gap().apply { remove("reason") })),
            response(gaps = JSONArray().put(gap().put("attributes", JSONObject().put("category", "니트웨어")))),
        )) {
            server.enqueue(MockResponse().setBody(json.toString()))
            assertInvalidResponse()
        }
    }

    @Test fun wardrobeGaps_rejectsLinksAndPhotosOutsideTheExpectedPlaces() = runBlocking {
        for (url in listOf(
            "https://www.musinsa.com.evil.example/search/goods?keyword=knit",
            "http://www.musinsa.com/search/goods?keyword=knit",
            "https://www.musinsa.com/search/goods",
            "not a url",
        )) {
            server.enqueue(MockResponse().setBody(response(gaps = JSONArray().put(gap().put("search_url", url))).toString()))
            assertInvalidResponse()
        }
        val outsidePhoto = gap().put("matched_garments", JSONArray()
            .put(garment("jeans", "청바지").put("image_url", "https://evil.example/a.jpg"))
            .put(garment("skirt", "와인 스커트")))
        server.enqueue(MockResponse().setBody(response(gaps = JSONArray().put(outsidePhoto)).toString()))
        assertInvalidResponse()
    }

    @Test fun wardrobeGaps_preservesTheServerFailureCode() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody(
            """{"error":"WARDROBE_GAPS_UNAVAILABLE","hint":"잠시 후 다시 시도해 주세요."}"""))
        try {
            repository.wardrobeGaps()
            fail("Expected model failure")
        } catch (error: OutfitApiException) {
            assertEquals("WARDROBE_GAPS_UNAVAILABLE", error.code)
        }
    }

    private suspend fun assertInvalidResponse() {
        try {
            repository.wardrobeGaps()
            fail("Expected invalid analysis response")
        } catch (error: OutfitApiException) {
            assertEquals("INVALID_RESPONSE", error.code)
        }
    }
}

package com.swpp.stylemate.data

import com.swpp.stylemate.data.recommendation.ExternalAttributes
import com.swpp.stylemate.data.recommendation.OutfitApiException
import com.swpp.stylemate.data.recommendation.OutfitRepository
import com.swpp.stylemate.data.recommendation.WardrobeMatch
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

class ExternalRecommendationRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: OutfitRepository
    private val searchUrl = "https://www.musinsa.com/search/goods?keyword=black+shirt&color=BLACK"

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        repository = OutfitRepository(testAccount(server.url("/").toString()))
    }

    @After fun tearDown() = server.shutdown()

    private fun response() = JSONObject()
        .put("source_garment_id", "garment-1")
        .put("target_slot", "top")
        .put("title", "이 바지와 어울리는 상의")
        .put("headline", "해당 옷과 가장 잘 어울리는 상의는 블랙 & 오버핏 속성이에요.")
        .put("reason", "선택한 바지와 어울리는 색상과 핏을 추천했어요.")
        .put("attributes", JSONObject().put("category", "셔츠").put("color", "블랙").put("fit", "오버사이즈"))
        .put("search_url", searchUrl)
        .put("wardrobe_matches", JSONArray().put(JSONObject().put("garment_id", "shirt-1").put("name", "검정 셔츠")))

    @Test fun matchExternal_sendsOnlySelectedGarmentWithAuthenticationAndReadsRecommendation() = runBlocking {
        server.enqueue(MockResponse().setBody(response().toString()))

        val result = repository.matchExternal("garment-1")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/outfits/match-external/", request.path)
        assertEquals("Bearer $TEST_TOKEN", request.getHeader("Authorization"))
        val sent = JSONObject(request.body.readUtf8())
        assertEquals(1, sent.length())
        assertEquals("garment-1", sent.getString("garment_id"))
        assertEquals("garment-1", result.sourceGarmentId)
        assertEquals("top", result.targetSlot)
        assertEquals("이 바지와 어울리는 상의", result.title)
        assertEquals("선택한 바지와 어울리는 색상과 핏을 추천했어요.", result.reason)
        assertEquals(ExternalAttributes("셔츠", "블랙", "오버사이즈"), result.attributes)
        assertEquals(searchUrl, result.searchUrl)
        assertEquals("해당 옷과 가장 잘 어울리는 상의는 블랙 & 오버핏 속성이에요.", result.headline)
        assertEquals(listOf(WardrobeMatch("shirt-1", "검정 셔츠")), result.wardrobeMatches)
    }

    @Test fun matchExternal_treatsMissingWardrobeMatchesAsEmpty() = runBlocking {
        server.enqueue(MockResponse().setBody(response().apply { remove("wardrobe_matches") }.toString()))
        assertEquals(emptyList<WardrobeMatch>(), repository.matchExternal("garment-1").wardrobeMatches)
    }

    @Test fun matchExternal_rejectsLinksOutsideTheExpectedSecureSearchPage() = runBlocking {
        for (url in listOf(
            "https://www.musinsa.com.evil.example/search/goods?keyword=shirt",
            "https://evil.example/search/goods?keyword=shirt",
            "http://www.musinsa.com/search/goods?keyword=shirt",
            "https://user@www.musinsa.com/search/goods?keyword=shirt",
            "https://www.musinsa.com:444/search/goods?keyword=shirt",
            "https://www.musinsa.com/products/1?keyword=shirt",
            "https://www.musinsa.com/search/goods",
            "not a url",
        )) {
            server.enqueue(MockResponse().setBody(response().put("search_url", url).toString()))
            assertInvalidResponse()
        }
    }

    @Test fun matchExternal_rejectsMissingDetailsUnsupportedSlotsAndAnotherGarmentsResult() = runBlocking {
        val missingAttributes = response().apply { remove("attributes") }
        for (json in listOf(
            missingAttributes,
            response().put("title", " "),
            response().put("headline", " "),
            response().put("wardrobe_matches", JSONArray().put(JSONObject().put("garment_id", "shirt-1"))),
            response().put("source_garment_id", "another-garment"),
            response().put("target_slot", "shoes"),
            response().put("attributes", JSONObject().put("category", "셔츠").put("color", "블랙")),
        )) {
            server.enqueue(MockResponse().setBody(json.toString()))
            assertInvalidResponse()
        }
    }

    @Test fun matchExternal_preservesTheServerFailureCodeAndDoesNotInventARecommendation() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422).setBody(
            """{"error":"UNSUPPORTED_GARMENT","hint":"상의와 하의를 선택해 주세요."}"""))
        try {
            repository.matchExternal("garment-1")
            fail("Expected unsupported garment failure")
        } catch (error: OutfitApiException) {
            assertEquals("UNSUPPORTED_GARMENT", error.code)
            assertEquals("상의와 하의를 선택해 주세요.", error.message)
        }
    }

    private suspend fun assertInvalidResponse() {
        try {
            repository.matchExternal("garment-1")
            fail("Expected invalid recommendation response")
        } catch (error: OutfitApiException) {
            assertEquals("INVALID_RESPONSE", error.code)
        }
    }
}

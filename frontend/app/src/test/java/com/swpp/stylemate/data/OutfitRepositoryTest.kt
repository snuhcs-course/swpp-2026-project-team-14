// AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.data

import com.swpp.stylemate.data.recommendation.OutfitApiException
import com.swpp.stylemate.data.recommendation.OutfitRepository
import org.json.JSONArray
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OutfitRepositoryTest {
    @get:Rule val temporary = TemporaryFolder(File("build/preview-request-tests").apply { mkdirs() })

    @Test fun localPreviewSendsOver100GarmentsWithoutNotesOrCachedAnalysisAndChecksByteLimit() = runBlocking {
        val local = LocalUserStore.inDirectory(temporary.root)
        fun row() = JSONObject().put("id", UUID.randomUUID().toString()).put("saved", true)
            .put("attributes", JSONObject().put("name", "티셔츠").put("category", "top").put("subcategory", "tshirt")
                .put("colors", JSONArray(listOf("#FFFFFF"))).put("styles", JSONArray()).put("fit_type", JSONObject.NULL)
                .put("leg_shape", JSONObject.NULL).put("rise_type", JSONObject.NULL).put("skirt_shape", JSONObject.NULL))
            .put("notes", "메모".repeat(1000)).put("model", "cached-model").put("details", JSONObject().put("print", "무지"))
        local.cacheGarments(JSONArray((0 until 101).map { row() }))
        server.enqueue(MockResponse().setBody("""{"outfits": [], "notes": [], "band": {"label": "x"}}"""))
        val repo = OutfitRepository(server.url("/").toString(), local = local)
        repo.recommend(body, null, null, emptyList())
        val request = server.takeRequest()
        assertEquals("/api/outfits/preview/", request.path)
        val rows = JSONObject(request.body.readUtf8()).getJSONArray("wardrobe")
        assertEquals(101, rows.length())
        assertEquals(setOf("id", "attributes", "dimensions", "labels", "notes"), rows.getJSONObject(0).keys().asSequence().toSet())
        assertEquals("", rows.getJSONObject(0).getString("notes"))
        assertEquals(2000, local.garments().first().getString("notes").length)
        local.cacheGarments(JSONArray((0 until 4000).map { row().put("notes", "") }))
        try { repo.recommend(body, null, null, emptyList()); fail() }
        catch (error: OutfitApiException) { assertEquals("BODY_TOO_LARGE", error.code) }
        assertEquals(1, server.requestCount)
    }

    private lateinit var server: MockWebServer
    private val body = JSONObject().put("height_cm", 162).put("gender", "female")

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun repository() = OutfitRepository(server.url("/").toString())

    private val outfitJson = """{"id": "o1", "source": "recommended", "saved": false, "name": "", "key": "w:a+w:b",
        "score": 74, "components": {"compat": 60}, "context": {}, "feedback": null, "created_at": "2026-10-12T09:00:00",
        "reasons": [{"kind": "color", "text": "베이지 · 블랙 뉴트럴 조합이에요.", "sub": "배색 규칙"}],
        "items": [{"garment_id": "a", "slot": "top", "name": "베이지 니트", "colors": ["#D6BE9A"],
                   "image_url": "/api/wardrobe/items/a/image/", "note": "내 옷 · 내 몸 기준 루즈 (가슴 여유 16 cm)"},
                  {"garment_id": "b", "slot": "bottom", "name": "검정 슬랙스", "colors": ["#202020"],
                   "image_url": "/api/wardrobe/items/b/image/", "note": "내 옷"}]}"""

    @Test
    fun recommend_sendsBodyWeatherTasteAndSeenOutfits() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"temp": 18.2, "apparent": 17.4, "precipitation": 0, "rain": false,
            "snow": false, "wind": 2, "label": "맑음", "source": "open-meteo", "band": "가을 간절기"}"""))
        server.enqueue(MockResponse().setBody("""{"outfits": [$outfitJson], "notes": ["내 옷 '작은 셔츠'는 작아요."],
            "band": {"label": "가을 간절기", "outer": "optional"}, "context": {}}"""))
        val repo = repository()
        val weather = repo.weather()
        assertEquals("맑음", weather.label)
        assertEquals("가을 간절기", weather.band)
        val result = repo.recommend(body, weather, null, listOf("w:x+w:y"))

        assertEquals("/api/weather/", server.takeRequest().path)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/outfits/recommend/", request.path)
        val sent = JSONObject(request.body.readUtf8())
        assertEquals(162, sent.getJSONObject("body").getInt("height_cm"))
        assertEquals(17.4, sent.getJSONObject("weather").getDouble("apparent"), 1e-9)
        assertEquals("w:x+w:y", sent.getJSONArray("seen_keys").getString(0))
        assertTrue(sent.isNull("preference"))

        val outfit = result.outfits.single()
        assertEquals(74, outfit.score)
        assertEquals(listOf("top", "bottom"), outfit.items.map { it.slot })
        assertEquals("/api/wardrobe/items/a/image/", outfit.items[0].imageUrl)
        assertNull(outfit.feedback)
        assertEquals("가을 간절기", result.band)
        assertEquals(1, result.notes.size)
    }

    @Test
    fun recommend_withoutWeatherFallsBackToADefaultTemperature() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"outfits": [], "notes": [], "band": {"label": "x"}}"""))
        repository().recommend(body, null, JSONObject().put("weights", JSONObject()), emptyList(), anchorId = "g1")
        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(OutfitRepository.DEFAULT_TEMP, sent.getDouble("temp"), 1e-9)
        assertFalse(sent.has("weather"))
        assertEquals("g1", sent.getString("anchor_id"))
    }

    @Test
    fun feedbackAndSave_returnUpdatedOutfitAndTaste() = runBlocking {
        val liked = outfitJson.replace("\"feedback\": null", "\"feedback\": \"liked\"")
        server.enqueue(MockResponse().setBody("""{"outfit": $liked, "preference": {"weights": {"색상:베이지": 0.3}}}"""))
        server.enqueue(MockResponse().setBody(outfitJson.replace("\"saved\": false", "\"saved\": true")))
        val repo = repository()
        val (outfit, taste) = repo.feedback("o1", "liked", null)
        assertEquals("liked", outfit.feedback)
        assertEquals(0.3, taste.getJSONObject("weights").getDouble("색상:베이지"), 1e-9)
        assertTrue(repo.setSaved("o1", true).saved)
        assertEquals("/api/outfits/o1/feedback/", server.takeRequest().path)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals(true, JSONObject(put.body.readUtf8()).getBoolean("saved"))
    }

    @Test
    fun errors_carryTheServerCodeAndAKoreanMessage() {
        server.enqueue(MockResponse().setResponseCode(422).setBody(
            """{"error": "WARDROBE_TOO_SMALL", "hint": "코디를 만들려면 상의와 하의가 필요해요."}"""))
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"error": "ANCHOR_TOO_SMALL", "hint": "기준으로 고른 옷이 몸보다 작아요."}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        for ((code, message) in listOf(
            "WARDROBE_TOO_SMALL" to "상의와 하의를 한 벌씩 등록하면 코디를 만들어 드려요.",
            "ANCHOR_TOO_SMALL" to "기준으로 고른 옷이 몸보다 작아요.",
            "HTTP_500" to OutfitRepository.SERVER_ERROR,
        )) {
            try {
                runBlocking { repository().recommend(body, null, null, emptyList()) }
                fail("expected OutfitApiException")
            } catch (e: OutfitApiException) {
                assertEquals(code, e.code)
                assertEquals(message, e.message)
            }
        }
    }

    @Test
    fun labels_postsTheGarmentBeingEdited() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"labels": {"fit": "루즈", "fit_basis": "가슴 여유 16 cm"}}"""))
        val attributes = JSONObject().put("name", "니트").put("category", "top")
        val labels = repository().labels(attributes, null, body, JSONObject().put("print", "무지"), "short_sleeve_top")
        assertEquals("루즈", labels?.getString("fit"))
        val request = server.takeRequest()
        assertEquals("/api/wardrobe/labels/", request.path)
        val sent = JSONObject(request.body.readUtf8())
        assertTrue(sent.isNull("dimensions"))
        assertEquals("무지", sent.getJSONObject("details").getString("print"))
        assertEquals("short_sleeve_top", sent.getString("garment_type"))
        // without a body profile the labels are still requested (the user then picks the fit)
        server.enqueue(MockResponse().setBody("""{"labels": {"fit": null}}"""))
        repository().labels(attributes, null, null, null)
        val noBody = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(noBody.isNull("body") && noBody.isNull("garment_type"))
    }
}

package com.swpp.stylemate.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class RemoteBodyAnalyzerTest {

    private lateinit var server: MockWebServer
    private val input = AnalysisInput(172, 65, Gender.FEMALE, ClothingType.LOOSE)
    private val photos = BodyPhotos(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun analyzer() = RemoteBodyAnalyzer(server.url("/").toString())

    private fun analyzeExpectingError(): BodyAnalysisException {
        try {
            runBlocking { analyzer().analyze(input, photos) }
        } catch (e: BodyAnalysisException) {
            return e
        }
        fail("expected BodyAnalysisException")
        throw AssertionError()
    }

    @Test
    fun success_parsesMeasurementsAndWarnings() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"analysis_id": "x", "pipeline_version": "baseline",
                    "measurements": [
                      {"type": "shoulder_width", "value_cm": 40.5, "confidence": "high"},
                      {"type": "chest", "value_cm": 92.0, "confidence": "low"},
                      {"type": "future_measurement", "value_cm": 1.0, "confidence": "low"}],
                    "derived": {}, "warnings": ["loose_clothing", "arms_touching_body"]}""",
            ),
        )
        val result = analyzer().analyze(input, photos)

        assertEquals(
            listOf(
                BodyMeasurement(MeasurementType.SHOULDER_WIDTH, 40.5, Confidence.HIGH),
                BodyMeasurement(MeasurementType.CHEST, 92.0, Confidence.LOW),
            ),
            result.measurements, // unknown types from a newer server are skipped
        )
        assertEquals(listOf(warningText("loose_clothing"), warningText("arms_touching_body")), result.warnings)
    }

    @Test
    fun request_sendsBothPhotosAndInputs() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"measurements": [], "warnings": []}"""))
        analyzer().analyze(input, photos)

        val request = server.takeRequest()
        assertEquals("/api/body-profile/analyze/", request.path)
        val body = request.body.readUtf8()
        listOf(
            "name=\"front_photo\"", "name=\"side_photo\"",
            "name=\"height_cm\"", "172", "name=\"weight_kg\"", "65",
            "name=\"gender\"", "female", "name=\"clothing\"", "loose",
        ).forEach { assertTrue(it, it in body) }
    }

    @Test
    fun rejectedPhoto_throwsServerHint() {
        server.enqueue(
            MockResponse().setResponseCode(422)
                .setBody("""{"error": "body_cropped", "photo": "front", "hint": "머리부터 발끝까지 전신이 나오게 찍어주세요."}"""),
        )
        val error = analyzeExpectingError()
        assertEquals("body_cropped", error.code)
        assertEquals("머리부터 발끝까지 전신이 나오게 찍어주세요.", error.message)
    }

    @Test
    fun serverCrash_throwsGenericMessage() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("<html>Server Error</html>"))
        assertEquals(RemoteBodyAnalyzer.SERVER_ERROR, analyzeExpectingError().message)
    }

    @Test
    fun serverDown_throwsNetworkMessage() {
        server.shutdown()
        assertEquals(RemoteBodyAnalyzer.NETWORK_ERROR, analyzeExpectingError().message)
    }
}

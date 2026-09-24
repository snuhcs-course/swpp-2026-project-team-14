package com.swpp.stylemate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Parses responses captured from the real Django server (backend/, synthetic body photos), so a
 * change in the API shape on either side breaks this test instead of the app.
 * Re-capture with the curl commands in backend/README.md when the API changes.
 */
class ServerContractTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource(name)!!.readText(Charsets.UTF_8)

    @Test
    fun successResponse_mapsEveryMeasurement() {
        val result = RemoteBodyAnalyzer.parseResponse(200, fixture("analyze_response_200.json"))
        assertTrue(result.measurements.size >= 12)
        assertTrue(result.measurements.any { it.type == MeasurementType.CHEST })
        assertTrue(result.measurements.all { it.valueCm > 0 })
    }

    @Test
    fun rejectedResponse_carriesKoreanHint() {
        try {
            RemoteBodyAnalyzer.parseResponse(422, fixture("analyze_response_422.json"))
            fail("expected BodyAnalysisException")
        } catch (e: BodyAnalysisException) {
            assertEquals("not_frontal", e.code)
            assertEquals("정면 사진은 카메라를 정면으로 바라보고 찍어주세요.", e.message)
        }
    }
}

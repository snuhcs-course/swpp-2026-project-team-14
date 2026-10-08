// AI-generated with Claude Code (Claude Opus 5.5), 2026-09-24, reviewed by Dongkun Moon
package com.swpp.stylemate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsTest {

    // Every ratio is clearly past its threshold + margin: inseam 78/165 = 0.473, shoulder 45/165 = 0.273,
    // hip/chest 100/88 = 1.136 (lower-volume type)
    private fun measurements(editedByUser: Boolean = false, inseam: Double = 78.0) = listOf(
        BodyMeasurement(MeasurementType.INSEAM, inseam, Confidence.HIGH, editedByUser = editedByUser),
        BodyMeasurement(MeasurementType.SHOULDER_WIDTH, 45.0, Confidence.HIGH, editedByUser = editedByUser),
        BodyMeasurement(MeasurementType.CHEST, 88.0, Confidence.MEDIUM, editedByUser = editedByUser),
        BodyMeasurement(MeasurementType.WAIST, 68.0, Confidence.MEDIUM, editedByUser = editedByUser),
        BodyMeasurement(MeasurementType.HIP, 100.0, Confidence.MEDIUM, editedByUser = editedByUser),
    )

    private val fitted = DetectedClothing()
    private val looseBoth = DetectedClothing(topLoose = true, bottomLoose = true)

    private fun insights(clothing: DetectedClothing, editedByUser: Boolean = false, inseam: Double = 78.0) =
        buildInsights(measurements(editedByUser, inseam), 165, clothing)

    private fun List<Insight>.mentions(word: String) = any { word in it.title || word in it.body }

    @Test
    fun fittedClothing_showsBodyTypeAndProportions() {
        val result = insights(fitted)
        assertEquals("체형 타입 · 하체 볼륨형", result.first().title)
        assertTrue(result.mentions("다리가 긴 편"))
        assertTrue(result.mentions("어깨가 넓은 편"))
        assertFalse(result.any { it.body == LOOSE_CLOTHING_INSIGHT_NOTE })
    }

    @Test
    fun looseEverywhere_hidesEverythingButTheNote() {
        assertEquals(listOf(Insight("안내", LOOSE_CLOTHING_INSIGHT_NOTE)), insights(looseBoth))
    }

    @Test
    fun looseBottomOnly_keepsUpperBodyInsights() {
        val result = insights(DetectedClothing(bottomLoose = true))
        assertTrue(result.mentions("어깨가 넓은 편")) // shoulder is a top measurement
        assertFalse(result.mentions("체형 타입")) // needs the hip
        assertFalse(result.mentions("다리가 긴 편")) // needs the inseam
        assertTrue(result.mentions(LOOSE_CLOTHING_INSIGHT_NOTE))
    }

    @Test
    fun looseClothing_withUserEnteredValues_showsThem() {
        val result = insights(looseBoth, editedByUser = true)
        assertTrue(result.mentions("하체 볼륨형"))
        assertTrue(result.mentions("어깨가 넓은 편"))
        assertFalse(result.mentions(LOOSE_CLOTHING_INSIGHT_NOTE))
    }

    @Test
    fun ratiosNearTheThreshold_showNoSentence() {
        // inseam 76/165 = 0.461: inside 0.46 ± 0.01, so neither "long legs" nor "long torso"
        val neutral = insights(fitted, inseam = 76.0)
        assertFalse(neutral.mentions("다리가 긴 편"))
        assertFalse(neutral.mentions("상체가 비교적 긴 편"))
        // inseam 73/165 = 0.442: clearly below → long torso
        assertTrue(insights(fitted, inseam = 73.0).mentions("상체가 비교적 긴 편"))
    }

    @Test
    fun bodyShape_classifiesClearCasesAndSkipsBorderline() {
        assertEquals(BodyShape.LOWER_VOLUME, bodyShapeOf(chest = 88.0, waist = 70.0, hip = 100.0))
        assertEquals(BodyShape.UPPER_VOLUME, bodyShapeOf(chest = 104.0, waist = 84.0, hip = 94.0))
        assertEquals(BodyShape.HOURGLASS, bodyShapeOf(chest = 92.0, waist = 66.0, hip = 95.0))
        assertEquals(BodyShape.STRAIGHT, bodyShapeOf(chest = 95.0, waist = 82.0, hip = 96.0))
        assertNull(bodyShapeOf(chest = 95.0, waist = 72.0, hip = 96.0)) // waist/hip 0.75: too close to call
    }

    @Test
    fun insightsNeverUseWeightOrHealthWords() {
        val all = listOf(fitted, looseBoth).flatMap { insights(it) + insights(it, editedByUser = true) } +
            BodyShape.entries.map { Insight(it.label, it.description) }
        // not "살": "살려줘요" (brings out) is fine
        listOf("비만", "뚱뚱", "체중", "몸무게").forEach { word -> assertFalse(word, all.mentions(word)) }
    }
}

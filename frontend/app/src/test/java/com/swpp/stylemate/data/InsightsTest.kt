package com.swpp.stylemate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsTest {

    // Every ratio is clearly past its threshold + margin: inseam 78/165 = 0.473, shoulder 45/165 = 0.273,
    // hip/chest 100/88 = 1.136, waist/hip 68/100 = 0.68
    private fun profile(clothing: ClothingType, editedByUser: Boolean = false, inseam: Double = 78.0) = BodyProfile(
        input = AnalysisInput(165, 58, Gender.FEMALE, clothing),
        measurements = listOf(
            BodyMeasurement(MeasurementType.INSEAM, inseam, Confidence.HIGH, editedByUser = editedByUser),
            BodyMeasurement(MeasurementType.SHOULDER_WIDTH, 45.0, Confidence.HIGH, editedByUser = editedByUser),
            BodyMeasurement(MeasurementType.CHEST, 88.0, Confidence.MEDIUM, editedByUser = editedByUser),
            BodyMeasurement(MeasurementType.WAIST, 68.0, Confidence.MEDIUM, editedByUser = editedByUser),
            BodyMeasurement(MeasurementType.HIP, 100.0, Confidence.MEDIUM, editedByUser = editedByUser),
        ),
        preferredFit = PreferredFit.REGULAR,
        preferredStyles = emptyList(),
    )

    private fun List<String>.mentions(word: String) = any { word in it }

    @Test
    fun tightClothing_showsCircumferenceInsights() {
        val insights = buildInsights(profile(ClothingType.TIGHT))
        assertTrue(insights.mentions("하체 볼륨"))
        assertTrue(insights.mentions("허리 라인"))
        assertFalse(LOOSE_CLOTHING_INSIGHT_NOTE in insights)
    }

    @Test
    fun looseClothing_hidesProportionInsights() {
        val insights = buildInsights(profile(ClothingType.LOOSE))
        assertFalse(insights.mentions("하체 볼륨"))
        assertFalse(insights.mentions("허리 라인"))
        assertFalse(insights.mentions("다리가 긴 편"))
        assertFalse(insights.mentions("상체가 비교적 긴 편"))
        assertFalse(insights.mentions("어깨가 넓은 편"))
        assertEquals(listOf(LOOSE_CLOTHING_INSIGHT_NOTE), insights)
    }

    @Test
    fun looseClothing_withUserEnteredValues_showsThem() {
        val insights = buildInsights(profile(ClothingType.LOOSE, editedByUser = true))
        assertTrue(insights.mentions("하체 볼륨"))
        assertTrue(insights.mentions("다리가 긴 편") || insights.mentions("상체가 비교적 긴 편"))
        assertTrue(insights.mentions("어깨가 넓은 편"))
        assertFalse(LOOSE_CLOTHING_INSIGHT_NOTE in insights)
    }

    @Test
    fun ratiosNearTheThreshold_showNoSentence() {
        // inseam 76/165 = 0.461: inside 0.46 ± 0.01, so neither "long legs" nor "long torso"
        val neutral = buildInsights(profile(ClothingType.TIGHT, inseam = 76.0))
        assertFalse(neutral.mentions("다리가 긴 편"))
        assertFalse(neutral.mentions("상체가 비교적 긴 편"))
        // inseam 73/165 = 0.442: clearly below → long torso
        assertTrue(buildInsights(profile(ClothingType.TIGHT, inseam = 73.0)).mentions("상체가 비교적 긴 편"))
    }

    @Test
    fun insightsNeverUseWeightOrHealthWords() {
        val all = ClothingType.entries.flatMap { buildInsights(profile(it)) }
        // not "살": "살려줘요" (brings out) is fine
        listOf("비만", "뚱뚱", "체중", "몸무게").forEach { word -> assertFalse(word, all.mentions(word)) }
    }
}

// AI-generated with Claude Code (Claude Opus 5.5), 2026-09-24, reviewed by Dongkun Moon
package com.swpp.stylemate.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val PHOTOS = BodyPhotos(ByteArray(0), ByteArray(0))

class BodyMeasurementsTest {

    private fun input(weightKg: Int? = 65, gender: Gender = Gender.MALE) = AnalysisInput(172, weightKg, gender)

    @Test
    fun toggleStyle_addsAndRemoves() {
        assertEquals(listOf("미니멀"), toggleStyle(emptyList(), "미니멀"))
        assertEquals(emptyList<String>(), toggleStyle(listOf("미니멀"), "미니멀"))
    }

    @Test
    fun toggleStyle_refusesFourthStyle() {
        val three = listOf("미니멀", "캐주얼", "스트릿")
        assertEquals(three, toggleStyle(three, "클래식"))
        assertEquals(listOf("캐주얼", "스트릿"), toggleStyle(three, "미니멀"))
    }

    @Test
    fun fittedClothing_keepsBaseConfidence() {
        MeasurementType.entries.forEach {
            assertEquals(it.baseConfidence, FakeBodyAnalyzer.confidenceFor(it, DetectedClothing()))
        }
    }

    @Test
    fun looseTop_lowersOnlyUpperBody() {
        val looseTop = DetectedClothing(topLoose = true)
        assertEquals(Confidence.LOW, FakeBodyAnalyzer.confidenceFor(MeasurementType.CHEST, looseTop))
        assertEquals(Confidence.HIGH, FakeBodyAnalyzer.confidenceFor(MeasurementType.INSEAM, looseTop))
        assertEquals(listOf(warningText("loose_top")), FakeBodyAnalyzer.warningsFor(input(), looseTop))
        assertTrue(warningText("loose_top").contains("속옷이나 몸에 붙는 옷"))
    }

    @Test
    fun looseBottom_lowersLegs() {
        val looseBottom = DetectedClothing(bottomLoose = true)
        assertEquals(Confidence.MEDIUM, FakeBodyAnalyzer.confidenceFor(MeasurementType.INSEAM, looseBottom))
        assertEquals(Confidence.HIGH, FakeBodyAnalyzer.confidenceFor(MeasurementType.SHOULDER_WIDTH, looseBottom))
        assertEquals("헐렁한 하의", looseBottom.label)
        assertEquals("헐렁한 상·하의", DetectedClothing(true, true).label)
    }

    @Test
    fun looseAffects_coversEveryCircumferenceExceptWrist() {
        val covered = LOOSE_AFFECTS.values.flatten().toSet()
        MeasurementType.entries.filter { it.group == MeasurementGroup.CIRCUMFERENCE && it != MeasurementType.WRIST }
            .forEach { assertTrue(it.name, it in covered) }
    }

    @Test
    fun analyze_includesUnderbustOnlyForFemale() = runBlocking {
        val analyzer = FakeBodyAnalyzer(latencyMillis = 0)
        val male = analyzer.analyze(input(gender = Gender.MALE), PHOTOS).measurements.map { it.type }
        val female = analyzer.analyze(input(gender = Gender.FEMALE), PHOTOS).measurements.map { it.type }
        assertFalse(MeasurementType.UNDERBUST in male)
        assertTrue(MeasurementType.UNDERBUST in female)
    }

    @Test
    fun analyze_noWarningsForIdealInput() = runBlocking {
        val result = FakeBodyAnalyzer(latencyMillis = 0).analyze(input(), PHOTOS)
        assertTrue(result.warnings.isEmpty())
        assertTrue(result.measurements.all { it.valueCm > 0 })
    }
}

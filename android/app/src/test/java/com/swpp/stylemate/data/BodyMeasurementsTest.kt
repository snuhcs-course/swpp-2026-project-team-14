package com.swpp.stylemate.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyMeasurementsTest {

    private fun input(
        clothing: ClothingType = ClothingType.UNDERWEAR,
        hasSidePhoto: Boolean = true,
        weightKg: Int? = 65,
        gender: Gender = Gender.MALE,
    ) = AnalysisInput(172, weightKg, gender, clothing, hasSidePhoto)

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
    fun underwearWithBothPhotos_keepsBaseConfidence() {
        MeasurementType.entries.forEach {
            assertEquals(it.baseConfidence, FakeBodyAnalyzer.confidenceFor(it, input()))
        }
    }

    @Test
    fun looseClothing_lowersCircumferencesOnly() {
        val loose = input(clothing = ClothingType.LOOSE)
        assertEquals(Confidence.LOW, FakeBodyAnalyzer.confidenceFor(MeasurementType.CHEST, loose))
        assertEquals(Confidence.HIGH, FakeBodyAnalyzer.confidenceFor(MeasurementType.INSEAM, loose))
        assertTrue(FakeBodyAnalyzer.warningsFor(loose).any { "헐렁한" in it })
    }

    @Test
    fun missingSidePhoto_lowersDepthDependentMeasurements() {
        val frontOnly = input(hasSidePhoto = false)
        assertEquals(Confidence.MEDIUM, FakeBodyAnalyzer.confidenceFor(MeasurementType.TORSO_LENGTH, frontOnly))
        assertEquals(Confidence.HIGH, FakeBodyAnalyzer.confidenceFor(MeasurementType.SHOULDER_WIDTH, frontOnly))
    }

    @Test
    fun analyze_includesUnderbustOnlyForFemale() = runBlocking {
        val analyzer = FakeBodyAnalyzer(latencyMillis = 0)
        val male = analyzer.analyze(input(gender = Gender.MALE)).measurements.map { it.type }
        val female = analyzer.analyze(input(gender = Gender.FEMALE)).measurements.map { it.type }
        assertFalse(MeasurementType.UNDERBUST in male)
        assertTrue(MeasurementType.UNDERBUST in female)
    }

    @Test
    fun analyze_noWarningsForIdealInput() = runBlocking {
        val result = FakeBodyAnalyzer(latencyMillis = 0).analyze(input())
        assertTrue(result.warnings.isEmpty())
        assertTrue(result.measurements.all { it.valueCm > 0 })
    }
}

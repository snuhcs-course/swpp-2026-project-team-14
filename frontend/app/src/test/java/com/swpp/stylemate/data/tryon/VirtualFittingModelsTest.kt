package com.swpp.stylemate.data.tryon

import com.swpp.stylemate.data.AnalysisInput
import com.swpp.stylemate.data.Gender
import org.junit.Assert.*
import org.junit.Test

class VirtualFittingModelsTest {
    @Test fun catalogHasTwelveDistinctModelsForEachGender() {
        assertEquals(24, VirtualFittingModels.all.size)
        assertEquals(24, VirtualFittingModels.all.map { it.id }.distinct().size)
        assertTrue(VirtualFittingModels.all.filter { it.id.endsWith("_fuller") }.all { it.sourceKey.endsWith(":v2") })
        assertTrue(VirtualFittingModels.all.filterNot { it.id.endsWith("_fuller") }.all { it.sourceKey.endsWith(":v1") })
        for (gender in listOf(Gender.MALE, Gender.FEMALE)) {
            val rows = VirtualFittingModels.all.filter { it.gender == gender }
            assertEquals(12, rows.size)
            assertEquals(4, rows.groupBy { it.heightCm }.size)
            assertTrue(rows.groupBy { it.heightCm }.values.all { it.size == 3 })
        }
    }

    @Test fun selectionUsesGenderNearestHeightAndRelativeWeight() {
        fun selected(height: Int, weight: Int, gender: Gender = Gender.MALE) =
            VirtualFittingModels.closest(AnalysisInput(height, weight, gender))!!.id
        assertEquals("male_170_fuller", selected(170, 85))
        assertEquals("male_180_fuller", selected(180, 95))
        assertEquals("male_190_regular", selected(190, 83))
        assertEquals("male_160_slim", selected(160, 50))
        assertEquals("female_160_fuller", selected(160, 73, Gender.FEMALE))
        assertEquals("female_150_regular", selected(152, 50, Gender.FEMALE))
        assertEquals("female_180_slim", selected(180, 62, Gender.FEMALE))
        assertEquals(78, VirtualFittingModels.closest(AnalysisInput(170, 85, Gender.MALE))!!.weightKg)
        assertEquals(67, VirtualFittingModels.closest(AnalysisInput(160, 73, Gender.FEMALE))!!.weightKg)
    }

    @Test fun missingOrInvalidBodyInformationDoesNotGuessAModel() {
        assertNull(VirtualFittingModels.closest(null))
        assertNull(VirtualFittingModels.closest(AnalysisInput(170, null, Gender.MALE)))
        assertNull(VirtualFittingModels.closest(AnalysisInput(170, 70, Gender.UNSPECIFIED)))
        assertNull(VirtualFittingModels.closest(AnalysisInput(0, 70, Gender.MALE)))
        assertNull(VirtualFittingModels.closest(AnalysisInput(170, -1, Gender.MALE)))
    }
}

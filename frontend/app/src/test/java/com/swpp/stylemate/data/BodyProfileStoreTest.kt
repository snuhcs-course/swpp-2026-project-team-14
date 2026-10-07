package com.swpp.stylemate.data

import com.swpp.stylemate.ui.profile.BodyProfileViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyProfileStoreTest {

    private val profile = BodyProfile(
        input = AnalysisInput(heightCm = 172, weightKg = null, gender = Gender.FEMALE),
        measurements = listOf(
            BodyMeasurement(MeasurementType.WAIST, 66.5, Confidence.MEDIUM),
            BodyMeasurement(MeasurementType.INSEAM, 77.0, Confidence.HIGH, editedByUser = true),
        ),
        preferredFit = PreferredFit.LOOSE,
        preferredStyles = listOf("미니멀", "스트릿"),
        clothing = DetectedClothing(topLoose = true),
        reference = ReferenceMeasurements("f_avg", 160.0, mapOf(MeasurementType.WAIST to 65.4)),
    )

    @Test
    fun jsonRoundTripKeepsEverything() {
        assertEquals(profile, BodyProfileJson.decode(BodyProfileJson.encode(profile)))
    }

    @Test
    fun jsonUsesApiCodes() {
        val text = BodyProfileJson.encode(profile)
        assertTrue(text.contains("\"preferred_fit\":\"loose\""))
        assertTrue(text.contains("\"type\":\"waist\""))
    }

    @Test
    fun brokenOrForeignJsonIsIgnored() {
        assertNull(BodyProfileJson.decode("not json"))
        assertNull(BodyProfileJson.decode("""{"height_cm": 170}"""))
    }

    @Test
    fun savedProfileSkipsOnboardingAndDeleteClearsTheStore() {
        val store = InMemoryBodyProfileStore(profile)
        val model = BodyProfileViewModel(FakeBodyAnalyzer(0), store)
        assertEquals(profile, model.app.value.profile)
        assertFalse(model.app.value.setupVisible)

        model.deleteProfile()
        assertNull(store.load())
        assertNull(model.app.value.profile)
    }

    @Test
    fun withoutSavedProfileTheSetupFlowOpens() {
        val model = BodyProfileViewModel(FakeBodyAnalyzer(0), InMemoryBodyProfileStore())
        assertTrue(model.app.value.setupVisible)
    }
}

package com.swpp.stylemate.wardrobe

import com.swpp.stylemate.ui.wardrobe.estimatedMeasurementText
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GarmentEstimatePresentationTest {
    @Test fun aRangeIsTheOnlyDisplayedEstimateEvenWhenTheRepresentativeValueDisagrees() {
        val input = JSONObject("""{"value":48,"min":43,"max":45}""")
        val original = input.toString()
        assertEquals("43–45 cm", estimatedMeasurementText(input))
        assertEquals(original, input.toString())
    }

    @Test fun missingOrInvalidRangesFallBackToOneApproximateValue() {
        assertEquals("약 48 cm", estimatedMeasurementText(JSONObject("""{"value":48}""")))
        assertEquals("약 48.25 cm", estimatedMeasurementText(JSONObject("""{"value":48.25,"min":50,"max":43}""")))
        assertEquals("약 44 cm", estimatedMeasurementText(JSONObject("""{"value":44,"min":44,"max":44}""")))
        assertNull(estimatedMeasurementText(JSONObject("""{"value":null,"min":-1,"max":0}""")))
    }
}

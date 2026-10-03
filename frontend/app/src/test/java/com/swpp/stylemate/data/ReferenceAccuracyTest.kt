package com.swpp.stylemate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReferenceAccuracyTest {

    private val reference = ReferenceMeasurements(
        body = "m_avg",
        heightCm = 168.2,
        values = mapOf(MeasurementType.WAIST to 80.0, MeasurementType.CHEST to 100.0),
    )

    private fun accuracy(type: MeasurementType, value: Double) =
        reference.accuracyPercent(BodyMeasurement(type, value, Confidence.MEDIUM))

    @Test
    fun accuracyIs100MinusRelativeError() {
        assertEquals(100.0, accuracy(MeasurementType.WAIST, 80.0)!!, 1e-9)
        assertEquals(97.5, accuracy(MeasurementType.WAIST, 82.0)!!, 1e-9) // 2 cm over 80 cm
        assertEquals(97.5, accuracy(MeasurementType.WAIST, 78.0)!!, 1e-9) // under counts the same
        assertEquals(0.0, accuracy(MeasurementType.CHEST, 350.0)!!, 1e-9) // never negative
    }

    @Test
    fun noAccuracyWithoutATrueValue() {
        assertNull(accuracy(MeasurementType.NECK, 39.0))
    }

    @Test
    fun percentIsFormattedWithOneDecimalAndADot() {
        assertEquals("97.5%", formatPercent(97.5))
        assertEquals("98.8%", formatPercent(98.76))
    }
}

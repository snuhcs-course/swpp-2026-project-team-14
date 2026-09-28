package com.swpp.stylemate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyFigureGeometryTest {

    private fun hotspots(gender: Gender) = MeasurementType.entries.mapNotNull { hotspotFor(it, gender) }

    @Test
    fun everyMeasurementHasAPlaceOnTheFigure() {
        Gender.entries.forEach { gender ->
            MeasurementType.entries.filter { it != MeasurementType.UNDERBUST }
                .forEach { assertNotNull("$it / $gender", hotspotFor(it, gender)) }
        }
        assertNotNull(hotspotFor(MeasurementType.UNDERBUST, Gender.FEMALE))
        assertNull(hotspotFor(MeasurementType.UNDERBUST, Gender.MALE))
    }

    @Test
    fun hotspotsStayInsideTheFigureAndRunTopToBottom() {
        Gender.entries.forEach { gender ->
            hotspots(gender).forEach { h ->
                listOf(h.start, h.end).forEach { p ->
                    assertTrue("$h", p.y in 0.0..1.0)
                    assertTrue("$h", p.x in -1.0..1.0) // figure spans ±1 (chips sit outside that)
                }
            }
            fun y(type: MeasurementType) = hotspotFor(type, gender)!!.centre.y
            assertTrue(y(MeasurementType.NECK) < y(MeasurementType.CHEST))
            assertTrue(y(MeasurementType.CHEST) < y(MeasurementType.WAIST))
            assertTrue(y(MeasurementType.WAIST) < y(MeasurementType.HIP))
            assertTrue(y(MeasurementType.HIP) < y(MeasurementType.THIGH))
            assertTrue(y(MeasurementType.THIGH) < y(MeasurementType.CALF))
        }
    }

    @Test
    fun tappingEachHotspotSelectsItself() {
        // 360 dp wide card: x scale = 0.27·W, y scale = 1.25·W; taps within 26 dp
        val xScale = 0.27 * 360
        val yScale = 1.25 * 360
        Gender.entries.forEach { gender ->
            val all = hotspots(gender)
            all.forEach { h -> assertEquals("$gender", h.type, hotspotAt(h.centre, all, xScale, yScale, 26.0)?.type) }
        }
    }

    @Test
    fun tapFarFromTheBodySelectsNothing() {
        assertNull(hotspotAt(Point(0.95, 0.02), hotspots(Gender.MALE), 97.2, 450.0, 26.0))
    }
}

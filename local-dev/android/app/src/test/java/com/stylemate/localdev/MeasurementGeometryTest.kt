package com.stylemate.localdev

import org.junit.Assert.*
import org.junit.Test

class MeasurementGeometryTest {
    // Synthetic overhead camera: a 2 m wide viewport above a horizontal floor.
    private val inverse = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, -1f, 0f,
        0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f)
    private fun floor(y: Float = 0f) = MeasurementGeometry(inverse, MeasurePoint(0f, y, 0f), MeasurePoint(0f, 1f, 0f))

    @Test fun convertsViewportToMetricLengthWithoutPixelScaleConstant() {
        val a = floor().point(0.25f, 0.5f)!!
        val b = floor().point(0.75f, 0.5f)!!
        assertEquals(100f, distanceCm(a, b), 0.001f)
        assertEquals(0f, a.y, 0.0001f)
    }

    @Test fun screenYAxisIsFlippedAndDistancesWorkVertically() {
        val a = floor().point(0.5f, 0.25f)!!
        val b = floor().point(0.5f, 0.5f)!!
        assertEquals(-0.5f, a.z, 0.0001f)
        assertEquals(50f, distanceCm(a, b), 0.001f)
    }

    @Test fun rejectsOutsideImageAndInvalidInput() {
        assertNull(floor().point(-0.01f, 0.5f))
        assertNull(floor().point(0.5f, 1.01f))
        assertNull(floor().point(Float.NaN, 0.5f))
    }

    @Test fun rejectsPlaneBehindCameraAndParallelRays() {
        assertNull(floor(2f).point(0.5f, 0.5f))
        val vertical = MeasurementGeometry(inverse, MeasurePoint(0f, 0f, 0f), MeasurePoint(1f, 0f, 0f))
        assertNull(vertical.point(0.5f, 0.5f))
    }

    @Test fun rejectsSingularProjection() {
        val invalid = MeasurementGeometry(FloatArray(16), MeasurePoint(0f, 0f, 0f), MeasurePoint(0f, 1f, 0f))
        assertNull(invalid.point(0.5f, 0.5f))
    }

    @Test fun perspectiveDistanceChangesWithPlaneDepth() {
        // Inverse of a 90 degree perspective camera with near=.1 m, far=100 m.
        val perspective = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, -4.995f, 0f, 0f, -1f, 5.005f)
        fun widthAt(depth: Float): Float {
            val geometry = MeasurementGeometry(perspective, MeasurePoint(0f, 0f, -depth), MeasurePoint(0f, 0f, 1f))
            return distanceCm(geometry.point(0.25f, 0.5f)!!, geometry.point(0.75f, 0.5f)!!)
        }
        assertEquals(200f, widthAt(2f), 0.01f)
        assertEquals(400f, widthAt(4f), 0.01f)
    }

    @Test fun recoversKnownGarmentDimensionsAtDifferentHeightsAndAnglesWithoutReferenceObject() {
        val inverseProjection = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, -4.995f, 0f, 0f, -1f, 5.005f)
        for (height in listOf(.6f, 1f, 1.8f, 2.5f)) for (degrees in listOf(0f, 20f, 40f)) {
            val angle = Math.toRadians(degrees.toDouble())
            val s = kotlin.math.sin(angle).toFloat()
            val c = kotlin.math.cos(angle).toFloat()
            val cameraToWorld = floatArrayOf(1f, 0f, 0f, 0f, 0f, s, -c, 0f,
                0f, c, s, 0f, 0f, height, 0f, 1f)
            val inverseVP = FloatArray(16)
            for (column in 0..3) for (row in 0..3) for (k in 0..3) {
                inverseVP[column * 4 + row] += cameraToWorld[k * 4 + row] * inverseProjection[column * 4 + k]
            }
            val geometry = MeasurementGeometry(inverseVP, MeasurePoint(0f, 0f, 0f), MeasurePoint(0f, 1f, 0f))
            // Independent forward projection of known 3D points, then reconstruct via app code.
            fun reconstruct(x: Float, z: Float): MeasurePoint {
                val cameraY = -s * height - c * z
                val cameraZ = -c * height + s * z
                return geometry.point((x / -cameraZ + 1f) / 2f, (1f - cameraY / -cameraZ) / 2f)!!
            }
            assertEquals("h=$height tilt=$degrees", 50f, distanceCm(reconstruct(-.25f, 0f), reconstruct(.25f, 0f)), .01f)
            assertEquals("h=$height tilt=$degrees", 20f, distanceCm(reconstruct(0f, 0f), reconstruct(0f, -.2f)), .01f)
        }
    }

    @Test fun reportsTiltFromCameraDirectionAndPlaneNormal() {
        val normal = MeasurePoint(0f, 1f, 0f)
        assertEquals(30f, captureTiltDegrees(MeasurePoint(0f, -.8660254f, -.5f), normal)!!, .001f)
        assertEquals(0f, captureTiltDegrees(MeasurePoint(0f, -1f, 0f), normal)!!, .001f)
        assertNull(captureTiltDegrees(MeasurePoint(0f, 1f, 0f), normal))
        assertNull(captureTiltDegrees(MeasurePoint(0f, 0f, 0f), normal))
    }

    @Test fun seamLengthUsesIntermediatePointsInsteadOfEndpointShortcut() {
        val path = listOf(MeasurePoint(0f, 0f, 0f), MeasurePoint(.3f, 0f, 0f), MeasurePoint(.3f, 0f, .4f))
        assertEquals(70f, pathLengthCm(path), .001f)
        assertEquals(50f, distanceCm(path.first(), path.last()), .001f)
    }
}

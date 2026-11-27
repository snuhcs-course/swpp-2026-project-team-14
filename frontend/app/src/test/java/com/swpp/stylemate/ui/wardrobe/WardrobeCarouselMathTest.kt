// AI-generated with Codex and Claude Code (Dongkun Moon), 2026-11-25, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.wardrobe

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class WardrobeCarouselMathTest {
    @Test fun sideHangersKeepAReadableFrontSurfaceWhileTheCenterFacesForward() {
        assertEquals(0f, wardrobeHangerYaw(0f, 200f), .0001f)
        assertTrue(abs(wardrobeHangerYaw(120f, 200f)) in 30f..45f)
        assertEquals(-52f, wardrobeHangerYaw(200f, 200f), .0001f)
        for (offset in listOf(2f, 50f, 120f, 200f))
            assertEquals(-wardrobeHangerYaw(offset, 200f), wardrobeHangerYaw(-offset, 200f), .0001f)
    }

    @Test fun rotationOpensContinuouslyWithoutAJumpAtTheCenter() {
        assertTrue(abs(wardrobeHangerYaw(.1f, 200f)) < .1f)
        assertTrue(abs(wardrobeHangerYaw(199.999f, 200f) - wardrobeHangerYaw(200.001f, 200f)) < .01f)
    }

    @Test fun rotationChangesGraduallyAcrossTheWholeRailWithoutASharpKnee() {
        for (offset in 0 until 200) {
            val change = abs(wardrobeHangerYaw(offset + 1f, 200f) - wardrobeHangerYaw(offset.toFloat(), 200f))
            assertTrue("Abrupt turn at $offset", change < .5f)
        }
    }

    @Test fun railBowAndHookLiftShareTheSameShallowUProfile() {
        assertEquals(0f, wardrobeRailLift(0f, 200f), .0001f)
        assertEquals(1f, wardrobeRailLift(200f, 200f), .0001f)
        assertEquals(.25f, wardrobeRailLift(100f, 200f), .0001f)
        assertEquals(wardrobeRailLift(150f, 200f), wardrobeRailLift(-150f, 200f), .0001f)
        assertEquals(1f, wardrobeRailLift(500f, 200f), .0001f)
    }
    @Test fun closestGarment_staysInFrontAcrossSelectionBoundary() {
        assertTrue(wardrobeLayerDepth(2, 2.49f) > wardrobeLayerDepth(3, 2.49f))
        assertTrue(wardrobeLayerDepth(3, 2.51f) > wardrobeLayerDepth(2, 2.51f))
        assertEquals(wardrobeLayerDepth(2, 2.5f), wardrobeLayerDepth(3, 2.5f), .0001f)
    }

    @Test fun depthOrder_isContinuousAndSymmetricAroundCurrentPosition() {
        assertEquals(wardrobeLayerDepth(1, 2f), wardrobeLayerDepth(3, 2f), .0001f)
        assertTrue(abs(wardrobeLayerDepth(2, 2.499f) - wardrobeLayerDepth(2, 2.501f)) < .01f)
    }
    @Test fun reorderedOrFilteredCollection_preservesSelectedIdentity() {
        assertEquals(0f, wardrobeSelectionIndex(listOf("coat", "shirt"), "coat", 8f), .0001f)
        assertEquals(1f, wardrobeSelectionIndex(listOf("shirt", "coat"), "coat", 0f), .0001f)
    }

    @Test fun removedSelection_clampsToNearestRemainingPosition() {
        assertEquals(2f, wardrobeSelectionIndex(listOf("a", "b", "c"), "removed", 7f), .0001f)
        assertEquals(1f, wardrobeSelectionIndex(listOf("a", "b", "c"), "removed", 1f), .0001f)
        assertEquals(0f, wardrobeSelectionIndex(emptyList(), "removed", 7f), .0001f)
    }

    @Test fun spacing_isSymmetric_andCompressesTowardsEdges() {
        assertEquals(0f, wardrobeRailOffset(0f), .0001f)
        for (distance in 1..5) {
            assertEquals(-wardrobeRailOffset(distance.toFloat()), wardrobeRailOffset(-distance.toFloat()), .0001f)
        }
        val gaps = (1..5).map { wardrobeRailOffset(it.toFloat()) - wardrobeRailOffset((it - 1).toFloat()) }
        assertTrue(gaps.all { it > 0f })
        assertTrue(gaps.zipWithNext().all { (inner, outer) -> inner > outer })
    }

    @Test fun releasedDrag_snapsToNearestItem_withoutVelocity() {
        assertEquals(2, wardrobeSnapTarget(2.49f, 0f, 100f, 10))
        assertEquals(3, wardrobeSnapTarget(2.51f, 0f, 100f, 10))
    }

    @Test fun fling_usesDirection_andNeverLeavesRealItems() {
        assertTrue(wardrobeSnapTarget(4f, -1000f, 100f, 10) > 4)
        assertTrue(wardrobeSnapTarget(4f, 1000f, 100f, 10) < 4)
        assertEquals(0, wardrobeSnapTarget(0f, 100000f, 100f, 10))
        assertEquals(9, wardrobeSnapTarget(9f, -100000f, 100f, 10))
        assertEquals(0, wardrobeSnapTarget(0f, -100000f, 0f, 1))
        assertEquals(0, wardrobeSnapTarget(0f, -100000f, 0f, 0))
    }
}

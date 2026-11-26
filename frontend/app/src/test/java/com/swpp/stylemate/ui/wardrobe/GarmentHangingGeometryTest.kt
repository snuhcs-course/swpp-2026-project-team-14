// AI-generated with Codex and Claude Code (Dongkun Moon), 2026-11-25, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.wardrobe

import org.junit.Assert.*
import org.junit.Test

class GarmentHangingGeometryTest {
    @Test fun longSleevesDoNotDetermineTheShoulderWidth() {
        val mask = IntArray(100 * 100)
        for (y in 0..99) for (x in 30..69) mask[y * 100 + x] = -1
        for (y in 35..85) for (x in 0..99) mask[y * 100 + x] = -1
        val shape = garmentHangingShape(mask, 100, 100, "top")
        assertEquals(.5f, shape.neckX, .01f)
        assertTrue(shape.right - shape.left < .45f)
        assertTrue(shape.left > .25f && shape.right < .75f)
    }

    @Test fun flaredSkirtUsesTheWaistInsteadOfTheHem() {
        val mask = IntArray(100 * 100)
        for (y in 0..99) {
            val halfWidth = if (y < 12) 10 else 10 + (y - 12) * 39 / 87
            for (x in 50 - halfWidth until 50 + halfWidth) mask[y * 100 + x] = -1
        }
        val shape = garmentHangingShape(mask, 100, 100, "bottom")
        assertTrue(shape.right - shape.left < .25f)
        assertEquals(.5f, shape.neckX, .01f)
    }

    @Test fun transparentNeckOpeningMovesTheAttachmentDownIntoTheNeckline() {
        val mask = IntArray(100 * 100)
        for (y in 0..99) for (x in 20..79) {
            if (!(x in 43..56 && y < 12)) mask[y * 100 + x] = -1
        }
        val shape = garmentHangingShape(mask, 100, 100, "top")
        assertEquals(.12f, shape.neckY, .01f)
    }

    @Test fun asymmetricCropKeepsTheHookAlignedWithTheUpperGarment() {
        val mask = IntArray(100 * 100)
        for (y in 0..99) for (x in 40..79) mask[y * 100 + x] = -1
        val shape = garmentHangingShape(mask, 100, 100, "outerwear")
        assertEquals(.6f, shape.neckX, .01f)
    }

    @Test fun missingSilhouetteHasBoundedUsableFallbackAnchors() {
        val shape = garmentHangingShape(IntArray(20 * 100), 20, 100, "bottom")
        assertEquals(.2f, shape.aspectRatio, .001f)
        assertTrue(shape.left < shape.neckX && shape.right > shape.neckX)
    }
}

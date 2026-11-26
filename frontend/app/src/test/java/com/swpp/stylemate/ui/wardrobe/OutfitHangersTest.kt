// AI-generated with Claude Code (Claude Opus 5.5), 2026-11-26, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.wardrobe

import com.swpp.stylemate.data.recommendation.OutfitGarment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutfitHangersTest {
    private fun garment(id: String, slot: String) =
        OutfitGarment(id, slot, "옷 $id", "/api/wardrobe/items/$id/image/", "")

    @Test fun outfitsHangOuterwearThenTopThenBottom() {
        val hung = hangingOrder(listOf(garment("b", "bottom"), garment("t", "top"), garment("o", "outerwear")))
        assertEquals(listOf("outerwear", "top", "bottom"), hung.map { it.slot })
    }

    @Test fun onlyTheOuterGarmentsOfAShortRailTilt() {
        assertEquals(listOf(-6f, 0f, 6f), (0..2).map { railTilt(it, 3) })
        assertEquals(listOf(-6f, 6f), (0..1).map { railTilt(it, 2) })
        assertEquals(0f, railTilt(0, 1))
    }

    @Test fun anOutfitGarmentUsesItsStoredCutout() {
        val item = garment("g7", "top").asWardrobeItem()
        assertEquals("top", item.category)
        assertEquals("/api/wardrobe/items/g7/display-cutout/", storedCutoutPath(item))
    }

    // Home design B: the outfit shown as worn.
    private val jacket = GarmentHangingShape(aspectRatio = .9f, neckX = .5f)
    private val tee = GarmentHangingShape(aspectRatio = 1.1f, neckX = .5f)
    private val jeans = GarmentHangingShape(aspectRatio = .42f, left = .1f, right = .9f, supportY = .02f)

    @Test fun theTopOverlapsTheOuterwearInFrontAndTheBottomTucksUnderItsHem() {
        val layout = dressedLayout(400f, 600f, jacket, tee, jeans)
        val outer = layout.getValue("outerwear"); val top = layout.getValue("top"); val bottom = layout.getValue("bottom")
        assertTrue("jacket behind on the left, top in front on the right", outer.center.x < top.center.x)
        assertTrue("they overlap", outer.right > top.left)
        assertTrue("top starts below the jacket collar", top.top > outer.top)
        assertTrue("waist under the top hem, overlapping it", bottom.top < top.bottom && bottom.top > top.top + top.height * .7f)
    }

    @Test fun theFigureIsScaledToFitAndCentred() {
        val layout = dressedLayout(400f, 300f, jacket, tee, jeans)
        assertTrue("fits", layout.values.maxOf { it.bottom } <= 300.5f && layout.values.minOf { it.top } >= -.5f)
        assertTrue("inside the width", layout.values.minOf { it.left } >= 0f && layout.values.maxOf { it.right } <= 400.5f)
        val middle = (layout.values.minOf { it.left } + layout.values.maxOf { it.right }) / 2
        assertEquals("centred", 200f, middle, 1f)
    }

    @Test fun garmentsShareOneRealScaleWhateverTheirPhotos() {
        // A long-sleeved top (narrow shoulders in a wide photo) and wide trousers.
        val longSleeve = GarmentHangingShape(aspectRatio = 1.3f, left = .3f, right = .7f)
        val wideLegs = GarmentHangingShape(aspectRatio = .6f, left = .2f, right = .8f, supportY = .02f)
        val layout = dressedLayout(400f, 2000f, null, longSleeve, wideLegs)
        val shoulders = layout.getValue("top").width * .4f
        val waist = layout.getValue("bottom").width * .6f
        assertEquals("shoulders : waist = 44 : 38", TOP_SHOULDER_CM / WAIST_CM, shoulders / waist, .01f)
    }
}

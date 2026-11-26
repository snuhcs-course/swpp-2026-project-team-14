// AI-generated with Claude Code (Claude Opus 5.5), 2026-11-26, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.wardrobe

import com.swpp.stylemate.data.recommendation.OutfitGarment
import org.junit.Assert.assertEquals
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
}

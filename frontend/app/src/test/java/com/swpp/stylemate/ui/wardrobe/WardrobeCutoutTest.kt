// AI-generated with Codex and Claude Code (Dongkun Moon), 2026-11-25, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.wardrobe

import org.junit.Assert.*
import org.junit.Test

class WardrobeCutoutTest {
    // Partitions: opaque studio / existing alpha / uncertain scene / no foreground.
    @Test fun neutralBorder_removedButWhitePatternInsideGarmentPreserved() {
        val source = IntArray(100) { -1 }
        for (y in 2..7) for (x in 3..6) source[y * 10 + x] = 0xFF202020.toInt()
        source[44] = -1 // white logo enclosed by the garment, not connected to background
        val original = source.copyOf()
        val result = checkNotNull(studioCutout(source, 10, 10))
        assertEquals(4, result.width)
        assertEquals(6, result.height)
        assertEquals(-1, result.pixels[2 * 4 + 1])
        assertArrayEquals(original, source)
    }

    @Test fun existingAlpha_croppedWithoutChangingForegroundColours() {
        val source = IntArray(100)
        source[44] = 0xFFCC1133.toInt()
        source[45] = 0xFF22AA55.toInt()
        val result = checkNotNull(studioCutout(source, 10, 10))
        assertEquals(2, result.width)
        assertEquals(1, result.height)
        assertArrayEquals(intArrayOf(0xFFCC1133.toInt(), 0xFF22AA55.toInt()), result.pixels)
    }

    @Test fun colouredBackground_rejectedRatherThanReturningARectangle() {
        assertNull(studioCutout(IntArray(100) { 0xFFAA7733.toInt() }, 10, 10))
    }

    @Test fun allWhiteImage_rejectedAsNoVisibleGarment() {
        assertNull(studioCutout(IntArray(100) { -1 }, 10, 10))
    }

    @Test fun fullyTransparentImage_rejectedAsNoVisibleGarment() {
        assertNull(studioCutout(IntArray(100), 10, 10))
    }

    @Test fun tinyOpaqueWhiteImages_areNotMistakenForExistingAlpha() {
        for (width in 1..19) assertNull(studioCutout(IntArray(width) { -1 }, width, 1))
    }

    @Test fun lowContrastBodyWithOnlyDarkSeamsRemaining_isRejected() {
        val source = IntArray(10_000) { 0xFFF5F5F5.toInt() }
        for (y in 15..84) for (x in 20..79) source[y * 100 + x] = 0xFFEEEEEE.toInt()
        // The old matte retained these two bands (7.2% of the image), silently
        // removing most of the near-white garment between them.
        for (y in (15..20) + (79..84)) for (x in 20..79) source[y * 100 + x] = 0xFF888888.toInt()
        val original = source.copyOf()
        assertNull(studioCutout(source, 100, 100))
        assertArrayEquals(original, source)
    }

    @Test fun lowContrastBodyWithConnectedRimRemaining_isRejected() {
        val source = IntArray(10_000) { 0xFFF5F5F5.toInt() }
        for (y in 15..84) for (x in 20..79) {
            source[y * 100 + x] = if (x <= 22 || x >= 77 || y >= 82) 0xFF888888.toInt()
            else 0xFFEEEEEE.toInt()
        }
        // A U-shaped dark seam remains connected, but its light body is gone.
        assertNull(studioCutout(source, 100, 100))
    }

    @Test fun whiteGarmentWithClearStudioContrast_preservesItsBody() {
        val source = IntArray(400) { 0xFFDDDDDD.toInt() }
        for (y in 4..15) for (x in 6..13) source[y * 20 + x] = 0xFFFAFAFA.toInt()
        val result = checkNotNull(studioCutout(source, 20, 20))
        assertEquals(8, result.width)
        assertEquals(12, result.height)
        assertEquals(0xFFFAFAFA.toInt(), result.pixels[5 * 8 + 4])
    }

    @Test fun existingAlphaWithSeparateItems_doesNotLoseEitherComponent() {
        val source = IntArray(400)
        for (y in 4..15) for (x in (3..6) + (13..16)) source[y * 20 + x] = 0xFFFAFAFA.toInt()
        val result = checkNotNull(studioCutout(source, 20, 20))
        assertEquals(14, result.width)
        assertEquals(12, result.height)
        assertEquals(96, result.pixels.count { (it ushr 24) >= 16 })
    }

    @Test fun missingDimensions_rejectedExplicitly() {
        assertThrows(IllegalArgumentException::class.java) { studioCutout(IntArray(0), 0, 0) }
    }

    // Stored cutouts (#108): the rail loads the server's stored PNG, never the original photo.
    @Test fun storedCutout_preferredOverTheOriginalPhoto() {
        val item = WardrobeItem("g1", "셔츠", "top", imageUrl = "/api/wardrobe/items/g1/image/",
            cutoutUrl = "/api/wardrobe/items/g1/display-cutout/")
        assertEquals("/api/wardrobe/items/g1/display-cutout/", storedCutoutPath(item))
    }

    @Test fun olderServerWithoutCutoutUrl_usesTheSameRouteFromTheImageUrl() {
        val item = WardrobeItem("g2", "바지", "bottom", imageUrl = "/api/wardrobe/items/g2/image/")
        assertEquals("/api/wardrobe/items/g2/display-cutout/", storedCutoutPath(item))
    }

    @Test fun localPhotoWithoutAServerRecord_hasNoStoredCutout() {
        assertNull(storedCutoutPath(WardrobeItem("draft", "코트", "outerwear")))
        assertNull(storedCutoutPath(WardrobeItem("x", "코트", "outerwear", imageUrl = "/somewhere/else.png")))
    }
}

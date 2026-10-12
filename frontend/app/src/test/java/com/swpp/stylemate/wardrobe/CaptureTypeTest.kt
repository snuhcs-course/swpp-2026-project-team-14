// AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.wardrobe

import com.swpp.stylemate.data.wardrobe.applyCaptureType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureTypeTest {
    private fun bottom(subcategory: String?) = JSONObject().put("name", "바지").put("category", "bottom")
        .put("subcategory", subcategory ?: JSONObject.NULL).put("leg_shape", "wide").put("skirt_shape", "a_line")

    @Test
    fun shortsAndSkirtsFollowWhatTheUserMeasured() {
        assertEquals("shorts", applyCaptureType(bottom("jeans"), "shorts").getString("subcategory"))
        val skirt = applyCaptureType(bottom(null), "skirt")
        assertEquals("skirt", skirt.getString("subcategory"))
        assertTrue(skirt.isNull("leg_shape")) // a skirt has no leg shape
        assertEquals("a_line", skirt.getString("skirt_shape"))
    }

    @Test
    fun trousersKeepTheAiPantsKindOrBecomePants() {
        assertEquals("jeans", applyCaptureType(bottom("jeans"), "trousers").getString("subcategory"))
        val fixed = applyCaptureType(bottom("skirt"), "trousers")
        assertEquals("pants", fixed.getString("subcategory"))
        assertTrue(fixed.isNull("skirt_shape"))
    }

    @Test
    fun topsUnknownTypesAndTheInputAreLeftAlone() {
        val top = JSONObject().put("category", "top").put("subcategory", "knitwear")
        assertEquals("knitwear", applyCaptureType(top, "shorts").getString("subcategory"))
        val original = bottom("slacks")
        assertEquals("slacks", applyCaptureType(original, null).getString("subcategory"))
        applyCaptureType(original, "shorts")
        assertEquals("slacks", original.getString("subcategory")) // a copy is returned
    }
}

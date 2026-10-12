// AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.wardrobe

import com.swpp.stylemate.ui.wardrobe.GarmentLabels
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GarmentLabelsTest {
    // shape of GET /api/wardrobe/options/ -> label_options
    private val options = JSONObject("""{
        "top": {"fit": ["타이트", "노멀", "루즈", "오버사이즈"], "length": ["크롭", "노멀", "롱"],
                "sleeve": ["민소매", "캡", "반팔", "7부소매", "긴팔"], "season": ["여름", "간절기", "겨울"],
                "neckline": ["라운드넥", "브이넥"], "collar": ["셔츠칼라"], "prints": ["무지", "체크", "스트라이프"],
                "materials": ["니트", "린넨", "데님"]},
        "bottom": {"fit": ["스키니", "노멀", "벨보텀", "와이드", "루즈"], "length": ["미니", "니렝스", "미디", "발목", "맥시"],
                   "season": ["여름", "간절기", "겨울"], "prints": ["무지", "체크"], "materials": ["데님", "린넨"]},
        "limits": {"prints": 2, "materials": 2}}""")

    private val suggested = JSONObject("""{"fit": "루즈", "fit_basis": "가슴 여유 16 cm", "length": "노멀",
        "sleeve": "긴팔", "season": "간절기", "prints": ["무지"], "neckline": "라운드넥", "collar": null,
        "materials": ["니트"], "too_small": false, "confirmed": false}""")

    @Test
    fun edits_winAndTheResultIsConfirmed() {
        val merged = GarmentLabels.merge(suggested, mapOf("fit" to "오버사이즈", "materials" to JSONArray(listOf("린넨"))), "top", options)!!
        assertEquals("오버사이즈", merged.getString("fit"))
        assertEquals("가슴 여유 16 cm", merged.getString("fit_basis"))
        assertEquals(listOf("린넨"), (0 until merged.getJSONArray("materials").length()).map { merged.getJSONArray("materials").getString(it) })
        assertTrue(merged.getBoolean("confirmed"))
    }

    @Test
    fun valuesOutsideTheCategoryAreDropped() {
        // the garment became a bottom before the labels were recomputed
        val merged = GarmentLabels.merge(suggested, mapOf("prints" to JSONArray(listOf("체크", "스트라이프", "체크"))), "bottom", options)!!
        assertEquals("루즈", merged.getString("fit")) // valid for bottoms too
        assertTrue(merged.isNull("length")) // 노멀 is not a bottom length
        assertEquals(1, merged.getJSONArray("prints").length()) // 스트라이프 not allowed, duplicate removed
        assertTrue(merged.isNull("collar"))
        val cleared = GarmentLabels.merge(suggested, mapOf("fit" to null), "top", options)!!
        assertTrue(cleared.isNull("fit"))
    }

    @Test
    fun designDetails_areCheckedAndLimitedToThree() {
        val withDetails = JSONObject(options.toString())
        withDetails.getJSONObject("top").put("design_details", JSONArray(listOf("프릴", "리본", "포켓", "지퍼")))
        withDetails.getJSONObject("limits").put("design_details", 3)
        val merged = GarmentLabels.merge(suggested, mapOf("design_details" to JSONArray(listOf("프릴", "반짝이", "리본", "포켓", "지퍼"))),
            "top", withDetails)!!
        val details = merged.getJSONArray("design_details")
        assertEquals(listOf("프릴", "리본", "포켓"), (0 until details.length()).map { details.getString(it) })
    }

    @Test
    fun noSuggestionOrShoes_meansNoLabels() {
        assertNull(GarmentLabels.merge(null, mapOf("fit" to "루즈"), "top", options))
        assertNull(GarmentLabels.merge(suggested, emptyMap(), "shoes", options))
        assertNull(GarmentLabels.merge(suggested, emptyMap(), "top", null))
    }

    @Test
    fun details_comeFromTheAnalysisOrTheSavedLabels() {
        val analysis = JSONObject().put("details", JSONObject().put("print", "체크"))
        assertEquals("체크", GarmentLabels.details(analysis, suggested)!!.getString("print"))
        val fromSaved = GarmentLabels.details(null, suggested)!!
        assertEquals("무지", fromSaved.getString("print"))
        assertEquals("라운드넥", fromSaved.getString("neckline"))
        assertTrue(fromSaved.isNull("collar"))
        assertNull(GarmentLabels.details(null, null))
    }
}

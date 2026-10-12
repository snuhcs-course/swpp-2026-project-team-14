// AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.data.wardrobe

import org.json.JSONObject

private val PANTS = setOf("jeans", "slacks", "pants", "active_pants")

/**
 * The garment kind the user picked for AR measurement (`landmarkTypes`) is more reliable than a guess
 * from the photo for shorts and skirts, so it fills the bottom subcategory. Returns a copy.
 */
fun applyCaptureType(attributes: JSONObject, garmentType: String?): JSONObject {
    val result = JSONObject(attributes.toString())
    if (result.optString("category") != "bottom") return result
    val current = if (result.isNull("subcategory")) null else result.optString("subcategory")
    when (garmentType) {
        "shorts" -> result.put("subcategory", "shorts")
        "skirt" -> result.put("subcategory", "skirt")
        "trousers" -> if (current !in PANTS) result.put("subcategory", "pants")
    }
    // the shape fields follow the subcategory (wardrobe schema: skirts have no leg shape and vice versa)
    when (result.optString("subcategory")) {
        "skirt" -> result.put("leg_shape", JSONObject.NULL)
        "shorts", in PANTS -> result.put("skirt_shape", JSONObject.NULL)
    }
    return result
}

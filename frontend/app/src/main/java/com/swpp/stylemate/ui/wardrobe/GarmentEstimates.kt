package com.swpp.stylemate.ui.wardrobe

import androidx.compose.runtime.Composable
import org.json.JSONObject
import java.util.Locale

/** Show one estimate per field. A representative value is not a second measurement. */
internal fun estimatedMeasurementText(value: JSONObject): String? {
    fun number(key: String) = value.optDouble(key, Double.NaN).takeIf { it.isFinite() && it > 0 }
    fun format(number: Double) = String.format(Locale.KOREA, "%.2f", number).trimEnd('0').trimEnd('.')
    val min = number("min")
    val max = number("max")
    if (min != null && max != null && min <= max) return if (min == max) "약 ${format(min)} cm"
        else "${format(min)}–${format(max)} cm"
    return number("value")?.let { "약 ${format(it)} cm" }
}

/** Never return estimates as the editor's actual dimensions or save payload. */
internal fun matchingWornEstimates(record: JSONObject, attributes: JSONObject, labels: JSONObject?): JSONObject? {
    val estimate = record.optJSONObject("estimated_dimensions") ?: return null
    fun code(json: JSONObject?, field: String): String? = json?.takeUnless { it.isNull(field) }?.optString(field)
    return estimate.takeIf {
        it.optString("category") == attributes.optString("category") &&
            code(it, "subcategory") == code(attributes, "subcategory") &&
            listOf("fit", "length").all { field -> code(it, field) == code(labels, field) }
    }
}

@Composable
internal fun GarmentEstimates(estimate: JSONObject?, attributes: JSONObject, catalog: JSONObject,
                              measured: JSONObject? = null) {
    if (estimate == null || estimate.optString("category") != attributes.optString("category") ||
        estimate.opt("subcategory") != attributes.opt("subcategory")) return
    val measurements = estimate.optJSONObject("measurements") ?: return
    val group = if (attributes.optString("category") == "bottom") "bottom" else "top"
    val definitions = catalog.optJSONObject("dimensions")?.optJSONObject(group) ?: return
    val rows = measurements.keys().asSequence().filter { definitions.has(it) && measured?.has(it) != true }
        .mapNotNull { field -> measurements.optJSONObject(field)?.let(::estimatedMeasurementText)?.let {
            definitions.getJSONObject(field).getString("label") to it
        } }.toList()
    if (rows.isEmpty()) return
    GarmentSection("추정 치수", "사진과 체형으로 추정한 범위예요. 실제 측정값과 다를 수 있어요.") { GarmentInfoRows(rows) }
}

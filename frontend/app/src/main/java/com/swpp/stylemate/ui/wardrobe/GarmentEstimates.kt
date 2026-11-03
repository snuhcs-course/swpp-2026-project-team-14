package com.swpp.stylemate.ui.wardrobe

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.ui.components.SectionTitle
import org.json.JSONObject

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
    val fields = measurements.keys().asSequence().filter { definitions.has(it) && measured?.has(it) != true }.toList()
    if (fields.isEmpty()) return
    SectionTitle("추정 치수", "사진·체형 기준")
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            fields.forEach { field ->
                val value = measurements.getJSONObject(field)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(definitions.getJSONObject(field).getString("label"))
                    Text("약 ${value.getInt("value")}cm · 추정", color = MaterialTheme.colorScheme.primary)
                }
                Text("예상 범위 ${value.getInt("min")}~${value.getInt("max")}cm", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("실측값이 아니에요. 체형과 착용 모습으로 계산한 대략적인 범위입니다.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

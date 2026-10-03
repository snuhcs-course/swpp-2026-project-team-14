package com.swpp.stylemate.ui.wardrobe

import com.swpp.stylemate.data.wardrobe.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject.keysList(): List<String> = keys().asSequence().toList()
private fun JSONObject.code(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

private fun attributeChoices(attributes: JSONObject, catalog: JSONObject, field: String): JSONObject {
    val category = attributes.optString("category")
    val subcategory = attributes.code("subcategory")
    if (field == "subcategory") return catalog.getJSONObject("subcategories").getJSONObject(category)
    val top = category in setOf("top", "outerwear")
    val pants = category == "bottom" && subcategory in setOf("jeans", "slacks", "pants", "active_pants", "shorts")
    val skirt = category == "bottom" && subcategory == "skirt"
    if ((field in setOf("sleeve_length", "neckline", "shoulder_construction") && !top) ||
        (field == "leg_shape" && !pants) || (field == "skirt_shape" && !skirt) ||
        (field == "rise_type" && category != "bottom") ||
        (field in setOf("fit_type", "closure", "details") && category == "shoes")) return JSONObject()
    return catalog.getJSONObject("enums").getJSONObject(field)
}

private fun dimensionChoices(attributes: JSONObject, catalog: JSONObject): JSONObject {
    val category = attributes.optString("category")
    val subcategory = attributes.code("subcategory")
    if (category == "shoes") return JSONObject()
    val fields = catalog.getJSONObject("dimensions").getJSONObject(if (category == "bottom") "bottom" else "top")
    return JSONObject().apply {
        fields.keysList().filter { key ->
            !(category == "bottom" && subcategory == "skirt" && key !in setOf("waist_width_half", "hip_width_half", "total_length")) &&
                !(attributes.code("sleeve_length") == "sleeveless" && key in setOf("sleeve_length", "cuff_width_half"))
        }.forEach { put(it, fields.get(it)) }
    }
}

@Composable
fun GarmentEditor(id: String, initialAttributes: JSONObject, initialDimensions: JSONObject?,
                  initialProperties: JSONObject, initialNotes: String, catalog: JSONObject,
                  busy: Boolean, saved: Boolean, error: String?, onSave: (JSONObject) -> Unit) {
    var attributesText by rememberSaveable(id) { mutableStateOf(initialAttributes.toString()) }
    var propertiesText by rememberSaveable(id) { mutableStateOf(initialProperties.toString()) }
    var notes by rememberSaveable(id) { mutableStateOf(initialNotes) }
    var dimensionText by rememberSaveable(id) {
        mutableStateOf(JSONObject().apply {
            initialDimensions?.keysList()?.filter { it != "unit" }?.forEach { key ->
                initialDimensions.optJSONObject(key)?.let { put(key, String.format(Locale.US, "%.2f", it.getDouble("value"))) }
            }
        }.toString())
    }
    var extra by rememberSaveable(id) { mutableStateOf(false) }
    var personal by rememberSaveable(id) { mutableStateOf(false) }
    val attributes = remember(attributesText) { JSONObject(attributesText) }
    val properties = remember(propertiesText) { JSONObject(propertiesText) }
    val inputs = remember(dimensionText) { JSONObject(dimensionText) }
    val dimensions = dimensionChoices(attributes, catalog)
    val numberPattern = Regex("[0-9]+(?:\\.[0-9]{0,2})?")
    fun validNumber(text: String): Boolean = text.isBlank() ||
        (numberPattern.matches(text) && text.toBigDecimalOrNull()?.let { it > BigDecimal.ZERO } == true)
    val valid = attributes.optString("name").isNotBlank() && dimensions.keysList().all { validNumber(inputs.optString(it)) }

    fun updateAttribute(key: String, value: Any) {
        val next = JSONObject(attributesText).put(key, value)
        if (key == "category") {
            next.put("subcategory", JSONObject.NULL)
            dimensionText = "{}"
        }
        val limits = catalog.getJSONObject("array_limits")
        catalog.getJSONObject("enums").keysList().forEach { field ->
            val choices = attributeChoices(next, catalog, field)
            if (limits.has(field)) {
                val old = next.optJSONArray(field) ?: JSONArray()
                next.put(field, JSONArray((0 until old.length()).map { old.getString(it) }.filter { choices.has(it) }))
            } else if (next.code(field) != null && !choices.has(next.getString(field))) next.put(field, JSONObject.NULL)
        }
        val allowed = dimensionChoices(next, catalog)
        val remaining = JSONObject(dimensionText)
        remaining.keysList().filter { !allowed.has(it) }.forEach { remaining.remove(it) }
        dimensionText = remaining.toString()
        attributesText = next.toString()
    }

    Text(if (saved) "옷 정보" else "분석 결과", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(value = attributes.optString("name"), onValueChange = {
        if (it.length <= 80) attributesText = JSONObject(attributesText).put("name", it).toString()
    }, label = { Text("이름") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
    val primary = setOf("category", "colors")
    @Composable fun attribute(field: String) {
        val choices = attributeChoices(attributes, catalog, field)
        if (choices.length() > 0) ChoiceField(catalog.getJSONObject("labels").getString(field), choices,
            attributes.opt(field), catalog.getJSONObject("array_limits").optInt(field, 0), !busy,
            nullable = field != "category", onChange = { updateAttribute(field, it) })
    }
    primary.forEach { attribute(it) }
    TextButton(onClick = { extra = !extra }) { Text(if (extra) "상세 정보 접기" else "상세 정보") }
    if (extra) catalog.getJSONObject("enums").keysList().filter { it !in primary }.forEach { attribute(it) }
    if (dimensions.length() > 0) {
        Text("치수", style = MaterialTheme.typography.titleMedium)
        dimensions.keysList().forEach { field ->
            val text = inputs.optString(field)
            val original = initialDimensions?.optJSONObject(field)
            val isEstimate = original != null && original.optString("source") in setOf("arcore_manual", "arcore_assisted") && text.toDoubleOrNull() == original.optDouble("value")
            OutlinedTextField(value = text, onValueChange = {
                if (it.length <= 16) dimensionText = JSONObject(dimensionText).put(field, it).toString()
            }, label = { Text(dimensions.getJSONObject(field).getString("label")) }, suffix = { Text("cm") },
                supportingText = if (!validNumber(text)) ({ Text("소수 둘째 자리까지 양수를 입력해 주세요.") })
                                 else if (isEstimate) ({ Text("AR 추정") }) else null,
                isError = !validNumber(text), singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        }
    }
    TextButton(onClick = { personal = !personal }) { Text(if (personal) "착용 정보 접기" else "착용 정보") }
    if (personal) {
        OutlinedTextField(value = properties.code("material_note") ?: "", onValueChange = {
            if (it.length <= 200) propertiesText = JSONObject(propertiesText).put("material_note", it).toString()
        }, label = { Text("소재") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        catalog.getJSONObject("user_enums").keysList().forEach { field ->
            ChoiceField(catalog.getJSONObject("user_labels").getString(field), catalog.getJSONObject("user_enums").getJSONObject(field),
                properties.opt(field), if (field == "seasons") 4 else 0, !busy,
                onChange = { propertiesText = JSONObject(propertiesText).put(field, it).toString() })
        }
    }
    OutlinedTextField(value = notes, onValueChange = { if (it.length <= 2000) notes = it },
        label = { Text("메모") }, minLines = 4, enabled = !busy, modifier = Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = {
        val result = JSONObject().put("unit", "cm")
        dimensions.keysList().forEach { key ->
            val text = inputs.optString(key)
            if (text.isNotBlank()) {
                val value = text.toBigDecimal().setScale(2, RoundingMode.HALF_UP)
                val original = initialDimensions?.optJSONObject(key)
                val same = original != null && value.toDouble() == original.optDouble("value") &&
                    initialAttributes.optString("category") == attributes.optString("category")
                result.put(key, if (same) JSONObject(original.toString()).put("value", value) else
                    JSONObject().put("value", value).put("source", "user_measured").put("method", "unspecified").put("reference", JSONObject.NULL))
            }
        }
        onSave(JSONObject().put("attributes", attributes).put("dimensions", if (result.length() == 1) JSONObject.NULL else result)
            .put("user_properties", properties).put("notes", notes))
    }, enabled = valid && !busy, modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) "저장 중" else if (saved) "저장하기" else "옷장에 추가")
    }
}

@Composable
private fun ChoiceField(label: String, choices: JSONObject, value: Any?, multiple: Int, enabled: Boolean,
                        nullable: Boolean = true, onChange: (Any) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = if (value is JSONArray) (0 until value.length()).map { value.getString(it) }
                   else if (value is String) listOf(value) else emptyList()
    val description = selected.mapNotNull { if (choices.has(it)) choices.getString(it) else null }.joinToString(", ").ifEmpty { "미입력" }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("$label: $description") }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            if (nullable) DropdownMenuItem(text = { Text("미입력") }, onClick = {
                onChange(if (multiple > 0) JSONArray() else JSONObject.NULL); expanded = false
            })
            choices.keysList().forEach { code ->
                DropdownMenuItem(text = { Text(choices.getString(code)) },
                    leadingIcon = if (multiple > 0) ({ Checkbox(checked = code in selected, onCheckedChange = null) }) else null,
                    enabled = multiple == 0 || code in selected || selected.size < multiple,
                    onClick = {
                        if (multiple == 0) { onChange(code); expanded = false }
                        else onChange(JSONArray(if (code in selected) selected - code else selected + code))
                    })
            }
        }
    }
}

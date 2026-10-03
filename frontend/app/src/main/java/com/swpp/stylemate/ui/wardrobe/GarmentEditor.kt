package com.swpp.stylemate.ui.wardrobe

import com.swpp.stylemate.data.wardrobe.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.swpp.stylemate.ui.components.ScreenScaffold
import com.swpp.stylemate.ui.components.SectionTitle
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
    if ((field == "sleeve_length" && !top) ||
        (field == "leg_shape" && !pants) || (field == "skirt_shape" && !skirt) ||
        (field == "rise_type" && category != "bottom") ||
        (field == "fit_type" && category == "shoes")) return JSONObject()
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
                  initialNotes: String, catalog: JSONObject,
                  busy: Boolean, saved: Boolean, error: String?, onSave: (JSONObject) -> Unit,
                  onBack: (() -> Unit)? = null, photo: @Composable () -> Unit = {}) {
    var attributesText by rememberSaveable(id) { mutableStateOf(initialAttributes.toString()) }
    var notes by rememberSaveable(id) { mutableStateOf(initialNotes) }
    var dimensionText by rememberSaveable(id) {
        mutableStateOf(JSONObject().apply {
            initialDimensions?.keysList()?.filter { it != "unit" }?.forEach { key ->
                initialDimensions.optJSONObject(key)?.let { put(key, String.format(Locale.US, "%.2f", it.getDouble("value"))) }
            }
        }.toString())
    }
    var extra by rememberSaveable(id) { mutableStateOf(false) }
    val attributes = remember(attributesText) { JSONObject(attributesText) }
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

    fun save() {
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
            .put("notes", notes))
    }

    val primary = setOf("category", "colors")
    @Composable fun attribute(field: String) {
        val choices = attributeChoices(attributes, catalog, field)
        if (choices.length() > 0) ChoiceField(catalog.getJSONObject("labels").getString(field), choices,
            attributes.opt(field), catalog.getJSONObject("array_limits").optInt(field, 0), !busy,
            nullable = field != "category", onChange = { updateAttribute(field, it) })
    }

    ScreenScaffold(
        title = if (saved) "옷 정보" else "분석 결과",
        primaryLabel = if (busy) "저장 중" else if (saved) "저장하기" else "옷장에 추가",
        primaryEnabled = valid && !busy,
        onPrimary = ::save,
        onBack = onBack,
        navigationEnabled = !busy,
        contentWindowInsets = WindowInsets(0),
        modifier = Modifier.imePadding(),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            photo()
            SectionTitle("기본 정보")
            EditorSection {
                OutlinedTextField(value = attributes.optString("name"), onValueChange = {
                    if (it.length <= 80) attributesText = JSONObject(attributesText).put("name", it).toString()
                }, label = { Text("이름") }, singleLine = true, enabled = !busy,
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                primary.forEach { attribute(it) }
            }
            ExpandSection("상세 정보", extra) { extra = !extra }
            if (extra) EditorSection {
                catalog.getJSONObject("enums").keysList().filter { it !in primary }.forEach { attribute(it) }
            }
            if (dimensions.length() > 0) {
                SectionTitle("치수", "cm")
                EditorSection {
                    dimensions.keysList().forEach { field ->
                        val text = inputs.optString(field)
                        OutlinedTextField(value = text, onValueChange = {
                            if (it.length <= 16) dimensionText = JSONObject(dimensionText).put(field, it).toString()
                        }, label = { Text(dimensions.getJSONObject(field).getString("label")) }, suffix = { Text("cm") },
                            supportingText = if (!validNumber(text)) ({ Text("소수 둘째 자리까지 양수를 입력해 주세요.") })
                                             else null,
                            isError = !validNumber(text), singleLine = true, enabled = !busy,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(value = notes, onValueChange = { if (it.length <= 2000) notes = it },
                label = { Text("메모") }, minLines = 4, enabled = !busy,
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EditorSection(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun ExpandSection(label: String, expanded: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "접기" else "펼치기", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
        OutlinedButton(onClick = { expanded = true }, enabled = enabled,
            shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text("$label: $description", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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

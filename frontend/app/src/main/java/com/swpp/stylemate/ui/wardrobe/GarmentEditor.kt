// AI-generated with Codex, 2026-09-29, reviewed by Hyeon U Jeong; recommendation labels added with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.wardrobe

import androidx.activity.compose.BackHandler
import com.swpp.stylemate.data.wardrobe.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.swpp.stylemate.ui.components.ScreenScaffold
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject.keysList(): List<String> = keys().asSequence().toList()

/** Attributes replaced by the fit in the recommendation labels (GarmentLabels.kt). */
internal val LABEL_OWNED_FIELDS = setOf("fit_type", "leg_shape")
private fun JSONObject.code(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

private fun attributeChoices(attributes: JSONObject, catalog: JSONObject, field: String): JSONObject {
    val category = attributes.optString("category")
    val subcategory = attributes.code("subcategory")
    if (field == "subcategory") return catalog.getJSONObject("subcategories").getJSONObject(category)
    val pants = category == "bottom" && subcategory in setOf("jeans", "slacks", "pants", "active_pants", "shorts")
    val skirt = category == "bottom" && subcategory == "skirt"
    if ((field == "leg_shape" && !pants) || (field == "skirt_shape" && !skirt) ||
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
            !(category == "bottom" && subcategory == "skirt" && key !in setOf("waist_width_half", "hip_width_half", "total_length"))
        }.forEach { put(it, fields.get(it)) }
    }
}

@Composable
fun GarmentEditor(id: String, initialAttributes: JSONObject, initialDimensions: JSONObject?,
                  initialNotes: String, catalog: JSONObject,
                  busy: Boolean, saved: Boolean, error: String?, onSave: (JSONObject) -> Unit,
                  onBack: (() -> Unit)? = null, image: ImageBitmap? = null, photo: @Composable () -> Unit = {},
                  canSaveLabels: (JSONObject, JSONObject?) -> Boolean = { _, _ -> true },
                  labels: @Composable (attributes: JSONObject, dimensions: JSONObject?) -> Unit = { _, _ -> },
                  topBar: (@Composable (onBack: () -> Unit) -> Unit)? = null,
                  draft: JSONObject? = null,
                  onDraftChange: ((JSONObject) -> Unit)? = null,
                  measurementsEnabled: Boolean = true,
                  saveLabel: String? = null,
                  estimatedDimensions: JSONObject? = null,
                  hasLabelChanges: Boolean = false,
                  photoSource: String? = null) {
    var attributesText by rememberSaveable(id) { mutableStateOf((draft?.optJSONObject("attributes") ?: initialAttributes).toString()) }
    var notes by rememberSaveable(id) { mutableStateOf(draft?.optString("notes") ?: initialNotes) }
    var dimensionText by rememberSaveable(id) {
        mutableStateOf(draft?.optJSONObject("dimension_inputs")?.toString() ?: JSONObject().apply {
            initialDimensions?.keysList()?.filter { it != "unit" }?.forEach { key ->
                initialDimensions.optJSONObject(key)?.let { put(key, String.format(Locale.US, "%.2f", it.getDouble("value"))) }
            }
        }.toString())
    }
    var extra by rememberSaveable(id) { mutableStateOf(false) }
    val attributes = remember(attributesText) { JSONObject(attributesText) }
    val inputs = remember(dimensionText) { JSONObject(dimensionText) }
    val originalDimensions = remember(id) { JSONObject().apply {
        initialDimensions?.keysList()?.filter { it != "unit" }?.forEach { key ->
            initialDimensions.optJSONObject(key)?.let { put(key, String.format(Locale.US, "%.2f", it.getDouble("value"))) }
        }
    }.toString() }
    // Keep the baseline through tab restoration, even when the server list is refreshed.
    val baseline by rememberSaveable(id) { mutableStateOf(listOf(initialAttributes.toString(), initialNotes, originalDimensions)) }
    val changed = hasLabelChanges || attributesText != baseline[0] || notes != baseline[1] || dimensionText != baseline[2]
    var confirmingDiscard by rememberSaveable(id) { mutableStateOf(false) }
    fun leave() { if (!busy) { if (changed) confirmingDiscard = true else onBack?.invoke() } }
    BackHandler(enabled = onBack != null) { leave() }
    if (confirmingDiscard) AlertDialog(onDismissRequest = { confirmingDiscard = false },
        title = { Text("변경 내용을 버릴까요?") }, text = { Text("저장하지 않은 수정 내용이 사라져요.") },
        confirmButton = { TextButton(onClick = { confirmingDiscard = false; onBack?.invoke() }) { Text("버리기") } },
        dismissButton = { TextButton(onClick = { confirmingDiscard = false }) { Text("계속 편집") } })
    androidx.compose.runtime.LaunchedEffect(attributesText, notes, dimensionText) {
        onDraftChange?.invoke(JSONObject().put("attributes", JSONObject(attributesText)).put("notes", notes)
            .put("dimension_inputs", JSONObject(dimensionText)))
    }
    val dimensions = if (measurementsEnabled) dimensionChoices(attributes, catalog) else JSONObject()
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

    // Valid entries only, so the recommendation labels can follow the fields while the user types.
    fun currentDimensions(): JSONObject? {
        val result = JSONObject().put("unit", "cm")
        dimensions.keysList().forEach { key ->
            val text = inputs.optString(key)
            if (text.isNotBlank() && validNumber(text)) {
                val value = text.toBigDecimal().setScale(2, RoundingMode.HALF_UP)
                val original = initialDimensions?.optJSONObject(key)
                val same = original != null && value.toDouble() == original.optDouble("value") &&
                    initialAttributes.optString("category") == attributes.optString("category")
                result.put(key, if (same) JSONObject(original.toString()).put("value", value) else
                    JSONObject().put("value", value).put("source", "user_measured").put("method", "unspecified").put("reference", JSONObject.NULL))
            }
        }
        return if (result.length() == 1) null else result
    }

    fun save() {
        if (!valid || busy || !canSaveLabels(attributes, currentDimensions())) return
        onSave(JSONObject().put("attributes", attributes).put("dimensions", currentDimensions() ?: JSONObject.NULL)
            .put("notes", notes))
    }

    // The AI suggests the kind and styles, so they are shown up front for review. The fit lives only in
    // the recommendation labels ("내 몸 기준 라벨"), computed from the measurements and the body.
    val primary = setOf("category", "subcategory", "styles")
    val detailFields = catalog.getJSONObject("enums").keysList()
        .filter { it !in primary && it !in LABEL_OWNED_FIELDS && attributeChoices(attributes, catalog, it).length() > 0 }
    @Composable fun attribute(field: String) {
        val choices = JSONObject(attributeChoices(attributes, catalog, field).toString())
        if (!measurementsEnabled && field == "category") choices.remove("shoes")
        if (choices.length() > 0) ChoiceField(catalog.getJSONObject("labels").getString(field), choices,
            attributes.opt(field), catalog.getJSONObject("array_limits").optInt(field, 0), !busy,
            nullable = field != "category", onChange = { updateAttribute(field, it) })
    }

    ScreenScaffold(
        title = if (saved) "옷 정보 수정" else "분석 결과",
        primaryLabel = if (busy) "저장 중" else saveLabel ?: if (saved) "저장하기" else "옷장에 추가",
        primaryEnabled = valid && !busy && canSaveLabels(attributes, currentDimensions()),
        onPrimary = ::save,
        onBack = onBack?.let { { leave() } },
        navigationEnabled = !busy,
        contentWindowInsets = WindowInsets(0),
        modifier = Modifier.imePadding(),
        topBar = { if (topBar != null) topBar(::leave)
            else GarmentTopBar(if (saved) "옷 정보 수정" else "분석 결과", onBack?.let { { leave() } }, !busy) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (photoSource != null) GarmentPhotoPanel(source = photoSource, photo = photo) else photo()
            GarmentSection("기본 정보") {
                OutlinedTextField(value = attributes.optString("name"), onValueChange = {
                    if (it.length <= 80) attributesText = JSONObject(attributesText).put("name", it).toString()
                }, label = { Text("이름") }, singleLine = true, enabled = !busy,
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
                primary.forEach { attribute(it) }
                val colorValues = attributes.getJSONArray("colors")
                val palette = catalog.getJSONObject("color_palette")
                WardrobeColorField((0 until colorValues.length()).map { colorValues.getString(it) },
                    palette.keysList().associateWith { palette.getString(it) },
                    catalog.getJSONObject("array_limits").getInt("colors"), image, !busy,
                    onChange = { updateAttribute("colors", JSONArray(it)) })
            }
            if (detailFields.isNotEmpty()) Column {
                ExpandSection("상세 정보", extra) { extra = !extra }
                if (extra) EditorSection { detailFields.forEach { attribute(it) } }
            }
            labels(attributes, currentDimensions())
            if (dimensions.length() > 0) {
                GarmentSection("치수", "직접 측정한 값을 입력해 주세요. 단위는 cm예요.") {
                    dimensions.keysList().forEach { field ->
                        val text = inputs.optString(field)
                        OutlinedTextField(value = text, onValueChange = {
                            if (it.length <= 16) dimensionText = JSONObject(dimensionText).put(field, it).toString()
                        }, label = { Text(dimensions.getJSONObject(field).getString("label")) }, suffix = { Text("cm") },
                            supportingText = if (!validNumber(text)) ({ Text("소수 둘째 자리까지 양수를 입력해 주세요.") })
                                             else null,
                            isError = !validNumber(text), singleLine = true, enabled = !busy,
                            shape = MaterialTheme.shapes.medium,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            GarmentEstimates(estimatedDimensions, attributes, catalog, currentDimensions())
            GarmentSection("메모") {
                OutlinedTextField(value = notes, onValueChange = { if (it.length <= 2000) notes = it },
                    placeholder = { Text("메모를 남겨 보세요") }, minLines = 3, enabled = !busy,
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "메모" })
            }
            error?.let { Text(it, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun EditorSection(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun ExpandSection(label: String, expanded: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "접기" else "펼치기", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChoiceField(label: String, choices: JSONObject, value: Any?, multiple: Int, enabled: Boolean,
                        nullable: Boolean = true, onChange: (Any) -> Unit) {
    val selected = if (value is JSONArray) (0 until value.length()).map { value.getString(it) }
        else if (value is String) listOf(value) else emptyList()
    GarmentChoiceField(label, choices.keysList().map { it to choices.getString(it) }, selected, multiple, enabled, nullable) { values ->
        onChange(if (multiple > 0) JSONArray(values) else values.firstOrNull() ?: JSONObject.NULL)
    }
}

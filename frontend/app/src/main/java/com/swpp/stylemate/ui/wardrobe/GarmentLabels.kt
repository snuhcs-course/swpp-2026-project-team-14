// AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.wardrobe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.swpp.stylemate.data.recommendation.OutfitApiException
import com.swpp.stylemate.data.recommendation.OutfitRepository
import com.swpp.stylemate.ui.components.SectionTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Recommendation labels of the garment being edited: the server's suggestion plus the user's edits. */
data class LabelState(
    val garmentId: String? = null,
    val suggested: JSONObject? = null,
    val edits: Map<String, Any?> = emptyMap(),
    val inputKey: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

object GarmentLabels {
    private val SINGLE = listOf("fit", "length", "sleeve", "season", "neckline", "collar")
    private val MULTIPLE = listOf("prints", "materials", "design_details")

    /**
     * Suggestion + edits, with every value checked against the category's options (the server
     * rejects anything else), marked as confirmed by the user. Null without a suggestion or for shoes.
     */
    fun merge(suggested: JSONObject?, edits: Map<String, Any?>, category: String, options: JSONObject?): JSONObject? {
        val allowed = options?.optJSONObject(category) ?: return null
        val limits = options.optJSONObject("limits") ?: JSONObject()
        if (suggested == null) return null
        val result = JSONObject(suggested.toString())
        edits.forEach { (key, value) -> result.put(key, value ?: JSONObject.NULL) }
        SINGLE.forEach { key ->
            val values = allowed.optJSONArray(key)?.strings().orEmpty()
            val value = if (result.isNull(key)) null else result.optString(key)
            if (value != null && value !in values) result.put(key, JSONObject.NULL)
        }
        MULTIPLE.forEach { key ->
            val values = allowed.optJSONArray(key)?.strings().orEmpty()
            val chosen = result.optJSONArray(key)?.strings().orEmpty().filter { it in values }.distinct()
            result.put(key, JSONArray(chosen.take(limits.optInt(key, if (key == "design_details") 3 else 2))))
        }
        return result.put("confirmed", true)
    }

    fun inputKey(attributes: JSONObject, dimensions: JSONObject?, body: JSONObject? = null): String =
        listOf(attributes.optString("category"), attributes.opt("subcategory"), attributes.opt("colors"), dimensions, body).joinToString("|")

    fun canSave(state: LabelState, garmentId: String, attributes: JSONObject, dimensions: JSONObject?, body: JSONObject?): Boolean =
        attributes.optString("category") == "shoes" ||
            (state.garmentId == garmentId && state.inputKey == inputKey(attributes, dimensions, body) &&
                state.suggested != null && !state.loading && state.error == null)

    /** AI design details (print, neckline, collar, design details, visible materials) to send back with a
     * recomputation: from the analysis of a new garment, or from the saved labels. */
    fun details(analysis: JSONObject?, stored: JSONObject?): JSONObject? = analysis?.optJSONObject("details")
        ?: stored?.let { labels ->
            JSONObject()
                .put("print", labels.optJSONArray("prints")?.strings()?.firstOrNull() ?: JSONObject.NULL)
                .put("neckline", if (labels.isNull("neckline")) JSONObject.NULL else labels.optString("neckline"))
                .put("collar", if (labels.isNull("collar")) JSONObject.NULL else labels.optString("collar"))
                .put("design_details", labels.optJSONArray("design_details") ?: JSONArray())
                .put("materials", labels.optJSONArray("materials") ?: JSONArray())
        }

    internal fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
}

class GarmentLabelsViewModel(private val repository: OutfitRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(LabelState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    /** Opening an editor (each visit starts fresh: edits that were not saved are dropped). Saved labels
     * count as already computed for the garment's current inputs. */
    fun start(garmentId: String, stored: JSONObject?, inputKey: String) {
        job?.cancel()
        mutableState.value = LabelState(garmentId, stored, inputKey = if (stored != null) inputKey else null)
    }

    fun compute(garmentId: String, attributes: JSONObject, dimensions: JSONObject?, body: JSONObject?,
                details: JSONObject?, garmentType: String?, inputKey: String, force: Boolean = false) {
        val current = mutableState.value
        if (current.garmentId != garmentId || (!force && current.inputKey == inputKey && current.suggested != null && !current.loading && current.error == null)) return
        job?.cancel()
        mutableState.value = current.copy(inputKey = inputKey, loading = true, error = null)
        job = viewModelScope.launch {
            try {
                val labels = repository.labels(attributes, dimensions, body, details, garmentType)
                    ?: throw OutfitApiException("EMPTY_LABELS", "Empty labels")
                mutableState.value = mutableState.value.copy(suggested = labels, inputKey = inputKey, loading = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: OutfitApiException) {
                mutableState.value = mutableState.value.copy(suggested = JSONObject(), loading = false, error = "라벨을 계산하지 못했어요.")
            }
        }
    }

    fun confirmManual(inputKey: String) {
        val current = mutableState.value
        if (current.inputKey == inputKey && current.error != null && !current.loading) {
            mutableState.value = current.copy(error = null)
        }
    }

    fun edit(key: String, value: Any?) {
        mutableState.value = mutableState.value.copy(edits = mutableState.value.edits + (key to value))
    }

    fun merged(category: String, options: JSONObject?): JSONObject? =
        mutableState.value.let { GarmentLabels.merge(it.suggested, it.edits, category, options) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GarmentLabelSection(model: GarmentLabelsViewModel, garmentId: String, attributes: JSONObject, dimensions: JSONObject?,
                        body: JSONObject?, details: JSONObject?, stored: JSONObject?, options: JSONObject?, enabled: Boolean,
                        garmentType: String? = null) {
    val category = attributes.optString("category")
    val allowed = options?.optJSONObject(category) ?: return  // shoes: not used by the recommender
    val state by model.state.collectAsStateWithLifecycle()
    val key = GarmentLabels.inputKey(attributes, dimensions, body)
    LaunchedEffect(garmentId) { model.start(garmentId, stored, key) }
    LaunchedEffect(garmentId, key, body?.toString()) {
        delay(500) // wait until the user stops typing a dimension
        model.compute(garmentId, attributes, dimensions, body, details, garmentType, key)
    }
    SectionTitle("내 몸 기준 라벨", "추천에 쓰여요")
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (body == null) {
                Text("마이프로필에서 체형 분석을 하면 핏과 기장을 치수로 계산해 드려요. 지금은 직접 골라 주세요.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { message ->
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { model.compute(garmentId, attributes, dimensions, body, details, garmentType, key, force = true) },
                    enabled = enabled) { Text("다시 계산") }
            }
            val labels = GarmentLabels.merge(state.suggested ?: JSONObject(), state.edits, category, options) ?: return@Column
            val editable = enabled && !state.loading && state.inputKey == key
            if (labels.optBoolean("too_small")) {
                Text("${labels.optString("too_small_reason")}. 이 옷은 코디 추천에서 빠져요.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
            fun value(key: String) = if (labels.isNull(key)) null else labels.optString(key)
            fun values(key: String) = labels.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            fun choices(key: String) = allowed.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()

            LabelChips("핏", choices("fit"), value("fit"), value("fit_basis"), editable) { model.edit("fit", it) }
            LabelChips("기장", choices("length"), value("length"), value("length_basis"), editable) { model.edit("length", it) }
            if (allowed.has("sleeve")) {
                LabelChips("소매", choices("sleeve"), value("sleeve"), value("sleeve_basis"), editable) { model.edit("sleeve", it) }
            }
            LabelChips("계절", choices("season"), value("season"), null, editable) { model.edit("season", it) }
            LabelMenu("무늬", choices("prints"), values("prints"), 2, editable) { model.edit("prints", JSONArray(it)) }
            if (allowed.has("neckline")) {
                LabelMenu("넥라인", choices("neckline"), listOfNotNull(value("neckline")), 0, editable) { model.edit("neckline", it.firstOrNull()) }
                LabelMenu("옷깃", choices("collar"), listOfNotNull(value("collar")), 0, editable) { model.edit("collar", it.firstOrNull()) }
            }
            LabelMenu("디테일", choices("design_details"), values("design_details"), 3, editable) { model.edit("design_details", JSONArray(it)) }
            LabelMenu("소재", choices("materials"), values("materials"), 2, editable) { model.edit("materials", JSONArray(it)) }
            if (state.error != null) {
                Text("직접 입력한 라벨을 확인한 뒤 완료를 눌러 주세요.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { model.confirmManual(key) }, enabled = editable) { Text("수동 입력 완료") }
            }
            Text("치수와 사진으로 계산한 값이에요. 실제와 다르면 눌러서 고쳐 주세요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelChips(label: String, choices: List<String>, selected: String?, basis: String?, enabled: Boolean,
                       onChange: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { choice ->
                FilterChip(selected = choice == selected, enabled = enabled,
                    onClick = { onChange(if (choice == selected) null else choice) },
                    label = { Text(choice) }, shape = RoundedCornerShape(50),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary))
            }
        }
        if (basis != null) Text(basis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Long lists (무늬, 소재, 넥라인, 옷깃) as a menu; `multiple` = 0 for a single value. */
@Composable
private fun LabelMenu(label: String, choices: List<String>, selected: List<String>, multiple: Int, enabled: Boolean,
                      onChange: (List<String>) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("$label: ${selected.joinToString(", ").ifEmpty { "없음" }}", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            DropdownMenuItem(text = { Text("없음") }, onClick = { onChange(emptyList()); expanded = false })
            choices.forEach { choice ->
                DropdownMenuItem(text = { Text(choice) },
                    // a plain icon rather than a Checkbox: these menus have up to 40 rows (K-Fashion details)
                    leadingIcon = if (multiple > 0) ({
                        Icon(Icons.Filled.Check, contentDescription = if (choice in selected) "선택됨" else null,
                            tint = if (choice in selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    }) else null,
                    enabled = multiple == 0 || choice in selected || selected.size < multiple,
                    onClick = {
                        if (multiple == 0) { onChange(listOf(choice)); expanded = false }
                        else onChange(if (choice in selected) selected - choice else selected + choice)
                    })
            }
        }
    }
}

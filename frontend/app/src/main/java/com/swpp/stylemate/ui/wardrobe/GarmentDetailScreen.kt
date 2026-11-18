package com.swpp.stylemate.ui.wardrobe

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.ui.components.ScreenScaffold
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Reading does not trigger label computation; management actions share one menu. */
@Composable
internal fun GarmentDetailScreen(record: JSONObject, catalog: JSONObject?, onBack: () -> Unit,
    onEdit: () -> Unit, onMatch: () -> Unit, onDelete: () -> Unit,
    photo: @Composable () -> Unit = {
        GarmentPhoto(record.optString("image_url"), record.getJSONObject("attributes").optString("name"), framed = false)
    }) {
    val attrs = record.getJSONObject("attributes")
    val labels = record.optJSONObject("labels")
    val category = attrs.optString("category")
    val categoryName = wardrobeCategoryName(category, catalog)
    val kind = catalog?.optJSONObject("subcategories")?.optJSONObject(category)
        ?.optString(attrs.optString("subcategory"))?.takeIf(String::isNotBlank)
    val styles = detailStrings(attrs.optJSONArray("styles")).mapNotNull {
        catalog?.optJSONObject("enums")?.optJSONObject("styles")?.optString(it)?.takeIf(String::isNotBlank)
    }
    val dimensions = record.optJSONObject("dimensions")
    val estimate = matchingWornEstimates(record, attrs, labels)
    val definitions = catalog?.optJSONObject("dimensions")?.optJSONObject(if (category == "bottom") "bottom" else "top")
    val measured = dimensions?.keys()?.asSequence()?.mapNotNull { key ->
        val value = dimensions.optJSONObject(key)?.optDouble("value", Double.NaN) ?: return@mapNotNull null
        val label = definitions?.optJSONObject(key)?.optString("label")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        if (!value.isFinite()) null else label to "${String.format(Locale.KOREA, "%.2f", value).trimEnd('0').trimEnd('.')} cm"
    }?.toList().orEmpty()
    val traits = listOf("fit" to "핏", "length" to "기장", "sleeve" to "소매", "season" to "계절",
        "materials" to "소재", "prints" to "무늬", "neckline" to "넥라인", "collar" to "옷깃", "design_details" to "디테일")
        .mapNotNull { (key, title) ->
            val value = if (labels?.opt(key) is JSONArray) detailStrings(labels.optJSONArray(key)).joinToString(", ") { labelText(it) }
                else labels?.takeUnless { it.isNull(key) }?.optString(key).orEmpty().let(::labelText)
            value.takeIf(String::isNotBlank)?.let { title to it }
        }
    var menuOpen by remember { mutableStateOf(false) }
    val supportsMatch = category in setOf("top", "bottom", "outerwear")
    ScreenScaffold(title = "옷 상세", primaryLabel = if (supportsMatch) "함께 입을 옷 찾기" else null,
        primaryEnabled = supportsMatch, onPrimary = onMatch, onBack = onBack,
        contentWindowInsets = WindowInsets(0), topBar = {
            GarmentTopBar("옷 상세", onBack) {
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "옷 관리 메뉴") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("정보 수정") }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menuOpen = false; onEdit() })
                        DropdownMenuItem(text = { Text("삭제") }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error,
                                leadingIconColor = MaterialTheme.colorScheme.error),
                            onClick = { menuOpen = false; onDelete() })
                    }
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column {
                GarmentPhotoPanel(source = record.optString("image_source"), photo = photo)
            }
            GarmentSection("기본 정보") {
                Text(attrs.optString("name"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                GarmentInfoRows(listOfNotNull("분류" to categoryName, kind?.let { "종류" to it },
                    styles.takeIf { it.isNotEmpty() }?.let { "스타일" to it.joinToString(" · ") }))
                val colors = detailStrings(attrs.optJSONArray("colors"))
                if (colors.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("색상", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            colors.forEachIndexed { index, hex ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)
                                    Surface(Modifier.size(16.dp), color = color, shape = MaterialTheme.shapes.small,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {}
                                    Text(catalog?.optJSONObject("color_palette")?.optString(hex)?.takeIf(String::isNotBlank)
                                        ?: detailStrings(labels?.optJSONArray("kf_colors")).getOrNull(index)?.let { "$it 계열" }
                                        ?: "등록 색상", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
            if (traits.isNotEmpty()) GarmentSection("옷 특징") { GarmentInfoRows(traits) }
            if (labels?.optBoolean("too_small") == true) Text(
                labels.optString("too_small_reason").ifBlank { "체형에 비해 작은 옷으로 확인됐어요." } + " 이 옷은 코디 추천에서 제외돼요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            if (measured.isNotEmpty()) GarmentSection("치수", "측정하거나 직접 입력한 값이에요.") { GarmentInfoRows(measured) }
            if (catalog != null) GarmentEstimates(estimate, attrs, catalog, dimensions)
            if (measured.isEmpty() && estimate == null && category != "shoes") GarmentSection("치수") {
                Text("등록된 치수가 없어요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GarmentSection("메모") {
                Text(record.optString("notes").ifBlank { "작성한 메모가 없어요." },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (category == "shoes") Text("신발의 코디 추천은 아직 지원하지 않아요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun GarmentDeleteDialog(record: JSONObject, deleting: Boolean, error: String?, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val name = record.getJSONObject("attributes").optString("name")
    AlertDialog(onDismissRequest = { if (!deleting) onDismiss() }, title = { Text("이 옷을 삭제할까요?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(72.dp)) { GarmentPhoto(record.optString("image_url"), name, framed = false) }
                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text("이 옷이 포함된 코디도 함께 삭제돼요.", style = MaterialTheme.typography.bodyMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (deleting) LinearProgressIndicator(Modifier.fillMaxWidth())
        } }, confirmButton = { TextButton(onClick = onDelete, enabled = !deleting,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(if (deleting) "삭제 중…" else "삭제") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !deleting) { Text("취소") } })
}

@Composable
internal fun MissingGarmentScreen(loading: Boolean, onBack: () -> Unit, onRetry: () -> Unit) {
    ScreenScaffold("옷 정보", null, false, {}, onBack = onBack, contentWindowInsets = WindowInsets(0)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (loading) { CircularProgressIndicator(); Text("옷 정보를 불러오고 있어요.") }
            else { Text("옷 정보를 찾을 수 없어요."); OutlinedButton(onClick = onRetry) { Text("다시 불러오기") } }
        }
    }
}

internal fun wardrobeCategoryName(code: String, catalog: JSONObject?): String =
    catalog?.optJSONObject("enums")?.optJSONObject("category")?.optString(code)?.takeIf(String::isNotBlank)
        ?: mapOf("top" to "상의", "bottom" to "하의", "outerwear" to "아우터", "shoes" to "신발")[code].orEmpty()

private fun detailStrings(values: JSONArray?): List<String> = values?.let { array ->
    (0 until array.length()).mapNotNull { array.optString(it).takeIf { text -> text.isNotBlank() && text != "null" } }
}.orEmpty()

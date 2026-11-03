package com.swpp.stylemate.ui.wardrobe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject


internal typealias WornPhotoContent = @Composable (String, String?, String, Modifier) -> Unit
internal typealias WornReviewPhoto = @Composable (String, String?, String, Modifier, ContentScale) -> Unit

/** Shared by the account registration flow and the offline debug preview. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WornBatchReviewScreen(state: WornBatchState, catalog: JSONObject?, onBack: () -> Unit,
    onSave: () -> Unit, onInclude: (String, Boolean) -> Unit, onEdit: (String) -> Unit,
    onRegenerate: (String) -> Unit, onCrop: (String) -> Unit, onRefresh: (String) -> Unit,
    photo: WornReviewPhoto, previewActions: @Composable () -> Unit = {}, notice: @Composable () -> Unit = {}) {
    val items = state.items
    val enabled = !state.busy
    val selected = items.count { it.included }
    var comparisonId by remember { mutableStateOf<String?>(null) }
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = 18.sp, letterSpacing = 0.sp)) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { Text("등록할 옷 확인", fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack, enabled = enabled) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            actions = { previewActions() }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (catalog == null) Text("옷 정보 선택지를 불러오는 중이에요.", fontSize = 12.sp)
                    if (enabled) wornBatchSaveProblem(items)?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    Button(onClick = onSave, enabled = enabled && catalog != null && canSaveWornBatch(items),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                        if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.busy) "처리 중" else "선택한 ${selected}벌 내 옷장에 등록", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                    notice()
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.busy) WornProcessingIndicator(state.progress)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("사진에서 ${items.size}벌의 옷을 찾았어요.", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("등록할 옷을 고르고, 필요한 정보만 수정해 주세요.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(enabled = enabled, checked = selected == items.size && items.isNotEmpty(), onCheckedChange = { checked ->
                    items.forEach { onInclude(it.id, checked) }
                }, modifier = Modifier.semantics { contentDescription = "전체 선택" },
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
                Text("전체 선택 ($selected/${items.size})", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            items.sortedBy { if (it.payload.getJSONObject("attributes").optString("category") == "bottom") 0 else 1 }.forEach { item -> key(item.id) {
                WornReviewGarmentCard(item, catalog ?: JSONObject(), enabled, photo, onInclude = { onInclude(item.id, it) },
                    onEdit = { if (catalog != null) onEdit(item.id) }, onCompare = { comparisonId = item.id },
                    onRegenerate = { onRegenerate(item.id) }, onCrop = { onCrop(item.id) }, onRefresh = { onRefresh(item.id) })
            } }
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Edit, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("등록 후에도 언제든 수정할 수 있어요", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("가려진 부분과 추정 치수는 실제 옷과 다를 수 있어요. 지금은 등록할 옷만 골라 주세요.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    items.firstOrNull { it.id == comparisonId }?.let { item -> WornReviewComparison(item, photo, enabled,
        onClose = { comparisonId = null }, onRegenerate = { comparisonId = null; onRegenerate(item.id) },
        onUseOriginal = { comparisonId = null; onCrop(item.id) }) }
    }
}

@Composable
private fun WornReviewGarmentCard(item: WornBatchItem, catalog: JSONObject, enabled: Boolean, photo: WornReviewPhoto,
    onInclude: (Boolean) -> Unit, onEdit: () -> Unit, onCompare: () -> Unit, onRegenerate: () -> Unit,
    onCrop: () -> Unit, onRefresh: () -> Unit) {
    val attrs = item.payload.getJSONObject("attributes")
    val labels = item.payload.getJSONObject("labels")
    val name = attrs.optString("name")
    val category = attrs.optString("category")
    val categoryName = mapOf("top" to "상의", "bottom" to "하의", "outerwear" to "아우터")[category].orEmpty()
    val kind = catalog.optJSONObject("subcategories")?.optJSONObject(category)?.optString(attrs.optString("subcategory"))
        ?.takeIf { it.isNotBlank() && it != "null" }
    val original = "/api/wardrobe/items/${item.id}/worn-image/original/"
    val ready = item.generation.optString("status") == "ready"
    val path = if (item.imageChoice == "crop") original else if (ready) item.generation.optString("generated_url").takeIf { it.startsWith("/api/wardrobe/") } else null
    val processing = item.generation.optString("status") in setOf("pending", "running")
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(item.included, onCheckedChange = onInclude, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = "$name 등록 선택" },
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(name, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(categoryName, kind).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                TextButton(onClick = onEdit, enabled = enabled, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Icon(Icons.Default.Edit, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(3.dp)); Text("수정", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (item.included) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.weight(.34f).aspectRatio(1f).clip(RoundedCornerShape(8.dp))) {
                        if (path != null) photo("$name 표시 이미지", path, item.generation.optString("request_id"), Modifier.fillMaxSize(), ContentScale.Crop)
                        else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                            if (processing && !enabled) CircularProgressIndicator(Modifier.size(26.dp))
                            else Text(if (processing) "생성 대기" else "생성 실패", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (path != null) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(5.dp).clickable(enabled = enabled, onClick = onCompare),
                            color = Color.White.copy(alpha = .94f), shape = RoundedCornerShape(5.dp)) {
                            Text("원본 비교", Modifier.padding(vertical = 5.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                    Column(Modifier.weight(.66f), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        ReviewInfoLine("핏", if (labels.isNull("fit")) "미정" else labels.optString("fit"))
                        ReviewInfoLine("기장", if (labels.isNull("length")) "미정" else labels.optString("length"))
                        val colors = attrs.optJSONArray("colors")
                        val color = colors?.optString(0)?.takeIf { it.startsWith("#") }
                        val colorName = color?.let { catalog.optJSONObject("color_palette")?.optString(it, it) } ?: "미정"
                        ReviewInfoLine("색상", colorName, color)
                        val materials = labels.optJSONArray("materials")
                        ReviewInfoLine("소재", materials?.let { a -> (0 until a.length()).map { a.getString(it) }.joinToString(" · ").ifBlank { "미정" } } ?: "미정")
                        Surface(shape = RoundedCornerShape(5.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(if (item.imageChoice == "crop") "착용 사진 일부" else "AI 재구성 이미지", Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                if (!ready && item.imageChoice != "crop" && enabled) {
                    Text(if (processing) "사진 생성이 아직 완료되지 않았어요." else generationErrorMessage(item.generation.optString("error")), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRegenerate, enabled = enabled && item.error == null && item.generation.optString("status") != "running") { Text("다시 만들기", fontSize = 12.sp) }
                        TextButton(onClick = onCrop, enabled = enabled) { Text("원본 사진 사용", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                if (enabled && (item.error != null || item.generation.optString("status") == "running")) {
                    TextButton(onClick = onRefresh) { Text("결과 확인 · 추가 생성 없음", fontSize = 12.sp) }
                }
                val estimate = matchingWornEstimates(item.record, attrs, labels)
                ReviewEstimatePanel(estimate, category, catalog, enabled, onEdit)
            }
        }
    }
}

@Composable
private fun ReviewInfoLine(label: String, value: String, hex: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        Text(label, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (hex != null) {
                val swatch = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(swatch))
            }
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ReviewEstimatePanel(estimate: JSONObject?, category: String, catalog: JSONObject, enabled: Boolean, onEdit: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val measures = estimate?.optJSONObject("measurements")
    val definitions = catalog.optJSONObject("dimensions")?.optJSONObject(if (category == "bottom") "bottom" else "top")
    val fields = measures?.keys()?.asSequence()?.filter { definitions?.has(it) == true }?.toList().orEmpty()
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }
                .semantics { contentDescription = if (expanded) "추정 치수 접기" else "추정 치수 펼치기" }
                .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("추정 치수", fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (fields.isEmpty()) "정보가 부족해 비워뒀어요" else fields.take(3).joinToString(" · ") {
                        val title = definitions!!.getJSONObject(it).getString("label").replace(" 단면", "")
                        "$title 약 ${measures!!.getJSONObject(it).getInt("value")}cm"
                    }, fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                fields.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pair.forEach { field ->
                            val value = measures!!.getJSONObject(field)
                            Surface(Modifier.weight(1f), shape = RoundedCornerShape(5.dp), color = Color.White) {
                                Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(definitions!!.getJSONObject(field).getString("label"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("약 ${value.getInt("value")}cm", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text("${value.getInt("min")}~${value.getInt("max")}cm", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("사진·체형을 바탕으로 추정한 참고값이에요.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onEdit, enabled = enabled, contentPadding = PaddingValues(0.dp)) { Text("옷 정보 수정", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable
private fun WornReviewComparison(item: WornBatchItem, photo: WornReviewPhoto, enabled: Boolean, onClose: () -> Unit,
                              onRegenerate: () -> Unit, onUseOriginal: () -> Unit) {
    var original by remember { mutableStateOf(false) }
    val path = if (original) "/api/wardrobe/items/${item.id}/worn-image/original/" else item.generation.optString("generated_url")
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("원본 비교", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onClose) { Text("닫기", color = MaterialTheme.colorScheme.primary) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(original, onClick = { original = true }, label = { Text("원본") })
                    FilterChip(!original, onClick = { original = false }, label = { Text("완성 사진") })
                }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    photo(if (original) "원본 옷 영역" else "완성 사진", path, item.generation.optString("request_id"), Modifier.fillMaxSize(), ContentScale.Fit)
                }
                Text("가려진 부분은 실제 옷과 다를 수 있어요.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onRegenerate, enabled = enabled && item.error == null && item.generation.optString("status") != "running") { Text("사진 다시 만들기", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
                    TextButton(onClick = onUseOriginal, enabled = enabled) { Text("원본 사진으로 사용", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
internal fun WornProcessingIndicator(message: String) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(Modifier.size(28.dp).semantics { contentDescription = "처리 중" }, strokeWidth = 3.dp)
            Text(message.ifBlank { "처리하고 있어요" }, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

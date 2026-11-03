package com.swpp.stylemate.ui.wardrobe

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Offline controls only; the garment review layout is shared with the service. */
@Composable
internal fun WornPreviewReviewScreen(items: List<WornBatchItem>, catalog: JSONObject, images: Map<String, ImageBitmap>,
    scenario: String, onScenario: (String) -> Unit, onBack: () -> Unit, onSave: () -> Unit,
    onInclude: (String, Boolean) -> Unit, onEdit: (String) -> Unit,
    onRegenerate: (String) -> Unit, onCrop: (String) -> Unit) {
    var settings by remember { mutableStateOf(false) }
    WornBatchReviewScreen(WornBatchState(items = items), catalog, onBack, onSave, onInclude, onEdit,
        onRegenerate, onCrop, onRefresh = {}, photo = { label, path, _, modifier, scale ->
            Box(modifier, contentAlignment = Alignment.Center) {
                images[path]?.let { Image(it, label, Modifier.fillMaxSize(), contentScale = scale) } ?: Text(label)
            }
        }, previewActions = {
            Text("미리보기", fontSize = 11.sp)
            IconButton(onClick = { settings = true }) { Icon(Icons.Default.MoreVert, "미리보기 설정") }
        }, notice = {
            Text("오프라인 미리보기 · 예시 정보 · 실제 저장 없음", fontSize = 10.sp)
        })
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("미리보기 설정") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("이전 이미지와 예시 정보로 UI만 확인합니다. API 호출·실제 저장은 없습니다.", fontSize = 13.sp)
            listOf("완료", "일부 실패", "정보 미정").forEach { name ->
                FilterChip(selected = scenario == name, onClick = { onScenario(name); settings = false }, label = { Text(name) })
            }
        } }, confirmButton = { TextButton(onClick = { settings = false }) { Text("닫기") } })
}

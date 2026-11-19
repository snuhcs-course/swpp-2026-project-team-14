package com.swpp.stylemate.ui.wardrobe

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Reading and editing a garment share the same photo, section spacing and surfaces. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GarmentTopBar(title: String, onBack: (() -> Unit)?, enabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {}) {
    com.swpp.stylemate.ui.components.PageHeader(title, onBack = onBack, enabled = enabled, actions = actions)
}

@Composable
internal fun GarmentPhotoPanel(source: String? = null, photo: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("garment-photo")) {
        Surface(Modifier.fillMaxWidth().height((maxWidth * .72f).coerceIn(160.dp, 220.dp)),
            shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) { photo() }
                if (source != null) Box(Modifier.align(Alignment.TopStart).padding(horizontal = 8.dp)) { GarmentPhotoSource(source) }
            }
        }
    }
}

@Composable
internal fun GarmentPhotoSource(source: String) {
    val generated = source == "ai_flatlay"
    if (!generated && source != "worn_crop") return
    var showingInfo by remember { mutableStateOf(false) }
    val label = if (generated) "AI 이미지" else "착용 사진"
    Box(Modifier.heightIn(min = 48.dp).clickable(role = androidx.compose.ui.semantics.Role.Button,
        onClick = { showingInfo = true }), contentAlignment = Alignment.CenterStart) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface.copy(alpha = .94f)) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Icon(Icons.Outlined.Info, "$label 안내", Modifier.size(14.dp))
            }
        }
    }
    if (showingInfo) AlertDialog(onDismissRequest = { showingInfo = false }, title = { Text(label) },
        text = { Text(if (generated) "등록한 사진을 바탕으로 만든 이미지예요. 가려진 부분은 실제와 다를 수 있고, 옷 정보는 원본 사진에서 분석했어요."
            else "착용 사진에서 가져온 이미지예요. 다른 옷이나 가방의 특징이 섞이지 않았는지 확인해 주세요.") },
        confirmButton = { TextButton(onClick = { showingInfo = false }) { Text("확인") } })
}

@Composable
internal fun GarmentSection(title: String, description: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("garment-section-$title"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        description?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
internal fun GarmentInfoRows(rows: List<Pair<String, String>>) {
    rows.forEachIndexed { index, (label, value) ->
        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

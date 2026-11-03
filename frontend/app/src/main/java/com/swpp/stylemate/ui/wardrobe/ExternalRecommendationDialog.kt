package com.swpp.stylemate.ui.wardrobe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun WardrobeItemActionsDialog(item: WardrobeItem, onEdit: () -> Unit, onRecommend: () -> Unit,
                              onDismiss: () -> Unit) {
    val supported = item.category in setOf("top", "bottom", "outerwear")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("옷 정보 수정하기") }
                Button(onClick = onRecommend, enabled = supported, modifier = Modifier.fillMaxWidth()) {
                    Text("이 옷과 어울리는 옷 무신사에서 보기")
                }
                if (!supported) Text("신발은 아직 추천을 지원하지 않아요.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
fun ExternalRecommendationDialog(state: ExternalMatchState, onRetry: () -> Unit,
                                 onOpenLink: (String) -> Unit, onDismiss: () -> Unit) {
    // 결과가 바뀌면 옷장 목록 펼침 상태를 초기화
    var showWardrobe by remember(state.result) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.result != null) "분석 완료!" else "어울리는 옷 찾기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text("AI가 어울림 점수 분석 중...")
                }
                state.result?.let { result ->
                    Text(result.headline, style = MaterialTheme.typography.titleMedium)
                    Text(result.reason, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { showWardrobe = !showWardrobe }, modifier = Modifier.fillMaxWidth()) {
                        Text("내 옷장에서 아이템 검색")
                    }
                    if (showWardrobe) {
                        if (result.wardrobeMatches.isEmpty()) {
                            Text("옷장에 어울리는 ${slotLabel(result.targetSlot)}가 아직 없어요.",
                                style = MaterialTheme.typography.bodySmall)
                        } else {
                            result.wardrobeMatches.forEachIndexed { index, match ->
                                Text("${index + 1}. ${match.name}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    OutlinedButton(onClick = { onOpenLink(result.searchUrl) }, modifier = Modifier.fillMaxWidth()) {
                        Text("외부 쇼핑몰에서 아이템 검색")
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (state.error != null) TextButton(onClick = onRetry) { Text("다시 시도") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

private fun slotLabel(targetSlot: String) = if (targetSlot == "bottom") "하의" else "상의"

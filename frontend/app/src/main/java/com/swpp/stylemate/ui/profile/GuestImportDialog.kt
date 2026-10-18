// AI-generated with Codex, 2026-10-16
package com.swpp.stylemate.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
fun GuestImportDialog(state: GuestImportState, onConfirm: () -> Unit,
    onDecide: (Set<String>) -> Unit, onRetry: () -> Unit, onClose: () -> Unit) {
    if (state.stage == GuestImportStage.HIDDEN) return
    var selected by remember(state.plan) { mutableStateOf(emptySet<String>()) }
    AlertDialog(onDismissRequest = { if (!state.busy) onClose() },
        properties = DialogProperties(dismissOnBackPress = !state.busy, dismissOnClickOutside = !state.busy),
        title = { Text(when (state.stage) {
            GuestImportStage.CONFLICT -> "겹치는 데이터가 있어요"
            GuestImportStage.RUNNING -> "계정으로 가져오는 중"
            GuestImportStage.RESULT -> "데이터 가져오기"
            else -> "이 기기의 데이터를 가져올까요?"
        }) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state.stage) {
                    GuestImportStage.CONSENT -> {
                        Text("총 ${state.snapshot?.records?.size ?: 0}개")
                        Text(state.snapshot?.summary.orEmpty())
                        Text("체형·스타일·옷 사진·저장한 코디를 이 계정에 보관해요. 이 기기의 원본은 유지돼요.")
                    }
                    GuestImportStage.CHECKING -> { CircularProgressIndicator(); Text("계정 데이터를 확인하고 있어요.") }
                    GuestImportStage.LOADING -> { CircularProgressIndicator(); Text("이 기기의 데이터를 확인하고 있어요.") }
                    GuestImportStage.CONFLICT -> {
                        val conflicts = state.plan?.conflicts.orEmpty()
                        Text("프로필 ${conflicts.count { it.kind == "profile" }}건 · 옷 ${conflicts.count { it.kind == "garments" }}벌 · 코디 ${conflicts.count { it.kind == "outfits" }}개")
                        Text("로컬 값으로 교체할 항목만 선택하세요. 선택하지 않으면 계정 값을 유지하고, 겹치지 않는 항목은 추가해요.")
                        conflicts.forEach { record ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = record.key in selected, onCheckedChange = {
                                    selected = if (it) selected + record.key else selected - record.key
                                }, modifier = Modifier.semantics { contentDescription = "${record.label} 로컬 값으로 교체" })
                                Text(record.label, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    GuestImportStage.RUNNING -> {
                        val total = state.snapshot?.records?.size ?: 1
                        LinearProgressIndicator(progress = { state.done.toFloat() / total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                        Text("${state.done} / $total 항목 처리")
                    }
                    GuestImportStage.RESULT -> {
                        state.message?.let { Text(it) }
                        if (state.imported + state.kept + state.failed > 0) Text("가져옴 ${state.imported} · 계정 값 유지 ${state.kept} · 미완료 ${state.failed}")
                        state.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (state.stage) {
                GuestImportStage.CONSENT -> TextButton(onClick = onConfirm) { Text("가져오기") }
                GuestImportStage.CONFLICT -> TextButton(onClick = { onDecide(selected) }) { Text("선택대로 가져오기") }
                GuestImportStage.RESULT -> TextButton(onClick = onClose) { Text("닫기") }
                else -> TextButton(onClick = {}, enabled = false) { Text("처리 중…") }
            }
        },
        dismissButton = {
            when (state.stage) {
                GuestImportStage.CONSENT, GuestImportStage.LOADING -> TextButton(onClick = onClose) { Text("취소") }
                GuestImportStage.CONFLICT -> TextButton(onClick = onClose) { Text("취소") }
                GuestImportStage.RESULT -> if (state.failed > 0 || state.errors.isNotEmpty()) TextButton(onClick = onRetry) { Text("다시 시도") }
                else -> Unit
            }
        })
}

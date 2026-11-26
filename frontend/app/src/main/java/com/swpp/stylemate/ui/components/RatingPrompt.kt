// AI-generated with Claude Code (Claude Opus 5.5), 2026-11-26, review pending (Kihwan Kim)
package com.swpp.stylemate.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swpp.stylemate.data.usage.RatingAnswer
import com.swpp.stylemate.data.usage.RatingPrompts
import com.swpp.stylemate.data.usage.RatingTask
import com.swpp.stylemate.ui.theme.StyleMateTheme
import kotlinx.coroutines.delay

/**
 * 서버가 허락한 별점 요청을 띄운다. MainTabs에 하나만 둔다.
 * 지연 시간이 끝났을 때 앱이 화면에 떠 있지 않거나 다른 계정 요청이면 버린다.
 */
@Composable
fun RatingPromptHost(owner: String) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pending by RatingPrompts.pending.collectAsStateWithLifecycle()
    val visible by RatingPrompts.visible.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        val request = pending ?: return@LaunchedEffect
        delay((request.requestedAt + request.showAfterMs - System.currentTimeMillis()).coerceAtLeast(0))
        if (request.owner != owner || RatingPrompts.expired(request) ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) RatingPrompts.drop(request)
        else RatingPrompts.show(request)
    }
    val request = visible ?: return
    if (request.owner != owner) { RatingPrompts.close(); return }
    RatingDialog(request.task,
        onSubmit = { answer, noSeen -> RatingPrompts.submit(context, owner, request.task, answer, noSeen); RatingPrompts.close() },
        onSkip = { noSeen -> RatingPrompts.submit(context, owner, request.task, null, noSeen); RatingPrompts.close() })
}

/** 대기시간·완성도·편의성 별점 팝업. 건너뛰기·제출 버튼으로만 닫힌다 (바깥 터치·뒤로가기 무시). */
@Composable
fun RatingDialog(task: RatingTask, onSubmit: (RatingAnswer, Boolean) -> Unit, onSkip: (Boolean) -> Unit) {
    var wait by rememberSaveable { mutableStateOf(0.0) }
    var quality by rememberSaveable { mutableStateOf(0.0) }
    var convenience by rememberSaveable { mutableStateOf(0.0) }
    var noSeen by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("방금 '${task.label}' 어떠셨나요?", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("별을 누르거나 끌어서 0.5점 단위로 매겨 주세요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                RatingRow("대기시간", wait) { wait = it }
                RatingRow("완성도", quality) { quality = it }
                RatingRow("편의성", convenience) { convenience = it }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth().toggleable(value = noSeen, role = Role.Checkbox, onValueChange = { noSeen = it }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = noSeen, onCheckedChange = null)
                    Spacer(Modifier.width(4.dp))
                    Text("같은 평가 다시 보지 않기", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { Button(onClick = { onSubmit(RatingAnswer(wait, quality, convenience), noSeen) }) { Text("제출") } },
        dismissButton = { TextButton(onClick = { onSkip(noSeen) }) { Text("건너뛰기") } },
    )
}

@Composable
private fun RatingRow(label: String, value: Double, onChange: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
        StarInput(value, onChange, label)
        Spacer(Modifier.width(8.dp))
        Text(starsText(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** 별 5개. 누르거나 가로로 끌면 손가락 위치까지 0.5점 단위로 채운다. 맨 왼쪽은 0점. */
@Composable
fun StarInput(value: Double, onChange: (Double) -> Unit, label: String, modifier: Modifier = Modifier, starSize: Dp = 32.dp) {
    val change by rememberUpdatedState(onChange)
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    Row(modifier
        .semantics(mergeDescendants = true) {
            contentDescription = "$label ${starsText(value)}점 (5점 만점)"
            progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..5f, steps = 9)
            setProgress { target -> change(RatingPrompts.halfStep(target / 5f)); true }
        }
        .pointerInput(Unit) { detectTapGestures { change(RatingPrompts.halfStep(it.x / size.width)) } }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(onDragStart = { change(RatingPrompts.halfStep(it.x / size.width)) }) { event, _ ->
                change(RatingPrompts.halfStep(event.position.x / size.width))
            }
        }) {
        repeat(5) { i ->
            val fill = (value - i).coerceIn(0.0, 1.0).toFloat()
            Box(Modifier.size(starSize)) {
                Icon(Icons.Filled.Star, contentDescription = null, Modifier.size(starSize), tint = off)
                if (fill > 0f) Icon(Icons.Filled.Star, contentDescription = null, tint = on,
                    modifier = Modifier.size(starSize).drawWithContent {
                        clipRect(right = size.width * fill) { this@drawWithContent.drawContent() }
                    })
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RatingDialogPreview() {
    StyleMateTheme { RatingDialog(RatingTask.VIRTUAL_FITTING, onSubmit = { _, _ -> }, onSkip = {}) }
}

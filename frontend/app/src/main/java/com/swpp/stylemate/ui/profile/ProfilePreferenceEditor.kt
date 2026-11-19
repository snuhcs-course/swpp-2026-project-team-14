package com.swpp.stylemate.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.ui.components.ScreenScaffold

internal val FIT_LABELS = linkedMapOf("oversized" to "오버핏", "wide" to "와이드", "regular" to "레귤러", "tapered" to "테이퍼드", "slim" to "슬림")
internal val MOOD_LABELS = linkedMapOf("minimal" to "미니멀", "cityboy" to "시티보이", "casual" to "캐주얼", "street" to "스트릿", "classic" to "클래식 / 프레피", "sporty" to "스포티")
private val COLOR_GROUPS = linkedMapOf(
    "기본색" to linkedMapOf("#202020" to "블랙", "#484848" to "차콜", "#888888" to "그레이", "#C9C9C9" to "라이트 그레이", "#FFFFFF" to "화이트", "#FFFFF0" to "아이보리"),
    "베이지 · 브라운" to linkedMapOf("#F0E6D2" to "크림", "#C7B69A" to "베이지", "#C19A6B" to "카멜", "#A48374" to "모카", "#795548" to "브라운", "#49332B" to "다크 브라운"),
    "블루 · 그린" to linkedMapOf("#1F2A44" to "네이비", "#3975C6" to "블루", "#8BC4E8" to "스카이 블루", "#547A9C" to "데님 블루", "#686B42" to "카키", "#7C8B52" to "올리브", "#2F6F4E" to "그린", "#A4D7C2" to "민트"),
    "포인트 컬러" to linkedMapOf("#E9A2B3" to "핑크", "#BB7485" to "로즈", "#B83232" to "레드", "#762D3D" to "버건디", "#E89542" to "오렌지", "#F2D45C" to "옐로", "#B49636" to "머스터드", "#9464AA" to "퍼플", "#B5A1D4" to "라벤더")
)
internal val PROFILE_COLORS = COLOR_GROUPS.values.flatMap { it.entries }.associate { it.key to it.value }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfilePreferenceEditor(state: ProfilePreferenceState, modifier: Modifier,
    onSelect: (String) -> Unit, onDiscard: () -> Unit, onFit: (String) -> Unit = onSelect,
    onMood: (String) -> Unit = onSelect, onSave: () -> Unit) {
    val section = state.section ?: return
    var confirmDiscard by rememberSaveable(section) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    fun back() { if (!state.saving) { if (state.changed) confirmDiscard = true else onDiscard() } }
    BackHandler { back() }
    LaunchedEffect(state.error) { if (state.error != null) scroll.animateScrollTo(0) }
    ScreenScaffold(section.title, if (state.saving) "저장 중…" else "저장하기",
        !state.saving && state.changed, onSave, modifier, onBack = ::back,
        navigationEnabled = !state.saving, contentWindowInsets = WindowInsets(0)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
            if (section != ProfilePreference.COLORS) {
                if (section != ProfilePreference.MOOD) {
                    Text("선호 핏", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("대표 핏 하나를 골라 주세요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val fits = if (section == ProfilePreference.TASTE) state.fits else state.selected
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FIT_LABELS.forEach { (code, label) -> PreferenceOption(label, fits.firstOrNull() == code, !state.saving) { onFit(code) } }
                    }
                    if (fits.size > 1) Text("함께 저장한 핏: " + fits.drop(1).joinToString(" · ") { FIT_LABELS[it] ?: it },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (section != ProfilePreference.FIT) {
                    HorizontalDivider()
                    Text("선호 무드", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("좋아하는 분위기는 여러 개 골라도 좋아요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val moods = if (section == ProfilePreference.TASTE) state.moods else state.selected
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MOOD_LABELS.forEach { (code, label) -> PreferenceOption(label, code in moods, !state.saving) { onMood(code) } }
                    }
                }
            } else {
                Text("선택한 컬러 ${state.selected.size}/8", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (state.selected.isNotEmpty()) Text(state.selected.joinToString(" · ") { PROFILE_COLORS[it] ?: it },
                    style = MaterialTheme.typography.bodyMedium)
                Text("컬러는 프로필에 기록되며 현재 코디 추천에는 반영되지 않아요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val extra = state.original.filterNot(PROFILE_COLORS::containsKey).associateWith { it }
                val groups = COLOR_GROUPS + if (extra.isEmpty()) emptyMap() else mapOf("저장한 컬러" to extra)
                groups.forEach { (title, options) ->
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    options.entries.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (hex, label) ->
                                val selected = hex in state.selected
                                Surface(selected = selected, onClick = { onSelect(hex) },
                                    enabled = !state.saving && (selected || state.selected.size < 8),
                                    shape = MaterialTheme.shapes.medium, color = Color.Transparent, modifier = Modifier.weight(1f)) {
                                    Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val color = Color(android.graphics.Color.parseColor(hex))
                                        Surface(Modifier.size(44.dp), shape = CircleShape, color = color,
                                            border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                                            Box(contentAlignment = Alignment.Center) {
                                                if (selected) Icon(Icons.Filled.Check, null, tint = if (color.luminance() > .4f) Color.Black else Color.White)
                                            }
                                        }
                                        Text(label, style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    }
                                }
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text("변경 내용을 버릴까요?") }, text = { Text("아직 저장하지 않은 선택이 있어요.") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onDiscard() }) { Text("버리기") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("계속 수정") } })
}

@Composable
private fun PreferenceOption(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(selected = selected, onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

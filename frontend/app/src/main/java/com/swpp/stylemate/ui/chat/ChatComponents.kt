package com.swpp.stylemate.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitGarment
import com.swpp.stylemate.ui.components.OutfitSaveButton

@Composable
internal fun CurrentOutfitHeader(state: ChatState, changed: Set<String>, compact: Boolean, expanded: Boolean,
    onLock: (String) -> Unit, onPreview: () -> Unit,
    photo: @Composable (OutfitGarment, Modifier) -> Unit, photoViewport: Dp? = null) {
    val outfit = state.current ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("chat-current-outfit")) {
        if (compact) TextButton(onClick = onPreview, modifier = Modifier.heightIn(min = 48.dp)
            .focusProperties { canFocus = false }) {
            Text(if (expanded) "코디 사진 접기" else "코디 사진 보기")
            Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp))
        }
        if (!compact || expanded) CurrentOutfitPhotos(outfit, state.lockedIds, changed, !state.busy, onLock, photo,
            Modifier.then(if (photoViewport != null) Modifier.heightIn(max = photoViewport)
                .verticalScroll(rememberScrollState()) else Modifier).padding(top = 4.dp, bottom = 8.dp))
    }
}

@Composable
internal fun CurrentOutfitPhotos(outfit: Outfit, lockedIds: Set<String>, changed: Set<String>, enabled: Boolean,
    onLock: (String) -> Unit, photo: @Composable (OutfitGarment, Modifier) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cardWidth = 100.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        val scroll = maxWidth < cardWidth * outfit.items.size + 8.dp * (outfit.items.size - 1).coerceAtLeast(0)
        Row(Modifier.fillMaxWidth().then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            outfit.items.forEach { item ->
                val kept = item.garmentId in lockedIds
                val updated = item.garmentId in changed
                Surface(if (scroll) Modifier.width(cardWidth) else Modifier.weight(1f), shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(if (updated) 2.dp else 1.dp,
                        if (updated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box {
                            photo(item, Modifier.fillMaxWidth().height(88.dp))
                            if (updated) Surface(Modifier.align(Alignment.BottomStart), color = MaterialTheme.colorScheme.primary,
                                shape = MaterialTheme.shapes.small) {
                                Text("변경", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary)
                            }
                            IconToggleButton(checked = kept, onCheckedChange = { onLock(item.garmentId) }, enabled = enabled,
                                modifier = Modifier.align(Alignment.TopEnd).size(48.dp).testTag("chat-keep-${item.garmentId}").semantics {
                                    selected = kept
                                    stateDescription = if (kept) "계속 입을 옷으로 잠김" else "변경 가능"
                                }, colors = IconButtonDefaults.iconToggleButtonColors(
                                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                                    checkedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant, checkedContentColor = MaterialTheme.colorScheme.primary)) {
                                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = .94f)) {
                                    Icon(androidx.compose.ui.res.painterResource(if (kept) com.swpp.stylemate.R.drawable.ic_lock_closed else com.swpp.stylemate.R.drawable.ic_lock_open),
                                        "${item.name} ${if (kept) "잠금 해제" else "잠금"}", Modifier.padding(6.dp).size(20.dp))
                                }
                            }
                        }
                        Text(item.name, style = MaterialTheme.typography.labelSmall, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatDirectionsSheet(state: ChatState, onDirection: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("수정 조건", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = { state.directions.toList().forEach(onDirection) }, enabled = state.directions.isNotEmpty() && !state.busy) { Text("선택 해제") }
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
            listOf("밝기" to listOf("brighter", "darker"), "보온" to listOf("warmer", "cooler"),
                "격식" to listOf("formal", "casual"), "핏" to listOf("looser", "fitted")).forEach { (group, codes) ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(group, style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        codes.forEach { code ->
                            val checked = code in state.directions
                            OutlinedButton(onClick = { onDirection(code) }, enabled = !state.busy,
                                shape = MaterialTheme.shapes.medium,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                    contentColor = if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("chat-direction-$code").semantics { selected = checked }) {
                                if (checked) { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
                                Text(CHAT_DIRECTIONS.getValue(code))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatHistorySheet(state: ChatState, onRestore: (Outfit) -> Unit,
    onDismiss: () -> Unit, photo: @Composable (OutfitGarment, Modifier) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("수정 기록", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
            Text("사진을 골라 수정할 코디를 바꿀 수 있어요.", style = MaterialTheme.typography.bodyMedium)
            outfitVersions(state).forEach { version ->
                val current = version.outfit.key == state.current?.key
                Surface(Modifier.fillMaxWidth().testTag("chat-history-${version.outfit.key}").semantics {
                    selected = current
                    contentDescription = "${version.label}${if (current) ", 현재 코디" else ", 이 코디 선택"}"
                }.clickable(enabled = !state.busy, role = Role.RadioButton) {
                    if (!current) onRestore(version.outfit)
                    onDismiss()
                }, shape = MaterialTheme.shapes.large,
                    border = BorderStroke(if (current) 2.dp else 1.dp,
                        if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(version.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            if (current) Text("현재 코디", style = MaterialTheme.typography.labelMedium)
                            RadioButton(selected = current, onClick = null, modifier = Modifier.size(24.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            version.outfit.items.forEach { garment -> Column(Modifier.weight(1f)) {
                                photo(garment, Modifier.fillMaxWidth().height(64.dp))
                                Text(garment.name, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            } }
                        }
                    }
                }
            }

        }
    }
}

@Composable
internal fun CurrentFeedback(outfit: Outfit, state: ChatState, onFeedback: (Outfit, String) -> Unit,
    onLoadFeedback: (Outfit) -> Unit) {
    val loaded = outfit.key in state.feedbackLoaded
    val loading = outfit.key in state.feedbackLoading
    val enabled = !state.busy && state.feedbackPending.isEmpty() && loaded && !loading
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("이번 수정은 어때요?", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf("liked" to "좋아요", "disliked" to "별로예요").forEach { (value, label) ->
                val active = state.feedbacks[outfit.key] == value
                IconButton(onClick = { onFeedback(outfit, value) }, enabled = enabled,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        contentColor = if (active) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color(0xFF8A8A8A)),
                    modifier = Modifier.size(48.dp).semantics { selected = active }) {
                    Icon(if (active) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, label,
                        Modifier.size(18.dp).then(if (value == "disliked") Modifier.rotate(180f) else Modifier))
                }
            }
        }
        when {
            loading -> Text("평가 정보를 불러오는 중이에요", style = MaterialTheme.typography.bodySmall)
            !loaded -> TextButton(onClick = { onLoadFeedback(outfit) }) { Text("평가 다시 불러오기") }
            else -> state.feedbackMessages[outfit.key]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

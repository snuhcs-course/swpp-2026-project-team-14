package com.swpp.stylemate.ui.chat

import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.ui.home.GarmentThumbnail
import kotlinx.coroutines.launch

@Composable
fun ChatRoute(model: ChatViewModel, modifier: Modifier = Modifier) {
    val state by model.state.collectAsStateWithLifecycle()
    BackHandler { model.close() }
    ChatScreen(state, onBack = model::close, onDraft = model::setDraft, onLock = model::toggleLock,
        onDirection = model::toggleDirection, onSend = model::send, onRetry = model::retry,
        onUnlock = model::unlockAndRetry, onRestore = model::restore, onUndo = model::undo,
        onSave = model::save, modifier = modifier)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    state: ChatState,
    onBack: () -> Unit,
    onDraft: (String) -> Unit,
    onLock: (String) -> Unit,
    onDirection: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: (String) -> Unit,
    onUnlock: (String) -> Unit,
    onRestore: (Outfit) -> Unit,
    onUndo: () -> Unit,
    onSave: (Outfit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outfit = state.current ?: return
    var expanded by rememberSaveable(state.source?.id) { mutableStateOf(true) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var followLatest by remember { mutableStateOf(true) }
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    LaunchedEffect(list.isScrollInProgress, atBottom) {
        if (list.isScrollInProgress) followLatest = atBottom
    }
    val scrollRevision = state.turns.map { Triple(it.id, it.loading, it.result?.status ?: it.error) }
    LaunchedEffect(scrollRevision) {
        if (followLatest) list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
    }
    LaunchedEffect(state.turns.size) { if (state.turns.isNotEmpty()) expanded = false }
    fun send() {
        if (!state.canSend) return
        followLatest = true
        keyboard?.hide()
        onSend()
    }
    Scaffold(
        modifier = modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "오늘의 코디로 돌아가기")
                        }
                        Column(Modifier.weight(1f)) {
                            Text("코디 수정", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("옷을 눌러 잠그고, 원하는 방향을 골라요", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = onUndo, enabled = !state.busy && state.snapshots.isNotEmpty()) { Text("되돌리기") }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("현재 코디", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text("${state.lockedIds.size}벌 유지", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "접기" else "펼치기") }
                    }
                    if (expanded) {
                        OutfitItems(outfit, lockedIds = state.lockedIds, onLock = onLock, enabled = !state.busy,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))
                    } else {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            outfit.items.forEach { item ->
                                val locked = item.garmentId in state.lockedIds
                                FilterChip(selected = locked, enabled = !state.busy,
                                    onClick = { onLock(item.garmentId) },
                                    label = { Text(slotName(item.slot)) },
                                    leadingIcon = if (locked) {{ Icon(Icons.Filled.Lock, null, Modifier.size(14.dp)) }} else null,
                                    modifier = Modifier.semantics { stateDescription = if (locked) "유지" else "변경 가능" })
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CHAT_DIRECTIONS.forEach { (code, label) ->
                            FilterChip(selected = code in state.directions, onClick = { onDirection(code) },
                                label = { Text(label) })
                        }
                    }
                    if (state.allLocked) Text("바꿀 옷의 잠금을 하나 이상 풀어 주세요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(value = state.draft, onValueChange = onDraft,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                        placeholder = { Text("더 원하는 게 있나요? (선택)") },
                        label = { Text("메시지") }, maxLines = 3,
                        trailingIcon = {
                            IconButton(onClick = ::send, enabled = state.canSend) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "메시지 보내기")
                            }
                        })
                    if (state.directions.isNotEmpty()) {
                        Button(onClick = ::send, enabled = state.canSend, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            shape = RoundedCornerShape(14.dp)) {
                            Text(if (state.busy) "코디 수정 중…" else "선택한 조건으로 수정")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize(), state = list,
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item(key = "intro") {
                    AssistantBubble("유지할 옷은 눌러서 잠가 주세요. 아래 버튼을 고르거나, 원하는 내용을 메시지로 알려 주세요.")
                }
                items(state.turns, key = { it.id }) { turn ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Surface(shape = RoundedCornerShape(20.dp, 4.dp, 20.dp, 20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text(ChatViewModel.summary(turn.request), Modifier.padding(14.dp).widthIn(max = 280.dp),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        when {
                            turn.loading -> Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text("잠근 옷을 유지하며 코디를 찾고 있어요", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            turn.error != null -> {
                                AssistantBubble(turn.error)
                                if (state.turns.lastOrNull()?.id == turn.id) {
                                    val sameRequest = state.current?.key == turn.request.current.key && state.lockedIds == turn.request.lockedIds
                                    OutlinedButton(onClick = { onRetry(turn.id) }, enabled = !state.busy && sameRequest) { Text("다시 시도") }
                                    if (!sameRequest) Text("코디나 잠금이 바뀌었어요. 새 요청을 보내 주세요.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            turn.result != null -> {
                                val result = turn.result
                                AssistantBubble(result.message)
                                if (result.status == "unlock" && state.turns.lastOrNull()?.id == turn.id) {
                                    OutlinedButton(onClick = { onUnlock(turn.id) },
                                        enabled = !state.busy && state.current?.key == turn.request.current.key) { Text("잠금 풀고 수정") }
                                }
                                result.outfit?.let { resultOutfit ->
                                    ResultCard(resultOutfit, result.changedIds, result.lockedIds, state,
                                        onRestore, onSave)
                                }
                                result.notes.forEach { note -> Text(note, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
                state.notice?.let { notice -> item(key = "notice") { AssistantBubble(notice) } }
                item(key = "bottom") { Spacer(Modifier.height(1.dp)) }
            }
            if (!atBottom && state.turns.isNotEmpty()) {
                FilledTonalButton(onClick = {
                    followLatest = true
                    scope.launch { list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1) }
                }, modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)) { Text("최신 대화로") }
            }
        }
    }
}

@Composable
private fun AssistantBubble(message: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("StyleMate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold)
        Surface(shape = RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(message, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun slotName(slot: String) = when (slot) { "top" -> "상의"; "bottom" -> "하의"; else -> "아우터" }

@Composable
private fun OutfitItems(
    outfit: Outfit,
    lockedIds: Set<String>,
    modifier: Modifier = Modifier,
    changedIds: Set<String> = emptySet(),
    onLock: ((String) -> Unit)? = null,
    enabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        outfit.items.forEach { item ->
            val locked = item.garmentId in lockedIds
            Surface(modifier = Modifier.weight(1f).then(if (onLock != null) Modifier
                .semantics { stateDescription = if (locked) "잠김, 유지" else "잠금 해제, 변경 가능" }
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = if (locked) "잠금 해제" else "옷 유지") {
                    onLock(item.garmentId)
                } else Modifier), shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(if (locked) 2.dp else 1.dp,
                    if (locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GarmentThumbnail(item.imageUrl, item.name,
                        Modifier.fillMaxWidth().height(86.dp).clip(RoundedCornerShape(10.dp)))
                    Text(item.name, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (locked) Icon(Icons.Filled.Lock, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(when { locked -> "유지"; item.garmentId in changedIds -> "변경"; onLock != null -> "변경 가능"; else -> "그대로" },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (locked || item.garmentId in changedIds) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(outfit: Outfit, changedIds: Set<String>, lockedIds: Set<String>, state: ChatState,
                       onRestore: (Outfit) -> Unit, onSave: (Outfit) -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (state.current?.key == outfit.key) "이 코디로 계속 수정 중" else "수정한 코디",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            OutfitItems(outfit, lockedIds, changedIds = changedIds)
            outfit.reasons.take(2).forEach { reason -> Text(reason.text, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.current?.key != outfit.key) {
                    TextButton(onClick = { onRestore(outfit) }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("이 코디로 돌아가기") }
                }
                Button(onClick = { onSave(outfit) }, enabled = outfit.key !in state.savedKeys && outfit.key !in state.savingKeys,
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Text(when (outfit.key) { in state.savedKeys -> "저장됨"; in state.savingKeys -> "저장 중…";
                        in state.saveErrors -> "저장 다시 시도"; else -> "코디북에 저장" })
                }
            }
            state.saveErrors[outfit.key]?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error) }
        }
    }
}

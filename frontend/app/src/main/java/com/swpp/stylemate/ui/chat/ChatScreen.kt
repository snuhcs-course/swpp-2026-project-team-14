package com.swpp.stylemate.ui.chat

import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import com.swpp.stylemate.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitGarment
import com.swpp.stylemate.ui.home.GarmentThumbnail
import com.swpp.stylemate.ui.components.PageHeader
import kotlinx.coroutines.launch

@Composable
fun ChatRoute(model: ChatViewModel, modifier: Modifier = Modifier) {
    val state by model.state.collectAsStateWithLifecycle()
    BackHandler { model.close() }
    ChatScreen(state, onBack = model::close, onDraft = model::setDraft, onLock = model::toggleLock,
        onDirection = model::toggleDirection, onSend = model::send, onRetry = model::retry,
        onUnlock = model::unlockAndRetry, onRestore = model::restore, onUndo = model::undo,
        onSave = model::toggleSave, onFeedback = model::feedback, onLoadFeedback = model::loadFeedback,
        onEditRequest = model::editRequest, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatState, onBack: () -> Unit, onDraft: (String) -> Unit, onLock: (String) -> Unit,
    onDirection: (String) -> Unit, onSend: () -> Unit, onRetry: (String) -> Unit,
    onUnlock: (String) -> Unit, onRestore: (Outfit) -> Unit, onUndo: () -> Unit, onSave: (Outfit) -> Unit,
    modifier: Modifier = Modifier, onFeedback: (Outfit, String) -> Unit = { _, _ -> },
    onLoadFeedback: (Outfit) -> Unit = {}, onEditRequest: (String) -> Unit = {},
    photo: @Composable (OutfitGarment, Modifier) -> Unit = { item, m -> GarmentThumbnail(item.imageUrl, item.name, m) },
) {
    val outfit = state.current ?: return
    var choosingDirections by rememberSaveable(state.source?.id) { mutableStateOf(false) }
    var photosExpanded by rememberSaveable(state.source?.id) { mutableStateOf(false) }
    var showingHistory by rememberSaveable(state.source?.id) { mutableStateOf(false) }
    var replacingDraft by remember { mutableStateOf<ChatTurn?>(null) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val inputFocus = remember { FocusRequester() }
    var followLatest by remember { mutableStateOf(true) }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    LaunchedEffect(dragging, atBottom) {
        if (dragging) followLatest = atBottom
        else if (atBottom) followLatest = true
    }
    val revision = state.turns.map { Triple(it.id, it.loading, it.result?.status ?: it.error) }
    val activeResult = state.turns.lastOrNull { it.result?.outfit?.key == outfit.key }?.result
    val bottomIndex = state.turns.size + (if (state.turns.isEmpty()) 1 else 0) +
        (if (state.notice != null) 1 else 0) + (if (outfit.key in state.saveErrors) 1 else 0) +
        (if (activeResult != null) 1 else 0)
    LaunchedEffect(revision, state.notice, state.saveErrors[outfit.key],
        state.feedbackMessages[outfit.key], outfit.key in state.feedbackLoading, outfit.key in state.feedbackLoaded) {
        // layoutInfo can still be empty when a restored session first composes.
        if (followLatest) list.animateScrollToItem(bottomIndex)
    }
    LaunchedEffect(outfit.key, activeResult != null) { if (activeResult != null) onLoadFeedback(outfit) }
    fun send() {
        if (!state.canSend) return
        followLatest = true
        keyboard?.hide(); focus.clearFocus(); onSend()
    }
    fun edit(turn: ChatTurn) {
        onEditRequest(turn.id)
        if (turn.request.text.isNotBlank()) { inputFocus.requestFocus(); keyboard?.show() }
    }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BoxWithConstraints(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        val compact = imeVisible || maxHeight < 400.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        val photoViewport = if (compact) (maxHeight * .27f).coerceAtLeast(80.dp) else null
        Scaffold(Modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0), topBar = {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column {
                        PageHeader("대화로 수정하기", onBack = onBack, backDescription = "오늘의 코디로 돌아가기") {
                            IconButton(onClick = { focus.clearFocus(); keyboard?.hide(); showingHistory = true },
                                enabled = !state.busy && outfitVersions(state).size > 1) {
                                Icon(painterResource(R.drawable.ic_history), "수정 기록")
                            }
                            val saved = outfit.key in state.savedKeys
                            val saving = outfit.key in state.savingKeys
                            IconButton(onClick = { onSave(outfit) }, enabled = !state.busy && !saving,
                                modifier = Modifier.testTag("chat-save-outfit").semantics {
                                    contentDescription = if (saved) "코디북 저장 해제" else "코디북에 저장"
                                    stateDescription = if (saving) "저장 중" else if (saved) "저장됨" else "저장 안 됨"
                                }) {
                                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else if (saved) Icon(Icons.Filled.Star, null)
                                else Icon(painterResource(R.drawable.ic_star_outline), null)
                            }
                        }
                        CurrentOutfitHeader(state, activeResult?.changedIds.orEmpty(), compact, photosExpanded, onLock,
                            onPreview = { photosExpanded = !photosExpanded }, photo = photo,
                            photoViewport = photoViewport)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }, bottomBar = {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = state.draft, onValueChange = onDraft,
                            modifier = Modifier.fillMaxWidth().focusRequester(inputFocus), shape = MaterialTheme.shapes.medium,
                            placeholder = { Text("예: 하의만 치마로 바꿔줘") },
                            label = { Text(if (state.directions.isEmpty()) "메시지" else "메시지 추가(선택)") }, maxLines = 3,
                            leadingIcon = { IconButton(onClick = {
                                focus.clearFocus(); keyboard?.hide(); choosingDirections = true
                            }, enabled = !state.busy) {
                                BadgedBox(badge = { if (state.directions.isNotEmpty()) Badge { Text("${state.directions.size}") } }) {
                                    Icon(painterResource(R.drawable.ic_chat_conditions), "수정 조건 선택")
                                }
                            } },
                            trailingIcon = { IconButton(onClick = ::send, enabled = state.canSend) {
                                Icon(Icons.AutoMirrored.Filled.Send, "메시지 보내기")
                            } })
                        if (state.directions.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(state.directions.toList(), key = { it }) { code ->
                                InputChip(selected = true, onClick = { onDirection(code) }, enabled = !state.busy,
                                    label = { Text(CHAT_DIRECTIONS[code] ?: code) },
                                    trailingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(14.dp)) },
                                    modifier = Modifier.semantics { contentDescription = "${CHAT_DIRECTIONS[code]} 조건 해제" })
                            }
                        }
                    }
                }
            }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("chat-conversation"), state = list,
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (state.turns.isEmpty()) item("intro") {
                        AssistantBubble("원하는 변화를 알려 주세요. 계속 입을 옷은 사진의 자물쇠를 눌러 잠가 주세요.")
                    }
                    items(state.turns, key = { it.id }) { turn ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary) {
                                    Text(ChatViewModel.conversationText(turn.request), Modifier.padding(12.dp).widthIn(max = 280.dp),
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            when {
                                turn.loading -> Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text("코디를 수정하고 있어요", style = MaterialTheme.typography.bodyMedium)
                                }
                                turn.error != null -> {
                                    AssistantBubble(turn.error, "코디 수정 실패")
                                    if (state.turns.lastOrNull()?.id == turn.id) {
                                        val same = outfit.key == turn.request.current.key && state.lockedIds == turn.request.lockedIds
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = { onRetry(turn.id) }, enabled = !state.busy && same,
                                                shape = MaterialTheme.shapes.medium) { Text("다시 시도") }
                                            OutlinedButton(onClick = {
                                                if (state.draft.isNotBlank() || state.directions.isNotEmpty()) replacingDraft = turn else edit(turn)
                                            }, enabled = !state.busy, shape = MaterialTheme.shapes.medium) { Text("요청 수정하기") }
                                        }
                                        if (!same) Text("코디나 그대로 입을 옷이 바뀌었어요. 요청을 수정해 다시 보내 주세요.",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                turn.result != null -> {
                                    val result = turn.result
                                    AssistantBubble(changedOutfitMessage(turn), when (result.status) {
                                        "unchanged" -> "변경 없음"
                                        "clarify", "unlock" -> "확인이 필요해요"
                                        "undo" -> "수정 취소"
                                        else -> null
                                    })
                                    if (result.changes.isNotEmpty()) ChangeReasons(result.changes)
                                    if (result.status == "unlock" && state.turns.lastOrNull()?.id == turn.id) {
                                        OutlinedButton(onClick = { onUnlock(turn.id) },
                                            enabled = !state.busy && outfit.key == turn.request.current.key,
                                            shape = MaterialTheme.shapes.medium) { Text("이 옷도 바꾸기") }
                                    }
                                    result.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                    }
                    state.notice?.let { notice -> item("notice") { AssistantBubble(notice) } }
                    state.saveErrors[outfit.key]?.let { error -> item("save-error") {
                        Text("코디북에 저장하지 못했어요. $error", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium)
                    } }
                    if (activeResult != null) item("current-feedback") { CurrentFeedback(outfit, state, onFeedback, onLoadFeedback) }
                    item("bottom") { Spacer(Modifier.height(1.dp)) }
                }
                if (!atBottom && state.turns.isNotEmpty()) TextButton(onClick = {
                    followLatest = true
                    scope.launch { list.animateScrollToItem(bottomIndex) }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("최신 대화로") }
            }
        }
    }
    if (choosingDirections) ChatDirectionsSheet(state, onDirection) { choosingDirections = false }
    if (showingHistory) ChatHistorySheet(state, onRestore, { showingHistory = false }, photo)
    replacingDraft?.let { turn -> AlertDialog(onDismissRequest = { replacingDraft = null },
        title = { Text("작성 중인 요청을 바꿀까요?") }, text = { Text("실패한 요청의 메시지와 조건을 불러옵니다.") },
        confirmButton = { TextButton(onClick = { replacingDraft = null; edit(turn) }) { Text("불러오기") } },
        dismissButton = { TextButton(onClick = { replacingDraft = null }) { Text("계속 작성") } }) }
}

@Composable
private fun ChangeReasons(changes: List<com.swpp.stylemate.data.chat.ChatChange>) {
    val slots = mapOf("top" to "상의", "bottom" to "하의", "outerwear" to "아우터")
    Column(Modifier.fillMaxWidth().padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("바뀐 이유", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // The bubble above already names the garments (before → after); this says why, one line per slot.
        changes.forEach { change ->
            Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(slots[change.slot] ?: change.slot, style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold)
                Text(change.reason, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AssistantBubble(message: String, label: String? = null) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            label?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

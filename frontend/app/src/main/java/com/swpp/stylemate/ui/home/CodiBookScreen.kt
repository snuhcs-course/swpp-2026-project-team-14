package com.swpp.stylemate.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.res.painterResource
import com.swpp.stylemate.R
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitGarment
import com.swpp.stylemate.ui.components.PageHeader
import com.swpp.stylemate.ui.components.OutfitBoard
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal enum class CodiBookView { SLIDES, LIST }
enum class CodiBookFittingStatus(val action: String, val label: String? = null) {
    NONE("가상 피팅"), GENERATING("피팅 생성 중", "피팅 생성 중"),
    READY("피팅 결과 보기", "피팅 결과 있음"), FAILED("피팅 다시 확인", "피팅 확인 필요")
}

/** Selection and view mode belong to the authenticated tab's SaveableStateProvider. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodiBookScreen(
    state: CodiBookState,
    onRemove: (Outfit) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onTryOn: (Outfit) -> Unit = {},
    photo: @Composable (OutfitGarment, Modifier) -> Unit = { item, m -> GarmentThumbnail(item.imageUrl, item.name, m) },
    fittingStatuses: Map<String, CodiBookFittingStatus> = emptyMap(),
    onClearRemoveError: () -> Unit = {},
) {
    var view by rememberSaveable { mutableStateOf(CodiBookView.SLIDES) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastIndex by rememberSaveable { mutableIntStateOf(0) }
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }
    val ids = state.outfits.map { it.id }
    val index = ids.indexOf(selectedId).takeIf { it >= 0 } ?: lastIndex.coerceIn(0, ids.lastIndex.coerceAtLeast(0))
    val current = state.outfits.getOrNull(index)
    val pager = rememberPagerState(initialPage = index) { ids.size }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val enabled = !state.loading && state.removingId == null

    // Keep an outfit ID across reordering, removal and switching views. Never reset on every refresh.
    LaunchedEffect(view, ids) {
        if (ids.isEmpty()) return@LaunchedEffect
        val target = ids.indexOf(selectedId).takeIf { it >= 0 }
            ?: lastIndex.coerceIn(0, ids.lastIndex)
        selectedId = ids[target]
        lastIndex = target
        if (view == CodiBookView.SLIDES) {
            pager.scrollToPage(target)
            snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
                ids.getOrNull(page)?.let { selectedId = it; lastIndex = page }
            }
        } else {
            if (list.firstVisibleItemIndex != target) list.scrollToItem(target)
            snapshotFlow { list.isScrollInProgress to list.firstVisibleItemIndex }.distinctUntilChanged().collect { (moving, position) ->
                // A short list may not scroll the requested item to the top. Preserve its selection
                // until the user actually scrolls or chooses another card.
                if (moving) ids.getOrNull(position)?.let { selectedId = it; lastIndex = position }
            }
        }
    }
    LaunchedEffect(ids) {
        if (removingId != null && removingId !in ids && !state.loading) removingId = null
    }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PageHeader("코디북", horizontalPadding = 0.dp,
                subtitle = if (ids.isNotEmpty() || (!state.loading && state.error == null)) "저장한 코디 ${ids.size}개" else null)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                if (ids.isNotEmpty()) CodiBookView.entries.forEach { choice ->
                    IconToggleButton(checked = view == choice, onCheckedChange = { view = choice },
                        enabled = state.removingId == null && !pager.isScrollInProgress,
                        modifier = Modifier.size(48.dp).semantics { selected = view == choice },
                        colors = IconButtonDefaults.iconToggleButtonColors(checkedContentColor = MaterialTheme.colorScheme.primary,
                            checkedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        Icon(painterResource(if (choice == CodiBookView.SLIDES) R.drawable.ic_view_slides else R.drawable.ic_view_list),
                            if (choice == CodiBookView.SLIDES) "슬라이드 보기" else "목록 보기")
                    }
                }
            }
            if (state.loading && ids.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.error != null && ids.isNotEmpty()) {
                Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry, enabled = enabled) { Text("다시 불러오기") }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            when {
                ids.isEmpty() -> Column(Modifier.fillMaxSize().padding(28.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        state.loading -> { CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text("코디를 불러오고 있어요") }
                        state.error != null -> {
                            Text(state.error, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onRetry) { Text("다시 불러오기") }
                        }
                        else -> {
                            Text("아직 저장한 코디가 없어요", style = MaterialTheme.typography.titleMedium)
                            Text("홈이나 대화에서 마음에 드는 코디를 저장해 보세요.", Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                view == CodiBookView.SLIDES && current != null -> {
                    val boardHeight = (maxHeight * 0.58f).coerceIn(180.dp, 380.dp)
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
                        HorizontalPager(pager, Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = if (ids.size > 1) 28.dp else 20.dp),
                            pageSpacing = 12.dp, userScrollEnabled = state.removingId == null,
                            key = { ids[it] }) { page ->
                            Surface(Modifier.fillMaxWidth().height(boardHeight).semantics {
                                contentDescription = "저장한 코디 ${page + 1} / ${ids.size}"
                            }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                Box(Modifier.fillMaxSize()) {
                                    OutfitBoard(state.outfits[page].items, photo, Modifier.padding(12.dp))
                                    if (page == index) IconButton(onClick = { onClearRemoveError(); removingId = ids[page] },
                                        enabled = enabled && !pager.isScrollInProgress,
                                        colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .94f)),
                                        modifier = Modifier.align(Alignment.TopEnd).size(48.dp)) {
                                        Icon(Icons.Filled.Star, "코디북에서 빼기")
                                    }
                                }
                            }
                        }
                        if (ids.size > 1) CodiBookDots(ids, index, enabled && !pager.isScrollInProgress) { page ->
                            scope.launch { pager.animateScrollToPage(page) }
                        } else Spacer(Modifier.height(16.dp))
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(current.displayName(), style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Button(onClick = { onTryOn(current) },
                                enabled = enabled && !pager.isScrollInProgress && pager.settledPage == index,
                                shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text((fittingStatuses[current.id] ?: CodiBookFittingStatus.NONE).action)
                            }

                        }
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), state = list,
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.outfits, key = { it.id }) { outfit ->
                        Surface(onClick = {
                            selectedId = outfit.id; lastIndex = ids.indexOf(outfit.id); view = CodiBookView.SLIDES
                        }, enabled = state.removingId == null, shape = MaterialTheme.shapes.large,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${outfit.displayName()} 크게 보기" }) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutfitBoard(outfit.items, photo, Modifier.fillMaxWidth().height(190.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(outfit.displayName(), style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        fittingStatuses[outfit.id]?.label?.let {
                                            Text(it, style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    state.outfits.firstOrNull { it.id == removingId }?.let { outfit ->
        val busy = state.removingId == outfit.id
        fun dismiss() { if (!busy) { removingId = null; onClearRemoveError() } }
        AlertDialog(onDismissRequest = ::dismiss,
            properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
            title = { Text("코디북에서 뺄까요?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutfitBoard(outfit.items, photo, Modifier.fillMaxWidth().height(120.dp))
                    Text(outfit.displayName(), fontWeight = FontWeight.SemiBold)
                    Text("옷장에 등록된 옷은 그대로 유지돼요.")
                    if (state.removeErrorId == outfit.id) state.removeError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }, confirmButton = {
                TextButton(onClick = { onRemove(outfit) }, enabled = !busy) {
                    Text(if (busy) "빼는 중…" else if (state.removeErrorId == outfit.id) "다시 시도" else "빼기")
                }
            }, dismissButton = { TextButton(onClick = ::dismiss, enabled = !busy) { Text("취소") } })
    }
}

private fun Outfit.displayName() = name.ifBlank { items.joinToString(" + ") { it.name } }

@Composable
private fun CodiBookDots(ids: List<String>, index: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        IconButton(onClick = { onSelect(index - 1) }, enabled = enabled && index > 0) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 코디")
        }
        Text("${index + 1} / ${ids.size}", style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.widthIn(min = 72.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { onSelect(index + 1) }, enabled = enabled && index < ids.lastIndex) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 코디")
        }
    }
}

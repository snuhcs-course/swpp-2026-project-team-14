// AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ArrowDropDown
import com.swpp.stylemate.data.recommendation.HomeRegion
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitRepository
import com.swpp.stylemate.data.recommendation.OutfitGarment
import com.swpp.stylemate.data.wardrobe.WardrobeRepository
import com.swpp.stylemate.ui.components.OutfitFeedbackButtons
import com.swpp.stylemate.ui.components.PageHeader
import com.swpp.stylemate.ui.wardrobe.OutfitRail
import com.swpp.stylemate.ui.components.OutfitSaveButton
import com.swpp.stylemate.ui.components.StarRating
import com.swpp.stylemate.ui.components.starsOf
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** Compact outfit comparison. All actions follow the selected outfit; recommendation values stay intact. */
@Composable
fun HomeScreen(
    state: HomeState,
    onRefresh: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onSave: () -> Unit,
    onSetupProfile: () -> Unit,
    onOpenWardrobe: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit = {},
    onChat: (() -> Unit)? = null,
    fitting: (@Composable (Outfit, Modifier, Boolean) -> Unit)? = null,
    todayPlan: (@Composable () -> Unit)? = null,
    hint: (@Composable () -> Unit)? = null,
    onRegion: ((String) -> Unit)? = null,
    // null: hang the stored background-free photos; screen tests pass their own renderer.
    photo: (@Composable (OutfitGarment, Modifier) -> Unit)? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it, duration = if (it.length > 25) SnackbarDuration.Long else SnackbarDuration.Short); onMessageShown() }
    }
    val outfit = state.current
    val pager = rememberPagerState(initialPage = state.index) { state.outfits.size }
    val ready = !state.loading && !state.acting && !pager.isScrollInProgress
    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0), snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val boardHeight = (maxHeight * 0.38f).coerceIn(170.dp, 280.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                PageHeader("오늘의 코디", subtitleContent = { WeatherLine(state, onRegion, Modifier) })
                todayPlan?.let { content -> Box(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp)) { content() } }
                hint?.let { content -> Box(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp)) { content() } }
                when {
                    state.errorCode == HomeState.NO_PROFILE -> EmptyState("체형 정보가 필요해요",
                        "체형 분석을 하면 내 몸에 맞는 옷으로 코디를 골라 드려요.", "체형 분석하기", onSetupProfile)
                    state.errorCode == "WARDROBE_TOO_SMALL" -> EmptyState("옷을 조금 더 등록해 주세요",
                        state.error.orEmpty(), "옷장으로 가기", onOpenWardrobe)
                    outfit == null && state.loading -> Box(Modifier.fillMaxWidth().height(boardHeight), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    outfit == null -> EmptyState("코디를 불러오지 못했어요", state.error ?: OutfitRepository.SERVER_ERROR,
                        "다시 시도", onRefresh)
                    else -> {
                        state.error?.let { error ->
                            Column(Modifier.padding(horizontal = 20.dp)) {
                                Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = onRefresh, enabled = ready) { Text("다시 시도") }
                            }
                        }
                        OutfitCarousel(state, pager, boardHeight, onSelect, photo)
                        Column(Modifier.padding(horizontal = 20.dp)) {
                            outfit.score?.let { score ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("추천 별점", style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(8.dp))
                                    StarRating(starsOf(score), size = 18.dp)
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                fitting?.invoke(outfit, Modifier.weight(1f), ready)
                                onChat?.let { chat ->
                                    Button(onClick = chat, enabled = ready, shape = MaterialTheme.shapes.medium,
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                        modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("대화로 수정하기") }
                                }
                            }
                            OutfitFeedbackButtons(outfit.feedback, ready, onLike, onDislike,
                                Modifier.padding(top = 10.dp), leading = { m ->
                                    OutfitSaveButton(outfit.saved, ready, onSave, m)
                                })
                            OutfitInformation(outfit, state.notes)
                            OutlinedButton(onClick = onRefresh, enabled = ready, shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp)) {
                                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("새로 추천")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/** Selecting a cached outfit does not request new recommendations. */
@Composable
private fun OutfitCarousel(state: HomeState, pager: PagerState, height: Dp, onSelect: (Int) -> Unit,
    photo: (@Composable (OutfitGarment, Modifier) -> Unit)?) {
    val selectedIndex by rememberUpdatedState(state.index)
    val select by rememberUpdatedState(onSelect)
    LaunchedEffect(pager) {
        // Tab restoration or an empty state can leave the pager at an older selection.
        pager.scrollToPage(selectedIndex)
        snapshotFlow { pager.settledPage }.collect { if (it != selectedIndex) select(it) }
    }
    LaunchedEffect(state.index) {
        if (pager.currentPage != state.index) pager.animateScrollToPage(state.index)
    }
    HorizontalPager(pager, Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = if (state.outfits.size > 1) 28.dp else 20.dp),
        pageSpacing = 12.dp, userScrollEnabled = !state.loading && !state.acting,
        key = { state.outfits[it].id }) { page ->
        Box(Modifier.fillMaxWidth().height(height).semantics {
            contentDescription = "코디 ${page + 1} / ${state.outfits.size}"
        }) {
            OutfitRail(state.outfits[page].items, photo = photo)
            if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
    if (state.outfits.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        repeat(state.outfits.size) { page ->
            IconButton(onClick = { onSelect(page) }, enabled = !state.loading && !state.acting && !pager.isScrollInProgress,
                modifier = Modifier.size(40.dp).semantics {
                    contentDescription = "코디 ${page + 1} 선택"
                    selected = state.index == page
                }) {
                Box(Modifier.width(if (state.index == page) 20.dp else 6.dp).height(6.dp).clip(CircleShape)
                    .background(if (state.index == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}

@Composable
private fun OutfitInformation(outfit: Outfit, notes: List<String>) {
    val reasons = outfit.reasons.filter { it.text.isNotBlank() }.distinctBy { it.text.trim() }
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (reasons.isNotEmpty()) {
            Text("이 코디를 고른 이유", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            reasons.forEachIndexed { index, reason ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    val label = when (reason.kind) { "color" -> "색상"; "body", "fit" -> "체형"; "season", "weather" -> "날씨"; "style" -> "취향"; else -> "조합" }
                    Text(label, Modifier.widthIn(min = 40.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(reason.text.trim(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun WeatherLine(state: HomeState, onRegion: ((String) -> Unit)?, modifier: Modifier) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val region = Modifier.clip(MaterialTheme.shapes.medium).then(
            if (onRegion != null) Modifier.clickable(onClickLabel = "날씨 지역 바꾸기") { picking = true } else Modifier)
        Row(region.padding(end = 2.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LocationOn, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(state.region, Modifier.padding(start = 2.dp), style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            if (onRegion != null) Icon(Icons.Filled.ArrowDropDown, contentDescription = null, Modifier.size(18.dp), tint = muted)
        }
        val rest = weatherLine(state)
        if (rest.isNotBlank()) Text(" · $rest", style = MaterialTheme.typography.bodyMedium, color = muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (picking && onRegion != null) RegionDialog(state.region, onPick = { picking = false; onRegion(it) },
        onDismiss = { picking = false })
}

@Composable
private fun RegionDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("날씨를 볼 지역") },
        text = {
            Column {
                Text("오늘의 코디를 이 지역 날씨로 골라요. 여행지는 대화에서 \"내일 부산\"처럼 말해 주세요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    HomeRegion.OPTIONS.forEach { option ->
                        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                            .selectable(option.name == current, role = Role.RadioButton) { onPick(option.name) }
                            .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = option.name == current, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(option.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

private fun weatherLine(state: HomeState): String {
    val w = state.weather ?: return when {
        state.outfits.isNotEmpty() -> "날씨 정보 없이 18°C 기준으로 골랐어요"
        state.loading -> "날씨를 확인하고 있어요"
        else -> "" // no profile or an error: the card below explains
    }
    val parts = listOf("${String.format(Locale.KOREA, "%.0f", w.temp)}°C", w.label,
        if (kotlin.math.abs(w.apparent - w.temp) >= 2) "체감 ${String.format(Locale.KOREA, "%.0f", w.apparent)}°C" else null, w.band)
    return parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(title: String, text: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Button(onClick = onAction, shape = MaterialTheme.shapes.medium) { Text(action) }
    }
}

/** Loads a wardrobe photo while its card is visible. */
@Composable
fun GarmentThumbnail(path: String, name: String, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val account = com.swpp.stylemate.ui.LocalAccountAccess.current
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        if (bitmap != null) return@LaunchedEffect
        try {
            bitmap = WardrobeRepository.thumbnail(context, path, account)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
        failed = failed || bitmap == null
    }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
        Box(contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it, name, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit) }
                ?: if (failed) Text(name, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                   else CircularProgressIndicator(Modifier.size(24.dp))
        }
    }
}

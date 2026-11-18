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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
    photo: @Composable (OutfitGarment, Modifier) -> Unit = { item, m -> GarmentThumbnail(item.imageUrl, item.name, m) },
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
            val boardHeight = (maxHeight * 0.44f).coerceIn(180.dp, 300.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("오늘의 코디", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                    if (outfit != null) TextButton(onClick = onRefresh, enabled = ready) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("새로 추천")
                    }
                }
                WeatherLine(state, onRegion, Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp))
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
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                fitting?.invoke(outfit, Modifier.weight(1f), ready)
                                OutlinedButton(onClick = onSave, enabled = ready,
                                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).semantics {
                                        stateDescription = if (outfit.saved) "저장됨" else "저장 안 됨"
                                    }, shape = MaterialTheme.shapes.medium) {
                                    Icon(if (outfit.saved) Icons.Filled.Check else Icons.Filled.Add, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (outfit.saved) "저장됨" else "코디북에 저장")
                                }
                            }
                            onChat?.let { chat ->
                                OutlinedButton(onClick = chat, enabled = ready, shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp).heightIn(min = 48.dp)) {
                                    Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("대화로 수정하기")
                                }
                            }
                            OutfitFeedback(state, ready, onLike, onDislike)
                            OutfitInformation(outfit, state.notes)
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
    photo: @Composable (OutfitGarment, Modifier) -> Unit) {
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
            CompactOutfitBoard(state.outfits[page].items, photo)
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
        Text("${state.index + 1} / ${state.outfits.size}", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Tops and bottoms share one column beside outerwear; every garment remains visible and uncropped. */
@Composable
private fun CompactOutfitBoard(items: List<OutfitGarment>, photo: @Composable (OutfitGarment, Modifier) -> Unit) {
    val outerwear = items.firstOrNull { it.slot == "outerwear" }
    val other = items.filter { it !== outerwear }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (outerwear != null) photo(outerwear, Modifier.weight(1f).fillMaxHeight())
        if (other.isNotEmpty()) {
            if (outerwear == null && other.size <= 2) {
                other.forEach { photo(it, Modifier.weight(1f).fillMaxHeight()) }
            } else Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                other.forEach { photo(it, Modifier.weight(1f).fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun OutfitFeedback(state: HomeState, enabled: Boolean, onLike: () -> Unit, onDislike: () -> Unit) {
    val outfit = state.current ?: return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FeedbackButton("좋아요", outfit.feedback == "liked", enabled, onLike, Modifier.weight(1f))
        FeedbackButton("별로예요", outfit.feedback == "disliked", enabled, onDislike, Modifier.weight(1f), negative = true)
    }
}

@Composable
private fun FeedbackButton(label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, negative: Boolean = false) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        modifier = modifier.heightIn(min = 48.dp).semantics {
            selected = active
            stateDescription = if (active) "선택됨" else "선택 안 됨"
        }) {
        Icon(if (active) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, null,
            Modifier.size(18.dp).rotate(if (negative) 180f else 0f))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

@Composable
private fun OutfitInformation(outfit: Outfit, notes: List<String>) {
    val reasons = outfit.reasons.map { it.text.trim() }.filter { it.isNotEmpty() }.distinct()
    Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (reasons.isNotEmpty() || outfit.score != null) Row(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (reasons.isNotEmpty()) "이 코디를 고른 이유" else "추천 별점", Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            outfit.score?.let { StarRating(starsOf(it), size = 16.dp) }
        }
        reasons.forEachIndexed { index, reason ->
            Text(reason, style = if (index == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal,
                color = if (index == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
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

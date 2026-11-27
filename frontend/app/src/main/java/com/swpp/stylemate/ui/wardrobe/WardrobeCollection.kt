// AI-assisted wardrobe collection presentation, 2026-11-18.
package com.swpp.stylemate.ui.wardrobe

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.R
import com.swpp.stylemate.ui.components.PageHeader

internal const val ANALYZE_BUTTON_TEXT = "옷장 분석"

/** Account records stay in the existing route; this screen only owns browsing state. */
@Composable
internal fun WardrobeCollection(
    total: Int, items: List<WardrobeItem>, categories: List<Pair<String, String>>,
    category: String, onCategory: (String) -> Unit, message: String?, loading: Boolean,
    cameraBusy: Boolean, onRegister: () -> Unit, onRetry: (() -> Unit)?,
    onItemClick: (WardrobeItem) -> Unit, onDelete: ((WardrobeItem) -> Unit)?,
    deletingId: String?, onAnalyze: (() -> Unit)? = null,
    allItems: List<WardrobeItem> = items,
    photo: @Composable (WardrobeItem) -> Unit,
) {
    var grid by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    var sortByName by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val filtered = remember(items, query, sortByName) {
        items.filter { query.isBlank() || it.name.contains(query.trim(), true) ||
            it.style.orEmpty().contains(query.trim(), true) }
            .let { if (sortByName) it.sortedBy { garment -> garment.name } else it }
    }
    val selected = filtered.firstOrNull { it.id == selectedId } ?: filtered.firstOrNull()
    BackHandler(grid || searchOpen) { if (grid) grid = false else { searchOpen = false; query = "" } }
    if (filterOpen) {
        AlertDialog(onDismissRequest = { filterOpen = false }, title = { Text("옷장 필터") },
            text = { Column {
                Text("카테고리", style = MaterialTheme.typography.labelLarge)
                categories.forEach { (code, label) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(category == code,
                        role = Role.RadioButton, onClick = { onCategory(code) }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(category == code, onClick = null); Spacer(Modifier.width(8.dp)); Text(label)
                    }
                }
                HorizontalDivider(); Spacer(Modifier.height(12.dp)); Text("정렬", style = MaterialTheme.typography.labelLarge)
                listOf(false to "기존 순서", true to "이름순").forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(sortByName == value,
                        role = Role.RadioButton, onClick = { sortByName = value }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(sortByName == value, onClick = null); Spacer(Modifier.width(8.dp)); Text(label)
                    }
                }
            } }, confirmButton = { TextButton(onClick = { filterOpen = false }) { Text("적용") } })
    }
    if (grid) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { grid = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "옷장봉으로 돌아가기") }
                Text("전체 옷 보기", style = MaterialTheme.typography.titleMedium)
            }
            Box(Modifier.weight(1f)) {
                WardrobeGridCollection(total, filtered, categories, category, onCategory, message, loading,
                    cameraBusy, onRegister, onRetry, onItemClick, onDelete, deletingId, onAnalyze, photo)
            }
        }
        return
    }
    Scaffold(contentWindowInsets = WindowInsets(0), containerColor = WardrobeStyle.Canvas,
        bottomBar = { WardrobeRegistrationBar(cameraBusy, onRegister, onAnalyze, !loading && deletingId == null) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 20.dp)) {
            item(key = "header") {
                Column(Modifier.padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("옷장", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { heading() })
                        Spacer(Modifier.width(10.dp))
                        if (!(total == 0 && (loading || onRetry != null))) {
                            Surface(shape = RoundedCornerShape(50), color = Color(0xFFF3F2F0)) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    WardrobeHangerIcon(Modifier.size(15.dp)); Spacer(Modifier.width(5.dp))
                                    Text("총 ${total}벌", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                            Icon(if (searchOpen) Icons.Default.Close else Icons.Default.Search, if (searchOpen) "검색 닫기" else "옷 검색", Modifier.size(22.dp))
                        }
                        IconButton(onClick = { filterOpen = true }) { ClosetFilterIcon() }
                    }
                    Text("내 옷을 한눈에, 더 스마트하게", style = MaterialTheme.typography.bodySmall, color = WardrobeStyle.Muted,
                        modifier = Modifier.padding(top = 6.dp))
                    AnimatedVisibility(searchOpen) {
                        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                            label = { Text("이름 또는 스타일 검색") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "검색어 지우기") } })
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 18.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        categories.forEach { (code, label) -> WardrobeCategoryFilter(label, category == code) { onCategory(code) } }
                    }
                }
            }
            if (loading && total > 0) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = WardrobeStyle.Ink) }
            if (message != null && (total > 0 || onRetry == null)) item {
                Box(Modifier.padding(horizontal = 20.dp)) { WardrobeInlineError(message, loading, onRetry) }
            }
            item(key = "closet") {
                Column(Modifier.fillMaxWidth()) {
                    WardrobeCarousel(filtered, selectedId, { selectedId = it }, onItemClick,
                        enabled = deletingId == null, modifier = Modifier.fillMaxWidth().height(300.dp))
                    if (selected != null) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).animateContentSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(selected.name, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            Text(listOfNotNull(categories.firstOrNull { it.first == selected.category }?.second,
                                selected.style?.takeIf { it.isNotBlank() }).joinToString(" · "),
                                Modifier.padding(top = 5.dp), style = MaterialTheme.typography.bodySmall, color = WardrobeStyle.Muted)
                            val index = filtered.indexOfFirst { it.id == selected.id }.coerceAtLeast(0)
                            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.width(80.dp).height(2.dp).background(Color(0xFFE5E2DE))) {
                                    Box(Modifier.fillMaxWidth((index + 1f) / filtered.size).fillMaxHeight().background(WardrobeStyle.Ink))
                                }
                                Text("${index + 1} / ${filtered.size}", style = MaterialTheme.typography.labelSmall, color = WardrobeStyle.Muted)
                            }
                            if (filtered.size > 1) Text("좌우로 넘겨보고, 가운데 옷을 눌러 자세히 보세요", Modifier.padding(top = 10.dp),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = WardrobeStyle.Muted)
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (loading) CircularProgressIndicator(Modifier.size(24.dp), color = WardrobeStyle.Ink, strokeWidth = 2.dp)
                            Text(when { loading -> "옷장을 불러오고 있어요"; onRetry != null && total == 0 -> "옷장을 불러오지 못했어요"
                                query.isNotBlank() -> "검색 결과가 없어요"; total == 0 -> "첫 번째 옷을 걸어보세요"; else -> "이 카테고리는 아직 비어 있어요" },
                                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                            // A failed first load says why (the server's or network message), not that the closet is empty.
                            Text(when { !loading && onRetry != null && total == 0 && message != null -> message
                                total == 0 -> "등록한 옷으로 나만의 옷장을 채워보세요"; else -> "다른 카테고리나 검색어를 선택해 보세요" },
                                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = WardrobeStyle.Muted)
                            if (onRetry != null) WardrobeRetryButton(!loading, onRetry)
                        }
                    }
                }
            }
            item(key = "overview") {
                Column(Modifier.padding(top = 24.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("내 옷장 한눈에 보기", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f).semantics { heading() })
                        TextButton(onClick = { query = ""; onCategory("all"); grid = true }) { Text("전체 보기", style = MaterialTheme.typography.labelMedium) }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(categories.filter { it.first != "all" }, key = { it.first }) { (code, label) ->
                            val garments = allItems.filter { it.category == code }
                            Surface(onClick = { onCategory(code); query = "" }, shape = RoundedCornerShape(12.dp),
                                color = Color(0xFFF5F4F2), modifier = Modifier.width(120.dp)) {
                                Column(Modifier.padding(12.dp)) {
                                    Box(Modifier.fillMaxWidth().height(68.dp), contentAlignment = Alignment.Center) {
                                        garments.firstOrNull()?.let { HangingGarmentImage(it, Modifier.fillMaxSize()) }
                                            ?: WardrobeHangerIcon(Modifier.size(32.dp), Color(0xFFB7AEA1))
                                    }
                                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                        Text("${garments.size}벌", style = MaterialTheme.typography.bodySmall, color = WardrobeStyle.Muted)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClosetFilterIcon() {
    androidx.compose.foundation.Canvas(Modifier.size(22.dp).semantics { contentDescription = "필터 및 정렬" }) {
        val ink = Color(0xFF1C1C1C)
        listOf(.25f to .35f, .5f to .7f, .75f to .45f).forEach { (y, x) ->
            drawLine(ink, androidx.compose.ui.geometry.Offset(0f, size.height*y), androidx.compose.ui.geometry.Offset(size.width, size.height*y), 1.4.dp.toPx())
            drawCircle(Color.White, 3.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width*x, size.height*y))
            drawCircle(ink, 3.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width*x, size.height*y), style = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx()))
        }
    }
}

/** Presentation only: filtering, confirmation and all service state stay in WardrobeScreen/Route. */
@Composable
private fun WardrobeGridCollection(
    total: Int,
    items: List<WardrobeItem>,
    categories: List<Pair<String, String>>,
    category: String,
    onCategory: (String) -> Unit,
    message: String?,
    loading: Boolean,
    cameraBusy: Boolean,
    onRegister: () -> Unit,
    onRetry: (() -> Unit)?,
    onItemClick: (WardrobeItem) -> Unit,
    onDelete: ((WardrobeItem) -> Unit)?,
    deletingId: String?,
    onAnalyze: (() -> Unit)? = null,
    photo: @Composable (WardrobeItem) -> Unit,
) {
    Scaffold(
        // MainTabs owns system-bar and shared navigation insets.
        contentWindowInsets = WindowInsets(0),
        containerColor = WardrobeStyle.Canvas,
        bottomBar = { WardrobeRegistrationBar(cameraBusy, onRegister, onAnalyze, !loading && deletingId == null) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val fontScale = LocalDensity.current.fontScale
            val columns = when {
                maxWidth < 300.dp || (fontScale >= 1.6f && maxWidth < 600.dp) -> 1
                maxWidth >= 600.dp -> 3
                else -> 2
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = WardrobeStyle.PageInset, vertical = 0.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        PageHeader("옷장", horizontalPadding = 0.dp,
                            subtitle = if (total == 0 && (loading || onRetry != null)) null else "총 ${total}벌")
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            categories.forEach { (code, label) ->
                                WardrobeCategoryFilter(label, category == code) { onCategory(code) }
                            }
                        }
                        HorizontalDivider(color = WardrobeStyle.Border)
                        if (loading && total > 0) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "옷장 업데이트 중" },
                                color = WardrobeStyle.Ink, trackColor = WardrobeStyle.Photo)
                        }
                        if (message != null && (total > 0 || onRetry == null)) {
                            WardrobeInlineError(message, loading, onRetry)
                        }
                    }
                }
                if (items.isEmpty()) {
                    item(key = "state", span = { GridItemSpan(maxLineSpan) }) {
                        when {
                            loading -> WardrobeCollectionState("옷장을 불러오고 있어요", loading = true)
                            onRetry != null && total == 0 -> WardrobeCollectionState(
                                "옷장을 불러오지 못했어요", message ?: "잠시 후 다시 시도해 주세요.",
                                onRetry = onRetry)
                            else -> WardrobeCollectionState(
                                if (total == 0) "아직 등록된 옷이 없습니다" else "이 카테고리에 등록된 옷이 없습니다",
                                if (total == 0) "등록하기를 눌러 옷을 추가해 보세요." else "다른 카테고리를 선택하거나 옷을 추가해 보세요.")
                        }
                    }
                } else {
                    items(items, key = { it.id }) { garment ->
                        WardrobeGarmentCard(garment, categories.firstOrNull { it.first == garment.category }?.second,
                            enabled = deletingId == null,
                            deleteEnabled = !loading && deletingId == null,
                            onClick = { onItemClick(garment) },
                            onDelete = onDelete?.let { { it(garment) } }, photo = { photo(garment) })
                    }
                }
            }
        }
    }
}

@Composable
private fun WardrobeCategoryFilter(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(Modifier.heightIn(min = 48.dp).selectable(
        selected = selected, role = Role.Tab, interactionSource = interaction,
        indication = LocalIndication.current, onClick = onClick), contentAlignment = Alignment.Center) {
        Surface(
            shape = WardrobeStyle.FilterShape,
            color = when { selected -> WardrobeStyle.Ink; pressed -> WardrobeStyle.Pressed; else -> WardrobeStyle.Photo },
            contentColor = if (selected) WardrobeStyle.Canvas else WardrobeStyle.Muted,
            border = if (focused) BorderStroke(2.dp, WardrobeStyle.Focus) else null,
        ) {
            Text(label, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun WardrobeGarmentCard(
    garment: WardrobeItem,
    categoryLabel: String?,
    enabled: Boolean,
    deleteEnabled: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
    photo: @Composable () -> Unit,
) {
    // Flat card: its image container and two-line name define the grid, rather than a repeated border.
    Surface(onClick = onClick, enabled = enabled, shape = WardrobeStyle.ImageShape,
        color = WardrobeStyle.Canvas, contentColor = WardrobeStyle.Ink) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(WardrobeStyle.ImageShape)
                .background(WardrobeStyle.Photo), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                    when {
                        garment.thumbnail != null -> Image(garment.thumbnail, garment.name,
                            Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        garment.imageUrl != null -> photo()
                        else -> Text("사진 없음", style = MaterialTheme.typography.bodySmall, color = WardrobeStyle.Muted)
                    }
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete, enabled = deleteEnabled,
                        modifier = Modifier.align(Alignment.TopEnd).size(48.dp)) {
                        Surface(shape = WardrobeStyle.ImageShape, color = WardrobeStyle.Canvas,
                            border = BorderStroke(1.dp, WardrobeStyle.Border)) {
                            Icon(painterResource(R.drawable.ic_remove_garment), "${garment.name} 삭제",
                                Modifier.padding(6.dp).size(18.dp),
                                tint = if (deleteEnabled) WardrobeStyle.Ink else WardrobeStyle.DisabledInk)
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(garment.name, style = MaterialTheme.typography.labelLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(categoryLabel.orEmpty(),
                    style = MaterialTheme.typography.bodySmall, color = WardrobeStyle.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun WardrobeInlineError(message: String, loading: Boolean, onRetry: (() -> Unit)?) {
    Surface(color = WardrobeStyle.ErrorBackground, shape = WardrobeStyle.ImageShape) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = WardrobeStyle.Error, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (onRetry != null) WardrobeRetryButton(!loading, onRetry)
        }
    }
}

@Composable
private fun WardrobeCollectionState(title: String, description: String? = null,
                                    loading: Boolean = false, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().heightIn(min = 280.dp).padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp,
            color = WardrobeStyle.Ink, trackColor = WardrobeStyle.Photo)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (description != null) Text(description, style = MaterialTheme.typography.bodyLarge,
            color = WardrobeStyle.Muted, textAlign = TextAlign.Center)
        if (onRetry != null) WardrobeRetryButton(true, onRetry)
    }
}

@Composable
private fun WardrobeRetryButton(enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = WardrobeStyle.ImageShape,
        border = BorderStroke(1.dp, WardrobeStyle.Border), modifier = Modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = WardrobeStyle.Ink,
            disabledContentColor = WardrobeStyle.DisabledInk)) {
        Text("다시 불러오기", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun WardrobeRegistrationBar(busy: Boolean, onClick: () -> Unit, onAnalyze: (() -> Unit)?, analysisEnabled: Boolean) {
    Surface(color = WardrobeStyle.Canvas) {
        Column {
            HorizontalDivider(color = WardrobeStyle.Border)
            Row(Modifier.fillMaxWidth().padding(horizontal = WardrobeStyle.PageInset, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onAnalyze != null) OutlinedButton(onClick = onAnalyze, enabled = analysisEnabled && !busy,
                    shape = WardrobeStyle.ImageShape, contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(.4f).heightIn(min = 52.dp)) {
                    val ink = LocalContentColor.current
                    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
                        listOf(.45f, .75f, .58f).forEachIndexed { index, height ->
                            val x = size.width * (.2f + index * .3f)
                            drawLine(ink, androidx.compose.ui.geometry.Offset(x, size.height * .85f),
                                androidx.compose.ui.geometry.Offset(x, size.height * (1f - height)),
                                2.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(ANALYZE_BUTTON_TEXT)
                }
                Button(onClick = onClick, enabled = !busy, shape = WardrobeStyle.ImageShape,
                    modifier = Modifier.weight(.6f).heightIn(min = 52.dp), contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WardrobeStyle.Ink, contentColor = WardrobeStyle.Canvas,
                        disabledContainerColor = WardrobeStyle.Disabled, disabledContentColor = WardrobeStyle.DisabledInk)) {
                    Icon(Icons.Default.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                    Text(if (busy) "촬영 중" else "등록하기", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

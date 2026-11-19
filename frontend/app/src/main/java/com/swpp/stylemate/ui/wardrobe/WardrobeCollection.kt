// AI-assisted wardrobe collection presentation, 2026-11-18.
package com.swpp.stylemate.ui.wardrobe

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

/** Presentation only: filtering, confirmation and all service state stay in WardrobeScreen/Route. */
@Composable
internal fun WardrobeCollection(
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
                    modifier = Modifier.weight(.4f).heightIn(min = 52.dp)) { Text(ANALYZE_BUTTON_TEXT) }
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

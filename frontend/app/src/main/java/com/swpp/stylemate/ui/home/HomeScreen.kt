// AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitRepository
import com.swpp.stylemate.data.recommendation.OutfitGarment
import com.swpp.stylemate.data.wardrobe.WardrobeRepository
import kotlinx.coroutines.CancellationException
import java.util.Locale

private val SLOT_WIDTH = mapOf("outerwear" to 0.82f, "top" to 0.72f, "bottom" to 0.62f)

/**
 * 오늘의 코디 (mock-up p.3). [onChat] opens the chat editor for the current outfit.
 */
@Composable
fun HomeScreen(
    state: HomeState,
    onRefresh: () -> Unit,
    onNext: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onSave: () -> Unit,
    onSetupProfile: () -> Unit,
    onOpenWardrobe: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
    onChat: (() -> Unit)? = null,
    photo: @Composable (OutfitGarment, Modifier) -> Unit = { item, m -> GarmentThumbnail(item.imageUrl, item.name, m) },
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); onMessageShown() }
    }
    val outfit = state.current
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (outfit != null) Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onChat ?: onNext, enabled = !state.loading, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Text(if (onChat != null) "대화로 수정하기" else "다른 코디 보기", fontWeight = FontWeight.Bold)
                }
                if (onChat != null) {
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = onNext, enabled = !state.loading, shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.size(56.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = "다른 코디")
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("오늘의 코디", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                IconButton(onClick = onRefresh, enabled = !state.loading && state.errorCode != HomeState.NO_PROFILE) {
                    Icon(Icons.Filled.Refresh, contentDescription = "새로 고침")
                }
            }
            Text(weatherLine(state), Modifier.padding(top = 4.dp, bottom = 16.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                state.errorCode == HomeState.NO_PROFILE -> EmptyState("체형 정보가 필요해요",
                    "체형 분석을 하면 내 몸에 맞는 옷으로 코디를 골라 드려요.", "체형 분석하기", onSetupProfile)
                state.errorCode == "WARDROBE_TOO_SMALL" -> EmptyState("옷을 조금 더 등록해 주세요",
                    state.error ?: "", "옷장으로 가기", onOpenWardrobe)
                outfit == null && state.loading -> OutfitCard { CircularProgressIndicator(Modifier.padding(64.dp)) }
                outfit == null -> EmptyState("코디를 불러오지 못했어요", state.error ?: OutfitRepository.SERVER_ERROR,
                    "다시 시도", onRefresh)
                else -> OutfitDetails(state, outfit, onLike, onDislike, onSave, photo)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun weatherLine(state: HomeState): String {
    val w = state.weather ?: return when {
        state.outfits.isNotEmpty() -> "날씨 정보 없이 18°C 기준으로 골랐어요"
        state.loading -> "오늘 날씨를 확인하고 있어요"
        else -> "" // no profile or an error: the card below explains
    }
    val parts = listOf("서울 ${String.format(Locale.KOREA, "%.0f", w.temp)}°C", w.label,
        if (kotlin.math.abs(w.apparent - w.temp) >= 2) "체감 ${String.format(Locale.KOREA, "%.0f", w.apparent)}°C" else null, w.band)
    return parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutfitDetails(state: HomeState, outfit: Outfit, onLike: () -> Unit, onDislike: () -> Unit, onSave: () -> Unit,
                          photo: @Composable (OutfitGarment, Modifier) -> Unit) {
    OutfitCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            outfit.items.forEach { item ->
                photo(item, Modifier.fillMaxWidth(SLOT_WIDTH[item.slot] ?: 0.7f).height(150.dp).clip(RoundedCornerShape(16.dp)))
            }
        }
        if (state.outfits.size > 1) Text("${state.index + 1} / ${state.outfits.size}",
            Modifier.align(Alignment.TopEnd).padding(14.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center) {
        Pill(Icons.Filled.Check, "내 옷장에서 100% 구성")
        outfit.score?.let { Spacer(Modifier.width(8.dp)); Pill(null, "어울림 ${it}점", muted = true) }
    }
    FlowRow(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        outfit.items.forEach { item ->
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Text(item.name, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onLike, enabled = !state.acting) {
            Icon(if (outfit.feedback == "liked") Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
            Text(if (outfit.feedback == "liked") "좋아요 취소" else "좋아요")
        }
        TextButton(onClick = onDislike, enabled = !state.acting) {
            Text(if (outfit.feedback == "disliked") "별로예요 취소" else "별로예요", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onSave, enabled = !state.acting) {
            Icon(Icons.Filled.Star, contentDescription = null,
                tint = if (outfit.saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.width(4.dp))
            Text(if (outfit.saved) "저장됨" else "코디북에 저장")
        }
    }
    if (outfit.reasons.isNotEmpty()) {
        Text("이 코디를 고른 이유", Modifier.padding(top = 12.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold)
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                outfit.reasons.take(3).forEach { reason ->
                    Column {
                        Text(reason.text, style = MaterialTheme.typography.bodyMedium)
                        if (reason.sub.isNotBlank()) Text(reason.sub, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                outfit.items.filter { it.note.contains("내 몸 기준") }.forEach { item ->
                    Text("${item.name}: ${item.note.substringAfter("내 옷 · ")}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    state.notes.forEach { note ->
        Text(note, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OutfitCard(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 280.dp), contentAlignment = Alignment.Center, content = content)
    }
}

@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector?, text: String, muted: Boolean = false) {
    Surface(shape = RoundedCornerShape(50),
        color = if (muted) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (muted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EmptyState(title: String, text: String, action: String, onAction: () -> Unit) {
    OutfitCard {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction, shape = RoundedCornerShape(16.dp)) { Text(action, fontWeight = FontWeight.Bold) }
        }
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

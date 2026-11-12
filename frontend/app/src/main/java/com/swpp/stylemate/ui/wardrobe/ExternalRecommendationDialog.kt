package com.swpp.stylemate.ui.wardrobe

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.swpp.stylemate.data.recommendation.WardrobeMatch
import com.swpp.stylemate.ui.home.GarmentThumbnail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun WardrobeItemActionsDialog(item: WardrobeItem, onEdit: () -> Unit, onRecommend: () -> Unit,
                              onDismiss: () -> Unit) {
    val supported = item.category in setOf("top", "bottom", "outerwear")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("옷 정보 수정하기") }
                Button(onClick = onRecommend, enabled = supported, modifier = Modifier.fillMaxWidth()) {
                    Text("어울리는 옷 찾기")
                }
                if (!supported) Text("신발은 아직 추천을 지원하지 않아요.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
fun ExternalRecommendationDialog(state: ExternalMatchState, onRetry: () -> Unit,
                                 onOpenLink: (String) -> Unit, onDismiss: () -> Unit,
                                 wardrobe: List<WardrobeItem> = emptyList()) {
    val result = state.result
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (result != null) "어울리는 ${slotLabel(result.targetSlot)}" else "어울리는 옷 찾기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (state.loading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("AI가 어울림 점수 분석 중...")
                }
                if (result != null) {
                    // What to look for first, as words the user can search with
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(result.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(result.headline, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text("내 옷장에서", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (result.wardrobeMatches.isEmpty()) {
                        Text("옷장에 어울리는 ${slotLabel(result.targetSlot)}가 아직 없어요.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        result.wardrobeMatches.take(3).forEachIndexed { index, match ->
                            MatchCard(index + 1, match, wardrobe.firstOrNull { it.id == match.garmentId }, Modifier.weight(1f))
                        }
                        repeat(3 - result.wardrobeMatches.take(3).size) { Spacer(Modifier.weight(1f)) }
                    }
                    Button(onClick = { onOpenLink(result.searchUrl) }, modifier = Modifier.fillMaxWidth()) {
                        Text("무신사에서 비슷한 옷 찾기")
                    }
                    Text(result.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (state.error != null) TextButton(onClick = onRetry) { Text("다시 시도") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

/** One owned garment the model ranked as a match, with its photo when the wardrobe list has it. */
@Composable
private fun MatchCard(rank: Int, match: WardrobeMatch, item: WardrobeItem?, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val photo = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))
            when {
                item?.thumbnail != null -> Image(item.thumbnail, match.name, photo, contentScale = ContentScale.Fit)
                item?.imageUrl != null -> GarmentThumbnail(item.imageUrl, match.name, photo)
                else -> Box(photo, contentAlignment = Alignment.Center) { Text("$rank", style = MaterialTheme.typography.titleMedium) }
            }
            Text("$rank. ${match.name}", style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun slotLabel(targetSlot: String) = if (targetSlot == "bottom") "하의" else "상의"

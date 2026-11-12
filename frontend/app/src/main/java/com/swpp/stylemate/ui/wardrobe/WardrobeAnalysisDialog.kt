// AI-generated with Claude Code (Claude Opus 5.5), 2026-11-12, reviewed by Kihwan Kim
package com.swpp.stylemate.ui.wardrobe

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.data.recommendation.GapGarment
import com.swpp.stylemate.data.recommendation.WardrobeAnalysis
import com.swpp.stylemate.data.recommendation.WardrobeGap
import com.swpp.stylemate.data.wardrobe.WardrobeRepository
import com.swpp.stylemate.ui.LocalAccountAccess

const val ANALYSIS_LOADING_TEXT = "AI가 내 옷장을 분석하고 있어요..."
const val ANALYSIS_ENOUGH_TEXT = "지금 옷장으로도 충분히 코디할 수 있어요! 새로 살 옷은 없어요."
const val ANALYSIS_TOO_FEW_TEXT = "상의와 하의를 한 벌 이상씩, 합쳐서 4벌 이상 등록하면 옷장을 분석할 수 있어요."
const val ANALYSIS_SECTION_TEXT = "이런 옷이 있으면 좋아요"
const val ANALYSIS_LINK_TEXT = "무신사에서 보기"
private const val MAX_THUMBNAILS = 3

/**
 * 옷장 분석 팝업: 로딩 → 결과(추천 카드 최대 3개 / 살 옷 없음 / 옷 부족) 또는 에러.
 * 썸네일은 [thumbnail]로 그린다. 기본값은 서버에서 옷 사진을 불러오고, 테스트는 빈 칸을 넘긴다.
 */
@Composable
fun WardrobeAnalysisDialog(
    state: WardrobeAnalysisState,
    onRetry: () -> Unit,
    onOpenLink: (String) -> Unit,
    onDismiss: () -> Unit,
    thumbnail: @Composable (GapGarment) -> Unit = { GapGarmentPhoto(it) },
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.result != null) "옷장 분석 결과" else "옷장 분석") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text(ANALYSIS_LOADING_TEXT)
                }
                state.result?.let { AnalysisResult(it, onOpenLink, thumbnail) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (state.error != null) TextButton(onClick = onRetry) { Text("다시 시도") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun AnalysisResult(result: WardrobeAnalysis, onOpenLink: (String) -> Unit,
                           thumbnail: @Composable (GapGarment) -> Unit) {
    if (result.status == WardrobeAnalysis.TOO_FEW) {
        Text(ANALYSIS_TOO_FEW_TEXT, style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("옷 ${result.garmentCount}벌을 분석했어요", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        Text(slotSummary(result.slotCounts), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (result.status == WardrobeAnalysis.ENOUGH) {
        Text(ANALYSIS_ENOUGH_TEXT, style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text(ANALYSIS_SECTION_TEXT, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    result.gaps.forEachIndexed { index, gap -> GapCard(index, gap, onOpenLink, thumbnail) }
}

@Composable
private fun GapCard(index: Int, gap: WardrobeGap, onOpenLink: (String) -> Unit,
                    thumbnail: @Composable (GapGarment) -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${index + 1}. ${gap.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(gap.reason, style = MaterialTheme.typography.bodyMedium)
            // 근거가 된 내 옷: 사진 최대 3장과 나머지 수
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                gap.matchedGarments.take(MAX_THUMBNAILS).forEach { garment ->
                    Surface(Modifier.size(48.dp), shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface) { thumbnail(garment) }
                }
                val more = gap.matchedGarments.size - MAX_THUMBNAILS
                if (more > 0) Text("+$more", style = MaterialTheme.typography.labelLarge)
            }
            Text(matchedNames(gap.matchedGarments), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { onOpenLink(gap.searchUrl) }, modifier = Modifier.fillMaxWidth()) {
                Text(ANALYSIS_LINK_TEXT)
            }
        }
    }
}

/** "상의 9 · 하의 8 · 아우터 6" */
fun slotSummary(counts: Map<String, Int>): String =
    listOf("top" to "상의", "bottom" to "하의", "outerwear" to "아우터")
        .joinToString(" · ") { (code, label) -> "$label ${counts[code] ?: 0}" }

/** "어울리는 내 옷: 청바지, 검정 바지, 코트 외 1벌" (이름 끝 받침에 따라 조사가 바뀌지 않게 나열형으로 쓴다) */
fun matchedNames(garments: List<GapGarment>): String {
    val names = garments.take(MAX_THUMBNAILS).joinToString(", ") { it.name }
    val more = garments.size - MAX_THUMBNAILS
    return "어울리는 내 옷: $names" + if (more > 0) " 외 ${more}벌" else ""
}

/** 서버의 옷 사진 썸네일 (옷장 목록과 같은 방식으로 불러온다). */
@Composable
private fun GapGarmentPhoto(garment: GapGarment) {
    val context = LocalContext.current.applicationContext
    val account = LocalAccountAccess.current ?: return
    var bitmap by remember(garment.imageUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(garment.imageUrl) {
        bitmap = try { WardrobeRepository.thumbnail(context, garment.imageUrl, account) }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { null }
    }
    Box(Modifier.fillMaxSize().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, garment.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

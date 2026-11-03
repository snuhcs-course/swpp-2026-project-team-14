package com.swpp.stylemate.ui.wardrobe

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.data.AccountAccess
import com.swpp.stylemate.data.wardrobe.WardrobeRepository
import com.swpp.stylemate.ui.components.ScreenScaffold
import kotlinx.coroutines.CancellationException

internal fun generationErrorMessage(code: String): String = when (code) {
    "GENERATION_RATE_LIMITED" -> "이미지 생성 할당량이 초과됐습니다. 원본 크롭을 선택하거나 나중에 다시 시도해 주세요."
    "GENERATION_CREDITS_REQUIRED" -> "이미지 생성 크레딧이 부족합니다."
    "GENERATION_TIMEOUT", "GENERATION_INTERRUPTED" -> "생성이 시간 안에 끝나지 않았습니다. 재생성은 추가 요청으로 처리됩니다."
    "SEGMENTATION_NOT_CONFIGURED", "SEGMENTATION_MODEL_INVALID" -> "서버의 옷 분리 모델 설정이 필요합니다."
    "IMAGE_TARGET_UNCLEAR", "SEGMENTATION_FAILED" -> "선택 영역에서 옷을 분리하지 못했습니다. 다른 사진을 선택해 주세요."
    "GENERATION_NOT_CONFIGURED", "GENERATION_AUTH_FAILED" -> "서버의 이미지 생성 API 설정을 확인해 주세요."
    else -> "펼친 이미지를 생성하지 못했습니다. 다른 사진이나 원본 크롭을 선택할 수 있어요."
}

@Composable
internal fun WornGenerationReview(state: WornGenerationState, account: AccountAccess,
                                  model: WornGenerationViewModel, onBack: () -> Unit, onNewPhoto: () -> Unit) {
    val generation = state.generation
    val id = checkNotNull(state.draft).getString("id")
    val status = generation?.optString("status")
    val rejected = generation?.optString("decision") == "rejected"
    var confirmation by remember { mutableStateOf<String?>(null) }
    val ready = status == "ready" && !rejected && !state.busy
    ScreenScaffold(title = "생성 결과 확인", onBack = onBack, navigationEnabled = !state.busy,
        primaryLabel = if (ready) "생성 이미지 사용" else null, primaryEnabled = ready,
        onPrimary = { model.review("generated") }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("원본과 비교하고 사용할 이미지를 선택해 주세요.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ComparisonPhoto("원본 대상 영역", "/api/wardrobe/items/$id/worn-image/original/", account,
                    "original", Modifier.weight(1f))
                val generatedUrl = generation?.optString("generated_url")?.takeIf { it.startsWith("/api/wardrobe/") }
                ComparisonPhoto("AI가 펼친 이미지", generatedUrl, account,
                    generation?.optString("request_id").orEmpty(), Modifier.weight(1f))
            }
            Text("생성 이미지의 가려진 부분·주머니·단추는 실제 옷과 다를 수 있어요. 옷 정보는 원본에서 보이는 부분만 분석했어요.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (generation?.optBoolean("occlusion_warning", true) != false) {
                Text("가방이나 다른 옷에 많이 가려졌다면 다른 각도의 사진을 권장해요.", color = MaterialTheme.colorScheme.primary)
            }
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.progress.ifBlank { "선택한 결과를 확인 중" })
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            generation?.optString("error")?.takeIf { it.isNotBlank() && it != "null" }?.let {
                Text(generationErrorMessage(it), color = MaterialTheme.colorScheme.error)
            }
            if (!state.busy) {
                if (status == "running" || state.error != null || generation == null) {
                    OutlinedButton(onClick = model::refresh) { Text("결과 확인 · 추가 생성 없음") }
                }
                if (ready) OutlinedButton(onClick = { model.review("reject") }) { Text("생성 결과 거절") }
                if (rejected) Text("생성 결과를 사용하지 않습니다. 다른 사진이나 원본 크롭을 선택해 주세요.")
                if (status != "running") {
                    OutlinedButton(onClick = { confirmation = "regenerate" }) { Text("다시 생성 · 추가 요청") }
                    OutlinedButton(onClick = onNewPhoto) { Text("다른 사진 선택") }
                    TextButton(onClick = { confirmation = "crop" }) { Text("원본 크롭으로 등록") }
                }
            }
        }
    }
    confirmation?.let { action ->
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(if (action == "crop") "원본 크롭으로 등록할까요?" else "추가로 생성할까요?") },
            text = { Text(if (action == "crop") "선택한 착용 사진의 일부를 저장합니다. 사람이나 가방이 남을 수 있고 펼친 옷 사진은 아닙니다."
                else "새 이미지 생성 API 요청이 1회 발생하며 추가 비용이 들 수 있어요. 기존 결과 확인은 추가 생성 요청을 하지 않습니다.") },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                if (action == "crop") model.review("crop") else model.regenerate()
            }) { Text(if (action == "crop") "원본 크롭 사용" else "추가 생성 요청") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("취소") } })
    }
}

@Composable
internal fun ComparisonPhoto(label: String, path: String?, account: AccountAccess, requestId: String, modifier: Modifier,
                             contentScale: ContentScale = ContentScale.Fit, framed: Boolean = true) {
    val context = LocalContext.current
    var bitmap by remember(path, requestId) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(path, requestId) { mutableStateOf(false) }
    LaunchedEffect(path, requestId) {
        if (path != null) try { bitmap = WardrobeRepository.thumbnail(context, path, account) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (framed) Text(label, style = MaterialTheme.typography.titleSmall)
        Surface(if (framed) Modifier.fillMaxWidth().aspectRatio(.7f) else Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(contentAlignment = Alignment.Center) {
                bitmap?.let { Image(it, label, Modifier.fillMaxSize(), contentScale = contentScale) }
                    ?: if (path != null && !error) CircularProgressIndicator(Modifier.size(24.dp))
                    else Text(if (error) "사진을 불러오지 못했어요" else "생성 이미지 없음")
            }
        }
    }
}

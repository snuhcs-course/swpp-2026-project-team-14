// Manual fallback when automatic garment proposals are unavailable or incorrect.
package com.swpp.stylemate.ui.wardrobe

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.data.AccountAccess
import com.swpp.stylemate.data.wardrobe.CaptureFiles
import com.swpp.stylemate.data.wardrobe.requestAnalysis
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.swpp.stylemate.ui.components.ScreenScaffold
import com.swpp.stylemate.ui.profile.decodeDownscaled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Rectangles are expressed in the displayed, EXIF-oriented bitmap's coordinates. */
internal fun cropWornPhoto(bitmap: Bitmap, horizontal: ClosedFloatingPointRange<Float>,
                           vertical: ClosedFloatingPointRange<Float>): Bitmap {
    require(horizontal.start >= 0f && horizontal.endInclusive <= 1f &&
        vertical.start >= 0f && vertical.endInclusive <= 1f)
    val left = (horizontal.start * bitmap.width).toInt()
    val top = (vertical.start * bitmap.height).toInt()
    val right = (horizontal.endInclusive * bitmap.width).toInt()
    val bottom = (vertical.endInclusive * bitmap.height).toInt()
    require(right - left >= 32 && bottom - top >= 32) { "옷 영역이 너무 작습니다. 넓게 선택해 주세요." }
    return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualWornRegistration(account: AccountAccess, files: CaptureFiles, onBack: () -> Unit,
                     onComplete: (String, JSONObject) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var crop by remember { mutableStateOf<Bitmap?>(null) }
    var horizontal by remember { mutableStateOf(0f..1f) }
    var vertical by remember { mutableStateOf(0f..1f) }
    var adjusted by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf<String?>(null) }
    var photoBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val model: WornGenerationViewModel = viewModel(key = "worn-generation", factory = viewModelFactory {
        initializer { WornGenerationViewModel(account, files) }
    })
    val generation by model.state.collectAsStateWithLifecycle()
    val busy = photoBusy || generation.busy
    LaunchedEffect(generation.approved) {
        generation.approved?.let { record ->
            onComplete(checkNotNull(generation.photo), record)
            model.transferred()
        }
    }
    BackHandler { if (!busy) {
        if (generation.draft != null) { model.reset(); onBack() }
        else if (crop != null) { model.reset(); crop = null } else { model.reset(); onBack() }
    } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            photoBusy = true
            try {
                original = decodeDownscaled(context, uri, maxSide = 1536)
                model.reset()
                crop = null; adjusted = false; horizontal = 0f..1f; vertical = 0f..1f
                error = if (original == null) "사진을 읽을 수 없습니다. 다른 사진을 골라 주세요." else null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "사진을 읽을 수 없습니다. 다른 사진을 골라 주세요." }
            finally { photoBusy = false }
        }
    }
    if (generation.draft != null) {
        WornGenerationReview(generation, account, model, onBack = { model.reset(); onBack() },
            onNewPhoto = {
                model.reset(); original = null; crop = null
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
        return
    }
    ScreenScaffold(title = "착용 사진으로 등록", onBack = { model.reset(); if (crop != null) crop = null else onBack() },
        navigationEnabled = !busy,
        primaryLabel = if (busy) "처리 중" else if (crop != null) "분석·펼친 이미지 생성" else "선택 영역 확인",
        primaryEnabled = !busy && category != null && original != null && adjusted,
        onPrimary = {
            if (crop == null) {
                try { crop = cropWornPhoto(checkNotNull(original), horizontal, vertical); error = null }
                catch (failure: IllegalArgumentException) { error = failure.message }
            } else model.start(context, checkNotNull(crop), checkNotNull(category))
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("한 사람의 옷 한 벌만 등록해요. 가방과 다른 옷을 최대한 제외하고 옷 전체가 보이는 영역을 골라 주세요.")
            OutlinedButton(enabled = !busy, onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("사진 선택") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("top" to "상의", "bottom" to "하의", "outerwear" to "아우터").forEach { (code, title) ->
                    FilterChip(selected = category == code, enabled = !busy && crop == null,
                        onClick = { category = code }, label = { Text(title) })
                }
            }
            val shown = crop ?: original
            if (shown != null) {
                val outline = MaterialTheme.colorScheme.primary
                Box(Modifier.fillMaxWidth().aspectRatio(shown.width.toFloat() / shown.height)) {
                    Image(shown.asImageBitmap(), "착용 사진에서 선택한 옷 영역", Modifier.fillMaxSize())
                    if (crop == null) Canvas(Modifier.fillMaxSize().pointerInput(shown, busy) {
                        if (!busy) {
                            var start = Offset.Zero
                            detectDragGestures(onDragStart = { start = it; adjusted = false },
                                onDragEnd = { adjusted = true }) { change, _ ->
                                change.consume()
                                val end = change.position
                                horizontal = (minOf(start.x, end.x) / size.width).coerceIn(0f, 1f)..
                                    (maxOf(start.x, end.x) / size.width).coerceIn(0f, 1f)
                                vertical = (minOf(start.y, end.y) / size.height).coerceIn(0f, 1f)..
                                    (maxOf(start.y, end.y) / size.height).coerceIn(0f, 1f)
                            }
                        }
                    }) {
                        drawRect(outline, Offset(horizontal.start * size.width, vertical.start * size.height),
                            Size((horizontal.endInclusive - horizontal.start) * size.width,
                                (vertical.endInclusive - vertical.start) * size.height), style = Stroke(4.dp.toPx()))
                    }
                }
                if (crop == null) {
                    Text("사진 위에서 옷의 한쪽 모서리부터 반대쪽 모서리까지 드래그해 주세요.")
                } else {
                    Text("착용 사진의 일부입니다. 펼친 옷 사진이 아니며, 가려진 부분은 복원하지 않아요.")
                    Text("펼친 이미지를 생성하면 API 사용료가 발생해요. 원본과 비교한 뒤 사용할지 결정할 수 있어요.")
                    Text("다른 옷과 가방의 특징이 섞일 수 있어요. 분석 후 이름·종류·색상·디테일을 확인해 주세요.")
                    TextButton(enabled = !busy, onClick = { model.reset(); crop = null; error = null }) { Text("영역 다시 선택") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            generation.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}


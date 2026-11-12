package com.swpp.stylemate.ui.wardrobe

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.swpp.stylemate.data.AccountAccess
import com.swpp.stylemate.data.LocalUserStore
import com.swpp.stylemate.data.recommendation.OutfitRepository
import com.swpp.stylemate.data.wardrobe.WardrobeRepository
import com.swpp.stylemate.ui.components.ScreenScaffold
import com.swpp.stylemate.ui.profile.decodeDownscaled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.min

@Composable
fun WornRegistration(account: AccountAccess, onBack: () -> Unit, onBatchSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    var photoError by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<Pair<String, String>?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var catalog by remember { mutableStateOf<JSONObject?>(null) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    val store = remember(account.userId) { LocalUserStore.forContext(context, account.userId) }
    val model: WornBatchViewModel = viewModel(key = "worn-batch", factory = viewModelFactory {
        initializer { WornBatchViewModel(account) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val busy = state.busy || reading
    LaunchedEffect(Unit) {
        try { catalog = WardrobeRepository.json("/api/wardrobe/options/", store = store, account = account) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { catalogError = "옷 정보 선택지를 불러오지 못했어요. 다시 들어와 주세요." }
    }
    LaunchedEffect(state.savedCount) { if (state.savedCount > 0) { model.reset(); onBatchSaved() } }
    BackHandler { if (!busy) {
        if (editing != null) editing = null else { model.reset(); onBack() }
    } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            reading = true
            photoError = null
            try {
                val bitmap = decodeDownscaled(context, uri, maxSide = 1536)
                if (bitmap != null) model.detect(bitmap) else photoError = "사진을 읽을 수 없어요. 다른 사진을 골라 주세요."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { photoError = "사진을 읽을 수 없어요. 다른 사진을 골라 주세요." }
            finally { reading = false }
        }
    }
    // Camera capture goes to a temporary cache file that is deleted right after it is decoded.
    val captureFile = remember { java.io.File(context.cacheDir, "body_photos/worn-capture.jpg") }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        scope.launch {
            if (saved) {
                reading = true
                photoError = null
                try {
                    val bitmap = decodeDownscaled(context, android.net.Uri.fromFile(captureFile), maxSide = 1536)
                    if (bitmap != null) model.detect(bitmap) else photoError = "사진을 읽을 수 없어요. 다시 찍어 주세요."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { photoError = "사진을 읽을 수 없어요. 다시 찍어 주세요." }
                finally { reading = false }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { captureFile.delete() }
        }
    }
    fun capture() {
        captureFile.parentFile?.mkdirs()
        try { camera.launch(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.photos", captureFile)) }
        catch (_: Exception) { captureFile.delete(); photoError = "카메라를 열지 못했어요. 앨범에서 골라 주세요." }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) capture() else photoError = "카메라 권한을 허용하거나 앨범에서 사진을 골라 주세요."
    }
    fun takePhoto() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) capture()
        else cameraPermission.launch(android.Manifest.permission.CAMERA)
    }
    val editorItem = state.items.firstOrNull { it.id == editing }
    if (editorItem != null && catalog != null) {
        WornBatchEditor(editorItem, checkNotNull(catalog), account, onBack = { editing = null }, onEdit = {
            model.edit(editorItem.id, it); editing = null
        })
        return
    }
    val reviewing = state.items.isNotEmpty()
    val candidates = state.photo?.optJSONArray("candidates")?.let { rows ->
        (0 until rows.length()).map { rows.getJSONObject(it) }
    }.orEmpty()
    if (reviewing) WornBatchReviewScreen(state.copy(error = state.error ?: catalogError), catalog, onBack = { model.reset(); onBack() }, onSave = model::save,
        onInclude = model::include, onCrop = { confirmation = "crop" to it },
        onRegenerate = { confirmation = "regenerate" to it }, onRefresh = model::refresh,
        onEdit = { editing = it },
        photo = { label, path, requestId, modifier, scale ->
            ComparisonPhoto(label, path, account, requestId, modifier, contentScale = scale, framed = false)
        })
    else ScreenScaffold(title = "착용 사진으로 등록",
        onBack = { model.reset(); onBack() }, navigationEnabled = !busy,
        primaryLabel = when {
            state.original == null -> null
            busy -> "분석 중"
            candidates.isEmpty() -> "사진을 다시 선택해 주세요"
            state.selected.isEmpty() -> "등록할 옷을 선택해 주세요"
            else -> "선택한 ${state.selected.size}벌 사진 만들기"
        },
        primaryEnabled = !busy && catalog != null && candidates.isNotEmpty() && state.selected.isNotEmpty(),
        onPrimary = model::generateSelected) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (busy) WornProcessingIndicator(state.progress.ifBlank { "사진 읽는 중" })
                Text("본인이 옷을 입은 사진을 골라 주세요. 사진에서 옷을 찾고 핏·기장을 제안해요.")
                WornPhotoUpload(hasPhoto = state.original != null, enabled = !busy, onPick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, onCamera = ::takePhoto)
                if (state.original != null) AutoGarmentCandidates(checkNotNull(state.original), candidates,
                    state.selected, !busy, model::select)
                if (candidates.isNotEmpty()) Text("선택한 옷마다 이미지를 한 장씩 생성해요(옷마다 생성 비용 발생). 가려진 부분은 실제 옷과 다를 수 있어요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!busy && state.original != null && (state.photo == null || state.photo?.optString("status") == "detecting")) {
                    TextButton(onClick = model::refreshPhoto) { Text("찾은 결과 확인") }
                }
            listOfNotNull(photoError, state.error, catalogError).forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    confirmation?.let { (action, id) ->
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(when (action) {
                "crop" -> "착용 사진으로 등록할까요?"
                else -> "이미지를 생성할까요?"
            }) },
            text = { Text(when (action) {
                "generate" -> "선택한 ${state.selected.size}벌에 대해 이미지 생성 API를 각각 1회 호출합니다. 선택한 수만큼 사용료가 발생해요. 생성 후 원본과 비교할 수 있어요."
                "regenerate" -> "이 옷의 이미지 생성 API를 추가로 1회 호출합니다. 추가 사용료가 발생해요."
                "crop" -> "원본의 옷 영역을 저장합니다. 몸이나 다른 옷이 남을 수 있으며 펼친 옷 사진은 아닙니다."
                else -> "아직 저장하지 않은 옷은 옷장에 추가되지 않아요. 이미 사용한 생성 비용은 되돌릴 수 없어요."
            }) },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                when (action) {
                    "generate" -> model.generateSelected()
                    "regenerate" -> model.regenerate(id)
                    "crop" -> model.chooseImage(id, "crop")
                }
            }) { Text(if (action in setOf("generate", "regenerate")) "생성하기" else "확인") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("취소") } })
    }
}

/** Pure selection UI: boxes are automatic proposals, not reconstructed garment images. */
@Composable
internal fun AutoGarmentCandidates(original: Bitmap, candidates: List<JSONObject>, selected: Set<String>,
                                   enabled: Boolean, onToggle: (String) -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().height(320.dp)) {
        Image(original.asImageBitmap(), "착용 사진과 자동으로 찾은 옷 영역", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Canvas(Modifier.fillMaxSize()) {
            val scale = min(size.width / original.width, size.height / original.height)
            val width = original.width * scale
            val height = original.height * scale
            val left = (size.width - width) / 2
            val top = (size.height - height) / 2
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb(); textSize = 18.dp.toPx(); isFakeBoldText = true }
            candidates.forEachIndexed { index, candidate ->
                if (candidate.getString("id") in selected) {
                    val bounds = candidate.getJSONArray("bounds")
                    val x = left + bounds.getInt(0) * width / 1000
                    val y = top + bounds.getInt(1) * height / 1000
                    drawRect(color, Offset(x, y), Size((bounds.getInt(2) - bounds.getInt(0)) * width / 1000,
                        (bounds.getInt(3) - bounds.getInt(1)) * height / 1000), style = Stroke(2.dp.toPx()))
                    drawContext.canvas.nativeCanvas.drawText("${index + 1}", x + 4.dp.toPx(), y + 20.dp.toPx(), paint)
                }
            }
        }
    }
    candidates.forEachIndexed { index, candidate ->
        val id = candidate.getString("id")
        val bounds = candidate.getJSONArray("bounds")
        val preview = remember(original, id) { cropWornPhoto(original,
            bounds.getInt(0) / 1000f..bounds.getInt(2) / 1000f,
            bounds.getInt(1) / 1000f..bounds.getInt(3) / 1000f) }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxWidth().toggleable(id in selected, enabled = enabled, role = Role.Checkbox,
                onValueChange = { onToggle(id) }).padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Checkbox(id in selected, onCheckedChange = null, enabled = enabled)
                Image(preview.asImageBitmap(), null, Modifier.size(64.dp), contentScale = ContentScale.Fit)
                Text("${index + 1}. ${candidate.getJSONObject("attributes").getString("name")}", Modifier.weight(1f))
            }
        }
    }
}

/** Reuses the existing attribute, color and recommendation-label editor without saving yet. */
@Composable
private fun WornBatchEditor(item: WornBatchItem, catalog: JSONObject, account: AccountAccess,
                             onBack: () -> Unit, onEdit: (JSONObject) -> Unit) {
    val context = LocalContext.current
    val model: GarmentLabelsViewModel = viewModel(key = "worn-batch-labels-${item.id}", factory = viewModelFactory {
        initializer { GarmentLabelsViewModel(OutfitRepository(account)) }
    })
    val labels by model.state.collectAsStateWithLifecycle()
    val options = catalog.optJSONObject("label_options")
    var original by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.id) {
        try { original = WardrobeRepository.thumbnail(context, "/api/wardrobe/items/${item.id}/worn-image/original/", account) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { original = null }
    }
    fun valid(attrs: JSONObject): Boolean {
        val merged = model.merged(attrs.optString("category"), options)
        return GarmentLabels.canSave(labels, item.id, attrs, null, null) && merged != null
    }
    GarmentEditor(item.id, item.payload.getJSONObject("attributes"), null, item.payload.optString("notes"), catalog,
        busy = false, saved = false, error = null, measurementsEnabled = false, saveLabel = "변경 확인",
        estimatedDimensions = matchingWornEstimates(item.record, item.payload.getJSONObject("attributes"),
            model.merged(item.payload.getJSONObject("attributes").optString("category"), options) ?: item.payload.optJSONObject("labels")),
        onBack = onBack, image = original,
        photo = { Text("정보는 원본에서 보이는 옷을 기준으로 확인해 주세요. 아직 옷장에 저장하지 않았어요.") },
        canSaveLabels = { attrs, _ -> valid(attrs) },
        labels = { attrs, _ -> GarmentLabelSection(model, item.id, attrs, null, null,
            item.record.optJSONObject("details"), item.payload.optJSONObject("labels"), options, true, manualFit = true) },
        onSave = { payload -> model.merged(payload.getJSONObject("attributes").optString("category"), options)?.let { merged ->
            payload.put("labels", merged)
            payload.getJSONObject("attributes").put("fit_type", JSONObject.NULL).put("leg_shape", JSONObject.NULL)
            onEdit(payload)
        } })
}

/** Gallery entry styled like the existing body-profile photo slots, with an optional camera button. */
@Composable
internal fun WornPhotoUpload(hasPhoto: Boolean, enabled: Boolean, onCamera: (() -> Unit)? = null, onPick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onPick)
            .semantics { contentDescription = if (hasPhoto) "다른 착용 사진 업로드" else "착용 사진 업로드" },
            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().heightIn(min = if (hasPhoto) 88.dp else 200.dp).padding(20.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Add, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(if (hasPhoto) "다른 사진 올리기" else "착용 사진 올리기", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("눌러서 앨범에서 선택", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onCamera != null) OutlinedButton(onClick = onCamera, enabled = enabled, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)) {
            Text("카메라로 찍기")
        }
    }
}

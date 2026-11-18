// AI-generated with Codex, 2026-09-29, reviewed by Hyeon U Jeong; recommendation labels added with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.wardrobe

import com.swpp.stylemate.data.wardrobe.*
import com.swpp.stylemate.BuildConfig
import com.swpp.stylemate.data.BodyProfileJson
import com.swpp.stylemate.data.LocalBodyProfileStore
import com.swpp.stylemate.data.LocalUserStore
import com.swpp.stylemate.ui.LocalAccountAccess
import com.swpp.stylemate.data.recommendation.OutfitRepository
import com.swpp.stylemate.ui.wardrobe.capture.MeasurementActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.rememberCoroutineScope
import com.swpp.stylemate.ui.profile.decodeDownscaled
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import com.swpp.stylemate.R
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import com.swpp.stylemate.ui.components.PrimaryActionBar
import com.swpp.stylemate.ui.components.ScreenScaffold
import com.swpp.stylemate.ui.components.SectionTitle
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

const val ANALYZE_BUTTON_TEXT = "옷장 분석"

data class WardrobeItem(val id: String, val name: String, val category: String, val style: String? = null,
                        val thumbnail: ImageBitmap? = null, val imageUrl: String? = null)

@Composable
fun WardrobeRoute(
    onboardingDraft: JSONObject? = null,
    onDraftChange: ((JSONObject) -> Unit)? = null,
    onSaved: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onboardingTopBar: (@Composable (onBack: () -> Unit, enabled: Boolean) -> Unit)? = null,
    localStore: LocalUserStore? = null,
    onDeleted: () -> Unit = {},
) {
    val context = LocalContext.current
    val account = checkNotNull(LocalAccountAccess.current)
    val store = remember(localStore, account.userId) { localStore ?: LocalUserStore.forContext(context, account.userId) }
    val activity = context as Activity
    val files = remember { CaptureFiles(context.applicationContext) }
    val preferences = remember { context.getSharedPreferences("camera-permission", 0) }
    val initialDraft = remember { onboardingDraft }
    val initialCategory = initialDraft?.optString("category")
    var selected by rememberSaveable { mutableStateOf(initialDraft?.optString("capture")?.takeIf { it.isNotBlank() }) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var measurementBusy by rememberSaveable { mutableStateOf(false) }
    var selectedMeasurements by rememberSaveable { mutableStateOf(initialDraft?.optJSONObject("measurements")?.toString()) }
    // Tops and outerwear are measured from the back; their design is analysed from a separate front photo.
    var frontPhoto by rememberSaveable { mutableStateOf(initialDraft?.optString("front")?.takeIf { it.isNotBlank() }) }
    var editorDraftText by rememberSaveable { mutableStateOf(initialDraft?.optJSONObject("editor")?.toString()) }
    var pendingFront by rememberSaveable { mutableStateOf<String?>(null) }
    var frontPreview by remember { mutableStateOf<ImageBitmap?>(null) }
    var frontLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var wornRegistration by rememberSaveable { mutableStateOf(false) }
    val measured = remember(selectedMeasurements) { selectedMeasurements?.let { runCatching { JSONObject(it) }.getOrNull() } }
    val needsFront = needsFrontPhoto(measured?.optString("category"))
    val garmentType = measured?.let { if (it.isNull("garment_type")) null else it.optString("garment_type") }
    val analysisPhoto = if (needsFront) frontPhoto else selected
    val analysisModel: GarmentAnalysisViewModel = viewModel(factory = viewModelFactory { initializer { GarmentAnalysisViewModel(account) } })
    val analysis by analysisModel.state.collectAsStateWithLifecycle()
    val wardrobeModel: WardrobeViewModel = viewModel(factory = viewModelFactory {
        initializer { WardrobeViewModel(store, account) }
    })
    val wardrobe by wardrobeModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(wardrobe.deletedId) {
        if (wardrobe.deletedId != null) { wardrobeModel.clearDeleted(); onDeleted() }
    }
    val externalMatch by wardrobeModel.externalMatch.collectAsStateWithLifecycle()
    val wardrobeAnalysis by wardrobeModel.wardrobeAnalysis.collectAsStateWithLifecycle()
    var actionItemId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { wardrobeModel.refresh() }
    val labelsModel: GarmentLabelsViewModel = viewModel(factory = viewModelFactory {
        initializer { GarmentLabelsViewModel(OutfitRepository(account)) }
    })
    val labelState by labelsModel.state.collectAsStateWithLifecycle()
    // The body profile kept on the device (recommendation labels are computed on this body).
    val body = remember(store) { LocalBodyProfileStore(store).load()?.let { JSONObject(BodyProfileJson.encode(it)) } }
    var editingId by rememberSaveable { mutableStateOf(initialDraft?.optString("editing_id")?.takeIf { it.isNotBlank() }) }
    val actionRecord = wardrobe.records.firstOrNull { it.optString("id") == actionItemId }
    actionRecord?.let { record ->
        val attributes = record.getJSONObject("attributes")
        WardrobeItemActionsDialog(
            item = WardrobeItem(record.getString("id"), attributes.getString("name"), attributes.getString("category")),
            onEdit = { wardrobeModel.clearSaveState(); editingId = actionItemId; actionItemId = null },
            onRecommend = {
                actionItemId?.let(wardrobeModel::matchExternal)
                actionItemId = null
            },
            onDismiss = { actionItemId = null },
        )
    }
    if (externalMatch.garmentId != null) {
        ExternalRecommendationDialog(externalMatch,
            onRetry = { externalMatch.garmentId?.let(wardrobeModel::matchExternal) },
            onOpenLink = { url ->
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                catch (_: ActivityNotFoundException) {
                    wardrobeModel.closeExternalMatch()
                    message = "검색 페이지를 열 수 있는 앱이 없어요."
                }
            },
            onDismiss = wardrobeModel::closeExternalMatch,
            wardrobe = wardrobe.records.map { record ->
                val attrs = record.getJSONObject("attributes")
                WardrobeItem(record.getString("id"), attrs.getString("name"), attrs.getString("category"),
                    imageUrl = record.optString("image_url").ifBlank { null })
            },
        )
    }
    if (wardrobeAnalysis.open) {
        WardrobeAnalysisDialog(wardrobeAnalysis,
            onRetry = wardrobeModel::analyzeWardrobe,
            onOpenLink = { url ->
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                catch (_: ActivityNotFoundException) {
                    wardrobeModel.closeWardrobeAnalysis()
                    message = "검색 페이지를 열 수 있는 앱이 없어요."
                }
            },
            onDismiss = wardrobeModel::closeWardrobeAnalysis,
        )
    }
    var savedPhoto by remember(editingId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(analysisPhoto) { analysisModel.select(analysisPhoto,
        initialDraft?.optJSONObject("result")?.takeIf { initialDraft.optString("analysis_photo") == analysisPhoto }) }
    LaunchedEffect(selected, frontPhoto, selectedMeasurements, editingId, analysis.result?.toString(), editorDraftText) {
        onDraftChange?.invoke(JSONObject().put("category", initialCategory ?: "top").apply {
            selected?.let { put("capture", it) }; frontPhoto?.let { put("front", it) }
            selectedMeasurements?.let { put("measurements", JSONObject(it)) }
            editingId?.let { put("editing_id", it) }
            if (analysis.photo == analysisPhoto) analysis.result?.let { put("result", it); put("analysis_photo", analysisPhoto) }
            editorDraftText?.let { put("editor", JSONObject(it)) }
        })
    }
    LaunchedEffect(wardrobe.savedId) {
        if (wardrobe.savedId != null) {
            files.delete(selected)
            files.delete(frontPhoto)
            selected = null
            frontPhoto = null
            selectedMeasurements = null
            editingId = null
            message = null
            wardrobeModel.clearSaveState()
            editorDraftText = null
            onSaved?.invoke()
        }
    }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { files.cleanExpired(setOfNotNull(selected, frontPhoto, pendingFront)) } }
    LaunchedEffect(selected) {
        preview = null
        val name = selected ?: return@LaunchedEffect
        previewLoading = true
        preview = withContext(Dispatchers.IO) { runCatching { files.preview(name)?.asImageBitmap() }.getOrNull() }
        previewLoading = false
        if (preview == null) message = "사진을 읽을 수 없습니다. 다시 촬영해 주세요."
    }
    LaunchedEffect(frontPhoto) {
        frontPreview = null
        val name = frontPhoto ?: return@LaunchedEffect
        frontLoading = true
        frontPreview = withContext(Dispatchers.IO) { runCatching { files.preview(name)?.asImageBitmap() }.getOrNull() }
        frontLoading = false
        if (frontPreview == null) message = "앞면 사진을 읽을 수 없습니다. 다시 찍어 주세요."
    }

    val measurementCamera = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        measurementBusy = false
        val name = result.data?.getStringExtra("capture_name")
        if (result.resultCode == Activity.RESULT_OK && name != null && files.exists(name)) {
            files.delete(selected)
            files.delete(frontPhoto)
            frontPhoto = null
            selected = name
            selectedMeasurements = result.data?.getStringExtra("measurements")
            message = null
        } else {
            message = "실측 촬영이 취소되었습니다. 기존 사진은 유지됩니다."
        }
    }
    fun launchCamera() {
        if (measurementBusy) return
        measurementBusy = true
        try { measurementCamera.launch(Intent(context, MeasurementActivity::class.java).apply {
            initialCategory?.let { putExtra("initial_category", it) }
        }) }
        catch (_: Exception) { measurementBusy = false; message = "카메라를 열지 못했습니다." }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val blocked = !granted && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
        preferences.edit().putBoolean("blocked", blocked).apply()
        if (granted) launchCamera() else {
            message = "카메라 권한을 허용해야 촬영할 수 있습니다. 옷장 조회는 계속 사용할 수 있습니다."
            if (blocked) {
                permissionDialog = "settings"
            }
        }
    }
    fun requestPermission() {
        permission.launch(Manifest.permission.CAMERA)
    }
    fun takePhoto() {
        message = null
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                preferences.edit().putBoolean("blocked", false).apply()
                launchCamera()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA) -> permissionDialog = "rationale"
            preferences.getBoolean("blocked", false) -> permissionDialog = "settings"
            else -> requestPermission()
        }
    }
    val frontCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val name = pendingFront
        pendingFront = null
        if (saved && name != null && files.exists(name)) {
            if (initialCategory == "shoes") { files.delete(selected); selected = name }
            else { files.delete(frontPhoto); frontPhoto = name }
            message = null
        }
        else files.delete(name)
    }
    fun captureFront() {
        val name = files.create()
        pendingFront = name
        try { frontCamera.launch(files.uri(name)) }
        catch (_: Exception) { files.delete(name); pendingFront = null; message = "카메라를 열지 못했습니다." }
    }
    val frontPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) captureFront() else message = "카메라 권한을 허용하거나 갤러리에서 앞면 사진을 골라 주세요."
    }
    fun takeFront() {
        message = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) captureFront()
        else frontPermission.launch(Manifest.permission.CAMERA)
    }
    val frontGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bitmap = decodeDownscaled(context, uri, maxSide = 1536)
            val name = bitmap?.let { withContext(Dispatchers.IO) { runCatching { files.create().also { n -> files.write(n, it) } }.getOrNull() } }
            if (name == null) message = "사진을 읽을 수 없습니다. 다른 사진을 골라 주세요."
            else {
                if (initialCategory == "shoes") { files.delete(selected); selected = name }
                else { files.delete(frontPhoto); frontPhoto = name }
                message = null
            }
        }
    }
    fun discard() {
        files.delete(selected); files.delete(frontPhoto)
        selected = null; frontPhoto = null; selectedMeasurements = null; editorDraftText = null; message = null
    }
    BackHandler(selected != null || editingId != null) {
        if (!wardrobe.saving && !analysis.busy && !measurementBusy) {
            discard(); editingId = null; wardrobeModel.clearSaveState(); onCancel?.invoke()
        }
    }

    if (permissionDialog != null) {
        val settings = permissionDialog == "settings"
        AlertDialog(
            onDismissRequest = { permissionDialog = null },
            title = { Text("카메라 권한") },
            text = { Text(if (settings) "기기 설정에서 카메라 권한을 허용한 후 촬영 버튼을 다시 눌러 주세요."
                         else "옷 사진을 촬영하려면 카메라 권한이 필요합니다. 촬영한 사진은 확인 전 서버로 전송되지 않습니다.") },
            confirmButton = { TextButton(onClick = {
                permissionDialog = null
                if (settings) {
                    try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                    catch (_: ActivityNotFoundException) { message = "기기의 앱 설정에서 카메라 권한을 변경해 주세요." }
                } else requestPermission()
            }) { Text(if (settings) "설정 열기" else "권한 요청") } },
            dismissButton = { TextButton(onClick = { permissionDialog = null }) { Text("나중에") } },
        )
    }

    val editing = wardrobe.records.find { it.getString("id") == editingId }
    if (wornRegistration) {
        WornRegistration(account, onBack = { wornRegistration = false }, onBatchSaved = {
            wornRegistration = false
            wardrobeModel.refresh()
            onSaved?.invoke()
        })
        return
    }
    if (selected == null && editingId == null) {
      if (onboardingTopBar != null) {
        val cancel = { onCancel?.invoke(); Unit }
        var choosingMethod by rememberSaveable { mutableStateOf(false) }
        if (choosingMethod) RegistrationMethodSheet(onDismiss = { choosingMethod = false },
            onWornPhoto = { choosingMethod = false; wornRegistration = true },
            onCapture = { choosingMethod = false; takePhoto() })
        ScreenScaffold(title = "옷 등록", primaryLabel = "등록하기", primaryEnabled = !measurementBusy,
            onPrimary = { if (initialCategory == "shoes") takeFront() else choosingMethod = true },
            topBar = { onboardingTopBar(cancel, !measurementBusy) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (initialCategory == "shoes") "자주 신는 신발을 담아볼까요?" else "자주 입는 옷을 담아볼까요?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(if (initialCategory == "shoes") "사진을 확인한 뒤 이름과 종류를 저장해요." else "옷을 촬영하고 치수를 확인한 뒤 옷장에 추가해요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (initialCategory == "shoes") OutlinedButton(onClick = {
                    frontGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("앨범에서 선택") }
            }
        }
      } else {
        val items = wardrobe.records.map { record ->
            val attrs = record.getJSONObject("attributes")
            val styles = attrs.getJSONArray("styles")
            val style = (0 until styles.length()).mapNotNull { index ->
                wardrobe.catalog?.getJSONObject("enums")?.getJSONObject("styles")?.optString(styles.getString(index))
            }.joinToString(", ").ifBlank { null }
            WardrobeItem(record.getString("id"), attrs.getString("name"), attrs.getString("category"),
                style = style, imageUrl = record.getString("image_url"))
        }
        WardrobeScreen(items = items, message = message ?: wardrobe.error, cameraBusy = measurementBusy,
            onWornPhoto = { wornRegistration = true },
            onCapture = { wardrobeModel.clearSaveState(); takePhoto() },
            onItemClick = { actionItemId = it.id },
            onAnalyze = wardrobeModel::analyzeWardrobe,
            onDelete = { wardrobeModel.delete(it.id) },
            deletingId = wardrobe.deletingId, deleteError = wardrobe.deleteError,
            onDeleteDismiss = wardrobeModel::clearDeleteError,
            loading = wardrobe.loading,
            onRetry = if (wardrobe.error != null) ({ wardrobeModel.refresh() }) else null)
      }
    } else {
        val result = if (editingId == null) analysis.result else null
        val record = editing ?: result
        val worn = record?.optString("image_source") in setOf("worn_crop", "ai_flatlay")
        val generated = record?.optString("image_source") == "ai_flatlay"
        var originalForColor by remember(record?.optString("id")) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(record?.optString("id"), generated) {
            if (generated) try {
                originalForColor = WardrobeRepository.thumbnail(context,
                    "/api/wardrobe/items/${record!!.getString("id")}/worn-image/original/", account)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { originalForColor = null }
        }
        val labelBody = if (worn) null else body
        val catalog = wardrobe.catalog
        val measurements = measured
        val measuredCategory = measurements?.optString("category")
        val attrs = record?.getJSONObject("attributes")
        val sameCategory = measuredCategory == attrs?.optString("category") || measuredCategory == "top" && attrs?.optString("category") == "outerwear"
        val back = { discard(); editingId = null; wardrobeModel.clearSaveState(); onCancel?.invoke(); Unit }
        val topBar: (@Composable () -> Unit)? = onboardingTopBar?.let { render ->
            { render(back, !(wardrobe.saving || analysis.busy || measurementBusy)) }
        }
        // a new garment's bottom kind comes from what the user picked when measuring (shorts, skirt, trousers)
        val initialAttributes = remember(record, garmentType) {
            record?.getJSONObject("attributes")?.let { if (editing == null) applyCaptureType(it, garmentType) else it }
        }
        if (record != null && catalog != null && initialAttributes != null) {
            GarmentEditor(
                record.getString("id"), initialAttributes,
                if (editing != null) editing.optJSONObject("dimensions") else if (sameCategory) measurements?.optJSONObject("dimensions") else null,
                if (editing != null) editing.getString("notes") else "", catalog,
                wardrobe.saving || wardrobe.loading, editing != null, wardrobe.saveError,
                canSaveLabels = { attributes, dimensions ->
                    GarmentLabels.canSave(labelState, record.getString("id"), attributes, dimensions, labelBody)
                },
                onSave = save@{ payload ->
                    if (!GarmentLabels.canSave(labelsModel.state.value, record.getString("id"),
                            payload.getJSONObject("attributes"), payload.optJSONObject("dimensions"), labelBody)) return@save
                    labelsModel.merged(payload.getJSONObject("attributes").optString("category"), catalog.optJSONObject("label_options"))
                        ?.let { labels ->
                            payload.put("labels", labels)
                            // the fit is kept only in the labels, so the old attribute fields do not disagree with it
                            payload.getJSONObject("attributes").put("fit_type", JSONObject.NULL).put("leg_shape", JSONObject.NULL)
                        }
                    wardrobeModel.save(record.getString("id"), payload)
                }, onBack = back,
                image = if (generated) originalForColor else if (editing != null) savedPhoto else (if (needsFront) frontPreview else preview) ?: savedPhoto,
                photo = {
                    if (worn) Text(if (generated) "AI가 재구성한 이미지입니다. 옷 정보는 원본에서 분석했어요. 가려진 부분은 실제와 다를 수 있습니다."
                        else "착용 사진에서 가져온 이미지 · 펼친 옷 사진이 아닙니다. 다른 옷이나 가방의 특징이 섞이지 않았는지 확인해 주세요.", Modifier.padding(vertical = 12.dp))
                    if (editing != null || generated) GarmentPhoto(record.getString("image_url"), record.getJSONObject("attributes").getString("name"),
                        onLoaded = { savedPhoto = it }, store = store)
                    else if ((if (needsFront) frontPreview else preview) == null && !record.optString("image_url").isBlank())
                        GarmentPhoto(record.getString("image_url"), initialAttributes.optString("name"), onLoaded = { savedPhoto = it }, store = store)
                    else {
                        PhotoFrame {
                            (if (needsFront) frontPreview else preview)?.let {
                                Image(it, "촬영한 옷 사진", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            }
                            if (if (needsFront) frontLoading else previewLoading) CircularProgressIndicator()
                        }
                        if (measurements != null && !sameCategory) Text("옷 종류가 달라 치수를 다시 입력해 주세요.",
                            Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                },
                labels = { attributes, dimensions ->
                    val stored = record.optJSONObject("labels")
                    GarmentLabelSection(labelsModel, record.getString("id"), attributes, dimensions, labelBody,
                        GarmentLabels.details(result, stored), stored, catalog.optJSONObject("label_options"),
                        enabled = !(wardrobe.saving || wardrobe.loading), garmentType = if (editing == null) garmentType else null,
                        manualFit = worn)
                },
                topBar = topBar,
                draft = editorDraftText?.let { JSONObject(it) },
                onDraftChange = if (onDraftChange == null) null else { draft -> editorDraftText = draft.toString() },
                measurementsEnabled = !worn,
                estimatedDimensions = matchingWornEstimates(record, initialAttributes,
                    labelsModel.merged(initialAttributes.optString("category"), catalog.optJSONObject("label_options")) ?: record.optJSONObject("labels")),
            )
        } else {
            ScreenScaffold(
                title = if (editingId != null) "옷 정보" else "촬영한 옷",
                primaryLabel = if (editingId != null || result != null) null else if (analysis.busy) "분석 중" else "분석하기",
                primaryEnabled = !analysis.busy && !measurementBusy && preview != null && (!needsFront || frontPreview != null),
                onPrimary = { analysisPhoto?.let { analysisModel.analyze(context, it, garmentType) } },
                onBack = back,
                actionLabel = if (editingId == null && result == null) "다시 촬영" else null,
                onAction = { takePhoto() },
                navigationEnabled = !wardrobe.saving && !analysis.busy && !measurementBusy,
                contentWindowInsets = WindowInsets(0),
                topBar = topBar,
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                    if (editingId == null) {
                        PhotoFrame {
                            preview?.let { Image(it, "촬영한 옷 사진", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                            if (previewLoading) CircularProgressIndicator()
                        }
                        message?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error) }
                        measurements?.getJSONObject("dimensions")?.let { dimensions ->
                            MeasurementSummary((topMeasurementFields + bottomMeasurementFields).distinctBy { it.key }.mapNotNull { field ->
                                dimensions.optJSONObject(field.key)?.let { value ->
                                    field.label to String.format(java.util.Locale.KOREA, "%.2f", value.getDouble("value"))
                                }
                            })
                        }
                        if (needsFront) FrontPhotoCard(frontPreview, frontLoading, enabled = !analysis.busy && !measurementBusy,
                            onCamera = ::takeFront, onGallery = {
                                frontGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                        if (analysis.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                        analysis.error?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error) }
                    } else if (wardrobe.loading) {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else Text("옷 정보를 불러오지 못했습니다.", style = MaterialTheme.typography.bodyMedium)
                    if (catalog == null) {
                        wardrobe.error?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { wardrobeModel.refresh() }, enabled = !wardrobe.loading) { Text("다시 불러오기") }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
fun WardrobeScreen(items: List<WardrobeItem>, message: String?, cameraBusy: Boolean,
                   onCapture: () -> Unit,
                   onItemClick: (WardrobeItem) -> Unit = {}, loading: Boolean = false, onRetry: (() -> Unit)? = null,
                   onWornPhoto: (() -> Unit)? = null,
                   onDelete: ((WardrobeItem) -> Unit)? = null, deletingId: String? = null,
                   deleteError: String? = null, onDeleteDismiss: () -> Unit = {},
                   onAnalyze: (() -> Unit)? = null) {
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingDelete = items.firstOrNull { it.id == pendingDeleteId }
    LaunchedEffect(items) {
        if (pendingDeleteId != null && pendingDelete == null) pendingDeleteId = null
    }
    if (pendingDelete != null && onDelete != null) {
        val deleting = deletingId != null
        fun dismissDelete() {
            if (!deleting) { pendingDeleteId = null; onDeleteDismiss() }
        }
        AlertDialog(
            onDismissRequest = ::dismissDelete,
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text("옷 삭제") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("'${pendingDelete.name}'을(를) 옷장에서 삭제할까요?")
                    Text("이 옷이 들어간 코디북 코디도 함께 삭제돼요.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (deleting) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = { onDelete(pendingDelete) }, enabled = !deleting,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(if (deleting) "삭제 중…" else "삭제")
                }
            },
            dismissButton = { TextButton(onClick = ::dismissDelete, enabled = !deleting) { Text("취소") } },
        )
    }
    var choosingMethod by rememberSaveable { mutableStateOf(false) }
    if (choosingMethod) RegistrationMethodSheet(onDismiss = { choosingMethod = false },
        onWornPhoto = onWornPhoto?.let { { choosingMethod = false; it() } },
        onCapture = { choosingMethod = false; onCapture() })
    var category by rememberSaveable { mutableStateOf("all") }
    val categories = listOf("all" to "전체", "top" to "상의", "bottom" to "하의", "outerwear" to "아우터", "shoes" to "신발")
    val visible = items.filter { category == "all" || it.category == category }
    // Newest first: a garment just added (or a new category) starts from the top instead of keeping the old first card in view.
    val grid = rememberLazyGridState()
    LaunchedEffect(visible.firstOrNull()?.id) { grid.scrollToItem(0) }
    val configuration = LocalConfiguration.current
    val columns = if (configuration.screenWidthDp < 352 || configuration.fontScale > 1.3f) 2 else 3
    val nameStyle = MaterialTheme.typography.bodyMedium
    val detailStyle = MaterialTheme.typography.bodySmall
    val cardTextHeight = with(LocalDensity.current) { nameStyle.lineHeight.toDp() * 2 + detailStyle.lineHeight.toDp() } + 4.dp
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            PrimaryActionBar(if (cameraBusy) "촬영 중" else "등록하기", !cameraBusy, { choosingMethod = true })
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("내 옷장", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                // 옷장 전체를 보고 부족한 옷을 추천받는다 (WardrobeAnalysisDialog)
                if (onAnalyze != null) OutlinedButton(onClick = onAnalyze, enabled = !loading && deletingId == null,
                    shape = RoundedCornerShape(50)) { Text(ANALYZE_BUTTON_TEXT) }
            }
            Text("총 ${items.size}벌", Modifier.padding(top = 4.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { (code, label) ->
                    FilterChip(selected = category == code, onClick = { category = code }, label = { Text(label) },
                        shape = RoundedCornerShape(50), colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary))
                }
            }
            message?.let { Text(it, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            if (onRetry != null) TextButton(onClick = onRetry, enabled = !loading) { Text("다시 불러오기") }
            Spacer(Modifier.height(8.dp))
            if (visible.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (items.isEmpty()) "아직 등록된 옷이 없습니다" else "이 카테고리에 등록된 옷이 없습니다", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("등록하기를 눌러 옷을 추가해 보세요.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.weight(1f), state = grid,
                    contentPadding = PaddingValues(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(visible, key = { it.id }) { garment ->
                        Card(onClick = { onItemClick(garment) }, enabled = deletingId == null, shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                                if (garment.thumbnail != null) Image(garment.thumbnail, garment.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                else if (garment.imageUrl != null) GarmentPhoto(garment.imageUrl, garment.name)
                                else Text("사진 없음", style = MaterialTheme.typography.labelSmall)
                                // A trash can on a light badge: the earlier mark read as an edit pencil (HE).
                                if (onDelete != null) IconButton(
                                    onClick = { onDeleteDismiss(); pendingDeleteId = garment.id },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(32.dp),
                                    enabled = !loading && deletingId == null,
                                    colors = IconButtonDefaults.iconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                ) {
                                    Icon(Icons.Outlined.Delete, "${garment.name} 삭제", Modifier.size(18.dp))
                                }
                            }
                            Column(Modifier.fillMaxWidth().padding(12.dp).heightIn(min = cardTextHeight),
                                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                                Text(garment.name, style = nameStyle, fontWeight = FontWeight.SemiBold,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                garment.style?.takeIf { it.isNotBlank() }?.let { style ->
                                    Text(style, style = detailStyle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun GarmentPhoto(path: String, name: String, onLoaded: (ImageBitmap?) -> Unit = {}, store: LocalUserStore? = null) {
    val context = LocalContext.current.applicationContext
    val account = checkNotNull(LocalAccountAccess.current)
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(path) { mutableStateOf(true) }
    LaunchedEffect(path) {
        onLoaded(null)
        try { bitmap = WardrobeRepository.thumbnail(context, path, account) }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { bitmap = null }
        finally { loading = false }
        onLoaded(bitmap)
    }
    PhotoFrame {
        bitmap?.let { Image(it, name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        if (loading) CircularProgressIndicator()
        else if (bitmap == null) Text("사진을 불러오지 못했습니다.")
    }
}

@Composable
private fun PhotoFrame(content: @Composable BoxScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center, content = content)
    }
}

@Composable
fun MeasurementSummary(values: List<Pair<String, String>>) {
    if (values.isEmpty()) return
    SectionTitle("추정 치수", "cm")
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            values.forEachIndexed { index, (label, value) ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface) {
                        Text("$value cm", Modifier.padding(horizontal = 14.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RegistrationMethodSheet(onDismiss: () -> Unit, onWornPhoto: (() -> Unit)?, onCapture: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("어떻게 등록할까요?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            onWornPhoto?.let { RegistrationMethodCard("착용 사진으로 등록", "내가 입은 사진에서 옷을 찾아요", it) }
            RegistrationMethodCard("직접 촬영하기", "옷을 펼쳐 찍고 치수를 측정해요", onCapture)
        }
    }
}

@Composable
private fun RegistrationMethodCard(title: String, description: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

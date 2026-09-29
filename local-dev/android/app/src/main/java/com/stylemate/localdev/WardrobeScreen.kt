package com.stylemate.localdev

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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

data class WardrobeItem(val id: String, val name: String, val category: String, val style: String? = null,
                        val thumbnail: ImageBitmap? = null, val imageUrl: String? = null)

@Composable
fun WardrobeRoute(onOpenProbe: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val files = remember { CaptureFiles(context.applicationContext) }
    val preferences = remember { context.getSharedPreferences("camera-permission", 0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var measurementBusy by rememberSaveable { mutableStateOf(false) }
    var selectedMeasurements by rememberSaveable { mutableStateOf<String?>(null) }
    val analysisModel: GarmentAnalysisViewModel = viewModel()
    val analysis by analysisModel.state.collectAsStateWithLifecycle()
    val wardrobeModel: WardrobeViewModel = viewModel()
    val wardrobe by wardrobeModel.state.collectAsStateWithLifecycle()
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(selected) { analysisModel.select(selected) }
    LaunchedEffect(wardrobe.savedId) {
        if (wardrobe.savedId != null) {
            files.delete(selected)
            selected = null
            selectedMeasurements = null
            editingId = null
            message = null
            wardrobeModel.clearSaveState()
        }
    }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { files.cleanExpired() } }
    LaunchedEffect(selected) {
        preview = null
        val name = selected ?: return@LaunchedEffect
        previewLoading = true
        preview = withContext(Dispatchers.IO) { runCatching { files.preview(name)?.asImageBitmap() }.getOrNull() }
        previewLoading = false
        if (preview == null) message = "사진을 읽을 수 없습니다. 다시 촬영해 주세요."
    }

    val measurementCamera = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        measurementBusy = false
        val name = result.data?.getStringExtra("capture_name")
        if (result.resultCode == Activity.RESULT_OK && name != null && files.exists(name)) {
            files.delete(selected)
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
        try { measurementCamera.launch(Intent(context, MeasurementActivity::class.java)) }
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
    fun discard() { files.delete(selected); selected = null; selectedMeasurements = null; message = null }
    BackHandler(selected != null || editingId != null) {
        if (!wardrobe.saving) { discard(); editingId = null; wardrobeModel.clearSaveState() }
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
    if (selected == null && editingId == null) {
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
            onCapture = { wardrobeModel.clearSaveState(); takePhoto() }, onOpenProbe = onOpenProbe,
            onItemClick = { wardrobeModel.clearSaveState(); editingId = it.id },
            loading = wardrobe.loading, onRefresh = { wardrobeModel.refresh() })
    } else {
        Scaffold { insets ->
            Column(Modifier.fillMaxSize().padding(insets).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = { discard(); editingId = null; wardrobeModel.clearSaveState() }, enabled = !wardrobe.saving) { Text("옷장으로 돌아가기") }
                if (editingId != null) {
                    if (editing != null) {
                        GarmentPhoto(editing.getString("image_url"), editing.getJSONObject("attributes").getString("name"))
                        wardrobe.catalog?.let { catalog ->
                            GarmentEditor(editing.getString("id"), editing.getJSONObject("attributes"), editing.optJSONObject("dimensions"),
                                editing.getJSONObject("user_properties"), editing.getString("notes"), catalog,
                                wardrobe.saving || wardrobe.loading, true, wardrobe.saveError,
                                onSave = { wardrobeModel.save(editing.getString("id"), it) })
                        }
                    } else if (wardrobe.loading) CircularProgressIndicator()
                    else Text("옷 정보를 불러오지 못했습니다.")
                } else {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                        preview?.let { Image(it, "촬영한 옷 사진", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                        if (previewLoading) CircularProgressIndicator()
                    }
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val result = analysis.result
                    if (result == null) {
                        selectedMeasurements?.let { payload ->
                            Text("추정 치수", style = MaterialTheme.typography.titleMedium)
                            val dimensions = remember(payload) { JSONObject(payload).getJSONObject("dimensions") }
                            (topMeasurementFields + bottomMeasurementFields).distinctBy { it.key }.forEach { field ->
                                dimensions.optJSONObject(field.key)?.let { value ->
                                    Text("${field.label}: ${String.format(java.util.Locale.KOREA, "%.2f", value.getDouble("value"))} cm")
                                }
                            }
                        }
                        Button(onClick = { selected?.let { analysisModel.analyze(context, it) } },
                            enabled = !analysis.busy && !measurementBusy && preview != null,
                            modifier = Modifier.fillMaxWidth()) { Text(if (analysis.busy) "분석 중" else "분석하기") }
                        if (analysis.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        analysis.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = { takePhoto() }, enabled = !measurementBusy && !analysis.busy, modifier = Modifier.fillMaxWidth()) { Text("다시 촬영") }
                    } else {
                        val measurements = selectedMeasurements?.let { JSONObject(it) }
                        val attrs = result.getJSONObject("attributes")
                        val measuredCategory = measurements?.optString("category")
                        val sameCategory = measuredCategory == attrs.optString("category") || measuredCategory == "top" && attrs.optString("category") == "outerwear"
                        if (measurements != null && !sameCategory) Text("옷 종류가 달라 치수를 다시 입력해 주세요.", color = MaterialTheme.colorScheme.error)
                        wardrobe.catalog?.let { catalog ->
                            GarmentEditor(result.getString("id"), attrs, if (sameCategory) measurements?.optJSONObject("dimensions") else null,
                                JSONObject(), "", catalog, wardrobe.saving || wardrobe.loading, false, wardrobe.saveError,
                                onSave = { wardrobeModel.save(result.getString("id"), it) })
                        }
                    }
                }
                if (wardrobe.catalog == null) {
                    wardrobe.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { wardrobeModel.refresh() }, enabled = !wardrobe.loading) { Text("다시 불러오기") }
                }
            }
        }
    }
}

@Composable
fun WardrobeScreen(items: List<WardrobeItem>, message: String?, cameraBusy: Boolean,
                   onCapture: () -> Unit, onOpenProbe: () -> Unit,
                   onItemClick: (WardrobeItem) -> Unit = {}, loading: Boolean = false, onRefresh: () -> Unit = {}) {
    var category by rememberSaveable { mutableStateOf("all") }
    val categories = listOf("all" to "전체", "top" to "상의", "bottom" to "하의", "outerwear" to "아우터", "shoes" to "신발")
    val visible = items.filter { category == "all" || it.category == category }
    val configuration = LocalConfiguration.current
    val columns = if (configuration.screenWidthDp < 352 || configuration.fontScale > 1.3f) 2 else 3
    Scaffold(
        bottomBar = {
            Surface(shadowElevation = 4.dp) {
              Column(Modifier.navigationBarsPadding().padding(20.dp)) {
                Button(onClick = onCapture, enabled = !cameraBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (cameraBusy) "촬영 중" else "촬영하기")
                }
              }
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(8.dp))
            Text("내 옷장", style = MaterialTheme.typography.headlineMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("총 ${items.size}벌", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRefresh, enabled = !loading) { Text("새로고침") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { (code, label) ->
                    FilterChip(selected = category == code, onClick = { category = code }, label = { Text(label) })
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (visible.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (items.isEmpty()) "아직 등록된 옷이 없습니다" else "이 카테고리에 등록된 옷이 없습니다", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("옷 한 벌이 잘 보이도록 촬영해 주세요.")
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(visible, key = { it.id }) { garment ->
                        Card(onClick = { onItemClick(garment) }) {
                            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                                if (garment.thumbnail != null) Image(garment.thumbnail, garment.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                else if (garment.imageUrl != null) GarmentPhoto(garment.imageUrl, garment.name)
                                else Text("사진 없음", style = MaterialTheme.typography.labelSmall)
                            }
                            Text(garment.name, Modifier.padding(horizontal = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            garment.style?.let { Text(it, Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
            TextButton(onClick = onOpenProbe) { Text("개발용 DB 연결 확인") }
        }
    }
}


@Composable
private fun GarmentPhoto(path: String, name: String) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(path) { mutableStateOf(true) }
    LaunchedEffect(path) {
        try { bitmap = WardrobeRepository.thumbnail(path) }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { bitmap = null }
        finally { loading = false }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        if (loading) CircularProgressIndicator()
        else if (bitmap == null) Text("사진을 불러오지 못했습니다.")
    }
}

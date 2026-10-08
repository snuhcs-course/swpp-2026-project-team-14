// AI-generated with Codex, 2026-09-29, reviewed by Hyeon U Jeong
package com.swpp.stylemate.ui.wardrobe

import com.swpp.stylemate.data.wardrobe.*
import com.swpp.stylemate.ui.wardrobe.capture.MeasurementActivity
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

data class WardrobeItem(val id: String, val name: String, val category: String, val style: String? = null,
                        val thumbnail: ImageBitmap? = null, val imageUrl: String? = null)

@Composable
fun WardrobeRoute() {
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
    var savedPhoto by remember(editingId) { mutableStateOf<ImageBitmap?>(null) }
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
            onCapture = { wardrobeModel.clearSaveState(); takePhoto() },
            onItemClick = { wardrobeModel.clearSaveState(); editingId = it.id },
            loading = wardrobe.loading,
            onRetry = if (wardrobe.error != null) ({ wardrobeModel.refresh() }) else null)
    } else {
        val result = if (editingId == null) analysis.result else null
        val record = editing ?: result
        val catalog = wardrobe.catalog
        val measurements = selectedMeasurements?.let { JSONObject(it) }
        val measuredCategory = measurements?.optString("category")
        val attrs = record?.getJSONObject("attributes")
        val sameCategory = measuredCategory == attrs?.optString("category") || measuredCategory == "top" && attrs?.optString("category") == "outerwear"
        val back = { discard(); editingId = null; wardrobeModel.clearSaveState() }
        if (record != null && catalog != null) {
            GarmentEditor(
                record.getString("id"), record.getJSONObject("attributes"),
                if (editing != null) editing.optJSONObject("dimensions") else if (sameCategory) measurements?.optJSONObject("dimensions") else null,
                if (editing != null) editing.getString("notes") else "", catalog,
                wardrobe.saving || wardrobe.loading, editing != null, wardrobe.saveError,
                onSave = { wardrobeModel.save(record.getString("id"), it) }, onBack = back,
                image = if (editing != null) savedPhoto else preview,
                photo = {
                    if (editing != null) GarmentPhoto(editing.getString("image_url"), editing.getJSONObject("attributes").getString("name"),
                        onLoaded = { savedPhoto = it })
                    else {
                        PhotoFrame {
                            preview?.let { Image(it, "촬영한 옷 사진", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                            if (previewLoading) CircularProgressIndicator()
                        }
                        if (measurements != null && !sameCategory) Text("옷 종류가 달라 치수를 다시 입력해 주세요.",
                            Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                },
            )
        } else {
            ScreenScaffold(
                title = if (editingId != null) "옷 정보" else "촬영한 옷",
                primaryLabel = if (editingId != null || result != null) null else if (analysis.busy) "분석 중" else "분석하기",
                primaryEnabled = !analysis.busy && !measurementBusy && preview != null,
                onPrimary = { selected?.let { analysisModel.analyze(context, it) } },
                onBack = back,
                actionLabel = if (editingId == null && result == null) "다시 촬영" else null,
                onAction = { takePhoto() },
                navigationEnabled = !wardrobe.saving && !analysis.busy && !measurementBusy,
                contentWindowInsets = WindowInsets(0),
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
                   onItemClick: (WardrobeItem) -> Unit = {}, loading: Boolean = false, onRetry: (() -> Unit)? = null) {
    var category by rememberSaveable { mutableStateOf("all") }
    val categories = listOf("all" to "전체", "top" to "상의", "bottom" to "하의", "outerwear" to "아우터", "shoes" to "신발")
    val visible = items.filter { category == "all" || it.category == category }
    val configuration = LocalConfiguration.current
    val columns = if (configuration.screenWidthDp < 352 || configuration.fontScale > 1.3f) 2 else 3
    val nameStyle = MaterialTheme.typography.bodyMedium
    val detailStyle = MaterialTheme.typography.bodySmall
    val cardTextHeight = with(LocalDensity.current) { nameStyle.lineHeight.toDp() * 2 + detailStyle.lineHeight.toDp() } + 4.dp
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            PrimaryActionBar(if (cameraBusy) "촬영 중" else "촬영하기", !cameraBusy, onCapture)
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(16.dp))
            Text("내 옷장", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
                    Text("옷 한 벌이 잘 보이도록 촬영해 주세요.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(visible, key = { it.id }) { garment ->
                        Card(onClick = { onItemClick(garment) }, shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                                if (garment.thumbnail != null) Image(garment.thumbnail, garment.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                else if (garment.imageUrl != null) GarmentPhoto(garment.imageUrl, garment.name)
                                else Text("사진 없음", style = MaterialTheme.typography.labelSmall)
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
private fun GarmentPhoto(path: String, name: String, onLoaded: (ImageBitmap?) -> Unit = {}) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(path) { mutableStateOf(true) }
    LaunchedEffect(path) {
        onLoaded(null)
        try { bitmap = WardrobeRepository.thumbnail(path) }
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

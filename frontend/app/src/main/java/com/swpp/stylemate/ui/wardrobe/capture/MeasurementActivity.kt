package com.swpp.stylemate.ui.wardrobe.capture

import com.swpp.stylemate.data.wardrobe.*
import com.swpp.stylemate.ui.theme.StyleMateTheme
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.graphics.Bitmap
import android.os.SystemClock
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.round

data class SelectedMeasurement(val field: MeasurementField, val points: List<Offset>, val cm: Float, val assisted: Boolean = false)

class MeasurementActivity : ComponentActivity() {
    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: MeasurementCamera
    private var session: Session? = null
    private var installRequested = false
    private var snapshot by mutableStateOf<MeasurementSnapshot?>(null)
    private var status by mutableStateOf("AR 지원 여부를 확인하고 있습니다.")
    private var ready by mutableStateOf(false)
    private var capturing by mutableStateOf(false)
    private var saving by mutableStateOf(false)
    private var category by mutableStateOf("top")
    private var landmarkType by mutableStateOf<String?>("short_sleeve_top")
    private var landmarkBusy by mutableStateOf(false)
    private var landmarkStatus by mutableStateOf("")
    private var cameraResumed by mutableStateOf(false)
    private var livePairs by mutableStateOf<Map<String, LandmarkPath>>(emptyMap())
    private var liveJob: Job? = null
    private var landmarkEpoch = 0
    private var lastPreviewTime = 0L
    private var livePose: com.google.ar.core.Pose? = null
    private var removeBackground by mutableStateOf(true)
    private var processedPreview by mutableStateOf<Bitmap?>(null)
    private var retryAfter = 0L
    private var fieldIndex by mutableIntStateOf(0)
    private var first by mutableStateOf<Offset?>(null)
    private val measurements = mutableStateMapOf<String, SelectedMeasurement>()
    private val fields get() = if (category == "bottom") bottomMeasurementFields.filter {
        landmarkType != "skirt" || it.key in setOf("waist_width_half", "hip_width_half", "total_length")
    } else topMeasurementFields

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderer = MeasurementCamera(
            rotation = { surface.display?.rotation ?: 0 },
            onStatus = { text, canCapture -> runOnUiThread {
                if (snapshot == null && !isDestroyed) { status = text; ready = canCapture; capturing = false }
            } },
            onCapture = { captured -> runOnUiThread {
                if (isFinishing || isDestroyed) captured.bitmap.recycle() else {
                    landmarkEpoch++
                    livePairs = emptyMap()
                    snapshot = captured
                    capturing = false
                    surface.onPause()
                    session?.pause()
                    status = ""
                }
            } },
            onPreview = { preview -> runOnUiThread { detectPreview(preview) } },
        )
        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
        }
        setContent {
            StyleMateTheme {
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        TextButton(onClick = { finish() }, enabled = !saving) { Text("취소") }
                        Text("옷 실측", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
                        val captured = snapshot
                        if (captured == null) LiveCamera() else FrozenMeasurement(captured)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        cameraResumed = true
        if (snapshot == null) startAr()
    }

    private fun startAr() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status = "카메라 권한이 필요합니다. 옷장으로 돌아가 권한을 허용해 주세요."
            return
        }
        ArCoreApk.getInstance().checkAvailabilityAsync(this) { availability ->
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || snapshot != null) return@checkAvailabilityAsync
            if (!availability.isSupported) {
                status = "AR을 사용할 수 없습니다. Google Play Services for AR을 확인해 주세요."
                return@checkAvailabilityAsync
            }
            try {
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true
                    status = "Google Play Services for AR 설치 후 돌아와 주세요."
                    return@checkAvailabilityAsync
                }
                val active = session ?: Session(this).also {
                    it.configure(Config(it).apply { planeFindingMode = Config.PlaneFindingMode.HORIZONTAL })
                    session = it
                }
                renderer.resetStatus()
                active.resume()
                renderer.session = active
                surface.onResume()
            } catch (_: Exception) {
                ready = false
                status = "AR 카메라를 시작하지 못했습니다. AR 서비스·카메라 사용 상태를 확인한 뒤 다시 열어 주세요."
            }
        }
    }

    override fun onPause() {
        cameraResumed = false
        if (snapshot == null) landmarkEpoch++
        livePairs = emptyMap()
        renderer.previewRequested.set(false)
        surface.onPause()
        renderer.captureRequested.set(false)
        capturing = false
        ready = false
        session?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        renderer.session = null
        session?.close()
        super.onDestroy()
    }

    @Composable
    private fun ColumnScope.LiveCamera() {
        LaunchedEffect(cameraResumed, landmarkType) {
            while (isActive && cameraResumed) {
                if (SystemClock.uptimeMillis() - lastPreviewTime > 1200 || livePose?.let { !renderer.matchesPreview(it) } == true) livePairs = emptyMap()
                if (landmarkType != null && liveJob?.isCompleted != false && SystemClock.uptimeMillis() >= retryAfter) renderer.previewRequested.set(true)
                delay(500)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (landmarkTypes.toList() + (null to "직접 지정")).forEach { (type, label) ->
                FilterChip(selected = landmarkType == type, onClick = {
                    landmarkEpoch++; livePairs = emptyMap(); landmarkType = type; fieldIndex = 0
                    category = landmarkCategory(type)
                    retryAfter = 0; landmarkStatus = ""
                }, label = { Text(label) })
            }
        }
        Text("옷을 평평하게 펴세요. 상의는 뒷면을 촬영하세요.", Modifier.padding(16.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                livePairs.values.forEach { path ->
                    val points = path.points.map { Offset(it.x * size.width, it.y * size.height) }
                    points.zipWithNext().forEach { (a, b) -> drawLine(Color.Cyan, a, b, 2.dp.toPx()) }
                    points.forEach { drawCircle(Color.Yellow, 5.dp.toPx(), it) }
                }
            }
            Text("+", color = if (ready) Color.Green else Color.White, style = MaterialTheme.typography.headlineLarge)
        }
        if (landmarkStatus.isNotEmpty()) Text(landmarkStatus, Modifier.padding(horizontal = 16.dp))
        Text(status, Modifier.padding(16.dp))
        Button(onClick = { capturing = true; renderer.captureRequested.set(true) },
            enabled = ready && !capturing, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(if (capturing) "촬영 중" else "촬영")
        }
    }

    @Composable
    private fun ColumnScope.FrozenMeasurement(captured: MeasurementSnapshot) {
        LaunchedEffect(captured, landmarkType, removeBackground) { detectFrozen(captured) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("top" to "상의", "outerwear" to "아우터", "bottom" to "하의").forEach { (code, label) ->
                    FilterChip(selected = category == code, enabled = !saving && !landmarkBusy, onClick = {
                        if (category != code) {
                            category = code; fieldIndex = 0; first = null; measurements.clear()
                            landmarkType = when (code) { "bottom" -> "trousers"; "top" -> "short_sleeve_top"; else -> "long_sleeve_outerwear" }
                        }
                    }, label = { Text(label) })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                landmarkTypes.filterKeys { landmarkCategory(it) == category }.forEach { (type, label) ->
                    FilterChip(selected = landmarkType == type, enabled = !saving && !landmarkBusy, onClick = {
                        if (landmarkType != type) {
                            landmarkType = type; fieldIndex = 0; first = null; measurements.clear()
                        }
                    }, label = { Text(label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = removeBackground, onCheckedChange = { removeBackground = it }, enabled = !saving && !landmarkBusy)
                Text("배경 제외")
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.forEachIndexed { index, field ->
                    FilterChip(selected = fieldIndex == index, enabled = !saving && !landmarkBusy, onClick = { fieldIndex = index; first = null },
                        label = { Text(field.label + if (measurements.containsKey(field.key)) " ✓" else "") })
                }
            }
            val field = fields[fieldIndex]
            if (landmarkBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (landmarkStatus.isNotEmpty()) Text(landmarkStatus)
            Text(field.guide)
            Text(if (first == null) "시작점을 찍으세요." else "끝점을 찍으세요.")
            Box(Modifier.fillMaxWidth().aspectRatio(captured.bitmap.width.toFloat() / captured.bitmap.height)
                .pointerInput(field.key, first, saving, landmarkBusy) {
                    detectTapGestures { position ->
                        if (!saving && !landmarkBusy) selectPoint(captured, Offset(position.x / size.width, position.y / size.height))
                    }
                }) {
                Image((processedPreview ?: captured.bitmap).asImageBitmap(), "측정할 옷 사진", Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    measurements.values.forEach { measurement ->
                        val points = measurement.points.map { Offset(it.x * size.width, it.y * size.height) }
                        val color = if (measurement.field.key == field.key) Color.Yellow else Color.Cyan
                        points.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, 3.dp.toPx()) }
                        points.forEach { drawCircle(color, 5.dp.toPx(), it) }
                    }
                    first?.let { drawCircle(Color.Yellow, 6.dp.toPx(), Offset(it.x * size.width, it.y * size.height)) }
                }
            }
            if (status.isNotEmpty()) Text(status)
            if (measurements.isNotEmpty()) Text("추정 치수", style = MaterialTheme.typography.titleSmall)
            measurements.values.forEach { Text("${it.field.label}: ${String.format(Locale.KOREA, "%.2f", it.cm)} cm") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { first = null; measurements.remove(field.key) }, enabled = !saving && !landmarkBusy) { Text("지우기") }
                OutlinedButton(onClick = {
                    landmarkEpoch++; first = null; fieldIndex = 0; measurements.clear(); snapshot = null; ready = false
                    landmarkBusy = false; landmarkStatus = ""; livePairs = emptyMap(); processedPreview = null
                    status = "바닥을 다시 인식해 주세요."
                    startAr()
                }, enabled = !saving) { Text("재촬영") }
            }
        }
        Button(onClick = { finishMeasurement(captured) }, enabled = measurements.isNotEmpty() && first == null && !saving && !landmarkBusy,
            modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(if (saving) "사진 준비 중" else "확인")
        }
    }

    private fun detectPreview(preview: LandmarkPreview) {
        val bitmap = preview.bitmap
        val type = landmarkType
        if (!cameraResumed || snapshot != null || type == null || isDestroyed || liveJob?.isCompleted == false) {
            bitmap.recycle(); return
        }
        val epoch = landmarkEpoch
        val requested = SystemClock.uptimeMillis()
        liveJob = lifecycleScope.launch {
            try {
                val result = GarmentLandmarks.detect(bitmap, type)
                if (epoch == landmarkEpoch && snapshot == null && cameraResumed && SystemClock.uptimeMillis() - requested <= 1200 && renderer.matchesPreview(preview.pose)) {
                    livePairs = result.suggestions
                    lastPreviewTime = requested
                    livePose = preview.pose
                    landmarkStatus = if (livePairs.isEmpty()) "옷 전체를 화면에 맞춰 주세요." else "점 위치를 확인하세요."
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (epoch == landmarkEpoch && snapshot == null) {
                    livePairs = emptyMap()
                    landmarkStatus = error.message ?: "점을 찾지 못했습니다."
                    retryAfter = SystemClock.uptimeMillis() + 3000
                }
            } finally { bitmap.recycle() }
        }
    }

    private suspend fun detectFrozen(captured: MeasurementSnapshot) {
        val type = landmarkType ?: run { landmarkBusy = false; landmarkStatus = ""; return }
        val epoch = ++landmarkEpoch
        landmarkBusy = true
        processedPreview = null
        landmarkStatus = "점 찾는 중"
        measurements.keys.filter { measurements[it]?.assisted == true }.toList().forEach { measurements.remove(it) }
        try {
            liveJob?.join()
            val result = GarmentLandmarks.detect(captured.bitmap, type, removeBackground)
            if (epoch != landmarkEpoch || snapshot !== captured) return
            processedPreview = result.preview
            result.suggestions.forEach { (key, path) ->
                if (measurements[key]?.assisted == false) return@forEach
                val field = fields.find { it.key == key } ?: return@forEach
                val world = path.points.map { captured.point(it.x, it.y) ?: return@forEach }
                val cm = round(pathLengthCm(world) * 100) / 100
                if (cm.isFinite() && cm > 0) measurements[key] = SelectedMeasurement(field,
                    path.points.map { Offset(it.x, it.y) }, cm, assisted = true)
            }
            landmarkStatus = if (removeBackground && !result.backgroundRemoved) "배경 분리 실패 · 원본에서 탐지했습니다."
                else if (measurements.isEmpty()) "점을 직접 지정해 주세요." else "점 위치를 확인하세요."
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { if (epoch == landmarkEpoch) landmarkStatus = error.message ?: "점을 직접 지정해 주세요." }
        finally { if (epoch == landmarkEpoch) landmarkBusy = false }
    }

    private fun selectPoint(captured: MeasurementSnapshot, point: Offset) {
        val world = captured.point(point.x, point.y)
        if (world == null) {
            status = "바닥과 만나는 점을 계산할 수 없습니다. 위에서 다시 촬영해 주세요."
            return
        }
        val field = fields[fieldIndex]
        val start = first
        if (start == null) {
            measurements.remove(field.key)
            first = point
            status = ""
        } else {
            val startWorld = captured.point(start.x, start.y) ?: return
            val cm = round(distanceCm(startWorld, world) * 100) / 100
            if (!cm.isFinite() || cm <= 0f) { status = "서로 다른 두 지점을 찍으세요."; return }
            measurements[field.key] = SelectedMeasurement(field, listOf(start, point), cm)
            first = null
            status = ""
        }
    }

    private fun finishMeasurement(captured: MeasurementSnapshot) {
        if (saving || measurements.isEmpty() || first != null) return
        saving = true
        val dimensions = JSONObject().put("unit", "cm")
        val points = JSONObject()
        measurements.values.forEach {
            dimensions.put(it.field.key, JSONObject().put("value", java.math.BigDecimal.valueOf(it.cm.toDouble()).setScale(2, java.math.RoundingMode.HALF_UP))
                .put("source", if (it.assisted) "arcore_assisted" else "arcore_manual").put("method", it.field.method)
                .put("reference", if (it.assisted) "HRNet 제안점 · 사용자 확인 · 바닥 평면 기반 AR 추정" else "사용자 지정점 · 바닥 평면 기반 AR 추정"))
            points.put(it.field.key, JSONArray(it.points.map { point -> JSONArray(listOf(point.x, point.y)) }))
        }
        val payload = JSONObject().put("category", category).put("dimensions", dimensions)
            .put("measurement_capture", JSONObject().put("frame_timestamp_ns", captured.timestamp)
                .put("tilt_degrees", captured.tiltDegrees)
                .put("width", captured.bitmap.width).put("height", captured.bitmap.height)
                .put("points_normalized", points).put("validated_accuracy", false)).toString()
        lifecycleScope.launch {
            val files = CaptureFiles(applicationContext)
            var name: String? = null
            try {
                withContext(Dispatchers.IO) {
                    name = files.create()
                    files.write(checkNotNull(name), captured.bitmap)
                }
                setResult(RESULT_OK, Intent().putExtra("capture_name", name).putExtra("measurements", payload))
                finish()
            } catch (_: Exception) {
                files.delete(name)
                status = "사진을 준비하지 못했습니다. 저장 공간을 확인한 후 다시 시도해 주세요."
                saving = false
            }
        }
    }
}

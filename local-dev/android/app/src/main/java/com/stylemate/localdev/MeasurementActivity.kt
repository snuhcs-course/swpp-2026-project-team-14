package com.stylemate.localdev

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.round

data class SelectedMeasurement(val field: MeasurementField, val first: Offset, val second: Offset, val cm: Float)

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
    private var fieldIndex by mutableIntStateOf(0)
    private var first by mutableStateOf<Offset?>(null)
    private val measurements = mutableStateMapOf<String, SelectedMeasurement>()
    private val fields get() = if (category == "bottom") bottomMeasurementFields else topMeasurementFields

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderer = MeasurementCamera(
            rotation = { surface.display?.rotation ?: 0 },
            onStatus = { text, canCapture -> runOnUiThread {
                if (snapshot == null && !isDestroyed) { status = text; ready = canCapture; capturing = false }
            } },
            onCapture = { captured -> runOnUiThread {
                if (isFinishing || isDestroyed) captured.bitmap.recycle() else {
                    snapshot = captured
                    capturing = false
                    surface.onPause()
                    session?.pause()
                    status = ""
                }
            } },
        )
        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
        }
        setContent {
            MaterialTheme {
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
        Text("옷을 평평하게 펴세요. 상의는 뒷면을 촬영하세요.", Modifier.padding(16.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())
            Text("+", color = if (ready) Color.Green else Color.White, style = MaterialTheme.typography.headlineLarge)
        }
        Text(status, Modifier.padding(16.dp))
        Button(onClick = { capturing = true; renderer.captureRequested.set(true) },
            enabled = ready && !capturing, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(if (capturing) "촬영 중" else "촬영")
        }
    }

    @Composable
    private fun ColumnScope.FrozenMeasurement(captured: MeasurementSnapshot) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("top" to "상의", "outerwear" to "얇은 아우터", "bottom" to "바지").forEach { (code, label) ->
                    FilterChip(selected = category == code, enabled = !saving, onClick = {
                        if (category != code) { category = code; fieldIndex = 0; first = null; measurements.clear() }
                    }, label = { Text(label) })
                }
            }
            if (measurements.isNotEmpty()) Text("종류 변경 시 측정값이 초기화됩니다.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.forEachIndexed { index, field ->
                    FilterChip(selected = fieldIndex == index, enabled = !saving, onClick = { fieldIndex = index; first = null },
                        label = { Text(field.label + if (measurements.containsKey(field.key)) " ✓" else "") })
                }
            }
            val field = fields[fieldIndex]
            Text(field.guide)
            Text(if (first == null) "시작점을 찍으세요." else "끝점을 찍으세요.")
            Box(Modifier.fillMaxWidth().aspectRatio(captured.bitmap.width.toFloat() / captured.bitmap.height)
                .pointerInput(field.key, first, saving) {
                    detectTapGestures { position ->
                        if (!saving) selectPoint(captured, Offset(position.x / size.width, position.y / size.height))
                    }
                }) {
                Image(captured.bitmap.asImageBitmap(), "측정할 옷 사진", Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    measurements.values.forEach { measurement ->
                        val a = Offset(measurement.first.x * size.width, measurement.first.y * size.height)
                        val b = Offset(measurement.second.x * size.width, measurement.second.y * size.height)
                        val color = if (measurement.field.key == field.key) Color.Yellow else Color.Cyan
                        drawLine(color, a, b, 3.dp.toPx())
                        drawCircle(color, 5.dp.toPx(), a)
                        drawCircle(color, 5.dp.toPx(), b)
                    }
                    first?.let { drawCircle(Color.Yellow, 6.dp.toPx(), Offset(it.x * size.width, it.y * size.height)) }
                }
            }
            if (status.isNotEmpty()) Text(status)
            if (measurements.isNotEmpty()) Text("추정 치수", style = MaterialTheme.typography.titleSmall)
            measurements.values.forEach { Text("${it.field.label}: ${String.format(Locale.KOREA, "%.2f", it.cm)} cm") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { first = null; measurements.remove(field.key) }, enabled = !saving) { Text("지우기") }
                OutlinedButton(onClick = {
                    first = null; measurements.clear(); snapshot = null; ready = false
                    status = "바닥을 다시 인식해 주세요."
                    startAr()
                }, enabled = !saving) { Text("재촬영") }
            }
        }
        Button(onClick = { finishMeasurement(captured) }, enabled = measurements.isNotEmpty() && first == null && !saving,
            modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(if (saving) "사진 준비 중" else "확인")
        }
    }

    private fun selectPoint(captured: MeasurementSnapshot, point: Offset) {
        val world = captured.point(point.x, point.y)
        if (world == null) {
            status = "인식한 바닥 범위 밖입니다. 주변 바닥을 더 비추고 위에서 재촬영해 주세요."
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
            measurements[field.key] = SelectedMeasurement(field, start, point, cm)
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
                .put("source", "arcore_manual").put("method", it.field.method)
                .put("reference", "사용자 지정점 · 바닥 평면 기반 AR 추정"))
            points.put(it.field.key, JSONArray().put(JSONArray(listOf(it.first.x, it.first.y)))
                .put(JSONArray(listOf(it.second.x, it.second.y))))
        }
        val payload = JSONObject().put("category", category).put("dimensions", dimensions)
            .put("measurement_capture", JSONObject().put("frame_timestamp_ns", captured.timestamp)
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

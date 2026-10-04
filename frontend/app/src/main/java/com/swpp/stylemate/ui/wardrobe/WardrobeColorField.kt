package com.swpp.stylemate.ui.wardrobe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import java.util.Locale
import kotlin.math.min

internal fun photoPixelAt(point: Offset, viewport: IntSize, width: Int, height: Int): IntOffset? {
    if (width <= 0 || height <= 0 || viewport.width <= 0 || viewport.height <= 0) return null
    val scale = min(viewport.width.toFloat() / width, viewport.height.toFloat() / height)
    val x = (point.x - (viewport.width - width * scale) / 2) / scale
    val y = (point.y - (viewport.height - height * scale) / 2) / scale
    if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x >= width || y >= height) return null
    return IntOffset(x.toInt(), y.toInt())
}

internal fun ImageBitmap.colorAt(pixel: IntOffset): String {
    val buffer = IntArray(1)
    readPixels(buffer, startX = pixel.x, startY = pixel.y, width = 1, height = 1)
    return String.format(Locale.ROOT, "#%06X", buffer[0] and 0xFFFFFF)
}

private fun swatchColor(hex: String) = Color(hex.toColorInt())

@Composable
fun WardrobeColorField(colors: List<String>, palette: Map<String, String>, limit: Int,
                       image: ImageBitmap?, enabled: Boolean, onChange: (List<String>) -> Unit) {
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    fun add(color: String) {
        if (color !in colors && colors.size < limit) onChange(colors + color)
        picker = null
    }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("색상", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { picker = "palette" }, enabled = enabled && colors.size < limit) { Text("색상표") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            colors.forEach { hex ->
                Box(Modifier.size(48.dp).clickable(enabled = enabled, onClick = { onChange(colors - hex) })
                    .semantics { contentDescription = "${palette[hex] ?: hex} 색상 삭제" }, contentAlignment = Alignment.Center) {
                    ColorDot(hex, Modifier.size(36.dp))
                    Icon(Icons.Default.Close, null, Modifier.align(Alignment.TopEnd).size(18.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape).padding(2.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedIconButton(onClick = { picker = "photo" }, enabled = enabled && image != null && colors.size < limit,
                modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Add, contentDescription = "사진에서 색상 추가") }
        }
    }
    if (picker == "palette" && enabled) {
        AlertDialog(onDismissRequest = { picker = null }, title = { Text("색상표") },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    palette.entries.toList().chunked(5).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { (hex, label) ->
                                Column(Modifier.weight(1f).clickable(enabled = hex !in colors, onClick = { add(hex) })
                                    .padding(vertical = 4.dp).semantics { contentDescription = "$label 선택" },
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(Modifier.size(36.dp).then(if (hex in colors)
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(4.dp) else Modifier)) {
                                        ColorDot(hex, Modifier.fillMaxSize())
                                    }
                                    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            }
                            repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { picker = null }) { Text("닫기") } })
    }
    if (picker == "photo" && image != null && enabled) {
        PhotoColorPicker(image, colors, onDismiss = { picker = null }, onAdd = ::add)
    }
}

@Composable
private fun ColorDot(hex: String, modifier: Modifier = Modifier) {
    Box(modifier.background(swatchColor(hex), CircleShape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
}

@Composable
private fun PhotoColorPicker(image: ImageBitmap, colors: List<String>, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var pixelX by rememberSaveable { mutableIntStateOf(-1) }
    var pixelY by rememberSaveable { mutableIntStateOf(-1) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("사진에서 색상 선택") },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surface)
                    .onSizeChanged { viewport = it }.testTag("color-photo")
                    .pointerInput(image, viewport) {
                        detectTapGestures { position ->
                            photoPixelAt(position, viewport, image.width, image.height)?.let { pixel ->
                                selected = image.colorAt(pixel)
                                pixelX = pixel.x
                                pixelY = pixel.y
                            }
                        }
                    }) {
                    Image(image, contentDescription = "색상을 선택할 옷 사진", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    if (selected != null) Canvas(Modifier.fillMaxSize()) {
                        val scale = min(size.width / image.width, size.height / image.height)
                        val center = Offset((size.width - image.width * scale) / 2 + (pixelX + .5f) * scale,
                            (size.height - image.height * scale) / 2 + (pixelY + .5f) * scale)
                        drawCircle(Color.Black, 10.dp.toPx(), center, style = Stroke(4.dp.toPx()))
                        drawCircle(Color.White, 10.dp.toPx(), center, style = Stroke(2.dp.toPx()))
                    }
                }
                Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    selected?.let { ColorDot(it, Modifier.size(32.dp).semantics { contentDescription = "선택한 색상 $it" }) }
                    Text(if (selected == null) "원하는 색을 눌러 주세요." else if (selected in colors) "이미 추가한 색상" else "선택한 색상",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }, confirmButton = {
            TextButton(onClick = { selected?.let(onAdd) }, enabled = selected != null && selected !in colors) { Text("추가") }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

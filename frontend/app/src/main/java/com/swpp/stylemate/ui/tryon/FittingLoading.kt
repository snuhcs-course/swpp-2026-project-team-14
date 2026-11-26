// AI-generated with Claude Code (Claude Opus 5.5), 2026-11-26, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.tryon

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import com.swpp.stylemate.ui.wardrobe.GarmentHangingShape
import com.swpp.stylemate.ui.wardrobe.defaultShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.tryon.FittingProgress
import com.swpp.stylemate.data.tryon.FittingTaskStore
import com.swpp.stylemate.data.tryon.FittingWork
import com.swpp.stylemate.data.tryon.VirtualFittingModels
import com.swpp.stylemate.ui.wardrobe.HangingGarmentImage
import com.swpp.stylemate.ui.wardrobe.OutfitDressed
import com.swpp.stylemate.ui.wardrobe.asWardrobeItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Where a bundled model's body is, as fractions of its photo (computed once from the photos, see anchors.json). */
internal data class BodyAnchors(
    val shoulderY: Float, val shoulderL: Float, val shoulderR: Float,
    val waistY: Float, val hipL: Float, val hipR: Float, val ankleY: Float,
    val neckY: Float = shoulderY,
)

/** The photo being dressed, and where its body is when it is a bundled virtual model. */
internal data class FittingPerson(val image: ImageBitmap, val anchors: BodyAnchors? = null)

/**
 * Where each garment cut-out goes on the person shown in [photo] (the photo's on-screen rect): tops and
 * outerwear hang from the shoulders by their own shoulder points, bottoms from the waist by their waistband,
 * so the outfit reads as worn while the real fitting is being made. Outerwear is a little wider to sit over
 * the top; a bottom never runs past the ankles.
 */
/** A dressed top or outerwear is at most this many shoulder widths wide, sleeves included. */
internal const val MAX_SPREAD = 1.45f

internal fun dressOnBody(anchors: BodyAnchors, photo: Rect, shapes: Map<String, GarmentHangingShape>): Map<String, Rect> {
    fun x(f: Float) = photo.left + f * photo.width
    fun y(f: Float) = photo.top + f * photo.height
    val shoulders = (anchors.shoulderR - anchors.shoulderL) * photo.width
    val middle = (x(anchors.shoulderL) + x(anchors.shoulderR)) / 2
    // A crooked hip reading (one leg only) falls back to the usual hip-to-shoulder ratio, centred under the shoulders.
    val hipsRead = anchors.hipR - anchors.hipL > (anchors.shoulderR - anchors.shoulderL) * .6f
    val hipSpan = if (hipsRead) (anchors.hipR - anchors.hipL) * photo.width else shoulders * .82f
    val hipMiddle = if (hipsRead) (x(anchors.hipL) + x(anchors.hipR)) / 2 else middle
    return buildMap {
        for ((slot, shape) in shapes) {
            val anchorSpan = (shape.right - shape.left).coerceAtLeast(.2f)
            val anchorMiddle = (shape.left + shape.right) / 2
            when (slot) {
                "top", "outerwear" -> {
                    // Flat-lay sleeves spread out, so the whole piece is capped near the body's width.
                    val over = if (slot == "outerwear") 1.1f else 1f
                    val width = minOf(shoulders * over / anchorSpan, shoulders * over * MAX_SPREAD)
                    val height = width / shape.aspectRatio
                    val left = middle - anchorMiddle * width
                    // A collar sits at the neck, never over the chin.
                    val top = maxOf(y(anchors.shoulderY) - shape.supportY * height, y(anchors.neckY) - .012f * photo.height)
                    put(slot, Rect(left, top, left + width, top + height))
                }
                "bottom" -> {
                    var width = hipSpan / anchorSpan
                    var height = width / shape.aspectRatio
                    val legs = (y(anchors.ankleY) - y(anchors.waistY)) * 1.04f
                    if (height > legs) { width *= legs / height; height = legs }
                    val left = hipMiddle - anchorMiddle * width
                    val top = y(anchors.waistY) - shape.supportY * height
                    put(slot, Rect(left, top, left + width, top + height))
                }
            }
        }
    }
}

/**
 * The fitting screen while a fitting is made (design A, scan reveal). A scan line comes down at the real
 * progress; above it the person is in colour and, for a bundled model, already wears the outfit's cut-outs,
 * so the clothes go on as the line passes. Below it the person is soft and grey. Without the photo (a gallery
 * photo is removed after upload) the outfit itself is revealed. The AI dresses the whole outfit at once, so
 * the text only says how far along it is. Closing keeps the fitting going under [FittingProgressCard].
 */
@Composable
internal fun FittingScanLoading(
    outfit: Outfit?,
    person: FittingPerson?,
    progress: FittingProgress?,
    notificationsEnabled: Boolean,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val target = progress?.fraction ?: .05f
    val fraction by animateFloatAsState(target, tween(900), label = "fitting")
    val breathe by rememberInfiniteTransition(label = "scan").animateFloat(-.008f, .008f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "line")
    val line = (fraction + breathe).coerceIn(0f, 1f)
    val panel = MaterialTheme.colorScheme.surfaceContainerLow
    val ink = MaterialTheme.colorScheme.primary
    val items = outfit?.items.orEmpty()
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)).background(panel)
            .semantics { contentDescription = "가상 피팅 진행 ${(fraction * 100).toInt()}%, ${progress?.text ?: "준비 중"}" }) {
            val density = LocalDensity.current
            val inset = with(density) { 18.dp.toPx() }
            val boxW = with(density) { maxWidth.toPx() }
            val boxH = with(density) { maxHeight.toPx() }
            val lineY = boxH * line
            val revealed = Modifier.fillMaxSize().drawWithContent {
                clipRect(bottom = lineY) { this@drawWithContent.drawContent() }
            }
            if (person != null) {
                val grey = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
                Image(person.image, null, Modifier.fillMaxSize().padding(18.dp).blur(10.dp), contentScale = ContentScale.Fit,
                    colorFilter = grey, alpha = .5f)
                Box(revealed) {
                    Image(person.image, null, Modifier.fillMaxSize().padding(18.dp), contentScale = ContentScale.Fit)
                    val anchors = person.anchors
                    if (anchors != null && items.isNotEmpty()) {
                        // The photo's rect inside the panel (ContentScale.Fit, centred).
                        val availW = boxW - 2 * inset
                        val availH = boxH - 2 * inset
                        val scale = minOf(availW / person.image.width, availH / person.image.height)
                        val w = person.image.width * scale
                        val h = person.image.height * scale
                        val photo = Rect(inset + (availW - w) / 2, inset + (availH - h) / 2,
                            inset + (availW + w) / 2, inset + (availH + h) / 2)
                        val shapes = remember(items) { mutableStateMapOf<String, GarmentHangingShape>() }
                        val placed = dressOnBody(anchors, photo, items.associate { it.slot to (shapes[it.slot] ?: defaultShape(it.slot)) })
                        // Bottom first, then the top, then the outerwear worn over it.
                        items.sortedBy { listOf("bottom", "top", "outerwear").indexOf(it.slot) }.forEach { garment ->
                            val rect = placed[garment.slot] ?: return@forEach
                            key(garment.garmentId) {
                                HangingGarmentImage(garment.asWardrobeItem(), with(density) {
                                    Modifier.offset(rect.left.toDp(), rect.top.toDp()).size(rect.width.toDp(), rect.height.toDp())
                                }, onHangingShape = { shapes[garment.slot] = it }, alignment = Alignment.TopCenter)
                            }
                        }
                    }
                }
            } else if (items.isNotEmpty()) {
                OutfitDressed(items, Modifier.fillMaxSize().padding(18.dp).alpha(.22f))
                OutfitDressed(items, revealed.padding(18.dp))
            }
            Canvas(Modifier.fillMaxSize()) {
                val glow = 56.dp.toPx()
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = .55f)),
                    startY = lineY - glow, endY = lineY), Offset(0f, lineY - glow), Size(size.width, glow))
                drawLine(ink, Offset(0f, lineY), Offset(size.width, lineY), 1.5.dp.toPx())
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("가상 피팅을 만드는 중입니다.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth().height(3.dp), color = ink,
            trackColor = MaterialTheme.colorScheme.outlineVariant, drawStopIndicator = {})
        Spacer(Modifier.height(8.dp))
        Text(progress?.text ?: "준비하고 있어요", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("창을 닫아도 계속 만들어요.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (notificationsEnabled) "완료되면 알림을 보내드립니다." else "완료 후 코디에서 결과를 확인해 주세요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.medium) { Text("확인") }
    }
}

/** The bundled model photo and its body anchors for a virtual-model source (`virtual:<id>:v<n>`); null for a gallery photo. */
internal suspend fun virtualModelPreview(context: Context, source: String?): FittingPerson? {
    val model = VirtualFittingModels.all.firstOrNull { it.sourceKey == source } ?: return null
    return withContext(Dispatchers.IO) {
        val image = runCatching { context.assets.open(model.assetPath).use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }
            .getOrNull() ?: return@withContext null
        val anchors = runCatching {
            val all = org.json.JSONObject(context.assets.open("tryon/models/v1/anchors.json").bufferedReader().use { it.readText() })
            all.optJSONObject(model.id)?.let { a ->
                fun f(name: String) = a.getDouble(name).toFloat()
                BodyAnchors(f("shoulderY"), f("shoulderL"), f("shoulderR"), f("waistY"), f("hipL"), f("hipR"), f("ankleY"),
                    a.optDouble("neckY", a.getDouble("shoulderY")).toFloat())
            }
        }.getOrNull()
        FittingPerson(image, anchors)
    }
}

/**
 * Design C: while a fitting of [owner] is being made, a small card over the tabs shows its real progress, and
 * after it finishes it offers the result until opened or closed. Tapping it opens the fitting screen.
 */
@Composable
internal fun FittingProgressCard(owner: String, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val work = remember(context) { FittingWork(context) }
    var card by remember(owner) { mutableStateOf<Pair<String, org.json.JSONObject>?>(null) }
    var avatar by remember(owner) { mutableStateOf<Pair<String, FittingPerson?>?>(null) }
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) { resumed = true; onPauseOrDispose { resumed = false } }
    LaunchedEffect(owner, resumed) {
        while (resumed) {
            try {
                val chosen = withContext(Dispatchers.IO) {
                    FittingTaskStore.cardTask(work.tasks.all(owner), System.currentTimeMillis())
                }
                // state() also closes a task whose background work died.
                card = chosen?.let { (key, _) -> work.state(key)?.let { key to it } }
                val source = card?.second?.optString("source")
                if (source != null && avatar?.first != source) avatar = source to virtualModelPreview(context, source)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { card = null }
            delay(1_000)
        }
    }
    val (key, data) = card ?: return
    val state = data.optString("state")
    val progress = FittingTaskStore.progress(data)
    val name = runCatching { FittingTaskStore.outfit(data).name }.getOrNull().orEmpty()
    fun dismiss() { card = null; runCatching { work.tasks.dismissCard(key) } }
    val title: String
    val detail: String
    when (state) {
        "completed" -> { title = "가상 피팅이 완성됐어요"; detail = "눌러서 결과를 확인하세요" }
        "failed" -> { title = "가상 피팅을 만들지 못했어요"; detail = data.optString("error").ifBlank { "눌러서 다시 시도해 주세요" } }
        else -> { title = if (name.isBlank()) "가상 피팅 만드는 중" else "$name 입혀보는 중"
            detail = progress?.text ?: "준비하고 있어요" }
    }
    val shape = RoundedCornerShape(20.dp)
    Row(modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
        .clickable { if (state == "completed" || state == "failed") dismiss(); onOpen(key) }
        .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)
        .semantics(mergeDescendants = true) { contentDescription = "$title, $detail" },
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (state == "queued" || state == "running") {
                val fraction by animateFloatAsState(progress?.fraction ?: .05f, tween(600), label = "card")
                CircularProgressIndicator(progress = { fraction }, Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.5.dp, trackColor = MaterialTheme.colorScheme.outlineVariant, gapSize = 0.dp)
            }
            val face = avatar?.second?.image
            val inner = Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerLow)
            // The full-body model photo, zoomed in on the face.
            if (face != null) Box(inner) {
                Image(face, null, Modifier.fillMaxSize().graphicsLayer {
                    scaleX = 2.4f; scaleY = 2.4f; transformOrigin = TransformOrigin(.5f, .12f)
                }, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            } else Box(inner, contentAlignment = Alignment.Center) {
                Icon(if (state == "completed") Icons.Filled.Check else Icons.Filled.Person, null, Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (state == "completed" || state == "failed") IconButton(onClick = ::dismiss) {
            Icon(Icons.Filled.Close, "알림 닫기", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Icon(Icons.Filled.KeyboardArrowRight, null, Modifier.padding(end = 8.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

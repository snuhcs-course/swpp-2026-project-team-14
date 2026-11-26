// AI-generated with Claude Code (Claude Opus 5.5), 2026-11-26, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.tryon

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.spring
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.swpp.stylemate.ui.wardrobe.OutfitDressed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The photo being dressed: the bundled virtual model, or the gallery photo while this screen keeps it. */
internal data class FittingPerson(val image: ImageBitmap, val isModel: Boolean = false)

/** easeInOutSine: the sweep slows gently at each end instead of snapping round. */
private val SineInOut = CubicBezierEasing(.37f, 0f, .63f, 1f)

/**
 * The fitting screen while a fitting is made, and the reveal once it is done. The AI returns the finished
 * picture in one piece, so while it works the photo to be dressed stays as it is and a scan line sweeps over
 * it. When [result] arrives, the same line glides back to the top from wherever it is and comes down again,
 * the result above it and the photo before fitting below (without that photo, a soft grey copy of the
 * result), then [onRevealed] is called. Without any photo the outfit is shown. Closing keeps the fitting
 * going under [FittingProgressCard].
 *
 * The line's position is read only while drawing, so the animation redraws the line and the clip, never the
 * whole screen.
 */
@Composable
internal fun FittingScanLoading(
    outfit: Outfit?,
    person: FittingPerson?,
    progress: FittingProgress?,
    notificationsEnabled: Boolean,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    result: ImageBitmap? = null,
    reveal: Boolean = true,
    onRevealed: () -> Unit = {},
    resultActions: @Composable () -> Unit = {},
) {
    val fraction by animateFloatAsState(if (result != null) 1f else progress?.fraction ?: .05f, tween(900), label = "fitting")
    val sweep = rememberInfiniteTransition(label = "scan").animateFloat(0f, 1f,
        infiniteRepeatable(tween(2800, easing = SineInOut), RepeatMode.Reverse), label = "sweep")
    val line = remember { Animatable(0f) }
    // 0: sweeping while it is made, 1: gliding back to the top, 2: revealing the result downwards,
    // 3: the result itself (straight away when an earlier result is reopened).
    var phase by remember { mutableIntStateOf(if (result != null && !reveal) 3 else 0) }
    val done by rememberUpdatedState(onRevealed)
    LaunchedEffect(result != null) {
        if (result == null || phase == 3) return@LaunchedEffect
        line.snapTo(sweep.value)
        phase = 1
        line.animateTo(0f, tween((line.value * 900).toInt().coerceAtLeast(120), easing = SineInOut))
        phase = 2
        line.animateTo(1f, tween(1800, easing = SineInOut))
        delay(200)
        phase = 3
        done()
    }
    val at = { if (phase == 0) sweep.value else line.value }
    val panel = MaterialTheme.colorScheme.surfaceContainerLow
    val ink = MaterialTheme.colorScheme.primary
    val items = outfit?.items.orEmpty()
    val grey = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)).background(panel)
            .then(if (phase == 3) Modifier else Modifier.semantics {
                contentDescription = "가상 피팅 진행 ${(fraction * 100).toInt()}%, ${progress?.text ?: "준비 중"}"
            })) {
            val inner = Modifier.fillMaxSize().padding(18.dp)
            when {
                person != null -> Image(person.image, null, inner, contentScale = ContentScale.Fit)
                result != null -> Image(result, null, inner.blur(12.dp), contentScale = ContentScale.Fit, colorFilter = grey, alpha = .55f)
                items.isNotEmpty() -> OutfitDressed(items, inner.alpha(.55f))
            }
            if (result != null && phase == 2) Image(result, null, Modifier.fillMaxSize().drawWithContent {
                clipRect(bottom = size.height * at()) { this@drawWithContent.drawContent() }
            }.padding(18.dp), contentScale = ContentScale.Fit)
            if (phase == 3 && result != null) Image(result, "생성된 피팅 이미지", Modifier.fillMaxSize().padding(18.dp),
                contentScale = ContentScale.Fit)
            else Canvas(Modifier.fillMaxSize()) {
                val y = size.height * at().coerceIn(0f, 1f)
                val glow = 64.dp.toPx()
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = .45f)),
                    startY = y - glow, endY = y), Offset(0f, y - glow), Size(size.width, glow))
                drawLine(ink, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx())
            }
        }
        // Below the panel: the progress while it is made, then the result's buttons; the panel grows smoothly.
        AnimatedContent(phase == 3, Modifier.fillMaxWidth(), label = "fitting actions", transitionSpec = {
            fadeIn(tween(350, delayMillis = 150)) togetherWith fadeOut(tween(200)) using SizeTransform(clip = false) { _, _ -> tween(450, easing = SineInOut) }
        }) { finished ->
            if (finished) Box(Modifier.fillMaxWidth().padding(top = 20.dp)) { resultActions() }
            else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(20.dp))
            Text(if (result != null) "가상 피팅이 완성됐어요." else "가상 피팅을 만드는 중입니다.",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth().height(3.dp), color = ink,
                trackColor = MaterialTheme.colorScheme.outlineVariant, drawStopIndicator = {})
            Spacer(Modifier.height(8.dp))
            Text(if (result != null) "결과를 보여드릴게요" else progress?.text ?: "준비하고 있어요",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    }
}

/** The bundled model photo for a virtual-model source (`virtual:<id>:v<n>`); null for a gallery photo. */
internal suspend fun virtualModelPreview(context: Context, source: String?): FittingPerson? {
    val model = VirtualFittingModels.all.firstOrNull { it.sourceKey == source } ?: return null
    return withContext(Dispatchers.IO) {
        runCatching { context.assets.open(model.assetPath).use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }
            .getOrNull()?.let { FittingPerson(it, isModel = true) }
    }
}

/** The person of a fitting task: its virtual model, or the device-only preview of its gallery photo. */
internal suspend fun fittingPerson(context: Context, tasks: FittingTaskStore, key: String, source: String?): FittingPerson? =
    virtualModelPreview(context, source) ?: withContext(Dispatchers.IO) {
        runCatching { tasks.preview(key)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap() }
            .getOrNull()?.let { FittingPerson(it) }
    }

/**
 * Design C: while a fitting of [owner] is being made, a small card over the tabs shows its real progress, and
 * after it finishes it offers the result until opened or closed. Tapping it opens the fitting screen.
 */
@Composable
internal fun FittingProgressCard(owner: String, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val work = remember(context) { FittingWork(context) }
    var summary by remember(owner) { mutableStateOf<com.swpp.stylemate.data.tryon.CardSummary?>(null) }
    var avatar by remember(owner) { mutableStateOf<Pair<String, FittingPerson?>?>(null) }
    // The finished picture itself, shown small on the card once a fitting is done (task key and image).
    var thumbnail by remember(owner) { mutableStateOf<Pair<String, ImageBitmap?>?>(null) }
    val account = com.swpp.stylemate.ui.LocalAccountAccess.current
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) { resumed = true; onPauseOrDispose { resumed = false } }
    LaunchedEffect(owner, resumed) {
        while (resumed) {
            try {
                // state() also closes a task whose background work died, so every running one is checked first.
                val tasks = withContext(Dispatchers.IO) { work.tasks.pruneStalePreviews(System.currentTimeMillis()); work.tasks.all(owner) }
                FittingTaskStore.running(tasks).forEach { (key, _) -> work.state(key) }
                summary = withContext(Dispatchers.IO) {
                    FittingTaskStore.cardSummary(work.tasks.all(owner), System.currentTimeMillis())
                }
                summary?.shown?.let { (shownKey, data) ->
                    // Keyed by this fitting (an outfit can be fitted again on someone else), and read again
                    // until the gallery preview, saved just after the task, is there.
                    val id = "$shownKey:${data.optLong("created_at")}"
                    if (avatar?.first != id || avatar?.second == null) avatar = id to fittingPerson(context, work.tasks, shownKey, data.optString("source"))
                }
                val shown = summary?.shown
                val done = shown?.let { "${it.first}:${it.second.optLong("finished_at")}" }
                if (shown != null && shown.second.optString("state") == "completed" && thumbnail?.first != done && account != null)
                    thumbnail = done!! to resultThumbnail(context, account, shown.second)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { summary = null }
            delay(1_000)
        }
    }
    val current = summary ?: return
    val (key, data) = current.shown
    val state = data.optString("state")
    val progress = FittingTaskStore.progress(data)
    val name = runCatching { FittingTaskStore.outfit(data).name }.getOrNull().orEmpty()
    fun dismiss() { summary = null; runCatching { work.tasks.dismissCard(key) } }
    val title: String
    val detail: String
    // With several fittings the card names how many more are under way, so none goes unnoticed.
    val alsoRunning = if (state == "queued" || state == "running") current.running - 1 else current.running
    val more = if (alsoRunning > 0) " · ${alsoRunning}개 더 만드는 중" else ""
    when (state) {
        "completed" -> {
            title = if (current.finished > 1) "가상 피팅 ${current.finished}개가 완성됐어요" else "가상 피팅이 완성됐어요"
            detail = "눌러서 결과를 확인하세요$more"
        }
        "failed" -> { title = "가상 피팅을 만들지 못했어요"; detail = data.optString("error").ifBlank { "눌러서 다시 시도해 주세요" } + more }
        else -> {
            title = when {
                current.running > 1 -> "가상 피팅 ${current.running}개 만드는 중"
                name.isBlank() -> "가상 피팅 만드는 중"
                else -> "$name 입혀보는 중"
            }
            detail = progress?.text ?: "준비하고 있어요"
        }
    }
    val shape = RoundedCornerShape(20.dp)
    val finished = state == "completed" || state == "failed"
    // A finished card can be swiped down to put it away, like a notification; it follows the finger and fades.
    val drag = remember(key) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val away = with(density) { 56.dp.toPx() }
    Row(modifier.fillMaxWidth()
        .offset { IntOffset(0, drag.value.roundToInt()) }
        .graphicsLayer { alpha = 1f - (drag.value / (away * 2.5f)).coerceIn(0f, .9f) }
        .draggable(rememberDraggableState { delta -> scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) } },
            Orientation.Vertical, enabled = finished, onDragStopped = { velocity ->
                if (drag.value > away || velocity > 1_200f) {
                    drag.animateTo(away * 4, tween(180))
                    dismiss()
                } else drag.animateTo(0f, spring())
            })
        .clip(shape).background(MaterialTheme.colorScheme.surface)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
        .clickable { summary = null; onOpen(key) }  // the fitting screen marks it seen once it shows it
        .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)
        .semantics(mergeDescendants = true) { contentDescription = "$title, $detail" },
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (state == "queued" || state == "running") {
                val fraction by animateFloatAsState(progress?.fraction ?: .05f, tween(600), label = "card")
                CircularProgressIndicator(progress = { fraction }, Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.5.dp, trackColor = MaterialTheme.colorScheme.outlineVariant, gapSize = 0.dp)
            }
            val picture = thumbnail?.takeIf { it.first == "$key:${data.optLong("finished_at")}" && state == "completed" }?.second
            val person = avatar?.takeIf { it.first == "$key:${data.optLong("created_at")}" }?.second
            val face = person?.image
            val inner = Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerLow)
            // A finished fitting shows its own picture; while one is made, the model's face.
            if (picture != null) Image(picture, "완성된 피팅 이미지", Modifier.size(36.dp, 48.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            else if (face != null) Box(inner) {
                // A model photo is full length, so it is zoomed in on the face; a gallery photo is shown as it is.
                val zoom = if (person.isModel) 2.4f else 1.3f
                Image(face, null, Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom; scaleY = zoom; transformOrigin = TransformOrigin(.5f, .12f)
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

/** A small copy of a finished fitting's picture, read from this device, for the card. */
private suspend fun resultThumbnail(context: Context, account: com.swpp.stylemate.data.AccountAccess, task: org.json.JSONObject): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val repository = com.swpp.stylemate.data.tryon.FittingRepository(
                com.swpp.stylemate.data.LocalUserStore.forContext(context, account.userId), account)
            val bytes = repository.result(FittingTaskStore.outfit(task), task.getString("source")) ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outHeight / (sample * 2) >= 200) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()
    }

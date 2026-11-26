// AI-generated with Codex and Claude Code (Dongkun Moon), 2026-11-25, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.wardrobe

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.data.wardrobe.WardrobeRepository
import com.swpp.stylemate.ui.LocalAccountAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private sealed interface GarmentAsset {
    data object Loading : GarmentAsset
    data object Missing : GarmentAsset
    data object Failed : GarmentAsset
    data object Uncertain : GarmentAsset
    data class Ready(val bitmap: ImageBitmap, val shape: GarmentHangingShape) : GarmentAsset
}

private val sourceRequests = Semaphore(3)
private val matteWork = Semaphore(2)

private suspend fun hangingAsset(bitmap: ImageBitmap, category: String): GarmentAsset.Ready =
    withContext(Dispatchers.Default) {
        val source = bitmap.asAndroidBitmap()
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        GarmentAsset.Ready(bitmap, garmentHangingShape(pixels, source.width, source.height, category))
    }

/** One display-size asset request per saved garment; direct previews keep the studio fallback. */
@Composable
internal fun HangingGarmentImage(
    item: WardrobeItem,
    modifier: Modifier = Modifier,
    onAspectRatio: ((Float) -> Unit)? = null,
    onHangingShape: ((GarmentHangingShape) -> Unit)? = null,
    alignment: Alignment = Alignment.TopCenter,
    loadDelayMillis: Long = 0,
) {
    val context = LocalContext.current.applicationContext
    val account = LocalAccountAccess.current
    var retry by remember(item.id, item.imageUrl, account?.userId) { mutableIntStateOf(0) }
    val state by produceState<GarmentAsset>(GarmentAsset.Loading, item, account, retry) {
        value = GarmentAsset.Loading
        if (item.thumbnail == null && item.imageUrl == null) {
            value = GarmentAsset.Missing
            return@produceState
        }
        try {
            // Let the center start first; quick swipes discard peripheral work before it starts.
            delay(loadDelayMillis)
            // No tokens are retained in cache keys; account ownership isolates otherwise equal paths.
            val key = "v4:${account?.userId}:${item.id}:${item.category}:${item.imageUrl}:${item.thumbnail?.let(System::identityHashCode)}"
            value = GarmentCutoutCache.load(key) {
                if (account != null && item.imageUrl?.endsWith("/image/") == true) {
                    val path = item.imageUrl.removeSuffix("image/") + "display-cutout/"
                    val derived = sourceRequests.withPermit { WardrobeRepository.thumbnail(context, path, account) }
                    return@load derived?.let { hangingAsset(it, item.category) } ?: GarmentAsset.Uncertain
                }
                val source = item.thumbnail ?: account?.let { owner ->
                    sourceRequests.withPermit {
                        WardrobeRepository.thumbnail(context, checkNotNull(item.imageUrl), owner)
                    }
                }
                val local = if (source == null) GarmentAsset.Failed else matteWork.withPermit { withContext(Dispatchers.Default) {
                    val original = source.asAndroidBitmap()
                    // Repository thumbnails are already bounded; callers can also supply a
                    // camera/preview bitmap directly. Bound that path before allocating masks.
                    val longestSide = max(original.width, original.height)
                    val bitmap = if (longestSide > 600) {
                        val scale = 600f / longestSide
                        Bitmap.createScaledBitmap(original,
                            (original.width * scale).roundToInt().coerceAtLeast(1),
                            (original.height * scale).roundToInt().coerceAtLeast(1), true)
                    } else original
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val cutout = studioCutout(pixels, bitmap.width, bitmap.height)
                    if (cutout == null) GarmentAsset.Uncertain else {
                        val cropped = Bitmap.createBitmap(cutout.pixels, cutout.width, cutout.height, Bitmap.Config.ARGB_8888)
                        hangingAsset(cropped.asImageBitmap(), item.category)
                    }
                } }
                local
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = GarmentAsset.Failed }
    }
    val latestAspectRatio by rememberUpdatedState(onAspectRatio)
    val latestShape by rememberUpdatedState(onHangingShape)
    var boundShape by remember(item.id, item.imageUrl, item.category) { mutableStateOf<GarmentHangingShape?>(null) }
    LaunchedEffect(state) {
        (state as? GarmentAsset.Ready)?.let {
            latestAspectRatio?.invoke(it.shape.aspectRatio)
            latestShape?.invoke(it.shape)
            boundShape = it.shape
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        when (val asset = state) {
            is GarmentAsset.Ready -> if (onHangingShape == null || boundShape == asset.shape) {
                // Publish anchors before the first visible frame, avoiding a one-frame
                // jump from the generic hanger size to the garment's measured silhouette.
                Image(asset.bitmap, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, alignment = alignment)
            }
            GarmentAsset.Loading -> Text("사진 준비 중", color = Color(0xFF999999), style = MaterialTheme.typography.labelSmall)
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
                Text(when (asset) {
                    GarmentAsset.Missing -> "사진 없음"
                    GarmentAsset.Uncertain -> "상세에서\n사진을 확인해 주세요"
                    else -> "사진을 불러오지 못했어요"
                }, color = Color(0xFF737373), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                if (asset == GarmentAsset.Failed || asset == GarmentAsset.Uncertain)
                    TextButton(onClick = { retry++ }) { Text("다시 시도") }
            }
        }
    }
}

private object GarmentCutoutCache {
    // Bounded RAM only: originals and account files are untouched, no derived private photos on disk.
    private val images = object : LruCache<String, GarmentAsset>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: GarmentAsset) =
            if (value is GarmentAsset.Ready) value.bitmap.width * value.bitmap.height * 4 else 64
    }
    private val guard = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = mutableMapOf<String, Deferred<GarmentAsset>>()
    suspend fun load(key: String, block: suspend () -> GarmentAsset): GarmentAsset {
        val work = synchronized(guard) {
            images.get(key)?.let { return it }
            pending.getOrPut(key) {
                scope.async(start = CoroutineStart.LAZY) {
                    try { block().also { if (it is GarmentAsset.Ready) images.put(key, it) } }
                    finally { synchronized(guard) { pending.remove(key) } }
                }
            }
        }
        // Scrolling an item off screen must not cancel an already running download and
        // repeat it when that item appears again. The bounded cache is shared by both views.
        return work.await()
    }
}

internal data class StudioCutout(val pixels: IntArray, val width: Int, val height: Int)

/** Border-connected matte, not a semantic segmenter. RGB pixels inside the matte are unchanged. */
internal fun studioCutout(source: IntArray, width: Int, height: Int): StudioCutout? {
    require(width > 0 && height > 0 && source.size == width * height)
    val pixels = source.copyOf()
    val transparent = source.count { (it ushr 24) < 16 }
    if (transparent.toLong() * 20 < source.size) {
        val border = ArrayList<Int>()
        val step = max(1, min(width, height) / 40)
        for (x in 0 until width step step) { border += source[x]; border += source[(height - 1) * width + x] }
        for (y in 0 until height step step) { border += source[y * width]; border += source[y * width + width - 1] }
        fun component(pixel: Int, shift: Int) = (pixel ushr shift) and 255
        val red = border.map { component(it, 16) }.sorted()[border.size / 2]
        val green = border.map { component(it, 8) }.sorted()[border.size / 2]
        val blue = border.map { component(it, 0) }.sorted()[border.size / 2]
        // Low-key/coloured/complex scenes need a real segmentation mask; don't silently damage them.
        if (min(red, min(green, blue)) < 175 || max(red, max(green, blue)) - min(red, min(green, blue)) > 25) return null
        fun distance(p: Int) = max(abs(component(p, 16) - red), max(abs(component(p, 8) - green), abs(component(p, 0) - blue)))
        if (border.count { distance(it) <= 26 } < border.size * .90) return null
        val visited = BooleanArray(source.size)
        val queue = IntArray(source.size)
        var read = 0
        var write = 0
        fun visit(index: Int) {
            if (!visited[index] && ((source[index] ushr 24) < 16 || distance(source[index]) <= 22)) {
                visited[index] = true
                queue[write++] = index
            }
        }
        for (x in 0 until width) { visit(x); visit((height - 1) * width + x) }
        for (y in 0 until height) { visit(y * width); visit(y * width + width - 1) }
        while (read < write) {
            val i = queue[read++]
            pixels[i] = source[i] and 0x00FFFFFF
            val x = i % width
            if (x > 0) visit(i - 1)
            if (x + 1 < width) visit(i + 1)
            if (i >= width) visit(i - width)
            if (i + width < source.size) visit(i + width)
        }
        val remaining = source.size - write
        if (remaining < source.size * .045 || remaining > source.size * .85) return null
        // A near-white garment can merge with the backdrop while its seams remain.
        // Do not present disconnected scraps or a thin surviving rim as the garment.
        // This deliberately rejects ambiguous mattes; an existing alpha mask bypasses
        // this heuristic, so properly segmented pairs of shoes remain supported.
        val checked = BooleanArray(source.size)
        var foreground = 0
        var largestComponent = 0
        var minX = width; var maxX = -1; var minY = height; var maxY = -1
        fun visitForeground(index: Int) {
            if (!checked[index] && (pixels[index] ushr 24) >= 16) {
                checked[index] = true
                queue[write++] = index
            }
        }
        for (start in pixels.indices) {
            if (checked[start] || (pixels[start] ushr 24) < 16) continue
            read = 0; write = 0
            visitForeground(start)
            while (read < write) {
                val i = queue[read++]
                val x = i % width; val y = i / width
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
                if (x > 0) visitForeground(i - 1)
                if (x + 1 < width) visitForeground(i + 1)
                if (i >= width) visitForeground(i - width)
                if (i + width < pixels.size) visitForeground(i + width)
            }
            foreground += write
            largestComponent = max(largestComponent, write)
        }
        val boundsArea = (maxX - minX + 1) * (maxY - minY + 1)
        if (largestComponent < foreground * .90 || foreground < boundsArea * .35) return null
        // A one-pixel feather on the foreground boundary avoids an opaque white fringe.
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val i = y * width + x
            if (!visited[i] && (visited[i - 1] || visited[i + 1] || visited[i - width] || visited[i + width])) {
                pixels[i] = (source[i] and 0x00FFFFFF) or (190 shl 24)
            }
        }
    }
    var left = width; var top = height; var right = -1; var bottom = -1
    pixels.forEachIndexed { index, pixel ->
        if ((pixel ushr 24) >= 16) {
            val x = index % width; val y = index / width
            left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
        }
    }
    if (right < left || bottom < top) return null
    val croppedWidth = right - left + 1; val croppedHeight = bottom - top + 1
    val cropped = IntArray(croppedWidth * croppedHeight)
    for (y in 0 until croppedHeight) pixels.copyInto(cropped, y * croppedWidth, (top + y) * width + left, (top + y) * width + right + 1)
    return StudioCutout(cropped, croppedWidth, croppedHeight)
}

// AI-generated with Claude Code (Claude Opus 5.5), 2026-11-26, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.wardrobe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.swpp.stylemate.data.recommendation.OutfitGarment

/** 홈 and 코디북 show outfits from the stored background-free photos, in the 옷장 rail's visual language. */

/** Outerwear, top, bottom: the order an outfit is hung in and read in. */
internal fun hangingOrder(items: List<OutfitGarment>): List<OutfitGarment> {
    val rank = mapOf("outerwear" to 0, "top" to 1, "bottom" to 2)
    return items.sortedBy { rank[it.slot] ?: 3 }
}

/** The rail's garment record for an outfit item; its stored cutout is found from the image URL. */
internal fun OutfitGarment.asWardrobeItem() = WardrobeItem(garmentId, name, slot, imageUrl = imageUrl)

/** Gentle tilt for the outer garments so a short rail reads as hung by hand, not as a grid. */
internal fun railTilt(index: Int, count: Int): Float = when {
    count < 2 -> 0f
    index == 0 -> -6f
    index == count - 1 -> 6f
    else -> 0f
}

/** Typical photo proportions until a garment's own cutout has loaded, so the layout barely moves. */
internal fun defaultShape(slot: String) = when (slot) {
    "bottom" -> GarmentHangingShape(aspectRatio = .45f, neckY = .02f, left = .14f, right = .86f, supportY = .03f)
    "outerwear" -> GarmentHangingShape(aspectRatio = .9f)
    else -> GarmentHangingShape(aspectRatio = 1.05f)
}

/** Lengths (cm) used when the server sends none: an ordinary top, jacket and full-length trousers. */
internal fun fallbackLengthCm(slot: String) = when (slot) {
    "bottom" -> 98f
    "outerwear" -> 66f
    else -> 67f
}

/**
 * Where each garment of an outfit sits in design B (홈 and 코디북), in px inside a [width] × [height]
 * figure. Every garment is drawn at one shared scale from its real length (cm, from the server: measured,
 * length label or typical for its kind), and its width follows from its own photo's proportions, so a coat
 * stays longer than a tee and trousers keep a real length next to them. It is a styled flat-lay: the
 * outerwear lies behind on the left (tilted by [OUTER_TILT]), the top overlaps it in front on the right,
 * and the bottom's waist sits just under the top's hem. The figure is scaled to fit and centred.
 */
internal fun dressedLayout(
    width: Float,
    height: Float,
    outer: GarmentHangingShape?,
    top: GarmentHangingShape?,
    bottom: GarmentHangingShape?,
    outerCm: Float = fallbackLengthCm("outerwear"),
    topCm: Float = fallbackLengthCm("top"),
    bottomCm: Float = fallbackLengthCm("bottom"),
): Map<String, Rect> {
    val layered = outer != null && top != null
    // Sizes in cm: real length, and the width the photo's proportions give at that length.
    var outerH = if (outer != null) outerCm else 0f
    var topH = if (top != null) topCm else 0f
    val bottomH = if (bottom != null) bottomCm else 0f
    var outerW = outer?.let { outerH * it.aspectRatio } ?: 0f
    var topW = top?.let { topH * it.aspectRatio } ?: 0f
    val bottomW = bottom?.let { bottomH * it.aspectRatio } ?: 0f
    // Balance each outfit on its own: spread sleeves make a top's photo far wider than the body, so the
    // top is kept within a believable width of the bottom it is worn with.
    if (top != null && bottom != null && bottomW > 0f) {
        val ratio = topW / bottomW
        val fit = ratio.coerceIn(TOP_OVER_BOTTOM_MIN, TOP_OVER_BOTTOM_MAX) / ratio
        topW *= fit; topH *= fit
    }
    // Outerwear is worn over the top, so it is never drawn narrower than it.
    if (layered && outerW < topW * OUTER_OVER_TOP) {
        val grow = topW * OUTER_OVER_TOP / outerW
        outerW *= grow; outerH *= grow
    }
    // Neck/waist centre lines in cm from the figure's middle.
    val outerX = if (layered) -12f else 0f
    val topX = if (layered) 12f else 0f
    val bottomX = if (top != null) topX - 3f else outerX
    val topY = if (layered) 6f else 0f  // a top shows a little below the jacket's collar
    val waistY = when {
        top != null -> topY + topH * .86f
        outer != null -> outerH * .62f
        else -> 0f
    }
    val bottomY = waistY - bottomH * (bottom?.supportY ?: 0f)
    val pieces = buildMap {
        if (outer != null) put("outerwear", Rect(Offset(outerX - outerW * outer.neckX, 0f), Size(outerW, outerH)))
        if (top != null) put("top", Rect(Offset(topX - topW * top.neckX, topY), Size(topW, topH)))
        if (bottom != null) put("bottom",
            Rect(Offset(bottomX - bottomW * (bottom.left + bottom.right) / 2, bottomY), Size(bottomW, bottomH)))
    }
    if (pieces.isEmpty()) return emptyMap()
    val left = pieces.values.minOf { it.left }; val right = pieces.values.maxOf { it.right }
    val upper = pieces.values.minOf { it.top }; val lower = pieces.values.maxOf { it.bottom }
    val scale = minOf(width * .94f / (right - left), height / (lower - upper))
    val shiftX = (width - (right - left) * scale) / 2 - left * scale
    val shiftY = (height - (lower - upper) * scale) * .45f - upper * scale
    return pieces.mapValues { (_, r) ->
        Rect(Offset(shiftX + r.left * scale, shiftY + r.top * scale), Size(r.width * scale, r.height * scale))
    }
}

/** A top is drawn this many times as wide as the bottom it is worn with (sleeves included). */
internal const val TOP_OVER_BOTTOM_MIN = 1.15f
internal const val TOP_OVER_BOTTOM_MAX = 1.6f

/** How much wider than the top a layered outerwear is drawn at least. */
internal const val OUTER_OVER_TOP = 1.05f

/** The outerwear lies back at a slight angle when a top is laid over it. */
internal const val OUTER_TILT = -5f

/**
 * One garment on its hanger inside a [width] × [height] box whose hook sits near the top. Sizes, the
 * hanger and the clips follow [GarmentHangingShape] exactly as on the 옷장 rail. [photo] replaces the
 * stored cutout (used by screen tests that have no server). [rodFromY] turns the hook into a straight rod.
 */
@Composable
internal fun HangingGarment(
    item: WardrobeItem,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    photo: (@Composable (Modifier) -> Unit)? = null,
    hanger: Boolean = true,
    rodFromY: Dp? = null,
) {
    var shape by remember(item.imageUrl, item.category) { mutableStateOf(GarmentHangingShape()) }
    val bottom = item.category == "bottom"
    val garmentWidth = minOf(width, (height - if (bottom) 68.dp else 44.dp) * shape.aspectRatio)
    val garmentHeight = garmentWidth / shape.aspectRatio
    val garmentTop = if (bottom) 57.dp - garmentHeight * shape.supportY
        else (32.dp - garmentHeight * shape.neckY.coerceAtMost(.075f)).coerceAtLeast(20.dp)
    val garmentLeft = width * .5f - garmentWidth * shape.neckX
    Box(modifier.size(width, height).semantics { contentDescription = item.name }) {
        if (hanger) HangerRenderer(item.category, shape, garmentWidth, garmentHeight, garmentTop, Modifier.fillMaxSize(),
            rodFromY = rodFromY)
        val placed = Modifier.align(Alignment.TopStart).offset(x = garmentLeft, y = garmentTop).size(garmentWidth, garmentHeight)
        if (photo != null) photo(placed)
        else HangingGarmentImage(item, placed, onHangingShape = { shape = it }, alignment = Alignment.TopCenter)
        if (bottom && hanger) HangerClips(shape, garmentWidth, Modifier.fillMaxSize())
    }
}

/** 코디북 large view: the outfit's garments side by side on a short rail. */
@Composable
internal fun OutfitRail(
    items: List<OutfitGarment>,
    modifier: Modifier = Modifier,
    photo: (@Composable (OutfitGarment, Modifier) -> Unit)? = null,
) {
    val ordered = remember(items) { hangingOrder(items) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        ClothingRail(Modifier.fillMaxWidth().height(46.dp).align(Alignment.TopCenter))
        val count = ordered.size.coerceAtLeast(1)
        // Neighbours overlap a little, like garments pushed together on a rail.
        val slot = (maxWidth / count * 1.12f).coerceAtMost(maxWidth * .62f)
        val step = if (count > 1) (maxWidth - slot) / (count - 1) else 0.dp
        val height = (maxHeight - 23.dp).coerceAtLeast(120.dp)
        ordered.forEachIndexed { index, garment ->
            key(garment.garmentId) {
                HangingGarment(garment.asWardrobeItem(), slot, height,
                    Modifier.offset(x = if (count > 1) step * index else (maxWidth - slot) / 2, y = 11.dp)
                        .zIndex(if (garment.slot == "top") 2f else 1f)
                        .rotate(railTilt(index, ordered.size)),
                    photo = photo?.let { render -> { m -> render(garment, m) } })
            }
        }
    }
}

/**
 * 홈 (design B): the outfit laid out as one styled look, without a body. The outerwear lies behind, the
 * top overlaps it in front, the bottom's waist tucks under the top's hem, and a soft shadow grounds it. Each garment's
 * proportions come from its own cutout, so the figure settles as photos load.
 */
@Composable
internal fun OutfitDressed(
    items: List<OutfitGarment>,
    modifier: Modifier = Modifier,
    photo: (@Composable (OutfitGarment, Modifier) -> Unit)? = null,
) {
    val outer = items.firstOrNull { it.slot == "outerwear" }
    val top = items.firstOrNull { it.slot == "top" }
    val bottom = items.firstOrNull { it.slot == "bottom" }
    val shapes = remember(items) { mutableStateMapOf<String, GarmentHangingShape>() }
    fun shapeOf(garment: OutfitGarment?) = garment?.let { shapes[it.garmentId] ?: defaultShape(it.slot) }
    // Garment names stay off 홈 (PR #82); each garment carries its name for screen readers instead.
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { (maxHeight - 14.dp).toPx() }
            fun cm(garment: OutfitGarment?, slot: String) = garment?.lengthCm ?: fallbackLengthCm(slot)
            val layout = dressedLayout(widthPx, heightPx, shapeOf(outer), shapeOf(top), shapeOf(bottom),
                cm(outer, "outerwear"), cm(top, "top"), cm(bottom, "bottom"))
            val shadowBrush = remember { Brush.radialGradient(listOf(Color.Black.copy(alpha = .08f), Color.Transparent)) }
            val lowest = layout.values.maxOfOrNull { it.bottom } ?: heightPx
            Canvas(Modifier.fillMaxSize()) {
                val shadowWidth = size.width * .5f
                drawOval(shadowBrush, Offset((size.width - shadowWidth) / 2, lowest - 4.dp.toPx()),
                    Size(shadowWidth, 16.dp.toPx()))
            }
            // Back to front: trousers, then the jacket, then the top laid over it.
            listOfNotNull(bottom, outer, top).forEachIndexed { depth, garment ->
                val rect = layout[garment.slot] ?: return@forEachIndexed
                key(garment.garmentId) {
                    val tilt = if (garment.slot == "outerwear" && top != null) OUTER_TILT else 0f
                    val placed = with(density) {
                        Modifier.offset(x = rect.left.toDp(), y = rect.top.toDp()).size(rect.width.toDp(), rect.height.toDp())
                            .rotate(tilt).zIndex(depth.toFloat()).semantics { contentDescription = garment.name }
                    }
                    if (photo != null) photo(garment, placed)
                    else HangingGarmentImage(garment.asWardrobeItem(), placed,
                        onHangingShape = { shapes[garment.garmentId] = it }, alignment = Alignment.TopCenter)
                }
            }
        }
    }
}

/**
 * 코디북 (design C): the whole outfit on one combination hanger. The outerwear hangs behind the top (the
 * top is drawn narrower in front, without a second hanger), and the bottom hangs from clips on a lower
 * bar joined to the same hook by a straight rod.
 */
@Composable
internal fun OutfitHanger(
    items: List<OutfitGarment>,
    modifier: Modifier = Modifier,
    photo: (@Composable (OutfitGarment, Modifier) -> Unit)? = null,
) {
    val outer = items.firstOrNull { it.slot == "outerwear" }
    val top = items.firstOrNull { it.slot == "top" }
    val bottom = items.firstOrNull { it.slot == "bottom" }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val upper = if (bottom != null) maxHeight * .52f else maxHeight
        val lowerHeight = maxHeight * .62f
        fun render(garment: OutfitGarment): (@Composable (Modifier) -> Unit)? = photo?.let { r -> { m -> r(garment, m) } }
        if (bottom != null) HangingGarment(bottom.asWardrobeItem(), maxWidth * .72f, lowerHeight,
            Modifier.align(Alignment.BottomCenter), photo = render(bottom),
            // From just under the top hook (24dp in the card) down to the lower bar.
            rodFromY = 24.dp - (maxHeight - lowerHeight),
            hanger = true)
        if (outer != null) HangingGarment(outer.asWardrobeItem(), maxWidth * .92f, upper,
            Modifier.align(Alignment.TopCenter).zIndex(1f), photo = render(outer))
        if (top != null) {
            // In front of the outerwear, as if worn underneath it with the jacket open.
            val inside = outer != null
            HangingGarment(top.asWardrobeItem(), maxWidth * (if (inside) .6f else .92f), upper * (if (inside) .82f else 1f),
                Modifier.align(Alignment.TopCenter).offset(y = if (inside) upper * .08f else 0.dp).zIndex(2f),
                photo = render(top), hanger = !inside)
        }
    }
}

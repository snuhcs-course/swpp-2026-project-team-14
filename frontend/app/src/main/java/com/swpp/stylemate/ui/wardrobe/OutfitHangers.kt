// AI-generated with Claude Code (Claude Opus 5.5), 2026-11-26, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.wardrobe

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.swpp.stylemate.data.recommendation.OutfitGarment

/** 홈 and 코디북 hang outfits the way the 옷장 rail does, from the stored background-free photos. */

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

/**
 * One garment on its hanger inside a [width] × [height] box whose hook sits near the top. Sizes, the
 * hanger and the clips follow [GarmentHangingShape] exactly as on the 옷장 rail. [photo] replaces the
 * stored cutout (used by screen tests that have no server).
 */
@Composable
internal fun HangingGarment(
    item: WardrobeItem,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    photo: (@Composable (Modifier) -> Unit)? = null,
    hanger: Boolean = true,
) {
    var shape by remember(item.imageUrl, item.category) { mutableStateOf(GarmentHangingShape()) }
    val bottom = item.category == "bottom"
    val garmentWidth = minOf(width, (height - if (bottom) 68.dp else 44.dp) * shape.aspectRatio)
    val garmentHeight = garmentWidth / shape.aspectRatio
    val garmentTop = if (bottom) 57.dp - garmentHeight * shape.supportY
        else (32.dp - garmentHeight * shape.neckY.coerceAtMost(.075f)).coerceAtLeast(20.dp)
    val garmentLeft = width * .5f - garmentWidth * shape.neckX
    Box(modifier.size(width, height)) {
        if (hanger) HangerRenderer(item.category, shape, garmentWidth, garmentHeight, garmentTop, Modifier.fillMaxSize())
        val placed = Modifier.align(Alignment.TopStart).offset(x = garmentLeft, y = garmentTop).size(garmentWidth, garmentHeight)
        if (photo != null) photo(placed)
        else HangingGarmentImage(item, placed, onHangingShape = { shape = it }, alignment = Alignment.TopCenter)
        if (bottom) HangerClips(shape, garmentWidth, Modifier.fillMaxSize())
    }
}

/** 홈 (design A): the outfit's garments side by side on a short rail. */
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
 * 코디북 (design C): the whole outfit on one hanger. The outerwear hangs behind the top, so the top is
 * drawn narrower in front; bottoms hang from clips below.
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
        val upper = if (bottom != null) maxHeight * .62f else maxHeight
        fun render(garment: OutfitGarment): (@Composable (Modifier) -> Unit)? = photo?.let { r -> { m -> r(garment, m) } }
        if (bottom != null) HangingGarment(bottom.asWardrobeItem(), maxWidth * .62f, maxHeight * .58f,
            Modifier.align(Alignment.BottomCenter), photo = render(bottom))
        if (outer != null) HangingGarment(outer.asWardrobeItem(), maxWidth, upper,
            Modifier.align(Alignment.TopCenter), photo = render(outer))
        if (top != null) {
            // In front of the outerwear, as if worn underneath it with the jacket open.
            val scale = if (outer != null) .62f else 1f
            HangingGarment(top.asWardrobeItem(), maxWidth * scale, upper * (if (outer != null) .8f else 1f),
                Modifier.align(Alignment.TopCenter).offset(y = if (outer != null) upper * .1f else 0.dp).zIndex(1f),
                photo = render(top), hanger = outer == null)
        }
    }
}

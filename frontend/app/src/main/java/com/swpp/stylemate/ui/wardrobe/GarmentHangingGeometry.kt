// AI-generated with Codex and Claude Code (Dongkun Moon), 2026-11-25, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.wardrobe

import kotlin.math.roundToInt

/** Normalized anchors inferred from the existing alpha silhouette, never from garment colour. */
internal data class GarmentHangingShape(
    val aspectRatio: Float = 1f,
    val neckX: Float = .5f,
    val neckY: Float = .035f,
    val left: Float = .24f,
    val right: Float = .76f,
    val supportY: Float = .14f,
)

internal fun garmentHangingShape(pixels: IntArray, width: Int, height: Int, category: String): GarmentHangingShape {
    require(width > 0 && height > 0 && pixels.size == width * height)
    val aspect = width.toFloat() / height
    fun solid(x: Int, y: Int) = (pixels[y * width + x] ushr 24) >= 160
    fun median(values: List<Float>, fallback: Float): Float =
        values.sorted().let { if (it.isEmpty()) fallback else it[it.size / 2] }
    fun rowBounds(y: Int): Pair<Float, Float>? {
        var left = 0
        while (left < width && !solid(left, y)) left++
        if (left == width) return null
        var right = width - 1
        while (right > left && !solid(right, y)) right--
        return left.toFloat() / width to (right + 1f) / width
    }
    // Sample the upper silhouette, before long sleeves or a flared hem widen it.
    val bottom = category == "bottom"
    val from = if (bottom) .025f else .10f
    val to = if (bottom) .08f else .18f
    val bounds = ((height * from).roundToInt()..(height * to).roundToInt())
        .mapNotNull { rowBounds(it.coerceIn(0, height - 1)) }
    val left = median(bounds.map { it.first }, if (bottom) .08f else .24f)
    val right = median(bounds.map { it.second }, if (bottom) .92f else .76f)
    val center = ((left + right) / 2f).coerceIn(.25f, .75f)
    val neckRows = (-2..2).mapNotNull { offset ->
        val x = (center * width + offset * width * .015f).roundToInt().coerceIn(0, width - 1)
        (0 until (height * .30f).roundToInt().coerceAtLeast(1)).firstOrNull { solid(x, it) }
            ?.let { it.toFloat() / height }
    }
    return GarmentHangingShape(aspect, center,
        median(neckRows, .035f).coerceIn(0f, .18f),
        (left + .015f).coerceAtMost(center - .03f),
        (right - .015f).coerceAtLeast(center + .03f),
        if (bottom) .045f else .15f)
}

package com.swpp.stylemate.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.floor

/** Half-star display scale shared with develop. The server's recommendation score is unchanged. */
fun starsOf(score: Int): Double = (floor(score.coerceIn(0, 100) / 10.0 + 0.5) / 2).coerceAtLeast(0.5)

fun starsText(stars: Double): String = String.format(Locale.KOREA, "%.1f", stars)

/** Read-only recommendation rating, always shown as five stars. */
@Composable
fun StarRating(stars: Double, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    Row(modifier.semantics(mergeDescendants = true) {
        contentDescription = "추천 별점 ${starsText(stars)}점 (5점 만점)"
    }) {
        repeat(5) { i ->
            val fill = (stars - i).coerceIn(0.0, 1.0).toFloat()
            Box(Modifier.size(size)) {
                Icon(Icons.Filled.Star, null, Modifier.size(size), tint = off)
                if (fill > 0f) Icon(Icons.Filled.Star, null, tint = on,
                    modifier = Modifier.size(size).drawWithContent {
                        clipRect(right = this.size.width * fill) { this@drawWithContent.drawContent() }
                    })
            }
        }
    }
}

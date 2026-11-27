// AI-generated with Claude Code (Claude Opus 5.5), 2026-11-27, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.wardrobe

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * When to suggest holding a styled look to spread it out. A tip, not a lesson: only after the person has
 * stayed on one outfit for a while, at most [PER_SCREEN] times per screen (홈, 코디북) in an app session and
 * once per outfit, and never again once they have used the gesture. Going back and forth between tabs does
 * not use it up. The use count is a per-device convenience, kept in the app's preferences.
 */
internal object SpreadTips {
    const val DWELL_MS = 7_000L
    const val SHOW_MS = 3_200L
    const val PER_SCREEN = 2
    const val STOP_AFTER_USES = 2
    private const val PREFS = "stylemate_tips"
    private const val USED = "spread_used"
    private val shownOnScreen = mutableMapOf<String, Int>()
    private val shownFor = mutableSetOf<Any>()

    fun eligible(uses: Int, shownOnScreen: Int, alreadyShownForOutfit: Boolean) =
        uses < STOP_AFTER_USES && shownOnScreen < PER_SCREEN && !alreadyShownForOutfit

    private fun uses(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(USED, 0)
    fun shouldShow(context: Context, screen: String, outfit: Any) =
        eligible(uses(context), shownOnScreen[screen] ?: 0, outfit in shownFor)
    fun shown(screen: String, outfit: Any) { shownOnScreen[screen] = (shownOnScreen[screen] ?: 0) + 1; shownFor += outfit }
    fun used(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putInt(USED, prefs.getInt(USED, 0) + 1).apply()
    }
}

/**
 * A small pill that fades in at the foot of the outfit for a few seconds and goes again: a pulsing press mark
 * and one line of text. [active] is false for pages beside the one in view.
 */
@Composable
internal fun SpreadTip(pageKey: Any, active: Boolean, screen: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    var visible by remember(pageKey) { mutableStateOf(false) }
    LaunchedEffect(pageKey, active) {
        visible = false
        if (!active) return@LaunchedEffect
        delay(SpreadTips.DWELL_MS)
        if (!SpreadTips.shouldShow(context, screen, pageKey)) return@LaunchedEffect
        SpreadTips.shown(screen, pageKey)
        visible = true
        delay(SpreadTips.SHOW_MS)
        visible = false
    }
    AnimatedVisibility(visible, modifier,
        enter = fadeIn(tween(450)) + slideInVertically(tween(450)) { it / 2 },
        exit = fadeOut(tween(600)) + slideOutVertically(tween(600)) { it / 3 }) {
        val ink = MaterialTheme.colorScheme.onSurface
        val pulse by rememberInfiniteTransition(label = "tip").animateFloat(0f, 1f,
            infiniteRepeatable(tween(1300), RepeatMode.Restart), label = "press")
        Row(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f))
            .border(.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically) {
            // A fingertip pressing: a dot with a ring spreading out from it.
            Canvas(Modifier.size(14.dp)) {
                val r = size.minDimension / 2
                drawCircle(ink.copy(alpha = .35f * (1f - pulse)), r * (.45f + .55f * pulse), style = Stroke(1.2.dp.toPx()))
                drawCircle(ink, r * .3f)
            }
            Spacer(Modifier.width(6.dp))
            Text("꾹 눌러 옷을 펼쳐 보세요", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

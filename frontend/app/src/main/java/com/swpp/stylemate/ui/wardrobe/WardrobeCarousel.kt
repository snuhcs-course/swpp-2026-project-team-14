// AI-generated with Codex and Claude Code (Dongkun Moon), 2026-11-25, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.wardrobe

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sign

/** The spacing approaches a compressed rail at the edges; this is not a uniform pager. */
internal fun wardrobeRailOffset(distance: Float): Float =
    sign(distance) * (.60f * (1f - exp(-1.3f * abs(distance))) + .09f * abs(distance))

internal fun wardrobeLayerDepth(index: Int, position: Float): Float = 20f - abs(index - position)

/** Shared projected rail geometry: zero lift at the low center, full lift at either end. */
internal fun wardrobeRailLift(horizontalOffset: Float, halfWidth: Float): Float {
    val t = (horizontalOffset / halfWidth.coerceAtLeast(1f)).coerceIn(-1f, 1f)
    return t * t
}

/** A gentle turn along the rail, retaining enough front surface to read the garment's shape. */
internal fun wardrobeHangerYaw(horizontalOffset: Float, halfWidth: Float): Float {
    val t = (abs(horizontalOffset) / (halfWidth.coerceAtLeast(1f) * .90f)).coerceIn(0f, 1f)
    // One smooth curve replaces the steep near-center turn. Adjacent clothes turn
    // about 37 degrees, and even the edge retains a readable silhouette at 52 degrees.
    return -sign(horizontalOffset) * 52f * t * t * (3f - 2f * t)
}

internal fun wardrobeSnapTarget(position: Float, velocity: Float, stride: Float, count: Int): Int {
    if (count <= 1) return 0
    val momentum = (-velocity / stride.coerceAtLeast(1f) * .12f).coerceIn(-2f, 2f)
    return (position + momentum).roundToInt().coerceIn(0, count - 1)
}

internal fun wardrobeSelectionIndex(ids: List<String>, selectedId: String?, previous: Float): Float {
    val preserved = ids.indexOf(selectedId)
    return if (preserved >= 0) preserved.toFloat()
    else previous.coerceIn(0f, (ids.size - 1).coerceAtLeast(0).toFloat())
}

@Composable
internal fun WardrobeCarousel(
    items: List<WardrobeItem>,
    selectedId: String?,
    onSelected: (String) -> Unit,
    onOpen: (WardrobeItem) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var position by remember { mutableFloatStateOf(items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0).toFloat()) }
    var motion by remember { mutableStateOf<Job?>(null) }
    var travelVelocity by remember { mutableFloatStateOf(0f) }
    val latestSelect by rememberUpdatedState(onSelected)
    val latestSelectedId by rememberUpdatedState(selectedId)
    val ids = remember(items) { items.map { it.id } }
    var appliedIds by remember { mutableStateOf(ids) }
    var gestureIds by remember { mutableStateOf<List<String>?>(null) }
    val collectionAlpha = remember { Animatable(1f) }
    // Resolve collection changes before drawing or reporting a selected item. Otherwise the old
    // numeric index can briefly select an unrelated garment in the new category.
    // Fractional progress is consumed by layer/placement callbacks, not composition. Only
    // crossing an item boundary needs to rebuild semantics or the small visible window.
    val displayPosition = remember(ids) {
        derivedStateOf(structuralEqualityPolicy()) {
            if (appliedIds == ids) position else wardrobeSelectionIndex(ids, latestSelectedId, position)
        }
    }
    LaunchedEffect(ids) {
        motion?.cancel()
        val changed = appliedIds != ids
        position = wardrobeSelectionIndex(ids, selectedId, position)
        appliedIds = ids
        if (changed) {
            collectionAlpha.snapTo(.30f)
            collectionAlpha.animateTo(1f, tween(280))
        }
    }
    val centered by remember(ids) {
        derivedStateOf(structuralEqualityPolicy()) {
            displayPosition.value.roundToInt().coerceIn(0, (ids.size - 1).coerceAtLeast(0))
        }
    }
    LaunchedEffect(centered, ids, appliedIds) {
        if (ids == appliedIds) items.getOrNull(centered)?.let { latestSelect(it.id) }
    }
    DisposableEffect(Unit) { onDispose { motion?.cancel() } }

    fun settle(index: Int, velocity: Float = 0f) {
        if (items.isEmpty()) return
        motion?.cancel()
        motion = scope.launch {
            animate(position, index.coerceIn(0, items.lastIndex).toFloat(), velocity.coerceIn(-18f, 18f),
                animationSpec = spring(dampingRatio = .96f, stiffness = 230f)) { value, currentVelocity ->
                position = value.coerceIn(0f, items.lastIndex.toFloat())
                travelVelocity = currentVelocity
            }
            travelVelocity = 0f
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth().height(328.dp).clipToBounds()) {
        val itemWidth = (maxWidth * .55f).coerceIn(156.dp, 268.dp)
        // Match the center's translation derivative so it follows the finger, rather
        // than racing ahead of it. Side spacing remains compressed along the rail.
        val stridePx = with(LocalDensity.current) { (itemWidth * .87f).toPx() }
        val widthPx = with(LocalDensity.current) { itemWidth.toPx() }
        val halfRailWidthPx = with(LocalDensity.current) { maxWidth.toPx() / 2f }
        val railSagPx = with(LocalDensity.current) { (maxWidth * .03f).coerceIn(8.dp, 14.dp).toPx() }
        val railY = 26.dp
        val topInset = railY - 15.dp
        val groupHeight = (maxHeight - topInset - 12.dp).coerceAtLeast(100.dp)
        val hookOrigin = 15.dp.value / groupHeight.value
        ClothingRail(Modifier.fillMaxWidth().height(46.dp).align(Alignment.TopCenter))
        val dragState = rememberDraggableState { delta ->
            if (gestureIds == ids) {
                motion?.cancel()
                travelVelocity = (travelVelocity * .65f - delta / stridePx * 60f * .35f).coerceIn(-12f, 12f)
                position = (position - delta / stridePx).coerceIn(0f, (items.size - 1).coerceAtLeast(0).toFloat())
            }
        }
        Box(Modifier.fillMaxSize()
            .semantics {
                contentDescription = "옷걸이를 좌우로 넘기는 옷장"
                collectionInfo = CollectionInfo(1, items.size)
                horizontalScrollAxisRange = ScrollAxisRange({ displayPosition.value }, { (items.size - 1).coerceAtLeast(0).toFloat() })
                if (items.isNotEmpty()) {
                    stateDescription = "${centered + 1} / ${items.size}"
                    customActions = listOf(
                        CustomAccessibilityAction("이전 옷") { if (enabled && centered > 0) { settle(centered - 1); true } else false },
                        CustomAccessibilityAction("다음 옷") { if (enabled && centered < items.lastIndex) { settle(centered + 1); true } else false },
                    )
                }
            }
            .draggable(dragState, Orientation.Horizontal, enabled = enabled && items.size > 1,
                onDragStarted = { motion?.cancel(); travelVelocity = 0f; gestureIds = ids },
                onDragStopped = { velocity ->
                    if (gestureIds == ids) settle(wardrobeSnapTarget(position, velocity, stridePx, items.size), -velocity / stridePx)
                    gestureIds = null
                })) {
            val start = (centered - 4).coerceAtLeast(0)
            val end = (centered + 4).coerceAtMost(items.lastIndex)
            // Start loading the selected garment first; far-away masks must not delay its photo.
            val visibleIndices = remember(start, end, centered) { (start..end).sortedBy { abs(it - centered) } }
            if (end >= start) for (index in visibleIndices) {
                val item = items[index]
                key(item.id) {
                    var shape by remember(item.imageUrl, item.thumbnail, item.category) { mutableStateOf(GarmentHangingShape()) }
                    val hanging = item.category !in setOf("shoes", "hat", "bag", "accessory", "socks")
                    val garmentWidth = minOf(itemWidth,
                        (groupHeight - if (item.category == "bottom") 68.dp else 44.dp) * shape.aspectRatio)
                    val garmentHeight = garmentWidth / shape.aspectRatio
                    val garmentTop = when {
                        !hanging -> groupHeight - garmentHeight - 12.dp
                        item.category == "bottom" -> 57.dp - garmentHeight * shape.supportY
                        else -> (32.dp - garmentHeight * shape.neckY.coerceAtMost(.075f)).coerceAtLeast(20.dp)
                    }
                    val garmentLeft = itemWidth * .5f - garmentWidth * shape.neckX
                    Box(Modifier.align(Alignment.TopCenter).padding(top = topInset)
                        .width(itemWidth).height(groupHeight)
                        // Front/back ordering changes only when crossing the midpoint.
                        // Fractional z updates would unnecessarily re-place every item.
                        .zIndex(wardrobeLayerDepth(index, centered.toFloat()))
                        .graphicsLayer {
                            // Mild edge fading does not need a full offscreen texture per
                            // garment. Modulating draw alpha avoids that render pass.
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                            val distance = index - displayPosition.value
                            val depth = abs(distance).coerceAtMost(4f)
                            val scale = 1f - .025f * depth
                            translationX = wardrobeRailOffset(distance) * widthPx
                            translationY = if (hanging) -railSagPx * wardrobeRailLift(translationX, halfRailWidthPx) else 0f
                            scaleX = scale
                            scaleY = scale
                            rotationY = if (hanging) wardrobeHangerYaw(translationX, halfRailWidthPx)
                                else -distance.coerceIn(-4f, 4f) * 8f
                            // Very small lag around the hook, like fabric following a hanger.
                            rotationZ = (travelVelocity * .12f).coerceIn(-1f, 1f) / (1f + depth * .25f)
                            // Keep the camera well outside the rotated plane so sleeves and
                            // hems are foreshortened without stretching into a trapezoid.
                            cameraDistance = widthPx * 10f
                            alpha = collectionAlpha.value * (1f - .025f * (depth - 1f).coerceAtLeast(0f))
                            transformOrigin = TransformOrigin(.5f, hookOrigin)
                        }
                        .clickable(enabled = enabled, role = Role.Button,
                            onClickLabel = if (index == centered) "옷 상세 보기" else "가운데로 가져오기") {
                            if (index == centered && abs(index - displayPosition.value) < .1f) onOpen(item) else settle(index)
                        }
                        .semantics {
                            contentDescription = item.name
                            selected = index == centered
                            collectionItemInfo = CollectionItemInfo(0, 1, index, 1)
                        }) {
                        if (hanging) HangerRenderer(item.category, shape, garmentWidth, garmentHeight, garmentTop,
                            Modifier.fillMaxSize())
                        val shadowBrush = remember {
                            Brush.radialGradient(listOf(Color.Black.copy(alpha = .06f), Color.Transparent))
                        }
                        Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth(.75f).height(12.dp)) {
                            // The parent layer supplies depth/opacity; the oval itself is static.
                            drawOval(shadowBrush, size = Size(size.width, size.height))
                        }
                        HangingGarmentImage(item, Modifier.align(Alignment.TopStart)
                            .offset(x = garmentLeft, y = garmentTop).size(garmentWidth, garmentHeight),
                            onHangingShape = { shape = it },
                            alignment = Alignment.TopCenter,
                            loadDelayMillis = abs(index - centered) * 70L)
                        if (hanging && item.category == "bottom") HangerClips(shape, garmentWidth, Modifier.fillMaxSize())
                        if (!hanging) Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth(.92f).height(8.dp)) {
                            drawRoundRect(Color(0xFFE9E5DF), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                            drawLine(Color(0xFFC7C0B6), Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ClothingRail(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val center = 26.dp.toPx()
        val thickness = 5.dp.toPx()
        val sag = (size.width * .03f).coerceIn(8.dp.toPx(), 14.dp.toPx())
        // Quadratic bow: the projected center stays at 26dp, both ends rise by sag.
        // Hangers sample this exact parabola using wardrobeRailLift() during movement.
        val path = Path().apply {
            moveTo(0f, center - sag)
            quadraticTo(size.width / 2f, center + sag, size.width, center - sag)
        }
        drawPath(path, Color(0xFFA39C92), style = Stroke(thickness, cap = StrokeCap.Round))
        drawPath(path, Color(0xFFCBC6BF), style = Stroke(thickness * .64f, cap = StrokeCap.Round))
        translate(top = -.85.dp.toPx()) {
            drawPath(path, Color(0xFFF1EFEB), style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
        }
        for (x in listOf(10.dp.toPx(), size.width - 10.dp.toPx())) {
            val y = center - sag * wardrobeRailLift(x - size.width / 2f, size.width / 2f)
            drawCircle(Color(0xFFE4E0DA), 6.dp.toPx(), Offset(x, y))
            drawCircle(Color(0xFFB3ABA0), 3.dp.toPx(), Offset(x, y))
            drawLine(Color(0xFFEAE7E2), Offset(x - 1.5.dp.toPx(), y), Offset(x + 1.5.dp.toPx(), y), 1.dp.toPx())
        }
    }
}

@Composable
internal fun HangerRenderer(
    category: String,
    shape: GarmentHangingShape,
    garmentWidth: Dp,
    garmentHeight: Dp,
    garmentTop: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val center = size.width / 2
        val clothWidth = garmentWidth.toPx()
        val clothHeight = garmentHeight.toPx()
        val imageLeft = center - clothWidth * shape.neckX
        val left = imageLeft + clothWidth * shape.left
        val right = imageLeft + clothWidth * shape.right
        val neckY = if (category == "bottom") 49.dp.toPx()
            else garmentTop.toPx() + clothHeight * shape.neckY.coerceAtMost(.075f)
        val hook = Path().apply {
            moveTo(center - 4.dp.toPx(), 14.dp.toPx())
            cubicTo(center - 7.dp.toPx(), 2.dp.toPx(), center + 8.dp.toPx(), 2.dp.toPx(), center + 5.dp.toPx(), 14.dp.toPx())
            cubicTo(center + 4.dp.toPx(), 19.dp.toPx(), center, 19.dp.toPx(), center, 24.dp.toPx())
            lineTo(center, neckY)
        }
        drawPath(hook, Color(0xFF9F9990), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
        if (category == "bottom") {
            drawLine(Color(0xFFC5B098), Offset(left, neckY), Offset(right, neckY), 4.dp.toPx(), StrokeCap.Round)
        } else {
            val shoulderY = garmentTop.toPx() + clothHeight * shape.supportY + 2.dp.toPx()
            val body = Path().apply {
                moveTo(center, neckY)
                cubicTo(center - (center - left) * .25f, neckY, left, shoulderY - 3.dp.toPx(), left, shoulderY)
                quadraticTo(left, shoulderY + 3.dp.toPx(), left + 3.dp.toPx(), shoulderY + 3.dp.toPx())
                lineTo(right - 3.dp.toPx(), shoulderY + 3.dp.toPx())
                quadraticTo(right, shoulderY + 3.dp.toPx(), right, shoulderY)
                cubicTo(right, shoulderY - 3.dp.toPx(), center + (right - center) * .25f, neckY, center, neckY)
            }
            drawPath(body, Color(0xFFC5B098), style = Stroke(if (category == "outerwear") 5.dp.toPx() else 3.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/** Clips sit in front of the waistband; the wooden bar stays behind the garment. */
@Composable
private fun HangerClips(shape: GarmentHangingShape, garmentWidth: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val width = garmentWidth.toPx()
        val left = size.width / 2 - width * shape.neckX + width * shape.left
        val right = size.width / 2 - width * shape.neckX + width * shape.right
        val inset = (right - left) * .08f
        for (x in listOf(left + inset, right - inset)) {
            drawRoundRect(Color(0xFF99938A), Offset(x - 3.dp.toPx(), 48.dp.toPx()),
                Size(6.dp.toPx(), 15.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()))
            drawLine(Color(0xFFD8D3CB), Offset(x - 1.5.dp.toPx(), 53.dp.toPx()),
                Offset(x + 1.5.dp.toPx(), 53.dp.toPx()), 1.dp.toPx())
        }
    }
}

@Composable
internal fun WardrobeHangerIcon(modifier: Modifier = Modifier, color: Color = Color(0xFF1C1C1C)) {
    Canvas(modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * .40f, h * .26f)
            cubicTo(w * .40f, h * .08f, w * .67f, h * .09f, w * .61f, h * .28f)
            lineTo(w * .50f, h * .41f)
            lineTo(w * .08f, h * .72f)
            quadraticTo(w * .04f, h * .79f, w * .15f, h * .80f)
            lineTo(w * .85f, h * .80f)
            quadraticTo(w * .96f, h * .79f, w * .92f, h * .72f)
            lineTo(w * .50f, h * .41f)
        }
        drawPath(path, color, style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round))
    }
}

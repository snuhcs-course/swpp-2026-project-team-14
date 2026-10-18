// AI-generated with Claude Code (Claude Opus 5.5), 2026-09-28, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.profile

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.data.BodyMeasurement
import com.swpp.stylemate.data.Confidence
import com.swpp.stylemate.data.FigureShape
import com.swpp.stylemate.data.Gender
import com.swpp.stylemate.data.Hotspot
import com.swpp.stylemate.data.KEY_MEASUREMENTS
import com.swpp.stylemate.data.MeasurementType
import com.swpp.stylemate.data.Point
import com.swpp.stylemate.data.ReferenceMeasurements
import com.swpp.stylemate.data.hotspotAt
import com.swpp.stylemate.data.hotspotFor
import com.swpp.stylemate.ui.theme.StyleMateTheme
import com.swpp.stylemate.ui.theme.Terracotta

private val SkinFill = Color(0xFFF6DCCB)
private val SkinLine = Color(0xFFE3B79F)
private val HairColor = Color(0xFF6B4A3A)
private val Cheek = Color(0xFFF2A7A0)
private val FaceInk = Color(0xFF4A3A33)

// Share of the card width used per figure x-unit, and card height / width.
private const val X_SCALE = 0.27f
private const val ASPECT = 1.25f
private val ChipWidth = 86.dp

/** Short explanation shown in the detail card for each measurement. */
private val DESCRIPTIONS = mapOf(
    MeasurementType.SHOULDER_WIDTH to "양쪽 어깨 끝 사이의 너비",
    MeasurementType.SLEEVE_LENGTH to "어깨 끝에서 손목까지",
    MeasurementType.TORSO_LENGTH to "목 뒤 아래에서 허리까지",
    MeasurementType.RISE to "허리에서 가랑이까지",
    MeasurementType.INSEAM to "가랑이에서 바닥까지 (바지 기장)",
    MeasurementType.OUTSEAM to "허리에서 바닥까지 다리 바깥쪽",
    MeasurementType.NECK to "목 아랫부분 둘레",
    MeasurementType.CHEST to "가슴의 가장 넓은 부분 둘레",
    MeasurementType.UNDERBUST to "가슴 바로 아래 둘레",
    MeasurementType.WAIST to "몸통의 가장 가는 부분 둘레",
    MeasurementType.HIP to "엉덩이의 가장 넓은 부분 둘레",
    MeasurementType.ARMHOLE to "팔과 몸통이 이어지는 부분 둘레",
    MeasurementType.BICEP to "팔 윗부분의 가장 굵은 곳 둘레",
    MeasurementType.WRIST to "손목 둘레",
    MeasurementType.THIGH to "허벅지 윗부분 둘레",
    MeasurementType.CALF to "종아리의 가장 굵은 부분 둘레",
)

/** Which side of the figure each always-visible chip sits on. */
private val KEY_ON_LEFT = setOf(MeasurementType.SHOULDER_WIDTH, MeasurementType.WAIST, MeasurementType.INSEAM)

/**
 * Cartoon body figure with every measurement as a highlighted body part.
 * Key measurements are pinned as chips; tapping any part shows its value in a card below.
 */
@Composable
fun BodyFigureCard(
    measurements: List<BodyMeasurement>,
    gender: Gender,
    onEdit: ((BodyMeasurement) -> Unit)?,
    modifier: Modifier = Modifier,
    reference: ReferenceMeasurements? = null,
) {
    var selected by remember { mutableStateOf<MeasurementType?>(null) }
    val byType = measurements.associateBy { it.type }
    val hotspots = remember(gender) { MeasurementType.entries.mapNotNull { hotspotFor(it, gender) } }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 16.dp, horizontal = 12.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cardWidth = maxWidth
            val figureHeight: Dp = cardWidth * ASPECT
            Box(Modifier.fillMaxWidth().height(figureHeight)) {
                FigureCanvas(
                    shape = FigureShape.of(gender),
                    hotspots = hotspots,
                    selected = selected,
                    keyAnchors = keyAnchors(hotspots),
                    onTap = { selected = it },
                    modifier = Modifier.fillMaxWidth().height(figureHeight),
                )
                keyAnchors(hotspots).forEach { (type, anchor) ->
                    val measurement = byType[type] ?: return@forEach
                    val left = type in KEY_ON_LEFT
                    val x = if (left) 0.dp else cardWidth - ChipWidth
                    val y = figureHeight * anchor.y.toFloat() - 22.dp
                    KeyChip(
                        measurement = measurement,
                        selected = selected == type,
                        onClick = { selected = type },
                        modifier = Modifier.offset(x = x, y = y),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        DetailCard(selected, selected?.let { byType[it] }, onEdit, reference)
    }
}

/** Point on each key hotspot where its chip's leader line ends. */
private fun keyAnchors(hotspots: List<Hotspot>): List<Pair<MeasurementType, Point>> =
    KEY_MEASUREMENTS.mapNotNull { type ->
        val h = hotspots.firstOrNull { it.type == type } ?: return@mapNotNull null
        val anchor = when {
            h.start.y == h.end.y -> if (type in KEY_ON_LEFT) h.start else h.end // horizontal band: nearest end
            else -> h.centre
        }
        type to anchor
    }

@Composable
private fun FigureCanvas(
    shape: FigureShape,
    hotspots: List<Hotspot>,
    selected: MeasurementType?,
    keyAnchors: List<Pair<MeasurementType, Point>>,
    onTap: (MeasurementType?) -> Unit,
    modifier: Modifier,
) {
    val pulse by rememberInfiniteTransition(label = "hotspot-pulse").animateFloat(
        initialValue = 0.25f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    val density = LocalDensity.current
    val tapRadius = with(density) { 26.dp.toPx() }
    val chipWidth = with(density) { ChipWidth.toPx() }

    Canvas(
        modifier.pointerInput(hotspots) {
            detectTapGestures { offset ->
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val sx = w * X_SCALE
                val tap = Point(((offset.x - w / 2) / sx).toDouble(), (offset.y / h).toDouble())
                onTap(hotspotAt(tap, hotspots, sx.toDouble(), h.toDouble(), tapRadius.toDouble())?.type)
            }
        },
    ) {
        val sx = size.width * X_SCALE
        fun p(pt: Point) = Offset(size.width / 2 + pt.x.toFloat() * sx, pt.y.toFloat() * size.height)

        drawFigure(shape, sx) { p(it) }

        // leader lines from the pinned chips to their body parts
        keyAnchors.forEach { (type, anchor) ->
            val end = p(anchor)
            val startX = if (type in KEY_ON_LEFT) chipWidth else size.width - chipWidth
            drawLine(Terracotta.copy(alpha = 0.45f), Offset(startX, end.y), end, 1.5.dp.toPx())
        }

        hotspots.forEach { h ->
            val isSelected = h.type == selected
            val isKey = h.type in KEY_MEASUREMENTS
            val color = when {
                isSelected -> Terracotta
                isKey -> Terracotta.copy(alpha = 0.75f)
                else -> Terracotta.copy(alpha = pulse)
            }
            when {
                // other parts stay as small dots at their centre until tapped, so the body is not cluttered
                h.isPoint || (!isKey && !isSelected) -> {
                    val c = p(h.centre)
                    drawCircle(color.copy(alpha = color.alpha * 0.35f), radius = (if (isSelected) 13 else 9).dp.toPx(), center = c)
                    drawCircle(color, radius = (if (isSelected) 6f else 4f).dp.toPx(), center = c)
                }
                else -> {
                    drawLine(color, p(h.start), p(h.end), (if (isSelected) 5f else 3f).dp.toPx(), StrokeCap.Round)
                    if (isSelected) {
                        drawCircle(color, radius = 4.5.dp.toPx(), center = p(h.start))
                        drawCircle(color, radius = 4.5.dp.toPx(), center = p(h.end))
                    }
                }
            }
        }
    }
}

/** Big-headed cartoon body in an A-pose. */
private fun DrawScope.drawFigure(f: FigureShape, sx: Float, p: (Point) -> Offset) {
    val h = size.height
    val line = Stroke(width = 1.5.dp.toPx())
    val headCentre = p(Point(0.0, f.headY))
    val headRadius = (f.headRadius * h).toFloat()

    // long hair behind the head
    if (f.longHair) {
        drawRoundRect(
            HairColor,
            topLeft = Offset(headCentre.x - headRadius * 1.12f, headCentre.y - headRadius * 0.4f),
            size = Size(headRadius * 2.24f, headRadius * 1.95f),
            cornerRadius = CornerRadius(headRadius, headRadius),
        )
    }

    // arms (behind the torso)
    val armWidth = 0.13f * sx
    for (side in listOf(-1, 1)) {
        val s = p(f.shoulder(side)); val e = p(f.elbow(side)); val w = p(f.wrist(side))
        drawLine(SkinLine, s, e, armWidth + line.width * 2, StrokeCap.Round)
        drawLine(SkinLine, e, w, armWidth * 0.85f + line.width * 2, StrokeCap.Round)
        drawLine(SkinFill, s, e, armWidth, StrokeCap.Round)
        drawLine(SkinFill, e, w, armWidth * 0.85f, StrokeCap.Round)
        drawCircle(SkinFill, radius = armWidth * 0.62f, center = Offset(w.x, w.y + armWidth * 0.25f))
        drawCircle(SkinLine, radius = armWidth * 0.62f, center = Offset(w.x, w.y + armWidth * 0.25f), style = line)
    }

    // legs
    for (side in listOf(-1, 1)) {
        val leg = Path().apply {
            val outerTop = p(Point(side * (f.hipHalf - 0.02), f.hipY + 0.02))
            val innerTop = p(Point(side * 0.02, f.crotchY))
            val outerAnkle = p(Point(side * (f.ankleCentre + 0.075), f.ankleY))
            val innerAnkle = p(Point(side * (f.ankleCentre - 0.07), f.ankleY))
            val outerKnee = p(Point(side * (f.legCentre + 0.11), f.kneeY))
            val innerKnee = p(Point(side * (f.legCentre - 0.12), f.kneeY))
            moveTo(outerTop.x, outerTop.y)
            quadraticTo(outerKnee.x, outerKnee.y, outerAnkle.x, outerAnkle.y)
            lineTo(innerAnkle.x, innerAnkle.y)
            quadraticTo(innerKnee.x, innerKnee.y, innerTop.x, innerTop.y)
            close()
        }
        drawPath(leg, SkinFill)
        drawPath(leg, SkinLine, style = line)
        val foot = p(Point(side * (f.ankleCentre + 0.02), f.ankleY + 0.022))
        drawOval(SkinFill, topLeft = Offset(foot.x - 0.13f * sx, foot.y - 0.018f * h), size = Size(0.26f * sx, 0.036f * h))
        drawOval(SkinLine, topLeft = Offset(foot.x - 0.13f * sx, foot.y - 0.018f * h), size = Size(0.26f * sx, 0.036f * h), style = line)
    }

    // torso: one smooth right-hand outline, mirrored for the left so both sides match
    val right = listOf(
        Point(f.shoulderHalf, f.shoulderY + 0.04),
        Point(f.chestHalf, f.chestY),
        Point(f.waistHalf, f.waistY),
        Point(f.hipHalf, f.hipY),
        Point(f.hipHalf - 0.04, f.crotchY),
    )
    val torso = Path().apply {
        val neck = Point(f.neckHalf, f.shoulderY - 0.02)
        moveTo(p(Point(-neck.x, neck.y)).x, p(neck).y)
        lineTo(p(neck).x, p(neck).y)
        // neck → shoulder tip: gentle slope
        val tip = p(right.first())
        val slope = p(Point(f.shoulderHalf - 0.04, f.shoulderY - 0.015))
        quadraticTo(slope.x, slope.y, tip.x, tip.y)
        // down the side with vertical tangents at every point
        for ((a, b) in right.zipWithNext()) {
            val pa = p(a); val pb = p(b); val mid = (pa.y + pb.y) / 2
            cubicTo(pa.x, mid, pb.x, mid, pb.x, pb.y)
        }
        val crotch = p(Point(0.0, f.crotchY + 0.012))
        val last = p(right.last())
        quadraticTo(last.x, crotch.y, crotch.x, crotch.y)
        val lastL = p(Point(-right.last().x, right.last().y))
        quadraticTo(lastL.x, crotch.y, lastL.x, lastL.y)
        for ((a, b) in right.reversed().zipWithNext()) {
            val pa = p(Point(-a.x, a.y)); val pb = p(Point(-b.x, b.y)); val mid = (pa.y + pb.y) / 2
            cubicTo(pa.x, mid, pb.x, mid, pb.x, pb.y)
        }
        val slopeL = p(Point(-(f.shoulderHalf - 0.04), f.shoulderY - 0.015))
        val neckL = p(Point(-neck.x, neck.y))
        quadraticTo(slopeL.x, slopeL.y, neckL.x, neckL.y)
        close()
    }
    drawPath(torso, SkinFill)
    drawPath(torso, SkinLine, style = line)

    // neck and head
    val neckTop = p(Point(-f.neckHalf * 0.75, f.headY + f.headRadius * 0.6))
    drawRect(SkinFill, topLeft = neckTop, size = Size(f.neckHalf.toFloat() * 1.5f * sx, (f.shoulderY - f.headY - f.headRadius * 0.6).toFloat() * h))
    drawCircle(SkinFill, radius = headRadius, center = headCentre)
    drawCircle(SkinLine, radius = headRadius, center = headCentre, style = line)

    // face
    val eyeY = headCentre.y + headRadius * 0.1f
    drawCircle(FaceInk, radius = headRadius * 0.08f, center = Offset(headCentre.x - headRadius * 0.35f, eyeY))
    drawCircle(FaceInk, radius = headRadius * 0.08f, center = Offset(headCentre.x + headRadius * 0.35f, eyeY))
    drawCircle(Cheek.copy(alpha = 0.6f), radius = headRadius * 0.13f, center = Offset(headCentre.x - headRadius * 0.55f, eyeY + headRadius * 0.25f))
    drawCircle(Cheek.copy(alpha = 0.6f), radius = headRadius * 0.13f, center = Offset(headCentre.x + headRadius * 0.55f, eyeY + headRadius * 0.25f))
    drawArc(
        FaceInk,
        startAngle = 20f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(headCentre.x - headRadius * 0.22f, eyeY + headRadius * 0.05f),
        size = Size(headRadius * 0.44f, headRadius * 0.3f),
        style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
    )

    // hair on top
    drawArc(
        HairColor,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = true,
        topLeft = Offset(headCentre.x - headRadius * 1.04f, headCentre.y - headRadius * 1.08f),
        size = Size(headRadius * 2.08f, headRadius * (if (f.longHair) 1.5f else 1.25f)),
    )
}

@Composable
private fun KeyChip(measurement: BodyMeasurement, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .width(ChipWidth)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Terracotta else MaterialTheme.colorScheme.background)
            .border(1.dp, Terracotta.copy(alpha = if (selected) 1f else 0.35f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val content = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
        Text(
            measurement.type.shortLabel(),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(formatCm(measurement.valueCm), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = content)
    }
}

private fun MeasurementType.shortLabel(): String = when (this) {
    MeasurementType.INSEAM -> "다리길이"
    else -> label.substringBefore(" ")
}

@Composable
private fun DetailCard(
    type: MeasurementType?,
    measurement: BodyMeasurement?,
    onEdit: ((BodyMeasurement) -> Unit)?,
    reference: ReferenceMeasurements?,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        when {
            type == null -> Text(
                "몸의 점이나 선을 누르면 해당 부위 치수를 볼 수 있어요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            measurement == null -> Column {
                Text(type.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "이번 사진에서는 이 부위를 재지 못했어요. 위의 안내에 따라 다시 찍으면 잴 수 있어요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(type.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        DESCRIPTIONS[type].orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    MeasurementStatus(measurement, reference)
                    reference?.values?.get(type)?.let { truth ->
                        Text(
                            "실제 ${formatCm(truth)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatCm(measurement.valueCm), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (onEdit != null) {
                        TextButton(onClick = { onEdit(measurement) }) {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("수정")
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun BodyFigurePreview() {
    val measurements = MeasurementType.entries.map { BodyMeasurement(it, 40.0 + it.ordinal, Confidence.MEDIUM) }
    StyleMateTheme {
        Column(Modifier.padding(16.dp)) {
            BodyFigureCard(measurements, Gender.FEMALE, onEdit = {})
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun BodyFigureMalePreview() {
    val measurements = MeasurementType.entries.map { BodyMeasurement(it, 40.0 + it.ordinal, Confidence.HIGH) }
    StyleMateTheme {
        Column(Modifier.padding(16.dp)) {
            BodyFigureCard(measurements, Gender.MALE, onEdit = null)
        }
    }
}

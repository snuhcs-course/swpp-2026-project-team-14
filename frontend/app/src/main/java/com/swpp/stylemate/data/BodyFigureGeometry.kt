// AI-generated with Claude Code (Claude Opus 5.5), 2026-09-28, reviewed by Dongkun Moon
package com.swpp.stylemate.data

import kotlin.math.hypot

/**
 * Proportions of the cartoon body figure, in "figure units": x is left(−)/right(+) from the
 * body's centre line (scaled by the figure width), y runs 0 (top of head) → 1 (soles).
 * Pure math so the drawing, tap detection and tests all use the same geometry.
 */
data class FigureShape(
    val shoulderHalf: Double,
    val chestHalf: Double,
    val waistHalf: Double,
    val hipHalf: Double,
    val longHair: Boolean,
) {
    val headY = 0.105
    val headRadius = 0.085 // share of figure height
    val neckY = 0.195
    val neckHalf = 0.10
    val shoulderY = 0.225
    val chestY = 0.29
    val underbustY = 0.335
    val waistY = 0.40
    val hipY = 0.49
    val crotchY = 0.555
    val kneeY = 0.735
    val calfY = 0.80
    val ankleY = 0.925
    val legCentre = 0.20 // |x| of each leg's centre line at the hips
    val ankleCentre = 0.15

    fun elbow(side: Int) = Point(side * (shoulderHalf + 0.16), 0.395)
    fun wrist(side: Int) = Point(side * (shoulderHalf + 0.25), 0.525)
    fun shoulder(side: Int) = Point(side * (shoulderHalf - 0.03), shoulderY + 0.01)

    companion object {
        fun of(gender: Gender): FigureShape = when (gender) {
            Gender.FEMALE -> FigureShape(shoulderHalf = 0.40, chestHalf = 0.38, waistHalf = 0.27, hipHalf = 0.45, longHair = true)
            Gender.MALE -> FigureShape(shoulderHalf = 0.47, chestHalf = 0.41, waistHalf = 0.34, hipHalf = 0.38, longHair = false)
            Gender.UNSPECIFIED -> FigureShape(shoulderHalf = 0.43, chestHalf = 0.39, waistHalf = 0.31, hipHalf = 0.41, longHair = false)
        }
    }
}

data class Point(val x: Double, val y: Double)

/** Where a measurement lives on the figure: a line segment (a band, a length) or a single point. */
data class Hotspot(val type: MeasurementType, val start: Point, val end: Point) {
    val isPoint: Boolean get() = start == end
    val centre: Point get() = Point((start.x + end.x) / 2, (start.y + end.y) / 2)

    /** Distance from [p] to this hotspot, with x and y already converted to the same unit. */
    fun distanceTo(p: Point, xScale: Double, yScale: Double): Double {
        val ax = start.x * xScale; val ay = start.y * yScale
        val bx = end.x * xScale; val by = end.y * yScale
        val px = p.x * xScale; val py = p.y * yScale
        val dx = bx - ax; val dy = by - ay
        val lengthSq = dx * dx + dy * dy
        val t = if (lengthSq == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lengthSq).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}

/** Hotspot of [type] on the figure for [gender], or null if that measurement does not apply (underbust). */
fun hotspotFor(type: MeasurementType, gender: Gender): Hotspot? {
    val f = FigureShape.of(gender)
    fun band(y: Double, half: Double, centre: Double = 0.0) = Hotspot(type, Point(centre - half, y), Point(centre + half, y))
    fun line(a: Point, b: Point) = Hotspot(type, a, b)
    fun dot(p: Point) = Hotspot(type, p, p)
    val right = 1
    val left = -1
    return when (type) {
        MeasurementType.NECK -> band(f.neckY, f.neckHalf)
        MeasurementType.SHOULDER_WIDTH -> band(f.shoulderY - 0.005, f.shoulderHalf)
        MeasurementType.CHEST -> band(f.chestY, f.chestHalf)
        MeasurementType.UNDERBUST -> if (gender == Gender.FEMALE) band(f.underbustY, f.chestHalf - 0.04) else null
        MeasurementType.WAIST -> band(f.waistY, f.waistHalf)
        MeasurementType.HIP -> band(f.hipY, f.hipHalf)
        MeasurementType.ARMHOLE -> dot(Point(right * (f.shoulderHalf - 0.02), f.shoulderY + 0.045))
        MeasurementType.BICEP -> {
            val s = f.shoulder(right); val e = f.elbow(right)
            dot(Point((s.x + e.x) / 2, (s.y + e.y) / 2))
        }
        MeasurementType.SLEEVE_LENGTH -> line(f.shoulder(left), f.wrist(left))
        MeasurementType.WRIST -> dot(f.wrist(right))
        MeasurementType.TORSO_LENGTH -> line(Point(-0.06, f.neckY + 0.02), Point(-0.06, f.waistY)) // off-centre so it does not cover the bands
        MeasurementType.RISE -> line(Point(0.06, f.waistY), Point(0.06, f.crotchY))
        MeasurementType.THIGH -> band(f.crotchY + 0.035, 0.16, centre = right * f.legCentre)
        MeasurementType.INSEAM -> line(Point(left * 0.05, f.crotchY + 0.01), Point(left * (f.ankleCentre - 0.05), f.ankleY))
        MeasurementType.OUTSEAM -> line(Point(right * (f.hipHalf + 0.02), f.waistY + 0.03), Point(right * (f.ankleCentre + 0.08), f.ankleY))
        MeasurementType.CALF -> band(f.calfY, 0.11, centre = left * 0.175)
    }
}

/** The hotspot closest to [tap] (figure units) within [maxDistance] (in y-scaled units), if any. */
fun hotspotAt(tap: Point, hotspots: List<Hotspot>, xScale: Double, yScale: Double, maxDistance: Double): Hotspot? =
    hotspots.minByOrNull { it.distanceTo(tap, xScale, yScale) }
        ?.takeIf { it.distanceTo(tap, xScale, yScale) <= maxDistance }

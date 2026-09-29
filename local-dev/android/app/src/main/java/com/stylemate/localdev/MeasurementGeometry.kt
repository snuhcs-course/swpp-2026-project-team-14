package com.stylemate.localdev

import kotlin.math.abs
import kotlin.math.sqrt

data class MeasurePoint(val x: Float, val y: Float, val z: Float) {
    operator fun minus(other: MeasurePoint) = MeasurePoint(x - other.x, y - other.y, z - other.z)
    fun dot(other: MeasurePoint) = x * other.x + y * other.y + z * other.z
}

/** Maps normalized coordinates of the captured viewport onto its frozen AR plane, in meters. */
class MeasurementGeometry(
    private val inverseViewProjection: FloatArray,
    private val planeOrigin: MeasurePoint,
    private val planeNormal: MeasurePoint,
) {
    fun point(u: Float, v: Float): MeasurePoint? {
        if (!u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f) return null
        val near = unproject(u * 2 - 1, 1 - v * 2, -1f) ?: return null
        val far = unproject(u * 2 - 1, 1 - v * 2, 1f) ?: return null
        val direction = far - near
        val denominator = direction.dot(planeNormal)
        val length = sqrt(direction.dot(direction))
        // Grazing rays amplify tiny pixel/plane errors; request a more overhead capture.
        if (length == 0f || abs(denominator) / length < 0.25f) return null
        val t = (planeOrigin - near).dot(planeNormal) / denominator
        if (!t.isFinite() || t < 0) return null
        return MeasurePoint(near.x + direction.x * t, near.y + direction.y * t, near.z + direction.z * t)
            .takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }
    }

    private fun unproject(x: Float, y: Float, z: Float): MeasurePoint? {
        val m = inverseViewProjection
        val w = m[3] * x + m[7] * y + m[11] * z + m[15]
        if (!w.isFinite() || abs(w) < 0.000001f) return null
        return MeasurePoint((m[0] * x + m[4] * y + m[8] * z + m[12]) / w,
            (m[1] * x + m[5] * y + m[9] * z + m[13]) / w,
            (m[2] * x + m[6] * y + m[10] * z + m[14]) / w)
    }
}

fun distanceCm(first: MeasurePoint, second: MeasurePoint): Float {
    val delta = first - second
    return sqrt(delta.dot(delta)) * 100f
}

data class MeasurementField(val key: String, val label: String, val method: String, val guide: String)

val topMeasurementFields = listOf(
    MeasurementField("shoulder_width", "어깨너비", "flat_shoulder_seam_to_seam", "옷 뒷면의 양 어깨 봉제점을 찍으세요."),
    MeasurementField("chest_width_half", "가슴 단면", "flat_underarm_to_underarm", "양 겨드랑이 아래의 좌우 끝을 찍으세요."),
    MeasurementField("total_length", "총장", "back_neck_to_hem", "뒷면 목 봉제선 중앙과 밑단을 찍으세요."),
    MeasurementField("hem_width_half", "밑단 단면", "flat_body_hem", "몸판 밑단의 좌우 끝을 찍으세요."),
    MeasurementField("cuff_width_half", "소매끝", "flat_sleeve_opening", "평평하게 편 한쪽 소매 끝의 좌우를 찍으세요."),
    MeasurementField("armhole_straight", "암홀", "armhole_top_to_underarm_straight", "일반 소매의 어깨·암홀 교점과 겨드랑이 봉제점을 찍으세요."),
)

val bottomMeasurementFields = listOf(
    MeasurementField("waist_width_half", "허리 단면", "flat_waistband_relaxed", "늘리지 않은 허리밴드 위쪽 좌우 끝을 찍으세요."),
    MeasurementField("hip_width_half", "엉덩이 단면", "flat_hip_max_width", "힙 부분의 가장 넓은 수평 단면 양 끝을 찍으세요."),
    MeasurementField("thigh_width_half", "허벅지 단면", "flat_thigh_at_crotch", "가랑이 높이에서 한쪽 바지 다리통의 양 끝을 찍으세요."),
    MeasurementField("hem_opening", "한쪽 밑단", "flat_single_leg_hem", "한쪽 바짓단의 좌우 끝을 찍으세요."),
)

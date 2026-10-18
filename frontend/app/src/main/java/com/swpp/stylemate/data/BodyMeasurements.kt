// AI-generated with Claude Code (Claude Opus 5.5), 2026-09-24, reviewed by Dongkun Moon
package com.swpp.stylemate.data

import java.util.Locale
import kotlin.math.abs

/** Measurement groups shown as separate sections on the result screen. */
enum class MeasurementGroup(val label: String) {
    LENGTH("길이"),
    CIRCUMFERENCE("둘레"),
}

/** How reliable a value is expected to be. Shown to the user as a badge. */
enum class Confidence(val label: String) {
    HIGH("정확도 높음"),
    MEDIUM("정확도 보통"),
    LOW("정확도 낮음");

    fun downgrade(): Confidence = when (this) {
        HIGH -> MEDIUM
        MEDIUM, LOW -> LOW
    }
}

/**
 * Body measurements StyleMate estimates, named after ISO 8559-1.
 *
 * @param baseConfidence expected confidence with underwear/tight clothing and both photos.
 */
enum class MeasurementType(
    val label: String,
    val group: MeasurementGroup,
    val baseConfidence: Confidence,
) {
    SHOULDER_WIDTH("어깨너비", MeasurementGroup.LENGTH, Confidence.HIGH),
    SLEEVE_LENGTH("소매길이", MeasurementGroup.LENGTH, Confidence.HIGH),
    TORSO_LENGTH("상체길이 (목~허리)", MeasurementGroup.LENGTH, Confidence.HIGH),
    RISE("밑위길이", MeasurementGroup.LENGTH, Confidence.MEDIUM),
    INSEAM("안쪽 다리길이", MeasurementGroup.LENGTH, Confidence.HIGH),
    OUTSEAM("바깥 다리길이", MeasurementGroup.LENGTH, Confidence.HIGH),

    NECK("목둘레", MeasurementGroup.CIRCUMFERENCE, Confidence.LOW),
    CHEST("가슴둘레", MeasurementGroup.CIRCUMFERENCE, Confidence.MEDIUM),
    UNDERBUST("밑가슴둘레", MeasurementGroup.CIRCUMFERENCE, Confidence.LOW),
    WAIST("허리둘레", MeasurementGroup.CIRCUMFERENCE, Confidence.MEDIUM),
    HIP("엉덩이둘레", MeasurementGroup.CIRCUMFERENCE, Confidence.MEDIUM),
    ARMHOLE("암홀둘레", MeasurementGroup.CIRCUMFERENCE, Confidence.LOW),
    BICEP("팔뚝둘레", MeasurementGroup.CIRCUMFERENCE, Confidence.MEDIUM),
    WRIST("손목둘레", MeasurementGroup.CIRCUMFERENCE, Confidence.LOW),
    THIGH("허벅지둘레", MeasurementGroup.CIRCUMFERENCE, Confidence.MEDIUM),
    CALF("종아리둘레", MeasurementGroup.CIRCUMFERENCE, Confidence.MEDIUM);

    /** Key used by the backend API, e.g. "shoulder_width". */
    val apiKey: String get() = name.lowercase()
}

enum class Gender(val label: String) {
    FEMALE("여성"),
    MALE("남성"),
    UNSPECIFIED("선택 안 함"),
}

/** Always visible on the body figure; the rest are shown when their body part is tapped. */
val KEY_MEASUREMENTS = listOf(
    MeasurementType.SHOULDER_WIDTH,
    MeasurementType.CHEST,
    MeasurementType.WAIST,
    MeasurementType.HIP,
    MeasurementType.INSEAM,
)

enum class ClothingRegion { TOP, BOTTOM }

/** Measurements a loose garment in each region distorts. Mirrors LOOSE_AFFECTS in the backend. */
val LOOSE_AFFECTS: Map<ClothingRegion, Set<MeasurementType>> = mapOf(
    ClothingRegion.TOP to setOf(
        MeasurementType.NECK, MeasurementType.SHOULDER_WIDTH, MeasurementType.CHEST,
        MeasurementType.UNDERBUST, MeasurementType.WAIST, MeasurementType.ARMHOLE,
        MeasurementType.BICEP, MeasurementType.TORSO_LENGTH,
    ),
    ClothingRegion.BOTTOM to setOf(
        MeasurementType.HIP, MeasurementType.THIGH, MeasurementType.CALF,
        MeasurementType.INSEAM, MeasurementType.RISE,
    ),
)

/** Clothing the server detected in the photos (users are no longer asked what they wore). */
data class DetectedClothing(val topLoose: Boolean = false, val bottomLoose: Boolean = false) {
    val anyLoose: Boolean get() = topLoose || bottomLoose

    fun affects(type: MeasurementType): Boolean =
        (topLoose && type in LOOSE_AFFECTS.getValue(ClothingRegion.TOP)) ||
            (bottomLoose && type in LOOSE_AFFECTS.getValue(ClothingRegion.BOTTOM))

    val label: String
        get() = when {
            topLoose && bottomLoose -> "헐렁한 상·하의"
            topLoose -> "헐렁한 상의"
            bottomLoose -> "헐렁한 하의"
            else -> "몸에 붙는 옷"
        }
}

enum class PreferredFit(val label: String) {
    SLIM("슬림핏"),
    REGULAR("레귤러핏"),
    LOOSE("루즈핏"),
}

val STYLE_OPTIONS = listOf("미니멀", "캐주얼", "스트릿", "러블리", "클래식", "스포티")
const val MAX_STYLES = 3

data class BodyMeasurement(
    val type: MeasurementType,
    val valueCm: Double,
    val confidence: Confidence,
    val editedByUser: Boolean = false,
)

data class AnalysisInput(
    val heightCm: Int,
    val weightKg: Int?,
    val gender: Gender,
)

/**
 * True measurements of one of our benchmark bodies, sent by the server only when the photos are
 * those benchmark renders (backend/body_analysis/reference.py). The app then shows real accuracy
 * per measurement; for every other photo it keeps the confidence badge.
 */
data class ReferenceMeasurements(
    val body: String,
    val heightCm: Double,
    val values: Map<MeasurementType, Double>,
) {
    /** 100 − relative error in %, or null if this measurement has no true value. */
    fun accuracyPercent(measurement: BodyMeasurement): Double? {
        val truth = values[measurement.type] ?: return null
        return (100.0 - abs(measurement.valueCm - truth) / truth * 100.0).coerceAtLeast(0.0)
    }
}

fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value)

data class AnalysisResult(
    val measurements: List<BodyMeasurement>,
    val warnings: List<String>,
    val clothing: DetectedClothing = DetectedClothing(),
    val reference: ReferenceMeasurements? = null,
)

/** Confirmed profile. This structured state is what recommendation and chat editing reuse. */
data class BodyProfile(
    val input: AnalysisInput,
    val measurements: List<BodyMeasurement>,
    val preferredFit: PreferredFit,
    val preferredStyles: List<String>,
    val clothing: DetectedClothing = DetectedClothing(),
    val reference: ReferenceMeasurements? = null,
) {
    fun valueOf(type: MeasurementType): Double? =
        measurements.firstOrNull { it.type == type }?.valueCm
}

/** Adds or removes [style], refusing to go over [MAX_STYLES]. */
fun toggleStyle(selected: List<String>, style: String): List<String> = when {
    style in selected -> selected - style
    selected.size >= MAX_STYLES -> selected
    else -> selected + style
}

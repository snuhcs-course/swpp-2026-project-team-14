package com.swpp.stylemate.data

import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.math.roundToInt

/** JPEG-encoded body photos. Kept in memory only and dropped after analysis. */
class BodyPhotos(val frontJpeg: ByteArray, val sideJpeg: ByteArray)

/** Analysis failed; [message] is a Korean hint that can be shown to the user as-is. */
class BodyAnalysisException(message: String, val code: String? = null, cause: Throwable? = null) :
    Exception(message, cause)

/** Turns body photos into measurements. [RemoteBodyAnalyzer] calls the Django pipeline. */
interface BodyAnalyzer {
    /** @throws BodyAnalysisException with a user-facing hint when the photos cannot be analysed. */
    suspend fun analyze(input: AnalysisInput, photos: BodyPhotos): AnalysisResult
}

/**
 * Offline stand-in used by previews and tests. It ignores the photo pixels and derives plausible
 * values from height, weight and gender using average body proportions. Confidence rules match
 * the backend.
 */
class FakeBodyAnalyzer(private val latencyMillis: Long = 1_800) : BodyAnalyzer {

    override suspend fun analyze(input: AnalysisInput, photos: BodyPhotos): AnalysisResult {
        delay(latencyMillis)
        val measurements = MeasurementType.entries
            .filter { it != MeasurementType.UNDERBUST || input.gender == Gender.FEMALE }
            .map { type ->
                BodyMeasurement(
                    type = type,
                    valueCm = estimateCm(type, input),
                    confidence = confidenceFor(type, input),
                )
            }
        return AnalysisResult(measurements, warningsFor(input))
    }

    private fun estimateCm(type: MeasurementType, input: AnalysisInput): Double {
        val ratio = when (input.gender) {
            Gender.MALE -> MALE_RATIOS.getValue(type)
            Gender.FEMALE -> FEMALE_RATIOS.getValue(type)
            Gender.UNSPECIFIED -> (MALE_RATIOS.getValue(type) + FEMALE_RATIOS.getValue(type)) / 2
        }
        var value = input.heightCm * ratio
        if (type.group == MeasurementGroup.CIRCUMFERENCE && input.weightKg != null) {
            val heightM = input.heightCm / 100.0
            val bmi = input.weightKg / (heightM * heightM)
            value *= (bmi / REFERENCE_BMI).pow(0.5)
        }
        return (value * 2).roundToInt() / 2.0 // 0.5 cm steps
    }

    companion object {
        private const val REFERENCE_BMI = 22.0

        fun confidenceFor(type: MeasurementType, input: AnalysisInput): Confidence {
            var confidence = type.baseConfidence
            if (input.clothing == ClothingType.LOOSE && type.group == MeasurementGroup.CIRCUMFERENCE) {
                confidence = confidence.downgrade()
            }
            return confidence
        }

        fun warningsFor(input: AnalysisInput): List<String> = buildList {
            if (input.clothing == ClothingType.LOOSE) add(warningText("loose_clothing"))
            if (input.weightKg == null) add(warningText("no_weight"))
        }

        // Share of body height, from average adult proportions. Placeholder until the real model exists.
        private val MALE_RATIOS = mapOf(
            MeasurementType.SHOULDER_WIDTH to 0.259,
            MeasurementType.SLEEVE_LENGTH to 0.350,
            MeasurementType.TORSO_LENGTH to 0.265,
            MeasurementType.RISE to 0.150,
            MeasurementType.INSEAM to 0.455,
            MeasurementType.OUTSEAM to 0.605,
            MeasurementType.NECK to 0.220,
            MeasurementType.CHEST to 0.555,
            MeasurementType.UNDERBUST to 0.480,
            MeasurementType.WAIST to 0.460,
            MeasurementType.HIP to 0.540,
            MeasurementType.ARMHOLE to 0.265,
            MeasurementType.BICEP to 0.175,
            MeasurementType.WRIST to 0.100,
            MeasurementType.THIGH to 0.315,
            MeasurementType.CALF to 0.215,
        )
        private val FEMALE_RATIOS = mapOf(
            MeasurementType.SHOULDER_WIDTH to 0.240,
            MeasurementType.SLEEVE_LENGTH to 0.335,
            MeasurementType.TORSO_LENGTH to 0.255,
            MeasurementType.RISE to 0.160,
            MeasurementType.INSEAM to 0.450,
            MeasurementType.OUTSEAM to 0.600,
            MeasurementType.NECK to 0.195,
            MeasurementType.CHEST to 0.530,
            MeasurementType.UNDERBUST to 0.440,
            MeasurementType.WAIST to 0.420,
            MeasurementType.HIP to 0.565,
            MeasurementType.ARMHOLE to 0.245,
            MeasurementType.BICEP to 0.165,
            MeasurementType.WRIST to 0.092,
            MeasurementType.THIGH to 0.330,
            MeasurementType.CALF to 0.210,
        )
    }
}

const val LOOSE_CLOTHING_INSIGHT_NOTE =
    "헐렁한 옷을 입고 찍은 사진이라 체형 비율 인사이트는 보여주지 않았어요. " +
        "속옷이나 몸에 붙는 옷을 입고 다시 분석하거나, 해당 치수를 직접 입력하면 볼 수 있어요."

/**
 * Ratio thresholds for the insights. Each insight is only shown when the ratio is past its threshold
 * by at least the margin, so a measurement error cannot flip it (docs/body-analysis/04 §9).
 * Margins ≈ the largest ratio error for underwear photos in the synthetic benchmark.
 */
object InsightThresholds {
    const val LEG_RATIO = 0.46 // inseam / height
    const val LEG_MARGIN = 0.01
    const val SHOULDER_RATIO = 0.255 // shoulder width / height
    const val SHOULDER_MARGIN = 0.01
    const val HIP_CHEST_RATIO = 1.05
    const val HIP_CHEST_MARGIN = 0.05
    const val WAIST_HIP_RATIO = 0.75
    const val WAIST_HIP_MARGIN = 0.05
}

/**
 * Neutral, styling-focused insight sentences derived from the confirmed measurements.
 *
 * Loose clothing distorts the measurements these insights use (synthetic benchmark,
 * docs/body-analysis/04 §8): circumferences and shoulder width are inflated, and wide trousers hide
 * the crotch so inseam comes out far too short. Insights are therefore only shown for underwear/tight
 * photos, or when the user typed the underlying values in themselves.
 */
fun buildInsights(profile: BodyProfile): List<String> = buildList {
    val height = profile.input.heightCm.toDouble()
    val looseClothing = profile.input.clothing == ClothingType.LOOSE
    var suppressed = false
    fun trusted(type: MeasurementType): Double? {
        val measurement = profile.measurements.firstOrNull { it.type == type } ?: return null
        if (looseClothing && !measurement.editedByUser) {
            suppressed = true
            return null
        }
        return measurement.valueCm
    }
    val inseam = trusted(MeasurementType.INSEAM)
    val shoulder = trusted(MeasurementType.SHOULDER_WIDTH)
    val waist = trusted(MeasurementType.WAIST)
    val hip = trusted(MeasurementType.HIP)
    val chest = trusted(MeasurementType.CHEST)

    with(InsightThresholds) {
        if (inseam != null) {
            // two-sided: close to the threshold neither sentence is shown
            val legRatio = inseam / height
            if (legRatio >= LEG_RATIO + LEG_MARGIN) {
                add("키에 비해 다리가 긴 편이에요. 크롭 기장 상의나 하이웨이스트 하의가 비율을 잘 살려줘요.")
            } else if (legRatio <= LEG_RATIO - LEG_MARGIN) {
                add("상체가 비교적 긴 편이에요. 하이웨이스트 하의와 상의 넣어 입기로 다리 라인을 길어 보이게 할 수 있어요.")
            }
        }
        if (shoulder != null && shoulder / height >= SHOULDER_RATIO + SHOULDER_MARGIN) {
            add("어깨가 넓은 편이라 어깨선이 딱 맞는 상의와 V넥이 균형 있게 어울려요.")
        }
        if (chest != null && hip != null && hip / chest >= HIP_CHEST_RATIO + HIP_CHEST_MARGIN) {
            add("하체 볼륨이 상체보다 있는 편이에요. A라인 스커트나 스트레이트 팬츠로 균형을 맞춰보세요.")
        }
        if (waist != null && hip != null && waist / hip <= WAIST_HIP_RATIO - WAIST_HIP_MARGIN) {
            add("허리 라인이 잘 드러나는 편이라 벨트나 허리선이 들어간 아이템이 잘 어울려요.")
        }
    }
    if (suppressed) add(LOOSE_CLOTHING_INSIGHT_NOTE)
}

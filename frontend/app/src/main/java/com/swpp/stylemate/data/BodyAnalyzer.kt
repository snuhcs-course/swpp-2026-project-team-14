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
                    confidence = confidenceFor(type, DetectedClothing()),
                )
            }
        return AnalysisResult(measurements, warningsFor(input), DetectedClothing())
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

        /** Same rule as the backend: a detected loose garment lowers the measurements it distorts. */
        fun confidenceFor(type: MeasurementType, clothing: DetectedClothing): Confidence =
            if (clothing.affects(type)) type.baseConfidence.downgrade() else type.baseConfidence

        fun warningsFor(input: AnalysisInput, clothing: DetectedClothing = DetectedClothing()): List<String> =
            buildList {
                if (clothing.topLoose) add(warningText("loose_top"))
                if (clothing.bottomLoose) add(warningText("loose_bottom"))
                if (input.weightKg == null) add(warningText("no_weight"))
            }

        // Share of body height, from average adult proportions. Placeholder until the real model exists.
        internal val MALE_RATIOS = mapOf(
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
    "헐렁한 옷 때문에 일부 체형 인사이트는 보여주지 않았어요. " +
        "속옷이나 몸에 붙는 옷을 입고 다시 분석하거나, 해당 치수를 직접 입력하면 볼 수 있어요."

/** One insight card: a short title and a styling-focused explanation. */
data class Insight(val title: String, val body: String)

/** Body-shape types, named neutrally for styling (no weight or health wording). */
enum class BodyShape(val label: String, val description: String) {
    LOWER_VOLUME(
        "하체 볼륨형",
        "상체보다 엉덩이·허벅지 쪽에 볼륨이 있는 체형이에요. 밝은 색과 디테일은 상의에 두고, 하의는 A라인 스커트나 스트레이트 팬츠로 균형을 맞춰보세요.",
    ),
    UPPER_VOLUME(
        "상체 볼륨형",
        "가슴·어깨 쪽에 볼륨이 있는 역삼각형 체형이에요. 상의는 V넥처럼 심플하게, 하의는 와이드 팬츠나 플레어 스커트로 볼륨을 더하면 균형이 좋아져요.",
    ),
    HOURGLASS(
        "허리 라인형",
        "가슴과 엉덩이 둘레가 비슷하고 허리가 잘 들어간 모래시계형이에요. 벨트, 랩 원피스, 하이웨이스트처럼 허리선을 살리는 옷이 잘 어울려요.",
    ),
    STRAIGHT(
        "일자형",
        "가슴·허리·엉덩이 둘레가 비슷한 일자형이에요. 벨트나 페플럼, 레이어드로 허리 라인을 만들어주면 입체감이 생겨요.",
    ),
}

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
    const val HIP_CHEST_RATIO = 1.05 // hip / chest (and chest / hip for the upper body)
    const val HIP_CHEST_MARGIN = 0.05
    const val WAIST_HIP_RATIO = 0.75 // waist / hip: defined waist below, straight above
    const val WAIST_HIP_MARGIN = 0.05
}

/** Body-shape type from chest, waist and hip, or null when the ratios are too close to call. */
fun bodyShapeOf(chest: Double, waist: Double, hip: Double): BodyShape? = with(InsightThresholds) {
    val bar = HIP_CHEST_RATIO + HIP_CHEST_MARGIN
    when {
        hip / chest >= bar -> BodyShape.LOWER_VOLUME
        chest / hip >= bar -> BodyShape.UPPER_VOLUME
        waist / hip <= WAIST_HIP_RATIO - WAIST_HIP_MARGIN -> BodyShape.HOURGLASS
        waist / hip >= WAIST_HIP_RATIO + WAIST_HIP_MARGIN -> BodyShape.STRAIGHT
        else -> null
    }
}

/**
 * Insight cards derived from the measurements (used on the results screen and in My Profile).
 *
 * Loose clothing distorts the measurements these insights use (synthetic benchmark,
 * docs/body-analysis/04 §8), so an insight is hidden when loose clothing was detected in the
 * region it depends on, unless the user typed the values in themselves.
 */
fun buildInsights(
    measurements: List<BodyMeasurement>,
    heightCm: Int,
    clothing: DetectedClothing,
): List<Insight> = buildList {
    val height = heightCm.toDouble()
    var suppressed = false
    fun trusted(type: MeasurementType): Double? {
        val measurement = measurements.firstOrNull { it.type == type } ?: return null
        if (clothing.affects(type) && !measurement.editedByUser) {
            suppressed = true
            return null
        }
        return measurement.valueCm
    }
    val chest = trusted(MeasurementType.CHEST)
    val waist = trusted(MeasurementType.WAIST)
    val hip = trusted(MeasurementType.HIP)
    val inseam = trusted(MeasurementType.INSEAM)
    val shoulder = trusted(MeasurementType.SHOULDER_WIDTH)

    if (chest != null && waist != null && hip != null) {
        bodyShapeOf(chest, waist, hip)?.let { add(Insight("체형 타입 · ${it.label}", it.description)) }
    }
    with(InsightThresholds) {
        if (inseam != null) {
            // two-sided: close to the threshold neither sentence is shown
            val legRatio = inseam / height
            if (legRatio >= LEG_RATIO + LEG_MARGIN) {
                add(Insight("다리 비율", "키에 비해 다리가 긴 편이에요. 크롭 기장 상의나 하이웨이스트 하의가 비율을 잘 살려줘요."))
            } else if (legRatio <= LEG_RATIO - LEG_MARGIN) {
                add(Insight("상체 비율", "상체가 비교적 긴 편이에요. 하이웨이스트 하의와 상의 넣어 입기로 다리 라인을 길어 보이게 할 수 있어요."))
            }
        }
        if (shoulder != null && shoulder / height >= SHOULDER_RATIO + SHOULDER_MARGIN) {
            add(Insight("어깨", "어깨가 넓은 편이라 어깨선이 딱 맞는 상의와 V넥이 균형 있게 어울려요."))
        }
    }
    if (suppressed) add(Insight("안내", LOOSE_CLOTHING_INSIGHT_NOTE))
}

fun buildInsights(profile: BodyProfile): List<Insight> =
    buildInsights(profile.measurements, profile.input.heightCm, profile.clothing)

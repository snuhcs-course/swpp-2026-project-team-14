package com.swpp.stylemate.data

import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.math.roundToInt

/** Analyzes body photos. The real implementation will call the Django `/api/body-profile/analyze/` endpoint. */
interface BodyAnalyzer {
    suspend fun analyze(input: AnalysisInput): AnalysisResult
}

/**
 * Prototype stand-in for the AI pipeline. It ignores the photo pixels and derives plausible values
 * from height, weight and gender using average body proportions, so the UI flow can be tried
 * end to end. Confidence rules match the design document.
 */
class FakeBodyAnalyzer(private val latencyMillis: Long = 1_800) : BodyAnalyzer {

    override suspend fun analyze(input: AnalysisInput): AnalysisResult {
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
            if (type.needsSidePhoto && !input.hasSidePhoto) confidence = confidence.downgrade()
            if (input.clothing == ClothingType.LOOSE && type.group == MeasurementGroup.CIRCUMFERENCE) {
                confidence = confidence.downgrade()
            }
            return confidence
        }

        fun warningsFor(input: AnalysisInput): List<String> = buildList {
            if (input.clothing == ClothingType.LOOSE) {
                add("헐렁한 옷을 입은 사진이라 둘레 치수의 정확도가 낮아요. 속옷이나 몸에 붙는 옷을 입고 다시 찍으면 더 정확해져요.")
            }
            if (!input.hasSidePhoto) {
                add("측면 사진이 없어 둘레와 상체길이는 대략적인 추정치예요.")
            }
            if (input.weightKg == null) {
                add("몸무게를 입력하면 둘레 치수가 더 정확해져요.")
            }
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

/** Neutral, styling-focused insight sentences derived from the confirmed measurements. */
fun buildInsights(profile: BodyProfile): List<String> = buildList {
    val height = profile.input.heightCm.toDouble()
    val inseam = profile.valueOf(MeasurementType.INSEAM)
    val shoulder = profile.valueOf(MeasurementType.SHOULDER_WIDTH)
    val waist = profile.valueOf(MeasurementType.WAIST)
    val hip = profile.valueOf(MeasurementType.HIP)
    val chest = profile.valueOf(MeasurementType.CHEST)

    if (inseam != null) {
        if (inseam / height >= 0.46) {
            add("키에 비해 다리가 긴 편이에요. 크롭 기장 상의나 하이웨이스트 하의가 비율을 잘 살려줘요.")
        } else {
            add("상체가 비교적 긴 편이에요. 하이웨이스트 하의와 상의 넣어 입기로 다리 라인을 길어 보이게 할 수 있어요.")
        }
    }
    if (shoulder != null && shoulder / height >= 0.255) {
        add("어깨가 넓은 편이라 어깨선이 딱 맞는 상의와 V넥이 균형 있게 어울려요.")
    }
    if (chest != null && hip != null && hip > chest * 1.05) {
        add("하체 볼륨이 상체보다 있는 편이에요. A라인 스커트나 스트레이트 팬츠로 균형을 맞춰보세요.")
    }
    if (waist != null && hip != null && waist / hip <= 0.75) {
        add("허리 라인이 잘 드러나는 편이라 벨트나 허리선이 들어간 아이템이 잘 어울려요.")
    }
}

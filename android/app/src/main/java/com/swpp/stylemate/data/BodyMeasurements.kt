package com.swpp.stylemate.data

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
 * @param needsSidePhoto the value depends on body depth, which only the side photo shows.
 * @param baseConfidence expected confidence with underwear/tight clothing and both photos.
 */
enum class MeasurementType(
    val label: String,
    val group: MeasurementGroup,
    val needsSidePhoto: Boolean,
    val baseConfidence: Confidence,
) {
    SHOULDER_WIDTH("어깨너비", MeasurementGroup.LENGTH, false, Confidence.HIGH),
    SLEEVE_LENGTH("소매길이", MeasurementGroup.LENGTH, false, Confidence.HIGH),
    TORSO_LENGTH("상체길이 (목~허리)", MeasurementGroup.LENGTH, true, Confidence.HIGH),
    RISE("밑위길이", MeasurementGroup.LENGTH, true, Confidence.MEDIUM),
    INSEAM("안쪽 다리길이", MeasurementGroup.LENGTH, false, Confidence.HIGH),
    OUTSEAM("바깥 다리길이", MeasurementGroup.LENGTH, false, Confidence.HIGH),

    NECK("목둘레", MeasurementGroup.CIRCUMFERENCE, true, Confidence.LOW),
    CHEST("가슴둘레", MeasurementGroup.CIRCUMFERENCE, true, Confidence.MEDIUM),
    UNDERBUST("밑가슴둘레", MeasurementGroup.CIRCUMFERENCE, true, Confidence.LOW),
    WAIST("허리둘레", MeasurementGroup.CIRCUMFERENCE, true, Confidence.MEDIUM),
    HIP("엉덩이둘레", MeasurementGroup.CIRCUMFERENCE, true, Confidence.MEDIUM),
    ARMHOLE("암홀둘레", MeasurementGroup.CIRCUMFERENCE, true, Confidence.LOW),
    BICEP("팔뚝둘레", MeasurementGroup.CIRCUMFERENCE, true, Confidence.MEDIUM),
    WRIST("손목둘레", MeasurementGroup.CIRCUMFERENCE, false, Confidence.LOW),
    THIGH("허벅지둘레", MeasurementGroup.CIRCUMFERENCE, true, Confidence.MEDIUM),
    CALF("종아리둘레", MeasurementGroup.CIRCUMFERENCE, true, Confidence.MEDIUM),
}

enum class Gender(val label: String) {
    FEMALE("여성"),
    MALE("남성"),
    UNSPECIFIED("선택 안 함"),
}

/** What the user wore in the photos. Loose clothing hides the body outline. */
enum class ClothingType(val label: String, val description: String) {
    UNDERWEAR("속옷", "가장 정확해요"),
    TIGHT("몸에 붙는 옷", "레깅스·타이트한 티셔츠"),
    LOOSE("평상복·헐렁한 옷", "정확도가 낮아져요"),
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
    val clothing: ClothingType,
    val hasSidePhoto: Boolean,
)

data class AnalysisResult(
    val measurements: List<BodyMeasurement>,
    val warnings: List<String>,
)

/** Confirmed profile. This structured state is what recommendation and chat editing reuse. */
data class BodyProfile(
    val input: AnalysisInput,
    val measurements: List<BodyMeasurement>,
    val preferredFit: PreferredFit,
    val preferredStyles: List<String>,
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

package com.swpp.stylemate.ui.profile

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swpp.stylemate.BuildConfig
import com.swpp.stylemate.data.AnalysisInput
import com.swpp.stylemate.data.BodyAnalysisException
import com.swpp.stylemate.data.BodyAnalyzer
import com.swpp.stylemate.data.BodyMeasurement
import com.swpp.stylemate.data.BodyPhotos
import com.swpp.stylemate.data.BodyProfile
import com.swpp.stylemate.data.DetectedClothing
import com.swpp.stylemate.data.Gender
import com.swpp.stylemate.data.RemoteBodyAnalyzer
import com.swpp.stylemate.data.MeasurementType
import com.swpp.stylemate.data.PreferredFit
import com.swpp.stylemate.data.ReferenceMeasurements
import com.swpp.stylemate.data.toggleStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

enum class SetupStep { GUIDE, INPUT, ANALYZING, REVIEW }

enum class PhotoSlot { FRONT, SIDE }

data class SetupState(
    val step: SetupStep = SetupStep.GUIDE,
    val frontPhoto: Bitmap? = null,
    val sidePhoto: Bitmap? = null,
    val heightText: String = "",
    val weightText: String = "",
    val gender: Gender = Gender.UNSPECIFIED,
    val lastInput: AnalysisInput? = null,
    val measurements: List<BodyMeasurement> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** What the server detected the user wore in the photos. */
    val detectedClothing: DetectedClothing = DetectedClothing(),
    /** True measurements when the photos were one of our benchmark bodies (shows accuracy %). */
    val reference: ReferenceMeasurements? = null,
    val preferredFit: PreferredFit = PreferredFit.REGULAR,
    val preferredStyles: List<String> = emptyList(),
    /** Retake hint from the last failed analysis, shown on the input screen. */
    val errorMessage: String? = null,
) {
    val heightCm: Int? get() = heightText.toIntOrNull()?.takeIf { it in 100..220 }
    val weightKg: Int? get() = weightText.toIntOrNull()?.takeIf { it in 30..200 }
    val heightError: Boolean get() = heightText.isNotEmpty() && heightCm == null
    val weightError: Boolean get() = weightText.isNotEmpty() && weightKg == null
    val canAnalyze: Boolean get() = frontPhoto != null && sidePhoto != null && heightCm != null && !weightError
}

data class AppState(
    val profile: BodyProfile? = null,
    /** True while the onboarding / re-analysis flow is on screen. */
    val setupVisible: Boolean = true,
)

class BodyProfileViewModel(
    private val analyzer: BodyAnalyzer = RemoteBodyAnalyzer(BuildConfig.API_BASE_URL),
) : ViewModel() {

    private val _app = MutableStateFlow(AppState())
    val app: StateFlow<AppState> = _app.asStateFlow()

    private val _setup = MutableStateFlow(SetupState())
    val setup: StateFlow<SetupState> = _setup.asStateFlow()

    fun startCapture() = _setup.update { it.copy(step = SetupStep.INPUT) }

    fun backToGuide() = _setup.update { it.copy(step = SetupStep.GUIDE) }

    fun setPhoto(slot: PhotoSlot, bitmap: Bitmap?) = _setup.update {
        when (slot) {
            PhotoSlot.FRONT -> it.copy(frontPhoto = bitmap, errorMessage = null)
            PhotoSlot.SIDE -> it.copy(sidePhoto = bitmap, errorMessage = null)
        }
    }

    fun showError(message: String) = _setup.update { it.copy(errorMessage = message) }

    fun setHeight(text: String) = _setup.update { it.copy(heightText = text.filter(Char::isDigit).take(3)) }

    fun setWeight(text: String) = _setup.update { it.copy(weightText = text.filter(Char::isDigit).take(3)) }

    fun setGender(gender: Gender) = _setup.update { it.copy(gender = gender) }

    fun analyze() {
        val state = _setup.value
        val height = state.heightCm ?: return
        val front = state.frontPhoto ?: return
        val side = state.sidePhoto ?: return
        if (!state.canAnalyze) return
        val input = AnalysisInput(
            heightCm = height,
            weightKg = state.weightKg,
            gender = state.gender,
        )
        _setup.update { it.copy(step = SetupStep.ANALYZING, errorMessage = null) }
        viewModelScope.launch {
            try {
                val photos = withContext(Dispatchers.Default) { BodyPhotos(front.toJpeg(), side.toJpeg()) }
                val result = analyzer.analyze(input, photos)
                // Photos are dropped right after analysis; only numbers are kept.
                _setup.update {
                    it.copy(
                        step = SetupStep.REVIEW,
                        frontPhoto = null,
                        sidePhoto = null,
                        lastInput = input,
                        measurements = result.measurements,
                        warnings = result.warnings,
                        detectedClothing = result.clothing,
                        reference = result.reference,
                    )
                }
            } catch (e: BodyAnalysisException) {
                // Keep the photos so the user can replace just the one that failed.
                _setup.update { it.copy(step = SetupStep.INPUT, errorMessage = e.message) }
            }
        }
    }

    fun editMeasurement(type: MeasurementType, valueCm: Double) = _setup.update { state ->
        state.copy(
            measurements = state.measurements.map {
                if (it.type == type) it.copy(valueCm = valueCm, editedByUser = true) else it
            },
        )
    }

    fun setPreferredFit(fit: PreferredFit) = _setup.update { it.copy(preferredFit = fit) }

    fun toggleStyle(style: String) = _setup.update {
        it.copy(preferredStyles = toggleStyle(it.preferredStyles, style))
    }

    fun confirmProfile() {
        val state = _setup.value
        val input = state.lastInput ?: return
        val profile = BodyProfile(
            input = input,
            measurements = state.measurements,
            preferredFit = state.preferredFit,
            preferredStyles = state.preferredStyles,
            clothing = state.detectedClothing,
            reference = state.reference,
        )
        _app.update { it.copy(profile = profile, setupVisible = false) }
    }

    fun skipSetup() = _app.update { it.copy(setupVisible = false) }

    /** Opens the flow again, pre-filled with the current profile (US-B4). */
    fun startReanalysis() {
        val profile = _app.value.profile
        _setup.value = if (profile == null) {
            SetupState()
        } else {
            SetupState(
                heightText = profile.input.heightCm.toString(),
                weightText = profile.input.weightKg?.toString().orEmpty(),
                gender = profile.input.gender,
                preferredFit = profile.preferredFit,
                preferredStyles = profile.preferredStyles,
            )
        }
        _app.update { it.copy(setupVisible = true) }
    }

    /** Leaves the flow without changing the saved profile. */
    fun cancelSetup() = _app.update { it.copy(setupVisible = false) }

    fun deleteProfile() = _app.update { it.copy(profile = null) }
}

/** Re-encoding also strips EXIF metadata (location, device) from the original photo. */
private fun Bitmap.toJpeg(quality: Int = 90): ByteArray =
    ByteArrayOutputStream().use { out ->
        compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }

// AI-generated with Claude Code (Dongkun Moon) and Codex (Hyeon U Jeong), 2026-09-24, reviewed by Dongkun Moon and Hyeon U Jeong
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
import com.swpp.stylemate.data.BodyProfileStore
import com.swpp.stylemate.data.InMemoryBodyProfileStore
import com.swpp.stylemate.data.DetectedClothing
import com.swpp.stylemate.data.Gender
import com.swpp.stylemate.data.RemoteBodyAnalyzer
import com.swpp.stylemate.data.MeasurementType
import com.swpp.stylemate.data.PreferredFit
import com.swpp.stylemate.data.ReferenceMeasurements
import com.swpp.stylemate.data.toggleStyle
import com.swpp.stylemate.data.OnboardingProgress
import com.swpp.stylemate.data.StyleProfile
import com.swpp.stylemate.data.AccountAccess
import com.swpp.stylemate.data.BodyProfileJson
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
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
    val saving: Boolean = false,
) {
    val heightCm: Int? get() = heightText.toIntOrNull()?.takeIf { it in 100..220 }
    val weightKg: Int? get() = weightText.toIntOrNull()?.takeIf { it in 30..200 }
    val heightError: Boolean get() = heightText.isNotEmpty() && heightCm == null
    val weightError: Boolean get() = weightText.isNotEmpty() && weightKg == null
    val canAnalyze: Boolean get() = frontPhoto != null && sidePhoto != null && heightCm != null && !weightError
}

data class AppState(
    val profile: BodyProfile? = null,
    val style: StyleProfile = StyleProfile(),
    val storageError: String? = null,
    /** True while the onboarding / re-analysis flow is on screen. */
    val setupVisible: Boolean = true,
)

class BodyProfileViewModel(
    private val analyzer: BodyAnalyzer = RemoteBodyAnalyzer(BuildConfig.API_BASE_URL),
    private val store: BodyProfileStore = InMemoryBodyProfileStore(),
    private val account: AccountAccess? = null,
) : ViewModel() {

    private fun loadAppState(): AppState = try {
        AppState(profile = store.load(), style = store.loadStyle(), setupVisible = !store.loadOnboarding().completed)
    } catch (_: Exception) {
        AppState(setupVisible = false, storageError = "저장된 정보를 불러오지 못했어요. 기존 데이터는 유지됩니다.")
    }
    private val _app = MutableStateFlow(loadAppState())
    val app: StateFlow<AppState> = _app.asStateFlow()
    private val _onboarding = MutableStateFlow(runCatching { store.loadOnboarding() }.getOrDefault(OnboardingProgress()))
    val onboarding = _onboarding.asStateFlow()

    private val _setup = MutableStateFlow(runCatching { restoreSetup() }.getOrDefault(SetupState()))
    val setup: StateFlow<SetupState> = _setup.asStateFlow()
    private var fitChanged = false
    private var moodsChanged = false

    fun reloadLocalData() {
        _app.value = loadAppState()
        if (_app.value.storageError == null) { _onboarding.value = store.loadOnboarding(); _setup.value = restoreSetup() }
    }

    fun refreshAccountProfile() {
        _app.update { it.copy(profile = store.load(), style = store.loadStyle()) }
    }

    private fun restoreSetup(): SetupState {
        val progress = store.loadOnboarding()
        val draft = JSONObject(progress.draft)
        val result = draft.optJSONObject("result")?.toString()?.let(BodyProfileJson::decode)
            ?: if (!progress.completed && progress.step >= 5) store.load() else null
        return SetupState(
            step = if (!progress.completed && progress.step >= 5 && result != null) SetupStep.REVIEW
                   else if (!progress.completed && progress.step >= 4) SetupStep.INPUT else SetupStep.GUIDE,
            heightText = draft.optString("height", result?.input?.heightCm?.toString().orEmpty()),
            weightText = draft.optString("weight", result?.input?.weightKg?.toString().orEmpty()),
            gender = Gender.entries.firstOrNull { it.name == draft.optString("gender") } ?: result?.input?.gender ?: Gender.UNSPECIFIED,
            lastInput = result?.input, measurements = result?.measurements.orEmpty(),
            detectedClothing = result?.clothing ?: DetectedClothing(), reference = result?.reference,
            preferredFit = store.loadStyle().legacyFit(), preferredStyles = store.loadStyle().styleLabels(),
        )
    }

    private fun persistSetup() = try {
        val state = _setup.value
        val progress = store.loadOnboarding()
        val draft = JSONObject(progress.draft).put("height", state.heightText).put("weight", state.weightText).put("gender", state.gender.name)
        draft.remove("result")
        if (state.step == SetupStep.REVIEW && state.lastInput != null) {
            draft.put("result", JSONObject(com.swpp.stylemate.data.BodyProfileJson.encode(BodyProfile(
                state.lastInput, state.measurements, state.preferredFit, state.preferredStyles,
                state.detectedClothing, state.reference,
            ))))
        }
        val step = if (progress.completed) progress.step else if (state.step == SetupStep.REVIEW) 5 else 4
        saveProgress(OnboardingProgress(step, progress.completed, draft.toString()))
    } catch (_: Exception) { _setup.update { it.copy(errorMessage = "입력 내용을 저장하지 못했어요. 다시 시도해 주세요.") } }

    private fun saveProgress(progress: OnboardingProgress) {
        store.saveOnboarding(progress)
        _onboarding.value = progress
    }

    fun moveOnboarding(step: Int) {
        try {
            if (step == 4) _setup.update { it.copy(step = SetupStep.INPUT, errorMessage = null) }
            saveProgress(_onboarding.value.copy(step = step))
        } catch (_: Exception) { _app.update { it.copy(storageError = "진행 상황을 저장하지 못했어요. 다시 시도해 주세요.") } }
    }

    fun updateOnboardingStyle(style: StyleProfile) {
        val error = saveStyleProfile(style)
        if (error != null) _setup.update { it.copy(errorMessage = error) }
        else _setup.update { it.copy(errorMessage = null) }
    }

    fun setOnboardingFits(fits: List<String>) {
        try {
            val draft = JSONObject(_onboarding.value.draft).put("preferred_fits", JSONArray(fits))
            saveProgress(_onboarding.value.copy(draft = draft.toString()))
            _setup.update { it.copy(errorMessage = null) }
        } catch (_: Exception) { showError("선택 내용을 저장하지 못했어요. 다시 시도해 주세요.") }
    }

    fun confirmOnboardingFits() {
        val values = JSONObject(_onboarding.value.draft).optJSONArray("preferred_fits")
        val fits = (0 until (values?.length() ?: 0)).map { values!!.getString(it) }
        val error = saveStyleProfile(_app.value.style.copy(preferredFits = fits))
        if (error != null) showError(error) else moveOnboarding(3)
    }

    fun skipOnboardingBody() {
        _setup.update { it.copy(frontPhoto = null, sidePhoto = null) }
        moveOnboarding(6)
    }

    fun startOnboardingGarment(category: String, id: String? = null) {
        try {
            val garment = JSONObject().put("category", category)
            id?.let { garment.put("editing_id", it) }
            val draft = JSONObject(_onboarding.value.draft).put("garment", garment)
            saveProgress(_onboarding.value.copy(step = 7, draft = draft.toString()))
        } catch (_: Exception) { _app.update { it.copy(storageError = "진행 상황을 저장하지 못했어요. 다시 시도해 주세요.") } }
    }

    fun saveOnboardingGarment(draft: JSONObject) {
        val progress = _onboarding.value
        if (progress.completed || progress.step != 7) return
        try { saveProgress(progress.copy(draft = JSONObject(progress.draft).put("garment", draft).toString())) }
        catch (_: Exception) { _app.update { it.copy(storageError = "옷 등록 내용을 저장하지 못했어요. 다시 시도해 주세요.") } }
    }

    fun finishOnboardingGarment() {
        try {
            val draft = JSONObject(_onboarding.value.draft).apply { remove("garment") }
            saveProgress(_onboarding.value.copy(step = 6, draft = draft.toString()))
        } catch (_: Exception) { _app.update { it.copy(storageError = "진행 상황을 저장하지 못했어요. 다시 시도해 주세요.") } }
    }

    fun startCapture() { _setup.update { it.copy(step = SetupStep.INPUT) }; persistSetup() }

    fun backToGuide() { _setup.update { it.copy(step = SetupStep.GUIDE) }; persistSetup() }

    fun setPhoto(slot: PhotoSlot, bitmap: Bitmap?) = _setup.update {
        when (slot) {
            PhotoSlot.FRONT -> it.copy(frontPhoto = bitmap, errorMessage = null)
            PhotoSlot.SIDE -> it.copy(sidePhoto = bitmap, errorMessage = null)
        }
    }

    fun showError(message: String) = _setup.update { it.copy(errorMessage = message) }

    fun setHeight(text: String) { _setup.update { it.copy(heightText = text.filter(Char::isDigit).take(3)) }; persistSetup() }

    fun setWeight(text: String) { _setup.update { it.copy(weightText = text.filter(Char::isDigit).take(3)) }; persistSetup() }

    fun setGender(gender: Gender) { _setup.update { it.copy(gender = gender) }; persistSetup() }

    fun analyze() {
        val state = _setup.value
        if (state.step == SetupStep.ANALYZING || state.saving) return
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
                persistSetup()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                // Keep the photos so the user can replace just the one that failed.
                _setup.update { it.copy(step = SetupStep.INPUT, errorMessage = e.message ?: "분석하지 못했어요. 다시 시도해 주세요.") }
            }
        }
    }

    fun editMeasurement(type: MeasurementType, valueCm: Double) { _setup.update { state ->
        state.copy(
            measurements = state.measurements.map {
                if (it.type == type) it.copy(valueCm = valueCm, editedByUser = true) else it
            },
        )
    }; persistSetup() }

    fun setPreferredFit(fit: PreferredFit) {
        if (_setup.value.saving) return
        _setup.update { it.copy(preferredFit = fit) }
        fitChanged = true
        if (account == null) {
            store.saveStyle(store.loadStyle().copy(preferredFits = StyleProfile.fromLegacy(fit, emptyList()).preferredFits))
            _app.update { it.copy(style = store.loadStyle()) }
        }
        persistSetup()
    }

    fun toggleStyle(style: String) {
        if (_setup.value.saving) return
        _setup.update {
        it.copy(preferredStyles = toggleStyle(it.preferredStyles, style))
    }
        moodsChanged = true
        if (account == null) {
            store.saveStyle(store.loadStyle().copy(preferredStyles = StyleProfile.fromLegacy(_setup.value.preferredFit, _setup.value.preferredStyles).preferredStyles))
            _app.update { it.copy(style = store.loadStyle()) }
        }
        persistSetup()
    }

    fun confirmProfile() {
        if (_setup.value.saving) return
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
        fun saved() {
            store.save(profile)
            val completed = _onboarding.value.completed
            val draft = if (completed) "{}" else JSONObject(_onboarding.value.draft).apply { remove("result"); remove("garment") }.toString()
            saveProgress(OnboardingProgress(step = if (completed) 8 else 6, completed = completed, draft = draft))
            _app.update { it.copy(profile = store.load(), style = store.loadStyle(), setupVisible = !completed) }
        }
        if (account == null) {
            try { saved() }
            catch (_: Exception) { _setup.update { it.copy(errorMessage = "저장하지 못했어요. 다시 시도해 주세요.") } }
        } else {
            _setup.update { it.copy(saving = true, errorMessage = null) }
            viewModelScope.launch {
                try {
                    val body = JSONObject(BodyProfileJson.encode(profile)).apply { remove("preferred_fit"); remove("preferred_styles") }
                    val patch = JSONObject().put("body_profile", body)
                    val style = StyleProfile.fromLegacy(state.preferredFit, state.preferredStyles).json()
                    if (fitChanged) patch.put("preferred_fits", style.getJSONArray("preferred_fits"))
                    if (moodsChanged) patch.put("preferred_styles", style.getJSONArray("preferred_styles"))
                    account.patchProfile(patch)
                    saved()
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { _setup.update { it.copy(errorMessage = error.message ?: "저장하지 못했어요. 다시 시도해 주세요.") } }
                finally { _setup.update { it.copy(saving = false) } }
            }
        }
    }

    fun skipSetup() {
        try {
            saveProgress(OnboardingProgress(step = 8, completed = true))
            _setup.update { it.copy(frontPhoto = null, sidePhoto = null) }
            _app.update { it.copy(setupVisible = false) }
        } catch (_: Exception) { _app.update { it.copy(storageError = "진행 상황을 저장하지 못했어요. 다시 시도해 주세요.") } }
    }

    fun saveStyleProfile(style: StyleProfile): String? = try {
        store.saveStyle(style.copy(preferredFits = style.preferredFits.ifEmpty { listOf("regular") }))
        val saved = store.loadStyle()
        _app.update { it.copy(style = saved, profile = store.load()) }
        _setup.update { it.copy(preferredFit = saved.legacyFit(), preferredStyles = saved.styleLabels()) }
        null
    } catch (_: Exception) {
        "저장하지 못했어요. 다시 시도해 주세요."
    }

    /** Opens the flow again, pre-filled with the current profile (US-B4). */
    fun startReanalysis() {
        fitChanged = false
        moodsChanged = false
        val profile = _app.value.profile
        _setup.value = if (profile == null) {
            SetupState(preferredFit = _app.value.style.legacyFit(), preferredStyles = _app.value.style.styleLabels())
        } else {
            SetupState(
                heightText = profile.input.heightCm.toString(),
                weightText = profile.input.weightKg?.toString().orEmpty(),
                gender = profile.input.gender,
                preferredFit = _app.value.style.legacyFit(),
                preferredStyles = _app.value.style.styleLabels(),
            )
        }
        _app.update { it.copy(setupVisible = true) }
    }

    /** Leaves the flow without changing the saved profile. */
    fun cancelSetup() = _app.update { it.copy(setupVisible = false) }

    fun deleteProfile() {
        store.save(null)
        _app.update { it.copy(profile = null) }
    }

    suspend fun deleteAccountProfile(): String? = try {
        account?.patchProfile(JSONObject().put("body_profile", JSONObject.NULL))
        deleteProfile()
        null
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) { error.message ?: "삭제하지 못했어요. 다시 시도해 주세요." }
}

/** Re-encoding also strips EXIF metadata (location, device) from the original photo. */
private fun Bitmap.toJpeg(quality: Int = 90): ByteArray =
    ByteArrayOutputStream().use { out ->
        compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }

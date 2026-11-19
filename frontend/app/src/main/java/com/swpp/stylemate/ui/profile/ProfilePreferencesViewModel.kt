package com.swpp.stylemate.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swpp.stylemate.data.StyleProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class ProfilePreference(val title: String) {
    TASTE("나의 취향"), FIT("선호 핏"), MOOD("선호 무드"), COLORS("컬러 수정")
}

internal data class ProfilePreferenceState(
    val section: ProfilePreference? = null,
    val original: List<String> = emptyList(),
    val selected: List<String> = emptyList(),
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val fits: List<String> = emptyList(), val moods: List<String> = emptyList(),
    val originalFits: List<String> = emptyList(), val originalMoods: List<String> = emptyList(),
) {
    val changed get() = if (section == ProfilePreference.TASTE) fits != originalFits || moods != originalMoods else selected != original
}

/** Owned by the existing authenticated ViewModelStore: drafts/jobs outlive tabs, never accounts. */
internal class ProfilePreferencesViewModel : ViewModel() {
    private val mutable = MutableStateFlow(ProfilePreferenceState())
    val state = mutable.asStateFlow()

    fun open(section: ProfilePreference, style: StyleProfile) {
        if (mutable.value.section != null || mutable.value.saving) return
        val values = when (section) {
            ProfilePreference.TASTE -> emptyList()
            ProfilePreference.FIT -> style.preferredFits.ifEmpty { listOf("regular") }
            ProfilePreference.MOOD -> style.preferredStyles
            ProfilePreference.COLORS -> style.favoriteColors
        }.toList()
        mutable.value = ProfilePreferenceState(section, values, values,
            fits = style.preferredFits.ifEmpty { listOf("regular") }.toList(), moods = style.preferredStyles.toList(),
            originalFits = style.preferredFits.ifEmpty { listOf("regular") }.toList(), originalMoods = style.preferredStyles.toList())
    }

    fun select(code: String) {
        val s = mutable.value
        if (s.saving) return
        val selected = when (s.section) {
            // The first value is the existing representative fit. Retain all other saved fits.
            ProfilePreference.FIT -> listOf(code) + s.selected.filterNot { it == code }
            ProfilePreference.MOOD, ProfilePreference.COLORS -> {
                val limit = if (s.section == ProfilePreference.COLORS) 8 else 6
                if (code in s.selected) s.selected - code
                else if (s.selected.size < limit) s.selected + code else s.selected
            }
            ProfilePreference.TASTE, null -> return
        }
        mutable.value = s.copy(selected = selected, error = null)
    }

    fun discard() { if (!mutable.value.saving) mutable.value = ProfilePreferenceState() }

    fun selectFit(code: String) {
        val s = mutable.value
        if (!s.saving && s.section == ProfilePreference.TASTE)
            mutable.value = s.copy(fits = listOf(code) + s.fits.filterNot { it == code }, error = null)
    }

    fun toggleMood(code: String) {
        val s = mutable.value
        if (!s.saving && s.section == ProfilePreference.TASTE)
            mutable.value = s.copy(moods = if (code in s.moods) s.moods - code else (s.moods + code).take(6), error = null)
    }

    fun saveTaste(write: suspend (List<String>, List<String>) -> String?) {
        val s = mutable.value
        if (s.section == ProfilePreference.TASTE) persist(s) { write(s.fits, s.moods) }
    }

    fun save(write: suspend (ProfilePreference, List<String>) -> String?) {
        val s = mutable.value
        val section = s.section ?: return
        persist(s) { write(section, s.selected) }
    }

    private fun persist(s: ProfilePreferenceState, write: suspend () -> String?) {
        if (s.saving || !s.changed) return
        mutable.value = s.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                val error = write()
                mutable.value = if (error == null) ProfilePreferenceState(notice = "변경 내용을 저장했어요")
                    else s.copy(error = error)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutable.value = s.copy(error = error.message ?: "저장하지 못했어요. 다시 시도해 주세요.")
            }
        }
    }
}

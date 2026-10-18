// AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitApiException
import com.swpp.stylemate.data.recommendation.OutfitRepository
import com.swpp.stylemate.data.recommendation.TasteStore
import com.swpp.stylemate.data.recommendation.Weather
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class HomeState(
    val loading: Boolean = false,
    val weather: Weather? = null,
    val outfits: List<Outfit> = emptyList(),
    val index: Int = 0,
    val notes: List<String> = emptyList(),
    val error: String? = null,
    val errorCode: String? = null,
    val acting: Boolean = false,
    val message: String? = null,
) {
    val current: Outfit? get() = outfits.getOrNull(index)

    companion object {
        const val NO_PROFILE = "NO_PROFILE"
    }
}

/** 오늘의 코디: outfits from the user's own wardrobe for today's weather, liked/disliked/saved. */
class HomeViewModel(private val repository: OutfitRepository, private val taste: TasteStore) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeState())
    val state = mutableState.asStateFlow()
    private val seen = mutableListOf<String>()
    private var body: JSONObject? = null
    private var job: Job? = null

    /** First load, or the refresh button: new weather and new outfits. */
    fun load(body: JSONObject?, refreshWeather: Boolean = true) {
        this.body = body
        if (body == null) {
            mutableState.value = HomeState(errorCode = HomeState.NO_PROFILE)
            return
        }
        job?.cancel()
        mutableState.value = mutableState.value.copy(loading = true, error = null, errorCode = null)
        job = viewModelScope.launch {
            try {
                val weather = if (refreshWeather || mutableState.value.weather == null) {
                    try { repository.weather() } catch (e: OutfitApiException) { mutableState.value.weather }
                } else mutableState.value.weather
                val result = repository.recommend(body, weather, taste.load(), seen)
                seen += result.outfits.map { it.key }
                mutableState.value = mutableState.value.copy(loading = false, weather = weather, outfits = result.outfits,
                    index = 0, notes = result.notes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutfitApiException) {
                mutableState.value = mutableState.value.copy(loading = false, error = e.message, errorCode = e.code)
            }
        }
    }

    /** Opening the tab: load the first time, after the body profile changed, or after an error. */
    fun ensureLoaded(body: JSONObject?) {
        val state = mutableState.value
        if (state.loading) return
        if (body?.toString() != this.body?.toString() || state.outfits.isEmpty() || state.errorCode != null) load(body)
    }

    /** "다른 코디": the next of the current set, then a new set that avoids the outfits already shown. */
    fun next() {
        val state = mutableState.value
        if (state.loading) return
        if (state.index + 1 < state.outfits.size) mutableState.value = state.copy(index = state.index + 1)
        else load(body, refreshWeather = false)
    }

    /** Like / dislike (tapping the active one again clears it). A dislike moves on to the next outfit. */
    fun feedback(value: String) {
        val outfit = mutableState.value.current ?: return
        if (mutableState.value.acting) return
        val newValue = if (outfit.feedback == value) null else value
        mutableState.value = mutableState.value.copy(acting = true)
        viewModelScope.launch {
            try {
                val (updated, preference) = repository.feedback(outfit.id, newValue, taste.load())
                taste.save(preference)
                replace(updated)
                mutableState.value = mutableState.value.copy(acting = false, message = when (newValue) {
                    "liked" -> "비슷한 코디를 더 자주 추천할게요."
                    "disliked" -> "비슷한 코디는 덜 추천할게요."
                    else -> null
                })
                if (newValue == "disliked") next()
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutfitApiException) {
                mutableState.value = mutableState.value.copy(acting = false, message = e.message)
            }
        }
    }

    fun toggleSaved() {
        val outfit = mutableState.value.current ?: return
        if (mutableState.value.acting) return
        mutableState.value = mutableState.value.copy(acting = true)
        viewModelScope.launch {
            try {
                val updated = repository.setSaved(outfit.id, !outfit.saved)
                replace(updated)
                mutableState.value = mutableState.value.copy(acting = false,
                    message = if (updated.saved) "코디북에 저장했어요." else "코디북에서 뺐어요.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutfitApiException) {
                mutableState.value = mutableState.value.copy(acting = false, message = e.message)
            }
        }
    }

    fun messageShown() {
        mutableState.value = mutableState.value.copy(message = null)
    }

    private fun replace(outfit: Outfit) {
        val state = mutableState.value
        mutableState.value = state.copy(outfits = state.outfits.map { if (it.id == outfit.id) outfit else it })
    }
}

/** 코디북: saved outfits. */
data class CodiBookState(val loading: Boolean = false, val outfits: List<Outfit> = emptyList(), val error: String? = null)

class CodiBookViewModel(private val repository: OutfitRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(CodiBookState())
    val state = mutableState.asStateFlow()

    fun load() {
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                mutableState.value = CodiBookState(outfits = repository.saved())
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutfitApiException) {
                mutableState.value = mutableState.value.copy(loading = false, error = e.message)
            }
        }
    }

    fun remove(outfit: Outfit) {
        viewModelScope.launch {
            try {
                repository.setSaved(outfit.id, false)
                mutableState.value = mutableState.value.copy(outfits = mutableState.value.outfits.filter { it.id != outfit.id })
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutfitApiException) {
                mutableState.value = mutableState.value.copy(error = e.message)
            }
        }
    }
}

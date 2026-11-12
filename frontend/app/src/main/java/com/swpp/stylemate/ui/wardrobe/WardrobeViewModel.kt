// AI-generated with Codex, 2026-10-03, reviewed by Hyeon U Jeong
package com.swpp.stylemate.ui.wardrobe

import com.swpp.stylemate.data.wardrobe.WardrobeRepository
import com.swpp.stylemate.data.LocalUserStore
import com.swpp.stylemate.data.AccountAccess
import com.swpp.stylemate.data.recommendation.ExternalRecommendation
import com.swpp.stylemate.data.recommendation.OutfitRepository
import com.swpp.stylemate.data.recommendation.WardrobeAnalysis

import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class WardrobeState(val records: List<JSONObject> = emptyList(), val catalog: JSONObject? = null,
                         val loading: Boolean = false, val saving: Boolean = false,
                         val error: String? = null, val saveError: String? = null, val savedId: String? = null,
                         val deletingId: String? = null, val deleteError: String? = null,
                         val deletedId: String? = null)

data class ExternalMatchState(val garmentId: String? = null, val loading: Boolean = false,
                              val result: ExternalRecommendation? = null, val error: String? = null)

/** 옷장 분석 팝업 상태. open이 false면 팝업을 띄우지 않는다. */
data class WardrobeAnalysisState(val open: Boolean = false, val loading: Boolean = false,
                                 val result: WardrobeAnalysis? = null, val error: String? = null)

class WardrobeViewModel(private val store: LocalUserStore, private val account: AccountAccess) : ViewModel() {
    private val mutableState = MutableStateFlow(WardrobeState())
    val state = mutableState.asStateFlow()
    private val mutableExternalMatch = MutableStateFlow(ExternalMatchState())
    val externalMatch = mutableExternalMatch.asStateFlow()
    private var externalMatchJob: Job? = null
    private val mutableAnalysis = MutableStateFlow(WardrobeAnalysisState())
    val wardrobeAnalysis = mutableAnalysis.asStateFlow()
    private var analysisJob: Job? = null
    init { refresh() }

    fun refresh() {
        if (mutableState.value.loading || mutableState.value.saving || mutableState.value.deletingId != null) return
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val rows = org.json.JSONArray(withContext(Dispatchers.IO) { store.garments() })
                val cached = withContext(Dispatchers.IO) { store.get("catalog") }
                mutableState.value = mutableState.value.copy(catalog = cached,
                    records = (0 until rows.length()).map { rows.getJSONObject(it) })
                run {
                    val remote = WardrobeRepository.json("/api/wardrobe/items/", store = store, account = account).getJSONArray("items")
                    mutableState.value = mutableState.value.copy(records = (0 until remote.length()).map { remote.getJSONObject(it) })
                }
                val catalog = WardrobeRepository.json("/api/wardrobe/options/", store = store, account = account)
                mutableState.value = mutableState.value.copy(catalog = catalog, loading = false)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.value = mutableState.value.copy(loading = false, error = error.message ?: "옷장을 불러오지 못했습니다.") }
        }
    }

    fun save(id: String, payload: JSONObject) {
        if (mutableState.value.saving || mutableState.value.loading || mutableState.value.deletingId != null) return
        mutableState.value = mutableState.value.copy(saving = true, saveError = null)
        viewModelScope.launch {
            try {
                val record = WardrobeRepository.json("/api/wardrobe/items/$id/", payload, store, account)
                val records = mutableState.value.records
                mutableState.value = mutableState.value.copy(saving = false, savedId = id,
                    records = if (records.any { it.getString("id") == id }) records.map { if (it.getString("id") == id) record else it }
                              else listOf(record) + records)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.value = mutableState.value.copy(saving = false, saveError = error.message ?: "저장하지 못했습니다.") }
        }
    }

    fun clearSaveState() { mutableState.value = mutableState.value.copy(savedId = null, saveError = null) }

    fun delete(id: String) {
        val current = mutableState.value
        if (current.loading || current.saving || current.deletingId != null || current.records.none { it.optString("id") == id }) return
        mutableState.value = current.copy(deletingId = id, deleteError = null)
        viewModelScope.launch {
            try {
                WardrobeRepository.delete(id, store, account)
                if (mutableExternalMatch.value.garmentId == id) closeExternalMatch()
                mutableState.value = mutableState.value.copy(deletingId = null, deleteError = null, deletedId = id,
                    records = mutableState.value.records.filter { it.optString("id") != id })
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutableState.value = mutableState.value.copy(deletingId = null,
                    deleteError = error.message ?: "삭제하지 못했습니다. 다시 시도해 주세요.")
            }
        }
    }

    fun clearDeleteError() { mutableState.value = mutableState.value.copy(deleteError = null) }
    fun clearDeleted() { mutableState.value = mutableState.value.copy(deletedId = null) }

    fun matchExternal(garmentId: String) {
        externalMatchJob?.cancel()
        mutableExternalMatch.value = ExternalMatchState(garmentId = garmentId, loading = true)
        externalMatchJob = viewModelScope.launch {
            try {
                val result = OutfitRepository(account).matchExternal(garmentId)
                mutableExternalMatch.value = ExternalMatchState(garmentId = garmentId, result = result)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutableExternalMatch.value = ExternalMatchState(garmentId = garmentId,
                    error = error.message ?: "어울리는 옷을 찾지 못했어요. 다시 시도해 주세요.")
            }
        }
    }

    fun closeExternalMatch() {
        externalMatchJob?.cancel()
        externalMatchJob = null
        mutableExternalMatch.value = ExternalMatchState()
    }

    /** 옷장 전체 분석. 다시 누르거나 다시 시도하면 이전 요청을 취소하고 새로 요청한다. */
    fun analyzeWardrobe() {
        analysisJob?.cancel()
        mutableAnalysis.value = WardrobeAnalysisState(open = true, loading = true)
        analysisJob = viewModelScope.launch {
            try {
                val result = OutfitRepository(account).wardrobeGaps()
                mutableAnalysis.value = WardrobeAnalysisState(open = true, result = result)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutableAnalysis.value = WardrobeAnalysisState(open = true,
                    error = error.message ?: "옷장을 분석하지 못했어요. 다시 시도해 주세요.")
            }
        }
    }

    /** 팝업을 닫으면 진행 중인 요청을 취소해, 늦게 온 응답이 팝업을 다시 열지 않게 한다. */
    fun closeWardrobeAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        mutableAnalysis.value = WardrobeAnalysisState()
    }

}

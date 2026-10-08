// AI-generated with Codex, 2026-10-03, reviewed by Hyeon U Jeong
package com.swpp.stylemate.ui.wardrobe

import com.swpp.stylemate.data.wardrobe.WardrobeRepository

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class WardrobeState(val records: List<JSONObject> = emptyList(), val catalog: JSONObject? = null,
                         val loading: Boolean = false, val saving: Boolean = false,
                         val error: String? = null, val saveError: String? = null, val savedId: String? = null)

class WardrobeViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(WardrobeState())
    val state = mutableState.asStateFlow()
    init { refresh() }

    fun refresh() {
        if (mutableState.value.loading || mutableState.value.saving) return
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val catalog = WardrobeRepository.json("/api/wardrobe/options/")
                val rows = WardrobeRepository.json("/api/wardrobe/items/").getJSONArray("items")
                mutableState.value = mutableState.value.copy(catalog = catalog,
                    records = (0 until rows.length()).map { rows.getJSONObject(it) }, loading = false)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.value = mutableState.value.copy(loading = false, error = error.message ?: "옷장을 불러오지 못했습니다.") }
        }
    }

    fun save(id: String, payload: JSONObject) {
        if (mutableState.value.saving || mutableState.value.loading) return
        mutableState.value = mutableState.value.copy(saving = true, saveError = null)
        viewModelScope.launch {
            try {
                val record = WardrobeRepository.json("/api/wardrobe/items/$id/", payload)
                val records = mutableState.value.records
                mutableState.value = mutableState.value.copy(saving = false, savedId = id,
                    records = if (records.any { it.getString("id") == id }) records.map { if (it.getString("id") == id) record else it }
                              else listOf(record) + records)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.value = mutableState.value.copy(saving = false, saveError = error.message ?: "저장하지 못했습니다.") }
        }
    }

    fun clearSaveState() { mutableState.value = mutableState.value.copy(savedId = null, saveError = null) }
}

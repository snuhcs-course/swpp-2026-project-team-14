package com.swpp.stylemate.ui.wardrobe

import com.swpp.stylemate.data.wardrobe.requestAnalysis

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AnalysisState(val photo: String? = null, val busy: Boolean = false,
                         val result: JSONObject? = null, val error: String? = null)

class GarmentAnalysisViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(AnalysisState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun select(photo: String?) {
        if (mutableState.value.photo == photo) return
        job?.cancel()
        mutableState.value = AnalysisState(photo)
    }

    fun analyze(context: Context, photo: String) {
        if (mutableState.value.busy) return
        select(photo)
        mutableState.value = AnalysisState(photo, busy = true)
        val application = context.applicationContext
        job = viewModelScope.launch {
            try {
                val result = requestAnalysis(application, photo)
                mutableState.value = AnalysisState(photo, result = result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.value = AnalysisState(photo, error = error.message ?: "분석하지 못했습니다.")
            }
        }
    }
}

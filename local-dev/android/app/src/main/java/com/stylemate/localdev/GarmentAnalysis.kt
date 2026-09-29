package com.stylemate.localdev

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

private suspend fun requestAnalysis(context: Context, photo: String): JSONObject = withContext(Dispatchers.IO) {
    // Physical devices use `adb -d reverse tcp:8001 tcp:8001`; no API secret lives in Android.
    val host = if (Build.PRODUCT.startsWith("sdk") || Build.HARDWARE in setOf("goldfish", "ranchu")) "10.0.2.2" else "127.0.0.1"
    val connection = URI("http://$host:8001/api/wardrobe/analyze/").toURL().openConnection() as HttpURLConnection
    try {
        val bytes = context.contentResolver.openInputStream(CaptureFiles(context).uri(photo))!!.use { input ->
            val buffer = ByteArray(5 * 1024 * 1024 + 1)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            check(count in 1 until buffer.size) { "사진 용량이 너무 큽니다. 다시 촬영해 주세요." }
            buffer.copyOf(count)
        }
        connection.connectTimeout = 5_000
        connection.readTimeout = 45_000
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.setRequestProperty("Content-Type", "image/jpeg")
        connection.outputStream.use { it.write(bytes) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val chars = CharArray(64 * 1024)
            var count = 0
            while (count < chars.size) {
                val read = reader.read(chars, count, chars.size - count)
                if (read < 0) break
                count += read
            }
            check(count < chars.size) { "분석 응답을 읽지 못했습니다." }
            String(chars, 0, count)
        }
        val result = runCatching { JSONObject(text ?: "{}") }.getOrElse { JSONObject() }
        if (status !in 200..299) error(when (result.optString("error")) {
            "AI_NOT_CONFIGURED" -> "서버에 API 키를 설정해 주세요."
            "AI_AUTH_FAILED" -> "서버의 API 키를 확인해 주세요."
            "AI_RATE_LIMITED", "AI_BUSY" -> "잠시 후 다시 분석해 주세요."
            "AI_TIMEOUT" -> "분석 시간이 초과됐습니다. 다시 시도해 주세요."
            "IMAGE_MULTIPLE" -> "옷 한 벌만 촬영해 주세요."
            "IMAGE_NO_GARMENT", "IMAGE_UNCLEAR", "INVALID_IMAGE" -> "옷이 잘 보이도록 다시 촬영해 주세요."
            "IMAGE_UNSUPPORTED" -> "지원하지 않는 옷 종류입니다."
            "IMAGE_TOO_LARGE" -> "사진 용량이 너무 큽니다."
            else -> "분석하지 못했습니다. 다시 시도해 주세요."
        })
        check(result.has("id") && result.has("attributes") && result.has("display")) { "분석 응답을 읽지 못했습니다." }
        result
    } catch (_: SocketTimeoutException) {
        error("응답 시간이 초과됐습니다. 잠시 후 다시 시도해 주세요.")
    } catch (_: java.io.IOException) {
        error("서버에 연결할 수 없습니다. PC 서버와 USB 연결을 확인해 주세요.")
    } finally {
        connection.disconnect()
    }
}

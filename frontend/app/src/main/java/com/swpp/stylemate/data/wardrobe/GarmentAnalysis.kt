package com.swpp.stylemate.data.wardrobe

import android.content.Context
import com.swpp.stylemate.BuildConfig
import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal suspend fun requestAnalysis(context: Context, photo: String): JSONObject = withContext(Dispatchers.IO) {
    val connection = URI("${BuildConfig.API_BASE_URL.trimEnd('/')}/api/wardrobe/analyze/").toURL().openConnection() as HttpURLConnection
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
            "DATABASE_NOT_CONFIGURED" -> "서버의 DB 설정이 필요합니다."
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

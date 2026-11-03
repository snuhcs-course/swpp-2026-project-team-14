package com.swpp.stylemate.data.wardrobe

import com.swpp.stylemate.BuildConfig
import com.swpp.stylemate.data.AccountAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/** Mutations are explicit: no retries, no automatic generation when polling status. */
object WornGenerationRepository {
    suspend fun request(account: AccountAccess, id: String, action: String,
                        body: JSONObject? = null): JSONObject {
        require(action in setOf("generation", "review"))
        require(java.util.UUID.fromString(id).toString() == id)
        return wornRequest(account, "/api/wardrobe/items/$id/$action/", body?.toString()?.toByteArray(Charsets.UTF_8))
    }
}

/** Shared transport; GET only polls and POST never retries a paid operation automatically. */
internal suspend fun wornRequest(account: AccountAccess, path: String, body: ByteArray? = null,
                                 contentType: String = "application/json"): JSONObject = withContext(Dispatchers.IO) {
        require(path.startsWith("/api/wardrobe/"))
        val connection = URI("${BuildConfig.API_BASE_URL.trimEnd('/')}$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 55_000
            connection.setRequestProperty("Authorization", account.authorization())
            if (body != null) {
                val bytes = body
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            account.checkResponse(status)
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = input?.use { it.readBytesLimited(64 * 1024) } ?: byteArrayOf()
            val result = JSONObject(String(bytes, Charsets.UTF_8).ifBlank { "{}" })
            check(status in 200..299) { when (result.optString("error")) {
                "WORN_REVIEW_REQUIRED" -> "선택한 옷의 이미지와 핏·기장을 모두 확인해 주세요."
                "IMAGE_REVIEW_REQUIRED" -> "아직 생성이 끝나지 않은 옷이 있어요."
                "AI_BUSY", "AI_RATE_LIMITED" -> "분석 요청이 많습니다. 잠시 후 다시 시도해 주세요."
                else -> "요청을 처리하지 못했습니다. (${result.optString("error", "SERVER_ERROR")})"
            } }
            currentCoroutineContext().ensureActive()
            result
        } catch (_: java.io.IOException) {
            error("서버 응답을 확인하지 못했습니다. 결과 확인을 눌러 주세요. 자동으로 다시 생성하지 않아요.")
        } finally { connection.disconnect() }
    }

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        check(output.size() + count <= limit) { "응답이 너무 큽니다." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

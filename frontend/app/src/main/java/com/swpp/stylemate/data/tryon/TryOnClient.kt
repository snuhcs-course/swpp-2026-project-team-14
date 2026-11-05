package com.swpp.stylemate.data.tryon

import com.swpp.stylemate.BuildConfig
import com.swpp.stylemate.data.AccountAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException

data class TryOnGarment(val category: String, val image: ByteArray, val photoType: String = "flat-lay",
                        val openFront: Boolean = true)

data class TryOnJob(val id: String, val token: String) {
    init { require(id.matches(Regex("[0-9a-f-]{36}")) && token.isNotBlank()) }
}

/** Photos and the retrieved image remain in memory; completed server jobs are deleted. */
class TryOnClient(private val account: AccountAccess, private val baseUrl: String = BuildConfig.API_BASE_URL) {
    suspend fun fit(person: ByteArray, garments: List<TryOnGarment>, consent: Boolean,
                    resumeJob: TryOnJob? = null,
                    onSubmitted: suspend (TryOnJob) -> Unit = {},
                    onResult: suspend (ByteArray) -> Unit = {},
                    keepForResume: Boolean = false, onStatus: (String) -> Unit): ByteArray {
        require(consent) { "사진 처리에 동의해 주세요." }
        require(resumeJob != null || (person.size in 1..MAX_IMAGE && garments.size in 1..3))
        require(garments.all { it.image.size in 1..MAX_IMAGE })
        if (garments.any { it.category == "outerwear" }) {
            require(garments.map { it.category } == listOf("tops", "bottoms", "outerwear"))
        }
        var job: JSONObject? = resumeJob?.let { JSONObject().put("id", it.id).put("token", it.token) }
        var checkpointed = resumeJob != null
        var preserveJob = false
        try {
            val rows = JSONArray()
            garments.forEach { rows.put(JSONObject().put("category", it.category)
                .put("photo_type", it.photoType).apply {
                    if (it.category == "outerwear") put("open_front", it.openFront)
                }.put("image", Base64.getEncoder().encodeToString(it.image))) }
            val payload = JSONObject().put("consent", true)
                .put("person", Base64.getEncoder().encodeToString(person)).put("garments", rows)
            if (job == null) {
                onStatus("사진을 전송하고 있어요…")
                job = JSONObject(String(request("POST", "jobs/", body = payload), Charsets.UTF_8))
                onSubmitted(TryOnJob(job.getString("id"), job.getString("token")))
                checkpointed = true
            }
            val id = job.getString("id")
            require(id.matches(Regex("[0-9a-f-]{36}")))
            val token = job.getString("token")
            repeat(900) {
                val state = JSONObject(String(retrieve("jobs/$id/", token), Charsets.UTF_8))
                when (state.getString("status")) {
                    "completed" -> {
                        check(state.optBoolean("inputs_deleted")) { "서버의 입력 사진 삭제 상태를 확인하지 못했어요." }
                        val image = retrieve("jobs/$id/image/", token)
                        onResult(image)
                        return image
                    }
                    "failed", "cancelled" -> error(when (state.optString("error")) {
                        "FITTING_PROVIDER_REJECTED" -> "이미지 생성 서비스가 요청을 거절했어요. 다른 사진으로 시도해 주세요."
                        "FITTING_CREDITS_REQUIRED" -> "피팅 서비스의 이용 한도가 소진됐어요. 잠시 후 다시 시도해 주세요."
                        "FITTING_RATE_LIMITED" -> "이미지 생성 요청이 많아요. 잠시 후 다시 시도해 주세요."
                        "FITTING_TIMEOUT" -> "이미지 생성 시간이 초과됐어요. 잠시 후 다시 시도해 주세요."
                        "FITTING_PROVIDER_UNAVAILABLE", "FITTING_AUTH_FAILED", "FITTING_NOT_CONFIGURED" ->
                            "피팅 서비스에 연결하지 못했어요. 잠시 후 다시 시도해 주세요."
                        else -> "피팅 생성에 실패했어요. 사진을 바꿔 다시 시도해 주세요."
                    })
                    "running" -> onStatus("옷을 입히고 있어요 · ${state.optDouble("elapsed_seconds").toInt()}초 경과")
                    else -> onStatus("대기 중 · 앞선 요청 ${state.optInt("queue_position")}개")
                }
                delay(1_000)
            }
            error("대기 시간이 길어졌어요. 잠시 후 다시 시도해 주세요.")
        } catch (error: CancellationException) {
            preserveJob = keepForResume
            throw error
        } catch (error: IOException) {
            preserveJob = keepForResume && checkpointed
            throw error
        } finally {
            job?.takeUnless { preserveJob }?.let { record -> withContext(NonCancellable + Dispatchers.IO) {
                try { request("DELETE", "jobs/${record.getString("id")}/", record.getString("token")) }
                catch (_: Exception) { /* Server expiry also clears abandoned jobs. */ }
            } }
        }
    }

    /** Retry reads of an accepted job; never submit the same photos twice. */
    private suspend fun retrieve(path: String, token: String): ByteArray {
        repeat(3) { attempt ->
            try { return request("GET", path, token) }
            catch (error: Exception) {
                if (error !is IOException || attempt == 2) throw error
                delay((attempt + 1) * 1_000L)
            }
        }
        error("피팅 서버에 연결하지 못했어요.")
    }

    private class FittingUnavailable : IOException("피팅 서비스에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.")

    private suspend fun request(method: String, path: String, token: String? = null,
                                body: JSONObject? = null): ByteArray = withContext(Dispatchers.IO) {
        val connection = URI("${baseUrl.trimEnd('/')}/api/fittings/$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setRequestProperty("Authorization", account.authorization())
            token?.let { connection.setRequestProperty("X-Fitting-Token", it) }
            body?.let {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val bytes = it.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { stream -> stream.write(bytes) }
            }
            val status = connection.responseCode
            account.checkResponse(status)
            if (status in listOf(500, 502, 503, 504)) throw FittingUnavailable()
            check(status in 200..299) {
                when (status) {
                    429 -> "피팅 대기열이 가득 찼어요. 잠시 후 다시 시도해 주세요."
                    404 -> "피팅 작업이 만료됐어요. 다시 생성해 주세요."
                    400, 413 -> "사진 형식과 크기를 확인해 주세요."
                    else -> "피팅 요청을 처리하지 못했어요. 다시 시도해 주세요."
                }
            }
            connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= MAX_RESPONSE) { "피팅 결과가 너무 큽니다." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }

    companion object {
        private const val MAX_IMAGE = 4 * 1024 * 1024
        private const val MAX_RESPONSE = 16 * 1024 * 1024
    }
}

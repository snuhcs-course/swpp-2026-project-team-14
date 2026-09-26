package com.stylemate.localdev

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ProbeItem(val id: Long, val name: String)

class ProbeRepository {
    private suspend fun request(baseUrl: String, path: String, payload: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val base = URI(baseUrl.trim().trimEnd('/'))
            require(base.scheme == "http" && base.host in setOf("10.0.2.2", "127.0.0.1", "localhost") &&
                base.port == 8001 && base.userInfo == null && base.query == null && base.fragment == null &&
                base.path.isNullOrEmpty()) { "로컬 주소와 8001 포트를 입력해 주세요." }
            val connection = URI("$base$path").toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.instanceFollowRedirects = false
                if (payload != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                check(status in 200..299) { "요청 실패 (HTTP $status). 서버 상태를 확인해 주세요." }
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            } finally {
                connection.disconnect()
            }
        }

    suspend fun health(baseUrl: String): String {
        val result = request(baseUrl, "/api/dev/health/")
        check(result.getString("status") == "ok" && result.getString("database") == "mysql")
        return "서버·MySQL 연결 정상"
    }

    suspend fun list(baseUrl: String): List<ProbeItem> {
        val rows = request(baseUrl, "/api/dev/items/").getJSONArray("results")
        return List(rows.length()) { index ->
            val item = rows.getJSONObject(index)
            ProbeItem(item.getLong("id"), item.getString("name"))
        }
    }

    suspend fun create(baseUrl: String, name: String) {
        request(baseUrl, "/api/dev/items/", JSONObject().put("name", name.trim()))
    }
}

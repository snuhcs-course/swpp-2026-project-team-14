// AI-generated with Codex, 2026-09-29, reviewed by Hyeon U Jeong
package com.swpp.stylemate.data.wardrobe

import android.graphics.BitmapFactory
import android.content.Context
import com.swpp.stylemate.data.LocalUserStore
import com.swpp.stylemate.data.AccountAccess
import com.swpp.stylemate.BuildConfig
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object WardrobeRepository {
    private suspend fun request(path: String, account: AccountAccess): ByteArray = withContext(Dispatchers.IO) {
        require(path.startsWith("/api/wardrobe/"))
        val connection = URI("${BuildConfig.API_BASE_URL.trimEnd('/')}$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", account.authorization())
            val status = connection.responseCode
            account.checkResponse(status)
            check(status in 200..299) {
                when (status) {
                    400 -> "입력한 옷 정보와 치수를 확인해 주세요."
                    404 -> "옷 정보를 찾을 수 없습니다. 다시 분석해 주세요."
                    413 -> "입력 내용이 너무 깁니다."
                    503 -> "옷장을 사용할 수 없습니다. 서버 설정을 확인해 주세요."
                    else -> "요청을 처리하지 못했습니다. 다시 시도해 주세요."
                }
            }
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 8 * 1024 * 1024) { "옷장 데이터를 읽지 못했습니다." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (_: java.io.IOException) {
            error("서버에 연결할 수 없습니다. PC 서버와 USB 연결을 확인해 주세요.")
        } finally { connection.disconnect() }
    }

    suspend fun json(path: String, body: JSONObject? = null, store: LocalUserStore, account: AccountAccess): JSONObject = withContext(Dispatchers.IO) {
        if (path == "/api/wardrobe/options/") {
            return@withContext try {
                account.json("GET", path).also { store.put("catalog", it) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (error is com.swpp.stylemate.data.AuthException && error.code in setOf("AUTHENTICATION_REQUIRED", "SESSION_CHANGED")) throw error
                store.get("catalog") ?: throw error
            }
        }
        if (path == "/api/wardrobe/items/") {
            val rows = account.list(path, "items")
            store.cacheGarments(rows)
            return@withContext JSONObject().put("items", rows)
        }
        account.json(if (body == null) "GET" else "PUT", path, body).also { store.cacheGarment(it) }
    }
    suspend fun thumbnail(context: Context, path: String, account: AccountAccess? = null): ImageBitmap? = withContext(Dispatchers.IO) {
        val bytes = request(path, account = checkNotNull(account))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 600) inSampleSize *= 2
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }
}

package com.swpp.stylemate.data.wardrobe

import android.graphics.BitmapFactory
import com.swpp.stylemate.BuildConfig
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object WardrobeRepository {
    private suspend fun request(path: String, body: JSONObject? = null): ByteArray = withContext(Dispatchers.IO) {
        require(path.startsWith("/api/wardrobe/"))
        val connection = URI("${BuildConfig.API_BASE_URL.trimEnd('/')}$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            if (body != null) {
                connection.requestMethod = "PUT"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
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

    suspend fun json(path: String, body: JSONObject? = null): JSONObject = JSONObject(request(path, body).toString(Charsets.UTF_8))
    suspend fun thumbnail(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val bytes = request(path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 600) inSampleSize *= 2
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }
}

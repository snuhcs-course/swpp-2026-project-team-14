package com.swpp.stylemate.data.wardrobe

import android.graphics.Bitmap
import com.swpp.stylemate.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class LandmarkPoint(val x: Float, val y: Float)
data class LandmarkPath(val points: List<LandmarkPoint>)
data class LandmarkResult(val suggestions: Map<String, LandmarkPath>, val elapsedMs: Long)

/** All positions refer to the uploaded viewport; the server never produces cm values. */
object GarmentLandmarks {
    suspend fun detect(bitmap: Bitmap, garment: String): LandmarkResult = withContext(Dispatchers.IO) {
        require(garment in landmarkTypes)
        val scale = minOf(1f, 768f / maxOf(bitmap.width, bitmap.height))
        val reduced = Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * scale).toInt()), maxOf(1, (bitmap.height * scale).toInt()), true)
        val bytes = try { ByteArrayOutputStream().use { out -> check(reduced.compress(Bitmap.CompressFormat.JPEG, 85, out)); out.toByteArray() } }
                    finally { if (reduced !== bitmap) reduced.recycle() }
        val connection = URI("${BuildConfig.API_BASE_URL.trimEnd('/')}/api/wardrobe/landmarks/?garment=$garment").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 2_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "image/jpeg")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            check(connection.responseCode in 200..299) { "자동 점을 찾지 못했습니다. 직접 지정할 수 있습니다." }
            val body = connection.inputStream.bufferedReader().use { reader ->
                val chars = CharArray(1024 * 1024)
                var count = 0
                while (count < chars.size) {
                    val read = reader.read(chars, count, chars.size - count)
                    if (read < 0) break
                    count += read
                }
                check(count < chars.size)
                String(chars, 0, count)
            }
            parse(JSONObject(body), garment)
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: java.io.IOException) {
            error("점 탐지 서버에 연결할 수 없습니다. 직접 지정할 수 있습니다.")
        } catch (_: Exception) {
            error("자동 점을 찾지 못했습니다. 직접 지정할 수 있습니다.")
        } finally { connection.disconnect() }
    }

    internal fun parse(data: JSONObject, garment: String): LandmarkResult {
        check(data.getString("garment") == garment)
        val values = data.getJSONObject("suggestions")
        val known = (topMeasurementFields + bottomMeasurementFields).map { it.key }.toSet()
        val suggestions = values.keys().asSequence().filter { it in known }.associateWith { key ->
            val pair = values.getJSONArray(key)
            check(pair.length() in 2..8)
            fun point(index: Int): LandmarkPoint {
                val coordinates = pair.getJSONArray(index)
                check(coordinates.length() == 2)
                val x = coordinates.getDouble(0).toFloat()
                val y = coordinates.getDouble(1).toFloat()
                check(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
                return LandmarkPoint(x, y)
            }
            LandmarkPath((0 until pair.length()).map { point(it) })
        }
        return LandmarkResult(suggestions, data.optLong("elapsed_ms"))
    }
}

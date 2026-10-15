// AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.data.recommendation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.swpp.stylemate.data.LocalUserStore
import com.swpp.stylemate.data.AccountAccess
import com.swpp.stylemate.data.AuthException

data class Weather(val temp: Double, val apparent: Double, val label: String, val band: String,
                   val rain: Boolean, val snow: Boolean, val json: JSONObject)

data class OutfitGarment(val garmentId: String, val slot: String, val name: String, val imageUrl: String, val note: String)

data class Reason(val kind: String, val text: String, val sub: String)

data class Outfit(
    val id: String,
    val key: String,
    val score: Int?,
    val items: List<OutfitGarment>,
    val reasons: List<Reason>,
    val saved: Boolean,
    val name: String,
    val feedback: String?,
    val createdAt: String,
)

data class Recommendation(val outfits: List<Outfit>, val notes: List<String>, val band: String)

class OutfitApiException(val code: String, message: String) : Exception(message)

/**
 * Outfit recommendation from the user's wardrobe (backend apps/outfits, docs/recommendation/api.md),
 * the weather, and the body-based labels of a garment being registered.
 */
class OutfitRepository(
    baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
    private val local: LocalUserStore? = null,
    private val account: AccountAccess? = null,
) {
    private val base = baseUrl.trimEnd('/')

    suspend fun weather(): Weather = parseWeather(call("GET", "/api/weather/"))

    suspend fun recommend(body: JSONObject, weather: Weather?, preference: JSONObject?, seenKeys: List<String>,
                          count: Int = 3, anchorId: String? = null): Recommendation {
        val request = JSONObject()
            .put("body", body)
            .put("count", count)
            .put("seen_keys", JSONArray(seenKeys.takeLast(MAX_SEEN)))
            .put("preference", preference ?: JSONObject.NULL)
        if (weather != null) request.put("weather", weather.json) else request.put("temp", DEFAULT_TEMP)
        anchorId?.let { request.put("anchor_id", it) }
        local?.let { request.put("wardrobe", JSONArray(withContext(Dispatchers.IO) { it.garments() })) }
        val result = call("POST", if (local == null) "/api/outfits/recommend/" else "/api/outfits/preview/", request)
        local?.let { withContext(Dispatchers.IO) { it.rememberOutfits(result.getJSONArray("outfits")) } }
        return parseRecommendation(result)
    }

    suspend fun saved(): List<Outfit> {
        local?.let { return withContext(Dispatchers.IO) { it.outfits().map(::parseOutfit) } }
        return call("GET", "/api/outfits/").getJSONArray("outfits").let { list ->
        (0 until list.length()).map { parseOutfit(list.getJSONObject(it)) }
        }
    }

    suspend fun setSaved(id: String, saved: Boolean): Outfit {
        local?.let { return withContext(Dispatchers.IO) { parseOutfit(it.saveOutfit(id, saved)) } }
        return parseOutfit(call("PUT", "/api/outfits/$id/", JSONObject().put("saved", saved)))
    }

    /** Returns the outfit and the updated taste profile to keep on the device. */
    suspend fun feedback(id: String, value: String?, preference: JSONObject?): Pair<Outfit, JSONObject> {
        if (local != null) {
            val outfit = withContext(Dispatchers.IO) { checkNotNull(local.outfit(id)) }
            val items = outfit.getJSONArray("items")
            val ids = (0 until items.length()).map { items.getJSONObject(it).getString("garment_id") }.toSet()
            val garments = withContext(Dispatchers.IO) { local.garments().filter { it.getString("id") in ids } }
            val result = call("POST", "/api/outfits/preview/feedback/", JSONObject().put("outfit", outfit)
                .put("wardrobe", JSONArray(garments)).put("feedback", value ?: JSONObject.NULL)
                .put("preference", preference ?: JSONObject.NULL))
            val updated = withContext(Dispatchers.IO) { local.setFeedback(id, value) }
            return parseOutfit(updated) to result.getJSONObject("preference")
        }
        val result = call("POST", "/api/outfits/$id/feedback/",
            JSONObject().put("feedback", value ?: JSONObject.NULL).put("preference", preference ?: JSONObject.NULL))
        return parseOutfit(result.getJSONObject("outfit")) to result.getJSONObject("preference")
    }

    /** Suggested labels for the garment being registered (nothing is saved). Without [body] the fit,
     * length and "too small" stay open for the user to pick. */
    suspend fun labels(attributes: JSONObject, dimensions: JSONObject?, body: JSONObject?, details: JSONObject?,
                       garmentType: String? = null): JSONObject? =
        call("POST", "/api/wardrobe/labels/", JSONObject()
            .put("attributes", attributes)
            .put("dimensions", dimensions ?: JSONObject.NULL)
            .put("body", body ?: JSONObject.NULL)
            .put("details", details ?: JSONObject.NULL)
            .put("garment_type", garmentType ?: JSONObject.NULL)).optJSONObject("labels")

    private suspend fun call(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        if (account != null) {
            try { return@withContext account.json(method, path, body) }
            catch (error: AuthException) {
                throw OutfitApiException(error.code, when (error.code) {
                    "WARDROBE_TOO_SMALL" -> "상의와 하의를 한 벌씩 등록하면 코디를 만들어 드려요."
                    "WEATHER_UNAVAILABLE" -> "날씨를 불러오지 못했어요."
                    else -> error.message.orEmpty()
                })
            }
        }
        val payload = body?.toString()?.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(base + path).method(method, payload).build()
        val (status, text) = try {
            client.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw OutfitApiException("NETWORK", NETWORK_ERROR)
        }
        parseBody(status, text)
    }

    companion object {
        const val NETWORK_ERROR = "서버에 연결하지 못했어요. 연결을 확인하고 다시 시도해 주세요."
        const val SERVER_ERROR = "코디를 불러오지 못했어요. 잠시 후 다시 시도해 주세요."
        const val DEFAULT_TEMP = 18.0
        const val MAX_SEEN = 60

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS) // the first recommendation loads the models
            .build()

        /** JSON body of a 2xx response, or an exception with the server's Korean hint. */
        fun parseBody(status: Int, text: String): JSONObject {
            val json = try { JSONObject(text) } catch (e: JSONException) { null }
            if (status in 200..299 && json != null) return json
            val code = json?.optString("error").orEmpty().ifEmpty { "HTTP_$status" }
            val message = when (code) {
                "WARDROBE_TOO_SMALL" -> "상의와 하의를 한 벌씩 등록하면 코디를 만들어 드려요."
                "WEATHER_UNAVAILABLE" -> "날씨를 불러오지 못했어요."
                "DATABASE_NOT_CONFIGURED" -> "서버의 DB 설정이 필요해요."
                else -> json?.optString("hint").orEmpty().takeIf { status == 422 && it.isNotEmpty() } ?: SERVER_ERROR
            }
            throw OutfitApiException(code, message)
        }

        fun parseWeather(json: JSONObject) = Weather(
            temp = json.getDouble("temp"),
            apparent = json.optDouble("apparent", json.getDouble("temp")),
            label = json.optString("label"),
            band = json.optString("band"),
            rain = json.optBoolean("rain"),
            snow = json.optBoolean("snow"),
            json = json,
        )

        fun parseRecommendation(json: JSONObject): Recommendation {
            val outfits = json.getJSONArray("outfits")
            val notes = json.optJSONArray("notes") ?: JSONArray()
            return Recommendation(
                outfits = (0 until outfits.length()).map { parseOutfit(outfits.getJSONObject(it)) },
                notes = (0 until notes.length()).map { notes.getString(it) },
                band = json.optJSONObject("band")?.optString("label").orEmpty(),
            )
        }

        fun parseOutfit(json: JSONObject): Outfit {
            val items = json.getJSONArray("items")
            val reasons = json.optJSONArray("reasons") ?: JSONArray()
            return Outfit(
                id = json.getString("id"),
                key = json.optString("key"),
                score = if (json.isNull("score")) null else json.optInt("score"),
                items = (0 until items.length()).map { i ->
                    val item = items.getJSONObject(i)
                    OutfitGarment(item.getString("garment_id"), item.getString("slot"), item.optString("name"),
                        item.getString("image_url"), item.optString("note"))
                },
                reasons = (0 until reasons.length()).map { i ->
                    val r = reasons.getJSONObject(i)
                    Reason(r.optString("kind"), r.optString("text"), r.optString("sub"))
                },
                saved = json.optBoolean("saved"),
                name = json.optString("name"),
                feedback = if (json.isNull("feedback")) null else json.optString("feedback"),
                createdAt = json.optString("created_at"),
            )
        }
    }
}

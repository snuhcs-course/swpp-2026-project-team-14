// AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.data.recommendation

import org.json.JSONArray
import org.json.JSONObject
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
class OutfitRepository(private val account: AccountAccess) {
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
        val result = call("POST", "/api/outfits/recommend/", request)
        return parseRecommendation(result)
    }

    suspend fun saved(): List<Outfit> {
        val rows = try { account.list("/api/outfits/", "outfits") }
        catch (error: AuthException) { throw OutfitApiException(error.code, error.message.orEmpty()) }
        return (0 until rows.length()).map { parseOutfit(rows.getJSONObject(it)) }
    }

    suspend fun setSaved(id: String, saved: Boolean): Outfit {
        return parseOutfit(call("PUT", "/api/outfits/$id/", JSONObject().put("saved", saved)))
    }

    /** Returns the outfit and the updated taste profile to keep on the device. */
    suspend fun feedback(id: String, value: String?, preference: JSONObject?): Pair<Outfit, JSONObject> {
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

    private suspend fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        try { return account.json(method, path, body) }
        catch (error: AuthException) {
            throw OutfitApiException(error.code, when (error.code) {
                "WARDROBE_TOO_SMALL" -> "상의와 하의를 한 벌씩 등록하면 코디를 만들어 드려요."
                "WEATHER_UNAVAILABLE" -> "날씨를 불러오지 못했어요."
                "NETWORK" -> NETWORK_ERROR
                else -> if (error.code.startsWith("HTTP_") || error.code == "INVALID_RESPONSE") SERVER_ERROR else error.message.orEmpty()
            })
        }
    }

    companion object {
        const val NETWORK_ERROR = "서버에 연결하지 못했어요. 연결을 확인하고 다시 시도해 주세요."
        const val SERVER_ERROR = "코디를 불러오지 못했어요. 잠시 후 다시 시도해 주세요."
        const val DEFAULT_TEMP = 18.0
        const val MAX_SEEN = 60
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

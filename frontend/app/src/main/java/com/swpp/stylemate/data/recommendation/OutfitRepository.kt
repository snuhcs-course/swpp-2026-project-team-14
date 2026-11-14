// AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.data.recommendation

import org.json.JSONArray
import org.json.JSONObject
import com.swpp.stylemate.data.AccountAccess
import com.swpp.stylemate.data.AuthException
import java.net.URI

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

data class ExternalAttributes(val category: String, val color: String, val fit: String)

data class WardrobeMatch(val garmentId: String, val name: String)

data class ExternalRecommendation(
    val sourceGarmentId: String,
    val targetSlot: String,
    val title: String,
    val headline: String,
    val reason: String,
    val attributes: ExternalAttributes,
    val searchUrl: String,
    val wardrobeMatches: List<WardrobeMatch> = emptyList(),
)

/** 옷장 분석에서 추천 근거가 된 내 옷 (그 추천 옷을 예측한 옷). */
data class GapGarment(val garmentId: String, val name: String, val imageUrl: String)

/** 옷장 분석의 추천 한 개: 내 옷 voteCount벌과 어울리는데 옷장에 없는 옷. */
data class WardrobeGap(
    val title: String,
    val slot: String,
    val voteCount: Int,
    val reason: String,
    val attributes: ExternalAttributes,
    val matchedGarments: List<GapGarment>,
    val searchUrl: String,
)

/** GET /api/outfits/wardrobe-gaps/ 결과. status: ok(추천 있음) | enough(살 옷 없음) | too_few(옷 부족). */
data class WardrobeAnalysis(
    val status: String,
    val garmentCount: Int,
    val slotCounts: Map<String, Int>,
    val gaps: List<WardrobeGap>,
) {
    companion object {
        const val OK = "ok"
        const val ENOUGH = "enough"
        const val TOO_FEW = "too_few"
    }
}

class OutfitApiException(val code: String, message: String) : Exception(message)

/**
 * Outfit recommendation from the user's wardrobe (backend apps/outfits, docs/recommendation/api.md),
 * the weather, and the body-based labels of a garment being registered.
 */
class OutfitRepository(private val account: AccountAccess) {
    suspend fun weather(region: Region = HomeRegion.SEOUL): Weather = parseWeather(call("GET",
        String.format(java.util.Locale.US, "/api/weather/?lat=%.4f&lon=%.4f", region.lat, region.lon)))

    suspend fun recommend(body: JSONObject, weather: Weather?, preference: JSONObject?, seenKeys: List<String>,
                          count: Int = 3, anchorId: String? = null, occasion: String? = null): Recommendation {
        val request = JSONObject()
            .put("body", body)
            .put("count", count)
            .put("seen_keys", JSONArray(seenKeys.takeLast(MAX_SEEN)))
            .put("preference", preference ?: JSONObject.NULL)
        if (weather != null) request.put("weather", weather.json) else request.put("temp", DEFAULT_TEMP)
        anchorId?.let { request.put("anchor_id", it) }
        occasion?.let { request.put("occasion", it) }  // only the occasion word, never the calendar event itself
        val result = call("POST", "/api/outfits/recommend/", request)
        return parseRecommendation(result)
    }

    suspend fun matchExternal(garmentId: String): ExternalRecommendation {
        val result = call("POST", "/api/outfits/match-external/", JSONObject().put("garment_id", garmentId))
        return try {
            fun text(json: JSONObject, key: String): String =
                (json.get(key) as? String)?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Missing $key")

            val sourceId = text(result, "source_garment_id")
            val targetSlot = text(result, "target_slot")
            require(sourceId == garmentId && targetSlot in setOf("top", "bottom"))
            val searchUrl = text(result, "search_url")
            require(isMusinsaSearch(searchUrl))
            val attributes = result.getJSONObject("attributes")
            // 옷장 매칭은 옷장에 맞는 옷이 없으면 빈 배열이고, 구버전 서버 응답엔 없을 수 있다
            val matches = result.optJSONArray("wardrobe_matches") ?: JSONArray()
            val wardrobeMatches = (0 until matches.length()).map { index ->
                val match = matches.getJSONObject(index)
                WardrobeMatch(text(match, "garment_id"), text(match, "name"))
            }
            ExternalRecommendation(sourceId, targetSlot, text(result, "title"), text(result, "headline"),
                text(result, "reason"),
                ExternalAttributes(text(attributes, "category"), text(attributes, "color"), text(attributes, "fit")),
                searchUrl, wardrobeMatches)
        } catch (_: Exception) {
            throw OutfitApiException("INVALID_RESPONSE", "추천 결과를 읽지 못했어요. 다시 시도해 주세요.")
        }
    }

    suspend fun wardrobeGaps(): WardrobeAnalysis {
        val result = call("GET", "/api/outfits/wardrobe-gaps/")
        return try {
            fun text(json: JSONObject, key: String): String =
                (json.get(key) as? String)?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Missing $key")
            fun count(json: JSONObject, key: String): Int =
                (json.get(key) as? Int)?.takeIf { it >= 0 } ?: throw IllegalArgumentException("Bad $key")

            val status = text(result, "status")
            require(status in setOf(WardrobeAnalysis.OK, WardrobeAnalysis.ENOUGH, WardrobeAnalysis.TOO_FEW))
            val counts = result.getJSONObject("slot_counts")
            val slotCounts = listOf("top", "bottom", "outerwear").associateWith { count(counts, it) }
            val rows = result.getJSONArray("gaps")
            val gaps = (0 until rows.length()).map { index ->
                val gap = rows.getJSONObject(index)
                val searchUrl = text(gap, "search_url")
                require(isMusinsaSearch(searchUrl))
                val slot = text(gap, "slot")
                require(slot in setOf("top", "bottom"))
                val attributes = gap.getJSONObject("attributes")
                val matched = gap.getJSONArray("matched_garments")
                val garments = (0 until matched.length()).map { i ->
                    val garment = matched.getJSONObject(i)
                    val imageUrl = text(garment, "image_url")
                    // 썸네일은 우리 서버의 옷 사진 경로만 불러온다
                    require(imageUrl.startsWith("/api/wardrobe/items/"))
                    GapGarment(text(garment, "garment_id"), text(garment, "name"), imageUrl)
                }
                val votes = count(gap, "vote_count")
                // 화면의 "내 옷 N벌"과 근거 옷 목록이 서로 맞아야 한다
                require(votes > 0 && votes == garments.size)
                WardrobeGap(text(gap, "title"), slot, votes, text(gap, "reason"),
                    ExternalAttributes(text(attributes, "category"), text(attributes, "color"), text(attributes, "fit")),
                    garments, searchUrl)
            }
            // 추천은 ok일 때만 있고, ok면 하나 이상 있어야 한다
            require((status == WardrobeAnalysis.OK) == gaps.isNotEmpty())
            WardrobeAnalysis(status, count(result, "garment_count"), slotCounts, gaps)
        } catch (_: Exception) {
            throw OutfitApiException("INVALID_RESPONSE", "옷장 분석 결과를 읽지 못했어요. 다시 시도해 주세요.")
        }
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
                else -> ErrorMessages.forCode(error.code)
                    ?: if (error.code.startsWith("HTTP_")) SERVER_ERROR else error.message.orEmpty()
            })
        }
    }

    companion object {
        /** 바로가기로 열어도 되는 주소: 무신사 검색 페이지(https, 기본 포트, 검색어 포함)만 허용한다. */
        fun isMusinsaSearch(url: String): Boolean = try {
            val uri = URI(url)
            uri.scheme == "https" && uri.host == "www.musinsa.com" && uri.rawAuthority == "www.musinsa.com" &&
                uri.rawPath == "/search/goods" && !uri.rawQuery.isNullOrBlank() && uri.rawFragment == null
        } catch (_: Exception) { false }

        const val NETWORK_ERROR = ErrorMessages.NETWORK
        const val SERVER_ERROR = ErrorMessages.SERVER
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

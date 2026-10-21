package com.swpp.stylemate.data.chat

import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitApiException
import com.swpp.stylemate.data.recommendation.OutfitRepository
import com.swpp.stylemate.data.recommendation.Weather
import com.swpp.stylemate.data.AccountAccess
import com.swpp.stylemate.data.AuthException
import com.swpp.stylemate.data.LocalUserStore
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

data class ChatRequest(
    val current: Outfit,
    val lockedIds: Set<String>,
    val directions: Set<String>,
    val text: String,
    val body: JSONObject,
    val weather: Weather?,
    val preference: JSONObject?,
    val history: List<Pair<String, String>>,
    val seenKeys: List<String>,
)

data class ChatResult(
    val status: String,
    val message: String,
    val outfit: Outfit?,
    val changedIds: Set<String>,
    val lockedIds: Set<String>,
    val unlockIds: Set<String>,
    val notes: List<String>,
)

class ChatRepository(
    baseUrl: String,
    private val client: OkHttpClient = OutfitRepository.defaultClient().newBuilder()
        .readTimeout(40, TimeUnit.SECONDS).callTimeout(50, TimeUnit.SECONDS).build(),
    private val local: LocalUserStore? = null,
    private val account: AccountAccess? = null,
) {
    private val base = baseUrl.trimEnd('/')

    suspend fun edit(input: ChatRequest): ChatResult {
        val body = JSONObject().put("body", input.body)
            .put("garment_ids", JSONArray(input.current.items.map { it.garmentId }))
            .put("locked_ids", JSONArray(input.lockedIds.toList()))
            .put("directions", JSONArray(input.directions.toList()))
            .put("text", input.text)
            .put("preference", input.preference ?: JSONObject.NULL)
            .put("seen_keys", JSONArray(input.seenKeys.takeLast(60)))
            .put("history", JSONArray(input.history.takeLast(6).map { (role, text) ->
                JSONObject().put("role", role).put("text", text.take(500))
            }))
        if (input.weather != null) body.put("weather", input.weather.json) else body.put("temp", 18)
        local?.let { store -> body.put("wardrobe", JSONArray(withContext(Dispatchers.IO) {
            store.garments().map(OutfitRepository::previewGarment)
        })) }
        val json = post(if (local == null) "/api/outfits/chat/" else "/api/outfits/preview/chat/", body)
        return try {
            ChatResult(json.getString("status"), json.getString("message"),
                json.optJSONObject("outfit")?.let(OutfitRepository::parseOutfit),
                strings(json, "changed_ids").toSet(), input.lockedIds,
                strings(json, "unlock_ids").toSet(), strings(json, "notes"))
        } catch (e: JSONException) {
            throw OutfitApiException("INVALID_RESPONSE", "응답을 읽지 못했어요. 다시 시도해 주세요.")
        }
    }

    suspend fun save(outfit: Outfit, saveId: String): Outfit = try {
        if (local != null) withContext(Dispatchers.IO) {
            val row = JSONObject().put("id", outfit.id).put("key", outfit.key).put("source", "chat")
                .put("saved", true).put("name", "대화로 만든 코디").put("score", JSONObject.NULL)
                .put("components", JSONObject()).put("reasons", JSONArray(outfit.reasons.map { reason ->
                    JSONObject().put("kind", reason.kind).put("text", reason.text).put("sub", reason.sub)
                })).put("feedback", JSONObject.NULL).put("created_at", outfit.createdAt)
                .put("items", JSONArray(outfit.items.map { item -> JSONObject()
                    .put("garment_id", item.garmentId).put("slot", item.slot).put("name", item.name)
                    .put("image_url", item.imageUrl).put("note", item.note) }))
            local.rememberOutfits(JSONArray().put(row))
            OutfitRepository.parseOutfit(local.saveOutfit(outfit.id, true))
        } else OutfitRepository.parseOutfit(post("/api/outfits/", JSONObject()
            .put("garment_ids", JSONArray(outfit.items.map { it.garmentId }))
            .put("source", "chat").put("name", "대화로 만든 코디")
            .put("context", JSONObject().put("chat_save_id", saveId))))
    } catch (e: JSONException) {
        throw OutfitApiException("INVALID_RESPONSE", "저장 결과를 확인하지 못했어요. 다시 시도해 주세요.")
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        if (account != null) {
            try { return@withContext account.json("POST", path, body) }
            catch (e: AuthException) { throw OutfitApiException(e.code, e.message.orEmpty()) }
        }
        val request = Request.Builder().url(base + path)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val (status, text) = try {
            client.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw OutfitApiException("NETWORK", OutfitRepository.NETWORK_ERROR)
        }
        if (status !in 200..299) {
            val error = try { JSONObject(text) } catch (e: JSONException) { JSONObject() }
            throw OutfitApiException(error.optString("error", "HTTP_$status"),
                error.optString("hint").ifBlank { "요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요." })
        }
        OutfitRepository.parseBody(status, text)
    }

    private fun strings(json: JSONObject, key: String): List<String> =
        json.optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList()
}

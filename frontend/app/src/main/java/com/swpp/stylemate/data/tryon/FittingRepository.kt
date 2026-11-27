package com.swpp.stylemate.data.tryon

import com.swpp.stylemate.data.AccountAccess
import com.swpp.stylemate.data.LocalUserStore
import com.swpp.stylemate.data.recommendation.Outfit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.security.MessageDigest
import java.util.UUID

data class FittingNotificationSettings(val enabled: Boolean = true) {
    fun json() = org.json.JSONObject().put("enabled", enabled)
    companion object {
        fun storageKey(owner: String) = "fitting-notifications:$owner"
        fun decode(value: org.json.JSONObject?) = FittingNotificationSettings(value?.optBoolean("enabled", true) ?: true)
    }
}

/** Validates outfit ownership and retains generated results in the active user's private store. */
class FittingRepository(private val store: LocalUserStore, private val account: AccountAccess) {
    suspend fun notificationSettings(): FittingNotificationSettings = withContext(Dispatchers.IO) {
        account.authorization()
        FittingNotificationSettings.decode(store.get(FittingNotificationSettings.storageKey(account.userId)))
    }

    suspend fun saveNotificationSettings(settings: FittingNotificationSettings) = withContext(Dispatchers.IO) {
        account.authorization()
        store.put(FittingNotificationSettings.storageKey(account.userId), settings.json())
    }

    // A fitting depends only on the garments (and their photos) and the person, not on which outfit card asked
    // for it: 홈 makes a new outfit id each time it recommends the same garments again, and the same garments
    // can be saved to 코디북 twice. So results and tasks are kept per garment combination. Results saved before
    // this, per outfit id, are still found under their old key.
    suspend fun result(outfit: Outfit, source: String = "gallery"): ByteArray? = withContext(Dispatchers.IO) {
        store.fittingResult(resultKey(outfit, source)) ?: store.fittingResult(legacyKey(outfit, source))
    }

    suspend fun hasResult(outfit: Outfit, source: String): Boolean = withContext(Dispatchers.IO) {
        store.hasFittingResult(resultKey(outfit, source)) || store.hasFittingResult(legacyKey(outfit, source))
    }

    suspend fun saveResult(outfit: Outfit, image: ByteArray, source: String = "gallery") = withContext(Dispatchers.IO) {
        store.saveFittingResult(resultKey(outfit, source), image)
        store.put("fitting-latest:${taskKey(outfit)}", org.json.JSONObject().put("source", source))
    }

    suspend fun latestSource(outfit: Outfit): String? = withContext(Dispatchers.IO) {
        (store.get("fitting-latest:${taskKey(outfit)}") ?: store.get("fitting-latest:${legacyKey(outfit, "background-job")}"))
            ?.getString("source")
    }

    fun taskKey(outfit: Outfit): String = resultKey(outfit, "background-job")

    private fun resultKey(outfit: Outfit, source: String): String =
        digest(listOf("nano-banana-2.1-2k-v2", account.userId, garments(outfit), source))

    /** The key used before results were shared by garment combination (it also held the outfit id). */
    internal fun legacyKey(outfit: Outfit, source: String): String =
        digest(listOf("nano-banana-2.1-2k-v1", account.userId, outfit.id, garments(outfit), source))

    private fun garments(outfit: Outfit) = JSONArray(outfit.items.sortedWith(compareBy({ it.slot }, { it.garmentId }))
        .map { JSONArray(listOf(it.slot, it.garmentId, it.imageUrl)) })

    private fun digest(parts: List<Any>): String {
        account.authorization()
        return MessageDigest.getInstance("SHA-256").digest(JSONArray(parts).toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    suspend fun validate(outfit: Outfit) = withContext(Dispatchers.IO) {
        require(outfit.items.size in 1..4 && outfit.items.map { it.garmentId }.distinct().size == outfit.items.size)
        outfit.items.associate { item ->
            require(UUID.fromString(item.garmentId).toString() == item.garmentId)
            val record = account.json("GET", "/api/wardrobe/items/${item.garmentId}/")
            check(record.getString("id") == item.garmentId && record.optBoolean("saved")) {
                "저장된 옷 정보를 다시 확인해 주세요."
            }
            check(record.getJSONObject("attributes").getString("category") == item.slot) {
                "코디의 옷 분류가 바뀌었어요. 코디를 다시 선택해 주세요."
            }
            item.garmentId to record
        }
    }
}

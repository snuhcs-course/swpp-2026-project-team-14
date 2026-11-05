package com.swpp.stylemate.data.tryon

import com.swpp.stylemate.data.AccountAccess
import com.swpp.stylemate.data.LocalUserStore
import com.swpp.stylemate.data.recommendation.Outfit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.security.MessageDigest
import java.util.UUID

data class FittingNotificationSettings(val enabled: Boolean = false) {
    fun json() = org.json.JSONObject().put("enabled", enabled)
    companion object {
        fun storageKey(owner: String) = "fitting-notifications:$owner"
        fun decode(value: org.json.JSONObject?) = FittingNotificationSettings(value?.optBoolean("enabled", false) ?: false)
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

    suspend fun result(outfit: Outfit, source: String = "gallery"): ByteArray? = withContext(Dispatchers.IO) {
        store.fittingResult(resultKey(outfit, source))
    }

    suspend fun saveResult(outfit: Outfit, image: ByteArray, source: String = "gallery") = withContext(Dispatchers.IO) {
        store.saveFittingResult(resultKey(outfit, source), image)
        store.put("fitting-latest:${taskKey(outfit)}", org.json.JSONObject().put("source", source))
    }

    suspend fun latestSource(outfit: Outfit): String? = withContext(Dispatchers.IO) {
        store.get("fitting-latest:${taskKey(outfit)}")?.getString("source")
    }

    fun taskKey(outfit: Outfit): String = resultKey(outfit, "background-job")

    private fun resultKey(outfit: Outfit, source: String): String {
        account.authorization()
        val items = outfit.items.sortedWith(compareBy({ it.slot }, { it.garmentId }))
            .map { JSONArray(listOf(it.slot, it.garmentId, it.imageUrl)) }
        val input = JSONArray(listOf("nano-banana-2.1-2k-v1", account.userId,
            outfit.id, JSONArray(items), source))
        return MessageDigest.getInstance("SHA-256").digest(input.toString().toByteArray(Charsets.UTF_8))
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

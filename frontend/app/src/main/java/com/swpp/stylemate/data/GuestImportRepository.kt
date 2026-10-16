// AI-generated with Codex, 2026-10-16
package com.swpp.stylemate.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class GuestImportRecord(val kind: String, val id: String, val value: JSONObject, val fingerprint: String) {
    val key get() = "$kind:$id"
    val label get() = when (kind) {
        "profile" -> if (id == "body") "체형 정보" else "스타일 프로필"
        "garments" -> value.optJSONObject("attributes")?.optString("name").orEmpty().ifEmpty { "옷" }
        else -> value.optString("name").ifEmpty { "코디" }
    }
}

data class GuestImportSnapshot(val records: List<GuestImportRecord>) {
    fun count(kind: String) = records.count { it.kind == kind }
    val summary get() = "프로필 ${count("profile")}건 · 옷 ${count("garments")}벌 · 코디 ${count("outfits")}개"
}

data class GuestImportPlan(val snapshot: GuestImportSnapshot, val versions: Map<String, String?>, val conflicts: List<GuestImportRecord>)

class GuestImportRepository(private val guest: LocalUserStore, private val account: AccountAccess) {
    suspend fun snapshot(includeKept: Boolean = false): GuestImportSnapshot = withContext(Dispatchers.IO) {
        val records = mutableListOf<GuestImportRecord>()
        fun add(kind: String, id: String, value: JSONObject, imageHash: String = "") {
            records += GuestImportRecord(kind, id, value, hash(canonical(value) + imageHash))
        }
        guest.get("body")?.let { add("profile", "body", it) }
        guest.get("style")?.let { add("profile", "style", StyleProfile.decode(it).json()) }
        guest.garments().forEach { row ->
            val id = row.getString("id")
            val value = JSONObject()
            for (key in listOf("attributes", "dimensions", "notes", "labels")) value.put(key, row.opt(key) ?: if (key == "notes") "" else JSONObject.NULL)
            val imageHash = runCatching { hash(guest.image("local:$id")) }.getOrDefault("missing-image")
            add("garments", id, value, imageHash)
        }
        guest.outfits().forEach { row ->
            val value = JSONObject().put("name", row.optString("name"))
            for (key in listOf("score", "components", "feedback")) value.put(key, row.opt(key) ?: JSONObject.NULL)
            value.put("reasons", row.optJSONArray("reasons") ?: JSONArray()).put("context", row.optJSONObject("context") ?: JSONObject())
            val items = row.getJSONArray("items")
            value.put("items", JSONArray((0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                JSONObject().put("garment_id", item.getString("garment_id")).put("slot", item.getString("slot")).put("note", item.optString("note"))
            }))
            add("outfits", row.getString("id"), value)
        }
        val state = guest.importState(account.userId)
        val receipts = state.optJSONObject("records") ?: JSONObject()
        val pending = records.filter {
            val receipt = receipts.optJSONObject(it.key)
            receipt?.optString("fingerprint") != it.fingerprint || (includeKept && receipt?.optString("status") == "kept")
        }
            .sortedWith(compareBy({ when (it.kind) { "profile" -> 0; "garments" -> 1; else -> 2 } }, { it.id }))
        GuestImportSnapshot(pending)
    }

    suspend fun preview(snapshot: GuestImportSnapshot): GuestImportPlan {
        val profile = JSONObject()
        snapshot.records.filter { it.kind == "profile" }.forEach { profile.put(it.id, it.value) }
        val garments = snapshot.records.filter { it.kind == "garments" }.map { it.id }
        val outfits = snapshot.records.filter { it.kind == "outfits" }.map { it.id }
        val versions = mutableMapOf<String, String?>()
        val conflicts = mutableListOf<GuestImportRecord>()
        val batches = maxOf(1, (maxOf(garments.size, outfits.size) + 99) / 100)
        repeat(batches) { index ->
            val data = JSONObject().put("profile", if (index == 0) profile else JSONObject())
                .put("garments", JSONArray(garments.drop(index * 100).take(100)))
                .put("outfits", JSONArray(outfits.drop(index * 100).take(100)))
            val result = account.json("POST", "/api/users/me/import/preview/", data)
            for (kind in listOf("profile", "garments", "outfits")) {
                val entries = result.getJSONObject(kind)
                entries.keys().forEach { id ->
                    val record = snapshot.records.first { it.kind == kind && it.id == id }
                    val entry = entries.getJSONObject(id)
                    versions[record.key] = if (entry.isNull("version")) null else entry.getString("version")
                    if (entry.getBoolean("conflict")) conflicts += record
                }
            }
        }
        check(versions.keys == snapshot.records.map { it.key }.toSet()) { "가져오기 정보를 확인하지 못했어요. 다시 시도해 주세요." }
        return GuestImportPlan(snapshot, versions, conflicts)
    }

    suspend fun importRecord(record: GuestImportRecord, plan: GuestImportPlan, replace: Boolean): String {
        val data = JSONObject().put("value", record.value).put("replace", replace)
            .put("expected", plan.versions[record.key] ?: JSONObject.NULL)
        val result = if (record.kind == "garments") {
            val bytes = withContext(Dispatchers.IO) {
                try { guest.image("local:${record.id}") }
                catch (error: java.io.IOException) { throw IllegalStateException("옷 사진을 읽지 못했어요. 사진을 확인한 뒤 다시 시도해 주세요.", error) }
            }
            check(bytes.isNotEmpty() && bytes.size <= 5 * 1024 * 1024) { "옷 사진은 5MB 이하의 이미지여야 해요." }
            check(record.fingerprint == hash(canonical(record.value) + hash(bytes))) { "이 기기의 옷 정보가 바뀌었어요. 다시 시도해 주세요." }
            account.upload("/api/users/me/import/garments/${record.id}/", data, bytes)
        } else account.json("PUT", "/api/users/me/import/${record.kind}/${record.id}/", data)
        val status = result.getString("status")
        check(status in setOf("imported", "unchanged", "kept")) { "가져오기 결과를 확인하지 못했어요." }
        withContext(Dispatchers.IO) { guest.recordImport(account.userId, record.key, record.fingerprint, status) }
        return status
    }

    suspend fun refreshAccount() = account.refreshProfile()

    companion object {
        private fun hash(value: String) = hash(value.toByteArray(Charsets.UTF_8))
        private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
        private fun canonical(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { "${JSONObject.quote(it)}:${canonical(value.get(it))}" }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            is Number -> JSONObject.numberToString(value)
            else -> value.toString()
        }
    }
}

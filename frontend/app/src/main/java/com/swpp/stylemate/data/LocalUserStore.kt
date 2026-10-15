// AI-generated with Codex, 2026-10-16
package com.swpp.stylemate.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

data class StyleProfile(
    val preferredFits: List<String> = listOf("regular"),
    val preferredStyles: List<String> = emptyList(),
    val favoriteColors: List<String> = emptyList(),
) {
    fun legacyFit() = when (preferredFits.firstOrNull()) {
        "oversized", "wide" -> PreferredFit.LOOSE
        "slim", "tapered" -> PreferredFit.SLIM
        else -> PreferredFit.REGULAR
    }
    fun styleLabels() = preferredStyles.map { STYLE_LABELS[it] ?: it }
    fun json() = JSONObject().put("preferred_fits", JSONArray(preferredFits.ifEmpty { listOf("regular") }))
        .put("preferred_styles", JSONArray(preferredStyles)).put("favorite_colors", JSONArray(favoriteColors))

    companion object {
        private val STYLE_LABELS = mapOf("minimal" to "미니멀", "cityboy" to "시티보이", "casual" to "캐주얼",
            "street" to "스트릿", "classic" to "클래식", "sporty" to "스포티")
        fun fromLegacy(fit: PreferredFit, styles: List<String>) = StyleProfile(
            listOf(when (fit) { PreferredFit.LOOSE -> "oversized"; PreferredFit.SLIM -> "slim"; else -> "regular" }),
            styles.mapNotNull { label -> STYLE_LABELS.entries.firstOrNull { it.value == label }?.key },
        )
        fun decode(json: JSONObject) = StyleProfile(
            strings(json.optJSONArray("preferred_fits")).ifEmpty { listOf("regular") },
            strings(json.optJSONArray("preferred_styles")), strings(json.optJSONArray("favorite_colors")),
        )
        private fun strings(array: JSONArray?) = (0 until (array?.length() ?: 0)).map { array!!.getString(it) }
    }
}

data class OnboardingProgress(val step: Int = 1, val completed: Boolean = false, val draft: String = "{}") {
    init { require(step in 1..8); JSONObject(draft) }
    fun json() = JSONObject().put("step", step).put("completed", completed).put("draft", JSONObject(draft))
}

/** One private snapshot per guest/account. Writes replace the previous file atomically. */
class LocalUserStore(private val directory: File) {
    private val snapshot get() = File(directory, "data.json")

    private fun read(): JSONObject = if (!snapshot.exists()) JSONObject() else try {
        JSONObject(snapshot.readText())
    } catch (error: Exception) {
        throw IllegalStateException("기기에 저장된 정보를 읽지 못했어요. 기존 파일은 유지됩니다.", error)
    }

    private fun write(file: File, bytes: ByteArray) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "저장 폴더를 만들지 못했어요." }
        val temporary = File.createTempFile("pending-", ".tmp", file.parentFile)
        try {
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }

    fun get(key: String): JSONObject? = synchronized(lock) {
        val data = read()
        if (!data.has(key) || data.isNull(key)) null else data.getJSONObject(key)
    }
    fun put(key: String, value: JSONObject?) = synchronized(lock) {
        val data = read()
        if (value == null) data.remove(key) else data.put(key, value)
        write(snapshot, data.toString().toByteArray(Charsets.UTF_8))
    }

    fun style(): StyleProfile = get("style")?.let(StyleProfile::decode) ?: StyleProfile()
    fun saveStyle(style: StyleProfile) {
        require(style.preferredFits.size <= 5 && style.preferredFits.distinct().size == style.preferredFits.size)
        require(style.preferredFits.all { it in setOf("oversized", "wide", "regular", "tapered", "slim") })
        require(style.preferredStyles.size <= 6 && style.preferredStyles.distinct().size == style.preferredStyles.size)
        require(style.preferredStyles.all { it in setOf("minimal", "cityboy", "casual", "street", "classic", "sporty") })
        require(style.favoriteColors.size <= 8 && style.favoriteColors.all { it.matches(Regex("#[0-9A-Fa-f]{6}")) })
        val colors = style.favoriteColors.map { it.uppercase() }
        require(colors.distinct().size == colors.size)
        put("style", style.copy(favoriteColors = colors).json())
    }

    fun onboarding(): OnboardingProgress = get("onboarding")?.let {
        OnboardingProgress(it.getInt("step"), it.getBoolean("completed"), it.optJSONObject("draft")?.toString() ?: "{}")
    } ?: OnboardingProgress()
    fun saveOnboarding(progress: OnboardingProgress) = put("onboarding", progress.json())

    fun cacheAccountProfile(user: AccountUser) = synchronized(lock) {
        val data = read().put("style", user.style.json())
        if (user.body == null) data.remove("body") else data.put("body", user.body)
        if (!data.has("onboarding")) data.put("onboarding", OnboardingProgress(8, true).json())
        write(snapshot, data.toString().toByteArray(Charsets.UTF_8))
    }

    fun cacheGarment(record: JSONObject) = upsert("garments", record)
    fun cacheGarments(rows: JSONArray) = synchronized(lock) {
        val data = read().put("garments", rows)
        write(snapshot, data.toString().toByteArray(Charsets.UTF_8))
    }

    fun importState(accountId: String): JSONObject = get("imports")?.optJSONObject(accountId) ?: JSONObject()

    fun recordImport(accountId: String, key: String, fingerprint: String, status: String) = synchronized(lock) {
        require(status in setOf("imported", "unchanged", "kept"))
        val data = read()
        val imports = data.optJSONObject("imports") ?: JSONObject()
        val account = imports.optJSONObject(accountId) ?: JSONObject()
        val records = account.optJSONObject("records") ?: JSONObject()
        records.put(key, JSONObject().put("fingerprint", fingerprint).put("status", status))
        account.put("records", records)
        imports.put(accountId, account)
        data.put("imports", imports)
        write(snapshot, data.toString().toByteArray(Charsets.UTF_8))
    }

    private fun records(data: JSONObject, key: String) = data.optJSONArray(key) ?: JSONArray()
    private fun list(key: String): List<JSONObject> = synchronized(lock) {
        val array = records(read(), key)
        (0 until array.length()).map { array.getJSONObject(it) }
    }
    private fun upsert(key: String, record: JSONObject) = synchronized(lock) {
        val data = read()
        val rows = records(data, key)
        val index = (0 until rows.length()).firstOrNull { rows.getJSONObject(it).getString("id") == record.getString("id") }
        rows.put(index ?: rows.length(), record)
        data.put(key, rows)
        write(snapshot, data.toString().toByteArray(Charsets.UTF_8))
    }

    fun garments() = list("garments").filter { it.optBoolean("saved") }
    fun garment(id: String) = list("garments").firstOrNull { it.getString("id") == id }

    fun createGarment(result: JSONObject, image: ByteArray): JSONObject = synchronized(lock) {
        require(image.isNotEmpty())
        val id = UUID.randomUUID().toString()
        val file = imageFile(id)
        write(file, image)
        val record = JSONObject(result.toString()).put("id", id).put("saved", false).put("notes", "")
            .put("dimensions", JSONObject.NULL).put("labels", JSONObject.NULL).put("image_url", "local:$id")
            .put("created_at", Instant.now().toString())
        try { upsert("garments", record) } catch (error: Exception) { file.delete(); throw error }
        record
    }

    fun saveGarment(id: String, payload: JSONObject): JSONObject = synchronized(lock) {
        val record = checkNotNull(garment(id)) { "옷 정보를 찾을 수 없어요." }
        require(payload.keys().asSequence().all { it in setOf("attributes", "dimensions", "notes", "labels") })
        payload.keys().forEach { record.put(it, payload.get(it)) }
        record.put("saved", true).put("updated_at", Instant.now().toString())
        upsert("garments", record)
        JSONObject(record.toString())
    }

    fun discardDraft(id: String) = synchronized(lock) {
        val record = garment(id)
        if (record == null || record.optBoolean("saved")) return@synchronized
        val data = read()
        val rows = records(data, "garments")
        val remaining = JSONArray()
        for (index in 0 until rows.length()) {
            if (rows.getJSONObject(index).getString("id") != id) remaining.put(rows.getJSONObject(index))
        }
        data.put("garments", remaining)
        write(snapshot, data.toString().toByteArray(Charsets.UTF_8))
        imageFile(id).delete()
        Unit
    }

    private fun imageFile(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(File(directory, "garments"), "$id.jpg")
    }
    fun image(path: String): ByteArray = synchronized(lock) {
        require(path.startsWith("local:"))
        imageFile(path.removePrefix("local:")).readBytes()
    }

    fun outfits() = list("outfits").filter { it.optBoolean("saved") }
    fun outfit(id: String) = list("outfits").firstOrNull { it.getString("id") == id }
    fun rememberOutfits(rows: JSONArray) = synchronized(lock) {
        for (index in 0 until rows.length()) {
            val record = rows.getJSONObject(index)
            val previous = outfit(record.getString("id"))
            if (previous != null) for (field in listOf("saved", "name", "feedback")) {
                if (previous.has(field)) record.put(field, previous.get(field))
            }
            upsert("outfits", record)
        }
    }
    fun saveOutfit(id: String, saved: Boolean): JSONObject = synchronized(lock) {
        val record = checkNotNull(outfit(id)) { "코디를 찾을 수 없어요." }.put("saved", saved)
        upsert("outfits", record)
        record
    }
    fun setFeedback(id: String, feedback: String?): JSONObject = synchronized(lock) {
        require(feedback == null || feedback in setOf("liked", "disliked"))
        val record = checkNotNull(outfit(id)) { "코디를 찾을 수 없어요." }.put("feedback", feedback ?: JSONObject.NULL)
        upsert("outfits", record)
        record
    }

    companion object {
        private val lock = Any()
        fun forContext(context: Context, accountId: String? = null): LocalUserStore =
            inDirectory(File(context.applicationContext.filesDir, "local-users"), accountId)
        fun inDirectory(root: File, accountId: String? = null): LocalUserStore {
            val scope = accountId?.let { UUID.fromString(it).toString() } ?: "guest"
            return LocalUserStore(File(root, scope))
        }
    }
}

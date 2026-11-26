package com.swpp.stylemate.data.tryon

import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The fitting the floating card shows, with how many are in progress and finished but not yet opened. */
data class CardSummary(val shown: Pair<String, JSONObject>, val running: Int, val finished: Int)

/** More fittings are already in progress than [FittingTaskStore.MAX_ACTIVE]. */
class TooManyFittings : IllegalStateException("가상 피팅은 한 번에 ${FittingTaskStore.MAX_ACTIVE}개까지 만들 수 있어요. 진행 중인 피팅이 끝나면 다시 시도해 주세요.")

/** Private job metadata survives process recreation; source photos are removed after upload. */
class FittingTaskStore(private val directory: File) {
    private fun file(key: String, suffix: String): File {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return File(directory, "$key.$suffix")
    }
    fun read(key: String): JSONObject? = synchronized(lock) {
        file(key, "json").takeIf { it.isFile }?.readText()?.let(::JSONObject)
    }
    private fun write(key: String, data: JSONObject) {
        directory.mkdirs()
        val target = file(key, "json")
        val temporary = file(key, "tmp")
        try {
            temporary.writeText(data.toString())
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    fun create(key: String, owner: String, source: String, outfit: Outfit, photo: ByteArray) = synchronized(lock) {
        require(photo.isNotEmpty())
        directory.mkdirs()
        deletePreview(key)  // a new fitting of the same outfit replaces the earlier photo
        try {
            file(key, "photo").writeBytes(photo)
            val snapshot = JSONObject().put("id", outfit.id).put("key", outfit.key).put("name", outfit.name)
                .put("items", JSONArray(outfit.items.map { item -> JSONObject().put("garment_id", item.garmentId)
                    .put("slot", item.slot).put("name", item.name).put("image_url", item.imageUrl).put("note", item.note) }))
            write(key, JSONObject().put("owner", owner).put("source", source).put("outfit", snapshot).put("state", "queued")
                .put("created_at", System.currentTimeMillis()))
        } catch (error: Exception) {
            deletePerson(key)
            throw error
        }
    }
    fun person(key: String): ByteArray = synchronized(lock) { file(key, "photo").readBytes() }
    /**
     * A small copy of a gallery photo being dressed, kept on this device only until that fitting has been
     * seen, so the loading screen, the reveal and the card can show the person after the full photo is
     * uploaded and deleted. Virtual models need none.
     */
    fun savePreview(key: String, jpeg: ByteArray) = synchronized(lock) { directory.mkdirs(); file(key, "preview").writeBytes(jpeg) }
    fun preview(key: String): ByteArray? = synchronized(lock) { file(key, "preview").takeIf { it.isFile }?.readBytes() }
    fun deletePreview(key: String) = synchronized(lock) { file(key, "preview").delete(); Unit }
    /** Previews of fittings that finished longer ago than the card shows them are removed. */
    fun pruneStalePreviews(now: Long) = synchronized(lock) {
        directory.listFiles { f -> f.name.endsWith(".preview") }.orEmpty().forEach { preview ->
            val data = read(preview.name.removeSuffix(".preview"))
            val finished = data?.optString("state") in listOf("completed", "failed")
            if (data == null || finished && (!data.has("finished_at") || now - data.getLong("finished_at") > CARD_RECENT_MS)) preview.delete()
        }
    }
    fun deletePerson(key: String) = synchronized(lock) { file(key, "photo").delete(); Unit }
    fun submitted(key: String, job: TryOnJob) = synchronized(lock) {
        val data = checkNotNull(read(key))
        write(key, data.put("job_id", job.id).put("job_token", job.token).put("state", "running"))
        deletePerson(key)
    }
    /** The latest progress of a running fitting, read by the fitting screen while it waits. */
    fun progress(key: String, progress: FittingProgress) = synchronized(lock) {
        val data = read(key) ?: return@synchronized
        if (data.optString("state") !in listOf("queued", "running")) return@synchronized
        write(key, data.put("stage", progress.stage).put("progress", progress.fraction.toDouble()).put("status", progress.text))
    }
    fun finish(key: String, error: String? = null) = synchronized(lock) {
        val data = checkNotNull(read(key))
        data.remove("job_id"); data.remove("job_token")
        deletePerson(key)
        write(key, data.put("state", if (error == null) "completed" else "failed").put("error", error ?: JSONObject.NULL)
            .put("finished_at", System.currentTimeMillis()))
    }
    /** Every task of [owner] with its key, for the progress card shown over the tabs. */
    fun all(owner: String): List<Pair<String, JSONObject>> = synchronized(lock) {
        directory.listFiles { file -> file.name.endsWith(".json") }.orEmpty().mapNotNull { file ->
            val key = file.name.removeSuffix(".json")
            runCatching { JSONObject(file.readText()) }.getOrNull()?.takeIf { it.optString("owner") == owner }?.let { key to it }
        }
    }
    /** The finished task's card was opened or closed, so it is not shown again. */
    fun dismissCard(key: String) = synchronized(lock) {
        deletePreview(key)
        val data = read(key) ?: return@synchronized
        write(key, data.put("card_dismissed", true))
    }
    companion object {
        private val lock = Any()
        fun outfit(data: JSONObject): Outfit = OutfitRepository.parseOutfit(data.getJSONObject("outfit"))
        fun progress(data: JSONObject): FittingProgress? = if (!data.has("stage")) null else
            FittingProgress(data.getString("stage"), data.optDouble("progress").toFloat(), data.optString("status"))
        const val CARD_RECENT_MS = 10 * 60_000L

        /**
         * The fitting the floating card follows: the newest one still in progress, otherwise the newest one
         * finished in the last [recentMs] whose card was not opened or closed yet. Tasks from before this
         * card existed carry no finish time and never show.
         */
        fun cardTask(tasks: List<Pair<String, JSONObject>>, now: Long, recentMs: Long = CARD_RECENT_MS): Pair<String, JSONObject>? =
            cardSummary(tasks, now, recentMs)?.shown

        /** How many fittings may be in progress at once for one account (each is a paid generation). */
        const val MAX_ACTIVE = 3

        fun running(tasks: List<Pair<String, JSONObject>>) =
            tasks.filter { it.second.optString("state") in listOf("queued", "running") }

        /**
         * What the floating card shows when several fittings are going: a finished one not yet opened comes
         * first (the newest), so a result is never hidden behind work still in progress; otherwise the
         * running one started first, which is closest to done. [running] and [finished] count the rest.
         */
        fun cardSummary(tasks: List<Pair<String, JSONObject>>, now: Long, recentMs: Long = CARD_RECENT_MS): CardSummary? {
            val active = running(tasks)
            val finished = tasks.filter { (_, data) ->
                data.optString("state") in listOf("completed", "failed") && !data.optBoolean("card_dismissed") &&
                    data.has("finished_at") && now - data.getLong("finished_at") in 0..recentMs
            }
            val shown = finished.maxByOrNull { it.second.optLong("finished_at") }
                ?: active.minByOrNull { it.second.optLong("created_at") } ?: return null
            return CardSummary(shown, active.size, finished.size)
        }
        fun job(data: JSONObject): TryOnJob? = if (data.has("job_id")) TryOnJob(data.getString("job_id"), data.getString("job_token")) else null
    }
}

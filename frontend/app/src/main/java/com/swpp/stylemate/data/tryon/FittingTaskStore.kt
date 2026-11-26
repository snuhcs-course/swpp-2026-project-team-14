package com.swpp.stylemate.data.tryon

import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
    fun create(key: String, owner: String, source: String, outfit: Outfit, photo: ByteArray,
               usage: JSONObject? = null) = synchronized(lock) {
        require(photo.isNotEmpty())
        directory.mkdirs()
        try {
            file(key, "photo").writeBytes(photo)
            val snapshot = JSONObject().put("id", outfit.id).put("key", outfit.key).put("name", outfit.name)
                .put("items", JSONArray(outfit.items.map { item -> JSONObject().put("garment_id", item.garmentId)
                    .put("slot", item.slot).put("name", item.name).put("image_url", item.imageUrl).put("note", item.note) }))
            write(key, JSONObject().put("owner", owner).put("source", source).put("outfit", snapshot).put("state", "queued")
                .put("usage", usage ?: JSONObject.NULL))
        } catch (error: Exception) {
            deletePerson(key)
            throw error
        }
    }
    fun person(key: String): ByteArray = synchronized(lock) { file(key, "photo").readBytes() }
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
        write(key, data.put("state", if (error == null) "completed" else "failed").put("error", error ?: JSONObject.NULL))
    }
    companion object {
        private val lock = Any()
        fun outfit(data: JSONObject): Outfit = OutfitRepository.parseOutfit(data.getJSONObject("outfit"))
        fun progress(data: JSONObject): FittingProgress? = if (!data.has("stage")) null else
            FittingProgress(data.getString("stage"), data.optDouble("progress").toFloat(), data.optString("status"))
        fun job(data: JSONObject): TryOnJob? = if (data.has("job_id")) TryOnJob(data.getString("job_id"), data.getString("job_token")) else null
    }
}

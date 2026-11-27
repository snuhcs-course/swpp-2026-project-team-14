package com.swpp.stylemate.data.usage

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Account-specific, atomically replaced queue. Only acknowledged event ids are removed. */
class UsageQueue(private val file: File) {
    private fun read(): List<JSONObject> {
        if (!file.exists()) return emptyList()
        val rows = JSONArray(file.readText())
        return (0 until rows.length()).map { rows.getJSONObject(it) }
    }

    private fun write(rows: List<JSONObject>) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            temporary.writeText(JSONArray(rows).toString())
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }

    fun add(event: JSONObject) = synchronized(lock) {
        val rows = read()
        if (rows.none { it.getString("id") == event.getString("id") }) {
            // Keep recent alpha-test events if a device stays offline for an extended period.
            write((rows + event).takeLast(MAX_EVENTS))
        }
    }

    fun batch(): List<JSONObject> = synchronized(lock) { read().take(BATCH_SIZE) }
    fun acknowledge(ids: Set<String>) = synchronized(lock) { write(read().filterNot { it.getString("id") in ids }) }

    companion object {
        const val BATCH_SIZE = 25
        const val MAX_EVENTS = 2000
        private val lock = Any()
    }
}

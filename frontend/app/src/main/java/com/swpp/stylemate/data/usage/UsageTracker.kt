package com.swpp.stylemate.data.usage

import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Only fixed codes and timings enter this collector; never pass user text or exception messages. */
class UsageTracker(
    private val emit: (JSONObject) -> Unit = {},
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
    private val boot: () -> Int = { 0 },
    private val now: () -> Instant = Instant::now,
    private val version: String = "test",
) {
    private val session = UUID.randomUUID().toString()

    fun start(name: String, source: String): Attempt {
        val checkpoint = JSONObject().put("attempt_id", UUID.randomUUID().toString()).put("session_id", session)
            .put("name", name).put("source", source).put("elapsed", elapsed()).put("boot", boot())
        return Attempt(checkpoint).also { it.record("started") }
    }

    fun resume(checkpoint: JSONObject): Attempt {
        UUID.fromString(checkpoint.getString("attempt_id"))
        UUID.fromString(checkpoint.getString("session_id"))
        require(checkpoint.getString("name") in setOf("garment_registration", "virtual_fitting", "chat_request"))
        require(checkpoint.getString("source") in setOf("wardrobe", "onboarding", "worn", "virtual", "gallery", "chat"))
        checkpoint.getLong("elapsed")
        checkpoint.getInt("boot")
        return Attempt(JSONObject(checkpoint.toString()))
    }

    inner class Attempt internal constructor(val checkpoint: JSONObject) {
        private val finished = AtomicBoolean(false)

        fun generated() = record("generated")
        fun success(status: String? = null) = finish("success", status = status)
        fun failure(code: String, status: String? = null) = finish("failure", code, status)
        fun cancel() = finish("cancelled")

        private fun finish(outcome: String, code: String = "", status: String? = null) {
            if (finished.compareAndSet(false, true)) record("finished", outcome, code, status)
        }

        internal fun record(phase: String, outcome: String = "", code: String = "", status: String? = null) {
            val duration = elapsed() - checkpoint.getLong("elapsed")
            val reset = boot() != checkpoint.getInt("boot") || duration < 0 || duration > MAX_DURATION
            val details = JSONObject().put("source", checkpoint.getString("source")).put("app_version", version)
            if (reset && phase != "started") details.put("clock_reset", true)
            status?.let { details.put("status", it) }
            val id = UUID.nameUUIDFromBytes("${checkpoint.getString("attempt_id")}:$phase".toByteArray()).toString()
            val event = JSONObject().put("id", id).put("attempt_id", checkpoint.getString("attempt_id"))
                .put("session_id", checkpoint.getString("session_id")).put("name", checkpoint.getString("name"))
                .put("phase", phase).put("outcome", outcome).put("occurred_at", now().toString())
                .put("duration_ms", if (phase == "started" || reset) JSONObject.NULL else duration)
                .put("error_code", code.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,63}")) } ?: "UNKNOWN")
                .put("details", details)
            if (outcome != "failure") event.put("error_code", "")
            // Storage and upload errors must never change the operation being measured.
            runCatching { emit(event) }
        }
    }

    companion object {
        private const val MAX_DURATION = 30L * 86400 * 1000
        val NONE = UsageTracker()
    }
}

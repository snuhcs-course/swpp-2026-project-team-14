package com.swpp.stylemate.data.usage

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed metadata and code locations only: never Throwable.message, thread names, or raw traces. */
internal class StabilityEvents(
    private val installation: String,
    private val session: String,
    private val version: String,
    private val sdk: Int,
) {
    fun event(kind: String, source: String, at: Instant, foreground: Boolean,
              frames: List<String> = emptyList(), exception: String = "", duration: Long? = null,
              reason: Int? = null, status: Int? = null, identity: String = "$session:$kind"): JSONObject =
        JSONObject().put("id", identity(identity)).put("installation_id", installation).put("session_id", session)
            .put("kind", kind).put("source", source).put("occurred_at", at.toString())
            .put("app_version", version).put("sdk_int", sdk).put("foreground", foreground)
            .put("frames", JSONArray(frames.take(20))).put("exception_type", exception)
            .put("duration_ms", duration ?: JSONObject.NULL).put("exit_reason", reason ?: JSONObject.NULL)
            .put("exit_status", status ?: JSONObject.NULL)

    fun active(at: Instant) = event("active", "lifecycle", at, true,
        identity = "$installation:active:${at.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()}:$version:$sdk")

    fun crash(error: Throwable, at: Instant, foreground: Boolean): JSONObject {
        val chain = generateSequence(error) { it.cause }.take(4).toList()
        val frames = chain.flatMap { frames(it.stackTrace) }.take(20)
        return event("crash", "java", at, foreground, frames, error.javaClass.name.take(160))
    }

    fun summary(): ByteArray = JSONObject().put("t", 1).put("s", session).put("v", version).put("a", sdk)
        .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= 128) }

    companion object {
        private val framePattern = Regex("[A-Za-z0-9_.$<>/-]+\\([A-Za-z0-9_.$:-]*\\)")
        fun identity(value: String): String = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()

        fun frames(stack: Array<StackTraceElement>): List<String> = stack.asSequence().mapNotNull {
            val file = it.fileName.orEmpty().takeIf { name -> name.matches(Regex("[A-Za-z0-9_.$-]*")) }.orEmpty()
            val location = if (it.lineNumber >= 0) "$file:${it.lineNumber}" else file
            "${it.className}.${it.methodName}($location)".takeIf { frame -> frame.length <= 180 && framePattern.matches(frame) }
        }.take(20).toList()

        fun traceFrames(trace: String): List<String> {
            val main = trace.lineSequence().dropWhile { !it.startsWith("\"main\"") }.drop(1)
                .takeWhile { !it.startsWith('"') }
            return main.mapNotNull { line ->
                Regex("\\s*at ([A-Za-z0-9_.$<>/-]+)\\s*\\(([^()]*)\\)\\s*").matchEntire(line)?.let {
                    "${it.groupValues[1]}(${it.groupValues[2].replace("Native method", "NativeMethod")})"
                }?.takeIf { it.length <= 180 && framePattern.matches(it) }
            }.take(20).toList()
        }

        fun fromSummary(installation: String, summary: ByteArray): StabilityEvents? = runCatching {
            val json = JSONObject(summary.toString(Charsets.UTF_8))
            require(json.getInt("t") == 1)
            val session = json.getString("s")
            require(UUID.fromString(session).toString() == session)
            val version = json.getString("v")
            require(version.matches(Regex("[A-Za-z0-9.+_-]{1,40}")))
            val sdk = json.getInt("a")
            require(sdk in 30..100)
            StabilityEvents(installation, session, version, sdk)
        }.getOrNull()
    }
}

/** The previous handler must run even if disk storage is unavailable or another thread also dies. */
internal class StabilityCrashHandler(
    private val next: Thread.UncaughtExceptionHandler,
    private val save: (Throwable) -> Unit,
) : Thread.UncaughtExceptionHandler {
    private val recorded = AtomicBoolean(false)
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try { if (recorded.compareAndSet(false, true)) runCatching { save(error) } }
        finally { next.uncaughtException(thread, error) }
    }
}

/** Heartbeat probes use monotonic uptime. A stale callback cannot clear a newer probe. */
internal class MainThreadWatchdog(private val thresholdMs: Long = 5000) {
    data class Tick(val ping: Long? = null, val stallMs: Long? = null)
    private var token = 0L
    private var pending: Long? = null
    private var reported = false

    @Synchronized fun tick(now: Long, enabled: Boolean): Tick {
        if (!enabled) { pending = null; token++; reported = false; return Tick() }
        if (pending == null) { pending = now; reported = false; return Tick(ping = ++token) }
        val duration = now - pending!!
        if (!reported && duration >= thresholdMs) { reported = true; return Tick(stallMs = duration) }
        return Tick()
    }

    @Synchronized fun acknowledge(ping: Long) { if (ping == token) pending = null }
}

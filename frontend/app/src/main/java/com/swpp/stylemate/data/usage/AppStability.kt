package com.swpp.stylemate.data.usage

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.swpp.stylemate.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class StabilityApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { AppStability.install(this) }
    }
}

/** Device-wide, sanitized diagnostics survive logout; upload still requires a valid login. */
object AppStability {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    @Volatile private var uploader: String? = null
    @Volatile private var foreground = false
    private var activeId: String? = null

    fun setUploader(owner: String?) { uploader = owner }

    internal fun queue(context: Context) = UsageQueue(File(context.noBackupFilesDir, "stability/events.json"),
        maxEvents = 200, batchSize = 5)

    internal fun install(application: Application) {
        val preferences = application.getSharedPreferences("stability", Context.MODE_PRIVATE)
        val installation = preferences.getString("installation", null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString("installation", it).commit())
        }
        val events = StabilityEvents(installation, UUID.randomUUID().toString(), BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT)
        fun save(event: JSONObject, schedule: Boolean = true) {
            queue(application).add(event)
            if (schedule) uploader?.let { UsageMetrics.schedule(application, it) }
        }
        fun active(at: Instant = Instant.now(), schedule: Boolean = true) = synchronized(this) {
            val event = events.active(at)
            if (activeId != event.getString("id")) {
                save(event, schedule)
                activeId = event.getString("id")
            }
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler() ?: Thread.UncaughtExceptionHandler { _, _ ->
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(10)
        }
        Thread.setDefaultUncaughtExceptionHandler(StabilityCrashHandler(previous) { error ->
            // Do not dispatch a coroutine or attempt networking while the process is dying.
            if (foreground) runCatching { active(schedule = false) }
            save(events.crash(error, Instant.now(), foreground), schedule = false)
        })
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                foreground = true
                val at = Instant.now()
                io.launch { runCatching { active(at) } }
            }
            override fun onActivityPaused(activity: Activity) { foreground = false }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        if (Build.VERSION.SDK_INT >= 30) io.launch {
            runCatching { recoverExits(application, installation, ::save) }
            runCatching { application.getSystemService(ActivityManager::class.java).setProcessStateSummary(events.summary()) }
        }
        val handler = Handler(Looper.getMainLooper())
        thread(name = "stability-watchdog", isDaemon = true) {
            val watchdog = MainThreadWatchdog()
            while (!Thread.currentThread().isInterrupted) {
                runCatching {
                    val enabled = foreground && !Debug.isDebuggerConnected() && !Debug.waitingForDebugger()
                    if (foreground) active()
                    val tick = watchdog.tick(SystemClock.uptimeMillis(), enabled)
                    tick.ping?.let { ping -> handler.post { watchdog.acknowledge(ping) } }
                    tick.stallMs?.let { duration ->
                        save(events.event("stall", "watchdog", Instant.now(), true,
                            frames = StabilityEvents.frames(Looper.getMainLooper().thread.stackTrace), duration = duration,
                            identity = UUID.randomUUID().toString()))
                    }
                }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }
    }

    @RequiresApi(30)
    private fun recoverExits(context: Context, installation: String, save: (JSONObject, Boolean) -> Unit) {
        val preferences = context.getSharedPreferences("stability", Context.MODE_PRIVATE)
        val seen = preferences.getStringSet("seen_exits", emptySet()).orEmpty().toMutableSet()
        val manager = context.getSystemService(ActivityManager::class.java)
        for (exit in manager.getHistoricalProcessExitReasons(context.packageName, 0, 32).asReversed()) {
            if (exit.processName != context.packageName) continue
            val kind = when (exit.reason) {
                ApplicationExitInfo.REASON_ANR -> "anr"
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash"
                else -> continue
            }
            val summary = exit.processStateSummary ?: continue
            val reporter = StabilityEvents.fromSummary(installation, summary) ?: continue
            val frames = if (kind == "anr") runCatching {
                exit.traceInputStream?.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0
                    while (size < buffer.size) {
                        val count = input.read(buffer, size, buffer.size - size)
                        if (count <= 0) break
                        size += count
                    }
                    StabilityEvents.traceFrames(String(buffer, 0, size, Charsets.UTF_8))
                }.orEmpty()
            }.getOrDefault(emptyList()) else emptyList()
            val event = reporter.event(kind, "os_exit", Instant.ofEpochMilli(exit.timestamp),
                foreground = exit.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE,
                frames = frames, reason = exit.reason, status = exit.status)
            if (event.getString("id") in seen) continue
            save(event, false)
            seen.add(event.getString("id"))
        }
        preferences.edit().putStringSet("seen_exits", seen.toList().takeLast(64).toSet()).apply()
    }
}

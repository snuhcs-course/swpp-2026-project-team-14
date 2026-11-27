package com.swpp.stylemate.data.usage

import com.swpp.stylemate.data.RoutedServer
import com.swpp.stylemate.data.failure
import com.swpp.stylemate.data.json
import com.swpp.stylemate.data.testAccount
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.UUID

class StabilityEventsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val installation = UUID.randomUUID().toString()
    private val session = UUID.randomUUID().toString()
    private val now = Instant.parse("2026-11-27T14:59:59Z")
    private fun reporter() = StabilityEvents(installation, session, "0.2.2", 35)

    @Test fun crashPersistsBeforeDelegationAndExcludesMessagesAndUnsafeFrames() {
        val queue = UsageQueue(File(temporary.root, "stability.json"))
        val error = IllegalStateException("token=secret; private chat").apply {
            stackTrace = arrayOf(StackTraceElement("com.swpp.Example", "save", "Example.kt", 42),
                StackTraceElement("com.swpp.Example", "save", "https://private/photo", 42))
        }
        var delegated = 0
        val handler = StabilityCrashHandler(Thread.UncaughtExceptionHandler { _, caught ->
            assertSame(error, caught)
            assertEquals(1, UsageQueue(File(temporary.root, "stability.json")).batch().size)
            delegated++
        }) { queue.add(reporter().crash(it, now, true)) }
        repeat(2) { handler.uncaughtException(Thread.currentThread(), error) }
        assertEquals(2, delegated)
        val event = queue.batch().single()
        assertFalse(event.toString().contains("secret"))
        assertFalse(event.toString().contains("private"))
        assertEquals("com.swpp.Example.save(Example.kt:42)", event.getJSONArray("frames").getString(0))
        assertEquals(2, event.getJSONArray("frames").length()) // Unsafe filename is replaced with a code-only location.
    }

    @Test fun storageFailureStillDelegatesFatalException() {
        var delegated = false
        StabilityCrashHandler(Thread.UncaughtExceptionHandler { _, _ -> delegated = true }) {
            throw OutOfMemoryError("disk failed")
        }.uncaughtException(Thread.currentThread(), IllegalStateException())
        assertTrue(delegated)
    }

    @Test fun historicalExitReusesCrashIdentityAndOriginalVersion() {
        val original = reporter()
        val restored = StabilityEvents.fromSummary(installation, original.summary())!!
        val java = original.crash(IllegalStateException(), now, true)
        val os = restored.event("crash", "os_exit", now, true, reason = 4, status = 0)
        assertEquals(java.getString("id"), os.getString("id"))
        assertEquals(session, os.getString("session_id"))
        assertEquals("0.2.2", os.getString("app_version"))
        assertTrue(original.summary().size <= 128)
        assertNull(StabilityEvents.fromSummary(installation, "legacy record".toByteArray()))
    }

    @Test fun activeInstallationIdentityUsesKstDateAndSurvivesProcessRelaunch() {
        val first = reporter().active(now)
        val another = StabilityEvents(installation, UUID.randomUUID().toString(), "0.2.2", 35)
        assertEquals(first.getString("id"), another.active(now.minusSeconds(1)).getString("id"))
        assertNotEquals(first.getString("id"), another.active(now.plusSeconds(1)).getString("id"))
    }

    @Test fun onlyCodeFramesFromMainThreadEnterRecoveredAnr() {
        val trace = """private header token=secret
"main" prio=5
  at com.swpp.Example.wait (Example.kt:12)
  at com.swpp.Example.nativeCall (Native method)
  private exception: password
"worker" prio=4
  at com.swpp.Private.method(Private.kt:1)
"""
        assertEquals(listOf("com.swpp.Example.wait(Example.kt:12)", "com.swpp.Example.nativeCall(NativeMethod)"),
            StabilityEvents.traceFrames(trace))
    }

    @Test fun reportsOneForegroundStallUntilRecoveryThenAcceptsAnother() {
        val watchdog = MainThreadWatchdog()
        val first = watchdog.tick(0, true).ping!!
        assertNull(watchdog.tick(4999, true).stallMs)
        assertEquals(5000L, watchdog.tick(5000, true).stallMs)
        assertNull(watchdog.tick(8000, true).stallMs)
        watchdog.acknowledge(first)
        assertNotNull(watchdog.tick(9000, true).ping)
        assertEquals(5000L, watchdog.tick(14000, true).stallMs)
    }

    @Test fun backgroundOrDebuggerResetsProbeAndStaleCallbacksCannotHideStall() {
        val watchdog = MainThreadWatchdog()
        val old = watchdog.tick(0, true).ping!!
        assertNull(watchdog.tick(6000, false).stallMs)
        assertNull(watchdog.tick(60000, false).ping)
        val fresh = watchdog.tick(60000, true).ping!!
        assertNotEquals(old, fresh)
        watchdog.acknowledge(old)
        assertEquals(5000L, watchdog.tick(65000, true).stallMs)
    }

    @Test fun diagnosticQueueHasSmallBatchesAndBoundedStorage() {
        val file = File(temporary.root, "bounded.json")
        val queue = UsageQueue(file, maxEvents = 8, batchSize = 5)
        repeat(10) { queue.add(JSONObject().put("id", "$it")) }
        assertEquals(listOf("2", "3", "4", "5", "6"), queue.batch().map { it.getString("id") })
        queue.acknowledge(queue.batch().map { it.getString("id") }.toSet())
        assertEquals(listOf("7", "8", "9"), queue.batch().map { it.getString("id") })
    }

    @Test fun stabilityUploadRetriesWithoutLosingOrChangingReports() = runBlocking {
        val server = RoutedServer()
        try {
            val queue = UsageQueue(File(temporary.root, "upload.json"), batchSize = 5)
            val event = reporter().crash(IllegalStateException("private"), now, true)
            queue.add(event)
            val id = event.getString("id")
            server.on("POST /api/usage/stability/", failure(503, "UNAVAILABLE"), json("""{"accepted":["$id"]}"""))
            val account = testAccount(server.baseUrl)
            try { UsageUploadWorker.upload(queue, account, "/api/usage/stability/"); fail() }
            catch (_: com.swpp.stylemate.data.AuthException) { }
            assertEquals(1, queue.batch().size)
            UsageUploadWorker.upload(queue, account, "/api/usage/stability/")
            assertTrue(queue.batch().isEmpty())
            val requests = server.requests("POST /api/usage/stability/")
            assertEquals(requests[0].toString(), requests[1].toString())
        } finally { server.shutdown() }
    }
}

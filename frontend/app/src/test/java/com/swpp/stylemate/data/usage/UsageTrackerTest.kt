package com.swpp.stylemate.data.usage

import com.swpp.stylemate.data.RoutedServer
import com.swpp.stylemate.data.failure
import com.swpp.stylemate.data.json
import com.swpp.stylemate.data.testAccount
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class UsageTrackerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun usesMonotonicTimeAndEmitsOnlyOneTerminalOutcome() {
        val events = mutableListOf<JSONObject>()
        var elapsed = 100L
        var wall = Instant.parse("2026-11-24T00:00:00Z")
        val tracker = UsageTracker(events::add, elapsed = { elapsed }, now = { wall })
        val attempt = tracker.start("garment_registration", "wardrobe")
        elapsed += 1234
        wall = wall.minusSeconds(3600)
        attempt.success()
        attempt.failure("NETWORK")
        attempt.cancel()
        assertEquals(listOf("started", "finished"), events.map { it.getString("phase") })
        assertEquals(1234L, events.last().getLong("duration_ms"))
        assertEquals("success", events.last().getString("outcome"))
        assertTrue(events.first().isNull("duration_ms"))
    }

    @Test fun resumedFittingSeparatesGenerationFromDisplayAndReusesEventIdentity() {
        val events = mutableListOf<JSONObject>()
        var elapsed = 0L
        val tracker = UsageTracker(events::add, elapsed = { elapsed })
        val checkpoint = tracker.start("virtual_fitting", "virtual").checkpoint
        elapsed = 40000
        tracker.resume(checkpoint).generated()
        elapsed = 60000
        tracker.resume(checkpoint).success()
        tracker.resume(checkpoint).success()
        assertEquals(40000, events[1].getInt("duration_ms"))
        assertEquals(60000, events[2].getInt("duration_ms"))
        assertEquals(events[2].getString("id"), events[3].getString("id"))
        assertEquals(1, events.map { it.getString("attempt_id") }.distinct().size)
    }

    @Test fun rebootDoesNotProduceAnInventedDuration() {
        val events = mutableListOf<JSONObject>()
        var boot = 1
        val tracker = UsageTracker(events::add, elapsed = { 1000L }, boot = { boot })
        val checkpoint = tracker.start("virtual_fitting", "gallery").checkpoint
        boot = 2
        tracker.resume(checkpoint).success()
        assertTrue(events.last().isNull("duration_ms"))
        assertTrue(events.last().getJSONObject("details").getBoolean("clock_reset"))
    }

    @Test fun collectorFailureDoesNotFailTheOperation() {
        val tracker = UsageTracker(emit = { error("disk unavailable") })
        tracker.start("chat_request", "chat").failure("HTTP_500")
    }

    @Test fun queueSurvivesRecreationDeduplicatesAndRemovesOnlyAcknowledgedEvents() {
        val file = File(temporary.root, "user-a/events.json")
        val queue = UsageQueue(file)
        val first = JSONObject().put("id", "one")
        queue.add(first); queue.add(first)
        queue.add(JSONObject().put("id", "two"))
        assertEquals(2, UsageQueue(file).batch().size)
        queue.acknowledge(setOf("one"))
        assertEquals("two", UsageQueue(file).batch().single().getString("id"))
        assertTrue(UsageQueue(File(temporary.root, "user-b/events.json")).batch().isEmpty())
    }

    @Test fun prolongedOfflineUseKeepsABoundedRecentQueue() {
        val file = File(temporary.root, "events.json")
        file.writeText(JSONArray((0 until UsageQueue.MAX_EVENTS).map { JSONObject().put("id", "$it") }).toString())
        UsageQueue(file).add(JSONObject().put("id", "newest"))
        assertEquals("1", UsageQueue(file).batch().first().getString("id"))
        assertEquals(UsageQueue.MAX_EVENTS, JSONArray(file.readText()).length())
    }

    @Test fun uploadFailureKeepsEventsAndRetrySendsTheSameId() = runBlocking {
        val server = RoutedServer()
        try {
            val queue = UsageQueue(File(temporary.root, "events.json"))
            queue.add(JSONObject().put("id", "event-one"))
            server.on("POST /api/usage/events/", failure(503, "UNAVAILABLE"), json("""{"accepted":["event-one"]}"""))
            val account = testAccount(server.baseUrl)
            try { UsageUploadWorker.upload(queue, account); fail("Expected failed upload") } catch (_: com.swpp.stylemate.data.AuthException) {}
            assertEquals(1, queue.batch().size)
            UsageUploadWorker.upload(queue, account)
            assertTrue(queue.batch().isEmpty())
            val requests = server.requests("POST /api/usage/events/")
            assertEquals(requests[0].toString(), requests[1].toString())
        } finally { server.shutdown() }
    }

    @Test fun anIncompleteAcknowledgementDoesNotDeletePendingEvents() = runBlocking {
        val server = RoutedServer()
        try {
            val queue = UsageQueue(File(temporary.root, "events.json"))
            queue.add(JSONObject().put("id", "event-one"))
            server.on("POST /api/usage/events/", json("""{"accepted":[]}"""))
            try { UsageUploadWorker.upload(queue, testAccount(server.baseUrl)); fail("Expected incomplete acknowledgement") }
            catch (_: IllegalStateException) {}
            assertEquals(1, queue.batch().size)
        } finally { server.shutdown() }
    }
}

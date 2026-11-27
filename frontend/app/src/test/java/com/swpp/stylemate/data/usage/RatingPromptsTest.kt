// AI-generated with Claude Code (Claude Opus 5.5), 2026-11-26, review pending (Kihwan Kim)
package com.swpp.stylemate.data.usage

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID

class RatingPromptsTest {
    private val owner = UUID.randomUUID().toString()

    @After fun reset() {
        RatingPrompts.pending.value?.let(RatingPrompts::drop)
        RatingPrompts.close()
        RatingPrompts.setPaused(false)
    }

    @Test fun dragPositionBecomesHalfStepScore() {
        assertEquals(0.0, RatingPrompts.halfStep(0f), 0.0)
        assertEquals(0.0, RatingPrompts.halfStep(-0.3f), 0.0)
        assertEquals(0.5, RatingPrompts.halfStep(0.1f), 0.0)
        assertEquals(2.5, RatingPrompts.halfStep(0.52f), 0.0)
        assertEquals(3.0, RatingPrompts.halfStep(0.58f), 0.0)
        assertEquals(5.0, RatingPrompts.halfStep(1f), 0.0)
        assertEquals(5.0, RatingPrompts.halfStep(1.4f), 0.0)
        for (i in 0..100) assertEquals(0.0, RatingPrompts.halfStep(i / 100f) * 2 % 1, 0.0)
    }

    @Test fun submittedEventMatchesServerContract() {
        val event = RatingPrompts.event(RatingTask.VIRTUAL_FITTING, RatingAnswer(3.5, 4.0, 0.0), noSeen = true,
            now = Instant.parse("2026-12-07T01:30:00Z"))
        assertEquals(setOf("id", "attempt_id", "session_id", "name", "phase", "outcome", "occurred_at", "duration_ms",
            "error_code", "details"), event.keys().asSequence().toSet())
        assertEquals("task_rating", event.getString("name"))
        assertEquals("finished", event.getString("phase"))
        assertEquals("success", event.getString("outcome"))
        assertTrue(event.isNull("duration_ms"))
        assertEquals("", event.getString("error_code"))
        assertEquals("2026-12-07T01:30:00Z", event.getString("occurred_at"))
        listOf("id", "attempt_id", "session_id").forEach { UUID.fromString(event.getString(it)) }
        val details = event.getJSONObject("details")
        assertEquals(setOf("task", "submitted", "wait", "quality", "convenience", "no_seen"), details.keys().asSequence().toSet())
        assertEquals("virtual_fitting", details.getString("task"))
        assertTrue(details.getBoolean("submitted"))
        assertEquals(3.5, details.getDouble("wait"), 0.0)
        assertEquals(4.0, details.getDouble("quality"), 0.0)
        assertEquals(0.0, details.getDouble("convenience"), 0.0)
        assertTrue(details.getBoolean("no_seen"))
    }

    @Test fun skipSendsZerosWithSubmittedFalse() {
        val details = RatingPrompts.event(RatingTask.RECOMMENDATION, null, noSeen = false).getJSONObject("details")
        assertFalse(details.getBoolean("submitted"))
        assertEquals("recommendation", details.getString("task"))
        assertEquals(0.0, details.getDouble("wait"), 0.0)
    }

    @Test fun onlyOneRequestAtATimeAndCancelOnlyAffectsPending() {
        RatingPrompts.request(RatingTask.RECOMMENDATION, owner, showAfterMs = 5_000, now = 1_000)
        RatingPrompts.request(RatingTask.VIRTUAL_FITTING, owner, now = 1_000)
        val pending = checkNotNull(RatingPrompts.pending.value)
        assertEquals(RatingTask.RECOMMENDATION, pending.task)
        RatingPrompts.cancel(RatingTask.VIRTUAL_FITTING)
        assertEquals(pending, RatingPrompts.pending.value)
        RatingPrompts.cancel(RatingTask.RECOMMENDATION)
        assertNull(RatingPrompts.pending.value)
    }

    @Test fun shownPromptBlocksNewRequestsAndIgnoresCancel() {
        RatingPrompts.request(RatingTask.GARMENT_REGISTRATION, owner, now = 1_000)
        val request = checkNotNull(RatingPrompts.pending.value)
        assertTrue(RatingPrompts.show(request))
        assertEquals(request, RatingPrompts.visible.value)
        assertFalse(RatingPrompts.show(request))
        RatingPrompts.cancel(RatingTask.GARMENT_REGISTRATION)
        assertEquals(request, RatingPrompts.visible.value)
        RatingPrompts.request(RatingTask.RECOMMENDATION, owner, now = 2_000)
        assertNull(RatingPrompts.pending.value)
        RatingPrompts.close()
        assertNull(RatingPrompts.visible.value)
    }

    @Test fun pausedScreensDoNotRequestAndStaleRequestsExpire() {
        RatingPrompts.setPaused(true)
        RatingPrompts.request(RatingTask.GARMENT_REGISTRATION, owner, now = 1_000)
        assertNull(RatingPrompts.pending.value)
        RatingPrompts.setPaused(false)
        RatingPrompts.request(RatingTask.GARMENT_REGISTRATION, owner, showAfterMs = 2_000, now = 1_000)
        val request = checkNotNull(RatingPrompts.pending.value)
        assertFalse(RatingPrompts.expired(request, now = 1_000 + 2_000 + RatingPrompts.EXPIRE_MS))
        assertTrue(RatingPrompts.expired(request, now = 1_001 + 2_000 + RatingPrompts.EXPIRE_MS))
    }
}

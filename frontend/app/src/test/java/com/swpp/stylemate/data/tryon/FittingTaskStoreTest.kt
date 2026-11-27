package com.swpp.stylemate.data.tryon

import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitGarment
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FittingTaskStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = "a".repeat(64)
    private val outfit = Outfit("outfit", "key", null,
        listOf(OutfitGarment("item", "top", "니트", "local:item", "")), emptyList(), true, "코디", null, "")

    @Test fun uploadedPhotoIsDeletedAndServerJobSurvivesProcessRecreation() {
        val store = FittingTaskStore(temporary.root)
        store.create(key, "guest", "virtual:male_170_regular:v1", outfit, byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(1, 2), store.person(key))
        val job = TryOnJob("12345678-1234-1234-1234-123456789012", "secret-token")
        store.submitted(key, job)
        val reopened = FittingTaskStore(temporary.root)
        val saved = reopened.read(key)!!
        assertFalse(temporary.root.resolve("$key.photo").exists())
        assertEquals(job, FittingTaskStore.job(saved))
        assertEquals("running", saved.getString("state"))
        assertEquals(outfit.items, FittingTaskStore.outfit(saved).items)
        assertEquals(outfit.id, FittingTaskStore.outfit(saved).id)
    }

    @Test fun completionRetainsTheResultLocatorAndRemovesCapabilityCredentials() {
        val store = FittingTaskStore(temporary.root)
        store.create(key, "guest", "gallery", outfit, byteArrayOf(1))
        store.submitted(key, TryOnJob("12345678-1234-1234-1234-123456789012", "secret-token"))
        store.finish(key)
        val saved = store.read(key)!!
        assertEquals("completed", saved.getString("state"))
        assertEquals("gallery", saved.getString("source"))
        assertNull(FittingTaskStore.job(saved))
        assertFalse(saved.toString().contains("secret-token"))
        assertFalse(temporary.root.resolve("$key.photo").exists())
    }

    @Test fun failureBeforeUploadAlsoDeletesTheTemporaryPhoto() {
        val store = FittingTaskStore(temporary.root)
        store.create(key, "guest", "gallery", outfit, byteArrayOf(1))
        store.finish(key, "서버 연결 실패")
        assertEquals("failed", store.read(key)!!.getString("state"))
        assertFalse(temporary.root.resolve("$key.photo").exists())
    }

    @Test fun failedMetadataWriteDoesNotLeaveAnUnscheduledPhotoOnDisk() {
        val store = FittingTaskStore(temporary.root)
        temporary.root.resolve("$key.tmp").mkdir()
        try { store.create(key, "guest", "gallery", outfit, byteArrayOf(1)); fail("Expected metadata write failure") }
        catch (_: java.io.IOException) {}
        assertNull(store.read(key))
        assertFalse(temporary.root.resolve("$key.photo").exists())
        assertFalse(temporary.root.resolve("$key.tmp").exists())
    }

    @Test fun notificationKeysCannotReadOutsideThePrivateTaskDirectory() {
        try { FittingTaskStore(temporary.root).read("../auth-session"); fail("Expected invalid task key") }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun usageCheckpointSurvivesBackgroundCompletion() {
        val checkpoint = com.swpp.stylemate.data.usage.UsageTracker().start("virtual_fitting", "virtual").checkpoint
        val store = FittingTaskStore(temporary.root)
        store.create(key, "owner", "virtual:model", outfit, byteArrayOf(1), checkpoint)
        store.finish(key)
        assertEquals(checkpoint.toString(), FittingTaskStore(temporary.root).read(key)!!.getJSONObject("usage").toString())
    }

    @Test fun theCardShowsAnUnopenedResultBeforeWorkStillRunning() {
        val store = FittingTaskStore(temporary.root)
        val first = "b".repeat(64); val second = "c".repeat(64)
        store.create(first, "me", "gallery", outfit, byteArrayOf(1))
        store.create(second, "me", "gallery", outfit, byteArrayOf(1))
        store.create(key, "someone else", "gallery", outfit, byteArrayOf(1))
        assertEquals("only my tasks", 2, store.all("me").size)
        store.finish(first)
        val now = System.currentTimeMillis()
        val summary = FittingTaskStore.cardSummary(store.all("me"), now)!!
        assertEquals("a finished result is not hidden behind running work", first, summary.shown.first)
        assertEquals(1, summary.running); assertEquals(1, summary.finished)
        store.dismissCard(first)
        assertEquals("then the one still running", second, FittingTaskStore.cardTask(store.all("me"), now)?.first)
        store.finish(second, "실패")
        assertEquals(second, FittingTaskStore.cardTask(store.all("me"), now + 1_000)?.first)
        assertNull("a result opened long ago is not offered", FittingTaskStore.cardTask(store.all("me"), now + FittingTaskStore.CARD_RECENT_MS + 60_000))
    }

    @Test fun withSeveralRunningTheOldestIsShownAndAllAreCounted() {
        fun task(state: String, created: Long, finished: Long? = null) = JSONObject().put("owner", "me").put("state", state)
            .put("created_at", created).apply { if (finished != null) put("finished_at", finished) }
        val tasks = listOf("a".repeat(64) to task("running", 3_000), "b".repeat(64) to task("queued", 5_000),
            "c".repeat(64) to task("running", 1_000))
        val summary = FittingTaskStore.cardSummary(tasks, 10_000)!!
        assertEquals("the one started first is closest to done", "c".repeat(64), summary.shown.first)
        assertEquals(3, summary.running); assertEquals(0, summary.finished)
        val withDone = tasks + ("d".repeat(64) to task("completed", 500, 9_000)) + ("e".repeat(64) to task("completed", 600, 9_500))
        val next = FittingTaskStore.cardSummary(withDone, 10_000)!!
        assertEquals("the newest result first", "e".repeat(64), next.shown.first)
        assertEquals(2, next.finished); assertEquals(3, next.running)
    }

    @Test fun tasksFromBeforeTheCardNeverShow() {
        val old = org.json.JSONObject().put("owner", "me").put("state", "completed")
        assertNull(FittingTaskStore.cardTask(listOf(key to old), System.currentTimeMillis()))
    }

    @Test fun theGalleryPreviewStaysOnlyUntilTheFittingIsSeen() {
        val store = FittingTaskStore(temporary.root)
        store.create(key, "me", "gallery", outfit, byteArrayOf(1, 2))
        store.savePreview(key, byteArrayOf(9))
        store.submitted(key, TryOnJob("12345678-1234-1234-1234-123456789012", "secret-token"))
        assertFalse("the uploaded photo is still removed", temporary.root.resolve("$key.photo").exists())
        assertArrayEquals("the small preview is kept for the loading screen and the card", byteArrayOf(9), store.preview(key))
        store.finish(key)
        store.pruneStalePreviews(System.currentTimeMillis())
        assertArrayEquals("still there while the card offers the result", byteArrayOf(9), store.preview(key))
        store.dismissCard(key)
        assertNull("gone once the result has been seen", store.preview(key))
    }

    @Test fun stalePreviewsAndThoseOfANewFittingAreRemoved() {
        val store = FittingTaskStore(temporary.root)
        store.create(key, "me", "gallery", outfit, byteArrayOf(1))
        store.savePreview(key, byteArrayOf(9))
        store.create(key, "me", "gallery", outfit, byteArrayOf(2))
        assertNull("a new fitting of the outfit replaces the earlier photo", store.preview(key))
        store.savePreview(key, byteArrayOf(9))
        store.finish(key)
        store.pruneStalePreviews(System.currentTimeMillis() + FittingTaskStore.CARD_RECENT_MS + 60_000)
        assertNull("not kept after the card stops offering the result", store.preview(key))
    }
}

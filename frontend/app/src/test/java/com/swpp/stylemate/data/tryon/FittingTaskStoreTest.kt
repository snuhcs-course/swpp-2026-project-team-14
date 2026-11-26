package com.swpp.stylemate.data.tryon

import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitGarment
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

    @Test fun theCardFollowsTheRunningFittingThenARecentUnopenedResult() {
        val store = FittingTaskStore(temporary.root)
        val first = "b".repeat(64); val second = "c".repeat(64)
        store.create(first, "me", "gallery", outfit, byteArrayOf(1))
        store.create(second, "me", "gallery", outfit, byteArrayOf(1))
        store.create(key, "someone else", "gallery", outfit, byteArrayOf(1))
        val now = System.currentTimeMillis()
        assertEquals("only my tasks", 2, store.all("me").size)
        store.finish(first)
        assertEquals("a running fitting comes first", second, FittingTaskStore.cardTask(store.all("me"), now)?.first)
        store.finish(second, "실패")
        assertEquals("then the newest finished one", second, FittingTaskStore.cardTask(store.all("me"), now + 1_000)?.first)
        store.dismissCard(second)
        assertEquals(first, FittingTaskStore.cardTask(store.all("me"), now + 1_000)?.first)
        assertNull("a result opened long ago is not offered", FittingTaskStore.cardTask(store.all("me"), now + FittingTaskStore.CARD_RECENT_MS + 60_000))
    }

    @Test fun tasksFromBeforeTheCardNeverShow() {
        val old = org.json.JSONObject().put("owner", "me").put("state", "completed")
        assertNull(FittingTaskStore.cardTask(listOf(key to old), System.currentTimeMillis()))
    }
}

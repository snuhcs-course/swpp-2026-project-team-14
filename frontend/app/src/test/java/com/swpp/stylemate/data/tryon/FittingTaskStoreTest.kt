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
}

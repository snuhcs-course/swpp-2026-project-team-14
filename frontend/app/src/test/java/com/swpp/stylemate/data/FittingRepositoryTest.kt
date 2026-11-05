package com.swpp.stylemate.data

import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitGarment
import com.swpp.stylemate.data.tryon.FittingRepository
import com.swpp.stylemate.data.tryon.FittingNotificationSettings
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.UUID

class FittingRepositoryTest {
    @get:Rule val temporary = TemporaryFolder(File("build/fitting-tests").apply { mkdirs() })
    private val server = MockWebServer()
    private val garmentId = UUID.randomUUID().toString()
    private lateinit var account: AccountAccess
    private lateinit var store: LocalUserStore
    @Before fun setup() {
        server.start()
        account = testAccount(server.url("/").toString())
        store = LocalUserStore.inDirectory(temporary.root, account.userId)
    }
    @After fun close() { server.shutdown() }
    private fun row(saved: Boolean = true) = JSONObject()
        .put("id", garmentId).put("saved", saved).put("attributes", JSONObject().put("category", "top"))
        .put("image_url", "/api/wardrobe/items/$garmentId/image/").put("image_source", "worn_crop")
    private fun outfit(ids: List<String> = listOf(garmentId)) = Outfit(
        "synthetic-outfit", "synthetic", null,
        ids.map { OutfitGarment(it, "top", "synthetic", "/api/wardrobe/items/$it/image/", "") },
        emptyList(), false, "", null, "",
    )
    private fun repository() = FittingRepository(store, account)

    @Test fun notificationChoicePersistsAcrossOutfitsAndReopening() = runBlocking {
        assertTrue(repository().notificationSettings().enabled)
        repository().saveNotificationSettings(FittingNotificationSettings(true))
        assertTrue(repository().notificationSettings().enabled)
        repository().saveNotificationSettings(FittingNotificationSettings(false))
        assertFalse(repository().notificationSettings().enabled)
        assertEquals(0, server.requestCount)
    }
    @Test fun missingNotificationChoiceDefaultsOnWhileExplicitOffSurvivesReopening() = runBlocking {
        assertTrue(FittingNotificationSettings().enabled)
        assertTrue(FittingNotificationSettings.decode(null).enabled)
        assertTrue(FittingNotificationSettings.decode(JSONObject()).enabled)
        repository().saveNotificationSettings(FittingNotificationSettings(false))
        val reopened = FittingRepository(LocalUserStore.inDirectory(temporary.root, account.userId), account)
        assertFalse(reopened.notificationSettings().enabled)
        assertEquals(0, server.requestCount)
    }
    @Test fun validationUsesAuthenticatedFreshRecordInsteadOfCache() = runBlocking {
        store.cacheGarment(row().put("image_source", "garment_photo"))
        server.enqueue(MockResponse().setBody(row().toString()))
        val records = repository().validate(outfit())
        assertEquals("worn_crop", records.getValue(garmentId).getString("image_source"))
        val request = server.takeRequest()
        assertEquals("/api/wardrobe/items/$garmentId/", request.path)
        assertEquals("Bearer $TEST_TOKEN", request.getHeader("Authorization"))
    }
    @Test fun missingServerGarmentNeverFallsBackToCache() = runBlocking {
        store.cacheGarment(row())
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        try { repository().validate(outfit()); fail() }
        catch (error: AuthException) { assertEquals("HTTP_404", error.code) }
    }
    @Test fun fittingDownloadsRegisteredPhotoBytesWithoutThumbnailDecoding() = runBlocking {
        val bytes = ByteArray(900_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        assertArrayEquals(bytes, com.swpp.stylemate.data.wardrobe.WardrobeRepository.fittingImage(
            "/api/wardrobe/items/$garmentId/image/", account, server.url("/").toString()))
        assertEquals("Bearer $TEST_TOKEN", server.takeRequest().getHeader("Authorization"))
    }
    @Test fun unsavedMismatchedAndRecategorizedGarmentsAreRejected() = runBlocking {
        for (record in listOf(row(false), row().put("id", UUID.randomUUID().toString()),
            row().apply { getJSONObject("attributes").put("category", "bottom") })) {
            server.enqueue(MockResponse().setBody(record.toString()))
            try { repository().validate(outfit()); fail() }
            catch (_: IllegalStateException) {}
        }
    }
    @Test fun invalidOrDuplicateGarmentsNeverReachTheServer() = runBlocking {
        for (ids in listOf(emptyList(), listOf("../other-account"), listOf(garmentId, garmentId))) {
            try { repository().validate(outfit(ids)); fail() }
            catch (_: IllegalArgumentException) {}
        }
        assertEquals(0, server.requestCount)
    }
    @Test fun resultSurvivesReopeningAndRenamingWithoutNetwork() = runBlocking {
        repository().saveResult(outfit(), byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), repository().result(outfit().copy(name = "renamed", saved = true)))
        assertEquals("gallery", repository().latestSource(outfit()))
        assertEquals(0, server.requestCount)
    }
    @Test fun changedOutfitsGarmentsAndSourcesNeverReuseAnotherResult() = runBlocking {
        val fitting = repository()
        fitting.saveResult(outfit(), byteArrayOf(1))
        assertNull(fitting.result(outfit().copy(id = "another")))
        assertNull(fitting.result(outfit(listOf(UUID.randomUUID().toString()))))
        assertNull(fitting.result(outfit().copy(items = outfit().items.map { it.copy(imageUrl = "replacement") })))
        fitting.saveResult(outfit(), byteArrayOf(2), "virtual:male_170_regular:v1")
        assertArrayEquals(byteArrayOf(1), fitting.result(outfit()))
        assertArrayEquals(byteArrayOf(2), fitting.result(outfit(), "virtual:male_170_regular:v1"))
        assertNull(fitting.result(outfit(), "virtual:female_170_regular:v1"))
    }
    @Test fun accountSwitchIsolatesResultsAndPreferencesAndRejectsStaleAccess() = runBlocking {
        val now = Instant.parse("2026-11-04T00:00:00Z")
        val sessionStore = object : AuthSessionStore {
            var saved: String? = null
            override fun read() = saved
            override fun write(value: String) { saved = value }
            override fun clear() { saved = null }
        }
        val auth = AuthRepository(server.url("/").toString(), sessionStore, now = { now })
        suspend fun signIn(id: String) {
            val user = AccountUser(id, "member_1", null, StyleProfile())
            val token = if (id == account.userId) TEST_TOKEN else "b".repeat(43)
            server.enqueue(MockResponse().setBody(AuthSession(token, now.plusSeconds(86400), user).json().toString()))
            auth.authenticate("member_1", "password_123")
        }
        signIn(account.userId)
        val first = FittingRepository(store, checkNotNull(auth.account()))
        first.saveResult(outfit(), byteArrayOf(1))
        first.saveNotificationSettings(FittingNotificationSettings(false))
        signIn(UUID.randomUUID().toString())
        val second = FittingRepository(store, checkNotNull(auth.account()))
        assertNull(second.result(outfit()))
        assertTrue(second.notificationSettings().enabled)
        try { first.result(outfit()); fail() }
        catch (error: AuthException) { assertEquals("SESSION_CHANGED", error.code) }
        try { first.saveResult(outfit(), byteArrayOf(2)); fail() }
        catch (error: AuthException) { assertEquals("SESSION_CHANGED", error.code) }
    }
}

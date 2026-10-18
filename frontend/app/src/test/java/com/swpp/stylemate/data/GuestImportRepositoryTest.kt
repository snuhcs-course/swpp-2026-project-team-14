// AI-generated with Codex, 2026-10-16
package com.swpp.stylemate.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
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

class GuestImportRepositoryTest {
    @get:Rule val temporary = TemporaryFolder(File("build/local-store-tests").apply { mkdirs() })
    private val server = MockWebServer()
    private lateinit var auth: AuthRepository
    private lateinit var guest: LocalUserStore
    private lateinit var importer: GuestImportRepository
    private val userId = UUID.randomUUID().toString()

    @Before fun setup() = runBlocking {
        server.start()
        auth = AuthRepository(server.url("/").toString(), object : AuthSessionStore {
            var value: String? = null
            override fun read() = value
            override fun write(value: String) { this.value = value }
            override fun clear() { value = null }
        })
        signIn(userId)
        guest = LocalUserStore.legacyGuestInDirectory(temporary.root)
        importer = GuestImportRepository(guest, auth.account()!!)
    }
    @After fun close() { server.shutdown() }

    private suspend fun signIn(id: String) {
        val user = StyleProfile().json().put("id", id).put("username", "member_1").put("body_profile", JSONObject.NULL)
        val response = JSONObject().put("token", UUID.randomUUID().toString().replace("-", "") + "a".repeat(11))
            .put("expires_at", Instant.now().plusSeconds(86400).toString()).put("user", user)
        server.enqueue(MockResponse().setBody(response.toString()))
        auth.authenticate("member_1", "password_123")
        server.takeRequest()
    }

    private fun garment(saved: Boolean = true): String {
        val row = guest.createGarment(JSONObject().put("attributes", JSONObject().put("name", "로컬 티셔츠")
            .put("category", "top").put("styles", JSONArray())), byteArrayOf(1, 2, 3))
        val id = row.getString("id")
        if (saved) guest.saveGarment(id, JSONObject().put("notes", "메모"))
        return id
    }

    private fun planResponse(snapshot: GuestImportSnapshot, conflict: Boolean = false): JSONObject {
        val result = JSONObject().put("profile", JSONObject()).put("garments", JSONObject()).put("outfits", JSONObject())
        snapshot.records.forEach { record -> result.getJSONObject(record.kind).put(record.id,
            JSONObject().put("version", if (conflict) "version-1" else JSONObject.NULL).put("conflict", conflict)) }
        return result
    }

    @Test fun emptyGuestDoesNotImportImplicitDefaultsOrStartNetworkRequests() = runBlocking {
        assertTrue(importer.snapshot().records.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test fun snapshotIncludesIndependentProfileAndSavedGarmentsOnly() = runBlocking {
        guest.put("body", JSONObject().put("height_cm", 175).put("measurements", JSONArray()))
        guest.saveStyle(StyleProfile(preferredStyles = listOf("minimal")))
        val saved = garment()
        garment(false)
        val snapshot = importer.snapshot()
        assertEquals(3, snapshot.records.size)
        assertEquals(2, snapshot.count("profile"))
        assertEquals(saved, snapshot.records.single { it.kind == "garments" }.id)
        assertEquals(setOf("attributes", "dimensions", "notes", "labels"), snapshot.records.last().value.keys().asSequence().toSet())
        assertEquals(1, server.requestCount)
    }

    @Test fun uploadIsAuthenticatedMultipartAndOnlyConfirmedResultIsRecorded() = runBlocking {
        val id = garment()
        val snapshot = importer.snapshot()
        server.enqueue(MockResponse().setBody(planResponse(snapshot).toString()))
        val plan = importer.preview(snapshot)
        assertEquals("/api/users/me/import/preview/", server.takeRequest().path)
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        try { importer.importRecord(snapshot.records.single(), plan, false); fail() } catch (_: AuthException) {}
        assertEquals(1, importer.snapshot().records.size)
        server.takeRequest()
        server.enqueue(MockResponse().setBody("{\"status\":\"unchanged\"}"))
        importer.importRecord(snapshot.records.single(), plan, false)
        val request = server.takeRequest()
        assertEquals("/api/users/me/import/garments/$id/", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        assertTrue(request.getHeader("Authorization")!!.startsWith("Bearer "))
        assertTrue(request.body.readUtf8().contains("filename=\"garment.jpg\""))
        assertTrue(GuestImportRepository(LocalUserStore.legacyGuestInDirectory(temporary.root), auth.account()!!).snapshot().records.isEmpty())
        assertEquals(1, guest.garments().size)
        assertArrayEquals(byteArrayOf(1, 2, 3), guest.image("local:$id"))
    }

    @Test fun signedInImportStartsHiddenWithLocalDataAndDoesNotSendRequests() = runBlocking {
        garment()
        val model = com.swpp.stylemate.ui.profile.GuestImportViewModel(importer)
        assertEquals(com.swpp.stylemate.ui.profile.GuestImportStage.HIDDEN, model.state.value.stage)
        model.close()
        assertEquals(1, importer.snapshot().records.size)
        assertEquals(1, server.requestCount)
    }

    @Test fun receiptsArePerAccountAndKeepDecisionDoesNotDeleteLocalData() = runBlocking {
        garment()
        val snapshot = importer.snapshot()
        val plan = GuestImportPlan(snapshot, snapshot.records.associate { it.key to "v1" }, snapshot.records)
        server.enqueue(MockResponse().setBody("{\"status\":\"kept\"}"))
        importer.importRecord(snapshot.records.single(), plan, false)
        server.takeRequest()
        assertTrue(importer.snapshot().records.isEmpty())
        assertEquals(1, importer.snapshot(includeKept = true).records.size)
        assertEquals(1, guest.garments().size)
        signIn(UUID.randomUUID().toString())
        assertEquals(1, GuestImportRepository(guest, auth.account()!!).snapshot().records.size)
    }

    @Test fun reorderedJsonKeysDoNotReimportHandledProfile() = runBlocking {
        guest.put("body", JSONObject("""{"height_cm":175,"gender":"male","measurements":[]}"""))
        val snapshot = importer.snapshot()
        val record = snapshot.records.single()
        guest.recordImport(userId, record.key, record.fingerprint, "imported")
        guest.put("body", JSONObject("""{"measurements":[],"gender":"male","height_cm":175}"""))
        assertTrue(importer.snapshot().records.isEmpty())
    }

    @Test fun changedImageRequiresFreshSnapshotAndCannotCompleteOldRecord() = runBlocking {
        val id = garment()
        val snapshot = importer.snapshot()
        val record = snapshot.records.single()
        File(temporary.root, "guest/garments/$id.jpg").writeBytes(byteArrayOf(4, 5, 6))
        try { importer.importRecord(record, GuestImportPlan(snapshot, mapOf(record.key to null), emptyList()), false); fail() }
        catch (_: IllegalStateException) {}
        assertEquals(1, server.requestCount)
        assertEquals(1, importer.snapshot().records.size)
    }

    @Test fun missingPhotoCannotUploadOrMarkTheGarmentComplete() = runBlocking {
        val id = garment()
        File(temporary.root, "guest/garments/$id.jpg").delete()
        val snapshot = importer.snapshot()
        try { importer.importRecord(snapshot.records.single(), GuestImportPlan(snapshot, mapOf("garments:$id" to null), emptyList()), false); fail() }
        catch (error: IllegalStateException) { assertTrue(error.message!!.contains("옷 사진")) }
        assertEquals(1, server.requestCount)
        assertEquals(1, importer.snapshot().records.size)
    }

    @Test fun previewSplitsMoreThanOneHundredIdsWithoutLosingConflicts() = runBlocking {
        val records = (0..100).map { GuestImportRecord("garments", UUID.randomUUID().toString(), JSONObject(), "hash-$it") }
        val snapshot = GuestImportSnapshot(records)
        server.enqueue(MockResponse().setBody(planResponse(GuestImportSnapshot(records.take(100))).toString()))
        server.enqueue(MockResponse().setBody(planResponse(GuestImportSnapshot(records.drop(100)), conflict = true).toString()))
        val plan = importer.preview(snapshot)
        assertEquals(100, JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("garments").length())
        assertEquals(1, JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("garments").length())
        assertEquals(101, plan.versions.size)
        assertEquals(listOf(records.last()), plan.conflicts)
    }
}

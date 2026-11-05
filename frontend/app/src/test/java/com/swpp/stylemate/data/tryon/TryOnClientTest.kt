package com.swpp.stylemate.data.tryon

import kotlinx.coroutines.runBlocking
import com.swpp.stylemate.data.testAccount
import kotlinx.coroutines.CancellationException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class TryOnClientTest {
    private val server = MockWebServer()
    private val id = UUID.randomUUID().toString()
    private lateinit var client: TryOnClient
    @Before fun setup() {
        server.start()
        client = TryOnClient(testAccount(server.url("/").toString()), server.url("/").toString())
    }
    @After fun close() { server.shutdown() }
    private fun garment() = listOf(TryOnGarment("tops", byteArrayOf(3, 4)))
    private fun accepted() = MockResponse().setResponseCode(202).setBody("""{"id":"$id","token":"private-job-token"}""")

    @Test fun transientWorkerDisconnectResumesTheAcceptedJobWithoutResubmission() = runBlocking {
        server.enqueue(accepted())
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("""{"status":"completed","inputs_deleted":true}"""))
        server.enqueue(MockResponse().setBody("generated-jpeg"))
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals("generated-jpeg", String(client.fit(byteArrayOf(1), garment(), true) {}))
        assertEquals("POST", server.takeRequest().method)
        repeat(3) { assertEquals("GET", server.takeRequest().method) }
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test fun expiredLoginDuringPollingStopsWithoutRetrying() = runBlocking {
        server.enqueue(accepted())
        server.enqueue(MockResponse().setResponseCode(401))
        try { client.fit(byteArrayOf(1), garment(), true) {}; fail() }
        catch (error: com.swpp.stylemate.data.AuthException) { assertEquals("AUTHENTICATION_REQUIRED", error.code) }
        assertEquals(2, server.requestCount)
    }

    @Test fun resumedBackgroundJobRetrievesTheExistingResultWithoutUploadingAgain() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"completed","inputs_deleted":true}"""))
        server.enqueue(MockResponse().setBody("resumed-jpeg"))
        server.enqueue(MockResponse().setResponseCode(204))
        val result = client.fit(byteArrayOf(), emptyList(), true, resumeJob = TryOnJob(id, "private-job-token")) {}
        assertEquals("resumed-jpeg", String(result))
        assertEquals("GET", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
        assertEquals("DELETE", server.takeRequest().method)
        assertEquals(3, server.requestCount)
    }

    @Test fun backgroundResultIsPersistedBeforeServerCleanup() = runBlocking {
        server.enqueue(accepted())
        server.enqueue(MockResponse().setBody("""{"status":"completed","inputs_deleted":true}"""))
        server.enqueue(MockResponse().setBody("generated-jpeg"))
        server.enqueue(MockResponse().setResponseCode(204))
        var submitted = false
        var persisted = false
        client.fit(byteArrayOf(1), garment(), true, onSubmitted = {
            assertEquals(id, it.id)
            assertEquals(1, server.requestCount)
            submitted = true
        }, onResult = {
            assertTrue(submitted)
            assertEquals("generated-jpeg", String(it))
            assertEquals(3, server.requestCount)
            persisted = true
        }) {}
        assertTrue(persisted)
        assertEquals(4, server.requestCount)
    }

    @Test fun stoppedBackgroundWorkerKeepsItsCheckpointedServerJobForResume() = runBlocking {
        server.enqueue(accepted())
        try {
            client.fit(byteArrayOf(1), garment(), true, keepForResume = true,
                onSubmitted = { throw CancellationException("Worker stopped") }) {}
            fail("Expected cancellation")
        } catch (_: CancellationException) {}
        assertEquals(1, server.requestCount)
    }

    @Test fun checkpointWriteFailureDeletesTheAcceptedServerJobInsteadOfLeavingAnOrphan() = runBlocking {
        server.enqueue(accepted())
        server.enqueue(MockResponse().setResponseCode(204))
        try {
            client.fit(byteArrayOf(1), garment(), true, keepForResume = true,
                onSubmitted = { throw java.io.IOException("Device storage full") }) {}
            fail("Expected checkpoint failure")
        } catch (_: java.io.IOException) {}
        assertEquals("POST", server.takeRequest().method)
        val cleanup = server.takeRequest()
        assertEquals("Bearer ${"a".repeat(43)}", cleanup.getHeader("Authorization"))
        assertEquals("DELETE", cleanup.method)
        assertEquals("private-job-token", cleanup.getHeader("X-Fitting-Token"))
        assertEquals(2, server.requestCount)
    }

    @Test fun prolongedDisconnectKeepsPaidJobAndResumesWithoutAnotherUpload() = runBlocking {
        server.enqueue(accepted())
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        var checkpoint: TryOnJob? = null
        try {
            client.fit(byteArrayOf(1), garment(), true, keepForResume = true,
                onSubmitted = { checkpoint = it }) {}
            fail("Expected temporary connection failure")
        } catch (_: java.io.IOException) {}
        assertNotNull(checkpoint)
        assertEquals(4, server.requestCount)
        assertEquals("POST", server.takeRequest().method)
        repeat(3) { assertEquals("GET", server.takeRequest().method) }

        server.enqueue(MockResponse().setBody("""{"status":"completed","inputs_deleted":true}"""))
        server.enqueue(MockResponse().setBody("retained-jpeg"))
        server.enqueue(MockResponse().setResponseCode(204))
        val image = client.fit(byteArrayOf(), emptyList(), true, resumeJob = checkpoint, keepForResume = true) {}
        assertEquals("retained-jpeg", String(image))
        assertEquals("GET", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
        assertEquals("DELETE", server.takeRequest().method)
        assertEquals(7, server.requestCount)
    }

    @Test fun consentIsRequiredBeforeAnyUpload() = runBlocking {
        try { client.fit(byteArrayOf(1), garment(), false) {}; fail("Expected consent check") }
        catch (_: IllegalArgumentException) {}
        assertEquals(0, server.requestCount)
    }

    @Test fun retrievesImageAndDeletesServerResultWithTheSameToken() = runBlocking {
        server.enqueue(accepted())
        server.enqueue(MockResponse().setBody("""{"status":"completed","inputs_deleted":true}"""))
        server.enqueue(MockResponse().setBody("generated-jpeg"))
        server.enqueue(MockResponse().setResponseCode(204))
        val result = client.fit(byteArrayOf(1, 2), garment(), true) {}
        assertEquals("generated-jpeg", String(result))
        val upload = server.takeRequest()
        assertEquals("/api/fittings/jobs/", upload.path)
        val data = JSONObject(upload.body.readUtf8())
        assertTrue(data.getBoolean("consent"))
        assertFalse(data.has("mode"))
        assertEquals("AQI=", data.getString("person"))
        for (method in listOf("GET", "GET", "DELETE")) {
            val request = server.takeRequest()
            assertEquals(method, request.method)
            assertEquals("private-job-token", request.getHeader("X-Fitting-Token"))
        }
    }

    @Test fun failedInferenceStillDeletesTheJob() = runBlocking {
        server.enqueue(accepted())
        server.enqueue(MockResponse().setBody("""{"status":"failed","error":"INFERENCE_FAILED"}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        try { client.fit(byteArrayOf(1), garment(), true) {}; fail("Expected inference error") }
        catch (error: IllegalStateException) { assertTrue(error.message!!.contains("실패")) }
        server.takeRequest(); server.takeRequest()
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test fun fullQueueIsShownAsAnErrorWithoutAFalseResult() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"FITTING_BUSY"}"""))
        try { client.fit(byteArrayOf(1), garment(), true) {}; fail("Expected busy error") }
        catch (error: IllegalStateException) { assertTrue(error.message!!.contains("대기열")) }
        assertEquals(1, server.requestCount)
    }

    @Test fun resultIsRejectedWhenInputDeletionIsNotConfirmed() = runBlocking {
        server.enqueue(accepted())
        server.enqueue(MockResponse().setBody("""{"status":"completed","inputs_deleted":false}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        try { client.fit(byteArrayOf(1), garment(), true) {}; fail("Expected deletion check") }
        catch (error: IllegalStateException) { assertTrue(error.message!!.contains("삭제")) }
        server.takeRequest(); server.takeRequest()
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test fun layeredOutfitUploadsThreeGarmentsInOrderWithExplicitOpenFront() = runBlocking {
        server.enqueue(accepted())
        server.enqueue(MockResponse().setBody("""{"status":"completed","inputs_deleted":true}"""))
        server.enqueue(MockResponse().setBody("layered-jpeg"))
        server.enqueue(MockResponse().setResponseCode(204))
        val clothes = listOf(TryOnGarment("tops", byteArrayOf(1)), TryOnGarment("bottoms", byteArrayOf(2)),
            TryOnGarment("outerwear", byteArrayOf(3), "model"))
        assertEquals("layered-jpeg", String(client.fit(byteArrayOf(4), clothes, true) {}))
        val data = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(data.has("mode"))
        val uploaded = data.getJSONArray("garments")
        assertEquals(3, uploaded.length())
        assertEquals("outerwear", uploaded.getJSONObject(2).getString("category"))
        assertTrue(uploaded.getJSONObject(2).getBoolean("open_front"))
        assertEquals("model", uploaded.getJSONObject(2).getString("photo_type"))
        server.takeRequest(); server.takeRequest()
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test fun providerRejectionIsShownAndJobIsDeleted() = runBlocking {
        server.enqueue(accepted())
        server.enqueue(MockResponse().setBody("""{"status":"failed","error":"FITTING_PROVIDER_REJECTED"}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        try { client.fit(byteArrayOf(1), garment(), true) {}; fail("Expected layering failure") }
        catch (error: IllegalStateException) { assertTrue(error.message!!.contains("거절")) }
        server.takeRequest(); server.takeRequest()
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test fun closedOuterwearChoiceIsUploadedWithoutPretendingItIsOpen() = runBlocking {
        server.enqueue(accepted())
        server.enqueue(MockResponse().setBody("""{"status":"completed","inputs_deleted":true}"""))
        server.enqueue(MockResponse().setBody("closed-outer-jpeg"))
        server.enqueue(MockResponse().setResponseCode(204))
        val clothes = listOf(TryOnGarment("tops", byteArrayOf(1)), TryOnGarment("bottoms", byteArrayOf(2)),
            TryOnGarment("outerwear", byteArrayOf(3), openFront = false))
        assertEquals("closed-outer-jpeg", String(client.fit(byteArrayOf(4), clothes, true) {}))
        val data = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(data.has("mode"))
        assertFalse(data.getJSONArray("garments").getJSONObject(2).getBoolean("open_front"))
    }
}

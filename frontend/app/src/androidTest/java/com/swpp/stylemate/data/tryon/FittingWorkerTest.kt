package com.swpp.stylemate.data.tryon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.swpp.stylemate.data.*
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitGarment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Uses real worker, HTTP client, image decoding and private stores; generation is a local fixture. */
class FittingWorkerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val server = MockWebServer()
    private val keys = mutableListOf<String>()
    private lateinit var account: AccountAccess
    private lateinit var repository: FittingRepository
    private lateinit var tasks: FittingTaskStore
    private lateinit var image: ByteArray
    private val job = TryOnJob(UUID.randomUUID().toString(), "synthetic-capability")

    @Before fun setup() = runBlocking {
        server.start()
        val session = AuthSession("a".repeat(43), Instant.parse("2099-01-01T00:00:00Z"),
            AccountUser(UUID.randomUUID().toString(), "worker_test", null, StyleProfile()))
        val auth = AuthRepository(server.url("/").toString(), object : AuthSessionStore {
            override fun read() = session.json().toString()
            override fun write(value: String) = Unit
            override fun clear() = Unit
        })
        auth.restore()
        account = checkNotNull(auth.account())
        repository = FittingRepository(LocalUserStore.forContext(context, account.userId), account)
        repository.saveNotificationSettings(FittingNotificationSettings(false))
        tasks = FittingWork(context).tasks
        val bitmap = Bitmap.createBitmap(24, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        image = try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it); it.toByteArray() } }
            finally { bitmap.recycle() }
    }

    @After fun cleanup() {
        server.shutdown()
        for (key in keys) for (suffix in listOf("json", "photo", "tmp")) {
            File(context.noBackupFilesDir, "fitting-tasks/$key.$suffix").delete()
        }
        File(context.filesDir, "local-users/${account.userId}").deleteRecursively()
    }

    private fun outfit(vararg slots: String) = Outfit(UUID.randomUUID().toString(), "synthetic", null,
        slots.map { slot ->
            val id = UUID.randomUUID().toString()
            OutfitGarment(id, slot, "fixture", "/api/wardrobe/items/$id/image/", "")
        }, emptyList(), true, "fixture", null, "")

    private fun prepare(outfit: Outfit): String = repository.taskKey(outfit).also { key ->
        keys.add(key)
        tasks.create(key, account.userId, "gallery", outfit, image)
    }

    private fun record(item: OutfitGarment) = JSONObject().put("id", item.garmentId).put("saved", true)
        .put("attributes", JSONObject().put("category", item.slot)).put("image_url", item.imageUrl)
        .put("image_source", if (item.slot == "top") "worn_crop" else "garment_photo")

    private fun enqueueReferences(outfit: Outfit) {
        outfit.items.forEach { server.enqueue(MockResponse().setBody(record(it).toString())) }
        outfit.items.forEach { server.enqueue(MockResponse().setBody(Buffer().write(image))) }
    }

    private fun enqueueResult(bytes: ByteArray = image) {
        server.enqueue(MockResponse().setBody("""{"status":"completed","inputs_deleted":true}"""))
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        server.enqueue(MockResponse().setResponseCode(204))
    }

    private fun worker(key: String, provider: suspend () -> AccountAccess? = { account }): FittingWorker =
        TestListenableWorkerBuilder<FittingWorker>(context).setInputData(workDataOf("key" to key))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                    if (workerClassName == FittingWorker::class.java.name)
                        FittingWorker(appContext, workerParameters, server.url("/").toString(), provider) else null
            }).build()

    @Test fun layeredOutfitUsesOriginalPhotosPersistsResultAndDeletesTemporaryInputs() = runBlocking {
        val outfit = outfit("outerwear", "bottom", "top")
        val key = prepare(outfit)
        enqueueReferences(outfit)
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"id":"${job.id}","token":"${job.token}"}"""))
        enqueueResult()
        assertEquals(ListenableWorker.Result.success(), worker(key).doWork())
        assertArrayEquals(image, repository.result(outfit))
        assertEquals("completed", tasks.read(key)!!.getString("state"))
        assertNull(FittingTaskStore.job(tasks.read(key)!!))
        assertFalse(File(context.noBackupFilesDir, "fitting-tasks/$key.photo").exists())
        val requests = List(server.requestCount) { checkNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        val upload = requests.single { it.method == "POST" }
        assertEquals("Bearer ${"a".repeat(43)}", upload.getHeader("Authorization"))
        val garments = JSONObject(upload.body.readUtf8()).getJSONArray("garments")
        assertEquals(listOf("tops", "bottoms", "outerwear"), (0..2).map { garments.getJSONObject(it).getString("category") })
        assertEquals("model", garments.getJSONObject(0).getString("photo_type"))
        assertEquals("flat-lay", garments.getJSONObject(1).getString("photo_type"))
        assertEquals("DELETE", requests.last().method)
        assertEquals(job.token, requests.last().getHeader("X-Fitting-Token"))
    }

    @Test fun checkpointedNetworkFailureRetriesTheExistingJobWithoutReuploading() = runBlocking {
        val outfit = outfit("top", "bottom")
        val key = prepare(outfit)
        tasks.submitted(key, job)
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        assertEquals(ListenableWorker.Result.retry(), worker(key).doWork())
        assertEquals(job, FittingTaskStore.job(tasks.read(key)!!))
        assertEquals("running", tasks.read(key)!!.getString("state"))
        assertNull(repository.result(outfit))
        enqueueResult()
        assertEquals(ListenableWorker.Result.success(), worker(key).doWork())
        assertArrayEquals(image, repository.result(outfit))
        assertEquals(6, server.requestCount)
        val methods = List(6) { checkNotNull(server.takeRequest(2, TimeUnit.SECONDS)).method }
        assertEquals(listOf("GET", "GET", "GET", "GET", "GET", "DELETE"), methods)
    }

    @Test fun completedCachedJobDoesNotGenerateOrDownloadAgain() = runBlocking {
        val outfit = outfit("top", "bottom")
        val key = prepare(outfit)
        repository.saveResult(outfit, image)
        tasks.finish(key)
        assertEquals(ListenableWorker.Result.success(), worker(key).doWork())
        assertEquals(0, server.requestCount)
    }

    @Test fun loggedOutAccountFailsBeforeNetworkAccessAndRemovesThePhoto() = runBlocking {
        val key = prepare(outfit("top"))
        assertEquals(ListenableWorker.Result.failure(), worker(key) { null }.doWork())
        assertEquals("failed", tasks.read(key)!!.getString("state"))
        assertFalse(File(context.noBackupFilesDir, "fitting-tasks/$key.photo").exists())
        assertEquals(0, server.requestCount)
    }

    @Test fun accountChangeBeforeResultStorageCannotSaveThePreviousUsersImage() = runBlocking {
        val outfit = outfit("top")
        val key = prepare(outfit)
        tasks.submitted(key, job)
        enqueueResult()
        var checks = 0
        assertEquals(ListenableWorker.Result.failure(), worker(key) { if (checks++ == 0) account else null }.doWork())
        assertNull(repository.result(outfit))
        assertEquals("failed", tasks.read(key)!!.getString("state"))
        assertNull(FittingTaskStore.job(tasks.read(key)!!))
    }

    @Test fun expiredCheckpointIsReportedWithoutAnotherPaidSubmission() = runBlocking {
        val key = prepare(outfit("top"))
        tasks.submitted(key, job)
        repeat(2) { server.enqueue(MockResponse().setResponseCode(404)) }
        assertEquals(ListenableWorker.Result.failure(), worker(key).doWork())
        assertTrue(tasks.read(key)!!.getString("error").contains("만료"))
        assertEquals(2, server.requestCount)
    }

    @Test fun invalidResultImageNeverEntersTheCache() = runBlocking {
        val outfit = outfit("top")
        val key = prepare(outfit)
        tasks.submitted(key, job)
        enqueueResult("not-an-image".toByteArray())
        assertEquals(ListenableWorker.Result.failure(), worker(key).doWork())
        assertNull(repository.result(outfit))
        assertEquals("failed", tasks.read(key)!!.getString("state"))
    }

    @Test fun incompleteOuterwearFailsBeforePhotoDownloadOrGeneration() = runBlocking {
        val outfit = outfit("outerwear")
        val key = prepare(outfit)
        server.enqueue(MockResponse().setBody(record(outfit.items.single()).toString()))
        assertEquals(ListenableWorker.Result.failure(), worker(key).doWork())
        assertTrue(tasks.read(key)!!.getString("error").contains("상의와 하의"))
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationPreservesUnsubmittedWorkForResume() = runBlocking {
        val key = prepare(outfit("top"))
        try { worker(key) { throw CancellationException("synthetic interruption") }.doWork(); fail() }
        catch (_: CancellationException) {}
        assertEquals("queued", tasks.read(key)!!.getString("state"))
        assertArrayEquals(image, tasks.person(key))
    }

    @Test fun defaultWorkerConstructorRejectsMissingMetadataAndProvidesAProgressNotification() = runBlocking {
        val missingInput = TestListenableWorkerBuilder<FittingWorker>(context).build()
        assertEquals(ListenableWorker.Result.failure(), missingInput.doWork())
        val notification = missingInput.getForegroundInfo().notification
        assertEquals("가상 피팅을 만드는 중입니다", notification.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals(ListenableWorker.Result.failure(), worker("a".repeat(64)).doWork())
        assertEquals(0, server.requestCount)
    }
}

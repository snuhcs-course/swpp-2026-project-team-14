package com.swpp.stylemate.data.tryon

import android.app.NotificationManager
import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.test.filters.SdkSuppress
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.swpp.stylemate.data.*
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitGarment
import com.swpp.stylemate.ui.theme.StyleMateTheme
import com.swpp.stylemate.ui.tryon.FittingDialog
import com.swpp.stylemate.ui.tryon.FittingNotificationResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Full UI-to-worker flow with a local HTTP fixture; no production auth or paid API calls. */
class FittingFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val server = MockWebServer()
    private val completed = AtomicBoolean(false)
    private val submissions = AtomicInteger()
    private val photos = mutableListOf<Uri>()
    @Volatile private var uploaded: JSONObject? = null
    private lateinit var account: AccountAccess
    private lateinit var repository: FittingRepository
    private lateinit var work: FittingWork
    private lateinit var outfit: Outfit
    private lateinit var key: String
    private lateinit var image: ByteArray
    private val job = TryOnJob(UUID.randomUUID().toString(), "synthetic-capability")

    @Before fun setup() = runBlocking {
        server.start()
        val session = AuthSession("a".repeat(43), Instant.parse("2099-01-01T00:00:00Z"),
            AccountUser(UUID.randomUUID().toString(), "flow_test", null, StyleProfile()))
        val auth = AuthRepository(server.url("/").toString(), object : AuthSessionStore {
            override fun read() = session.json().toString()
            override fun write(value: String) = Unit
            override fun clear() = Unit
        })
        auth.restore()
        account = checkNotNull(auth.account())
        repository = FittingRepository(LocalUserStore.forContext(context, account.userId), account)
        outfit = Outfit(UUID.randomUUID().toString(), "fixture", null,
            listOf("top", "bottom").map { slot ->
                val id = UUID.randomUUID().toString()
                OutfitGarment(id, slot, "fixture", "/api/wardrobe/items/$id/image/", "")
            }, emptyList(), true, "fixture", null, "")
        key = repository.taskKey(outfit)
        work = FittingWork(context)
        val bitmap = Bitmap.createBitmap(24, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        image = try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it); it.toByteArray() } }
            finally { bitmap.recycle() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (request.method == "POST" && path == "/api/fittings/jobs/") {
                    uploaded = JSONObject(request.body.readUtf8())
                    submissions.incrementAndGet()
                    return MockResponse().setResponseCode(202).setBody("""{"id":"${job.id}","token":"${job.token}"}""")
                }
                if (request.method == "DELETE") return MockResponse().setResponseCode(204)
                outfit.items.firstOrNull { path == "/api/wardrobe/items/${it.garmentId}/" }?.let { item ->
                    return MockResponse().setBody(JSONObject().put("id", item.garmentId).put("saved", true)
                        .put("attributes", JSONObject().put("category", item.slot))
                        .put("image_url", item.imageUrl).put("image_source", "garment_photo").toString())
                }
                if (path.endsWith("/image/")) return MockResponse().setBody(Buffer().write(image))
                if (path == "/api/fittings/jobs/${job.id}/") return MockResponse().setBody(
                    if (completed.get()) """{"status":"completed","inputs_deleted":true}"""
                    else """{"status":"running","elapsed_seconds":1}""")
                return MockResponse().setResponseCode(404)
            }
        }
        val config = Configuration.Builder().setExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                    if (workerClassName == FittingWorker::class.java.name)
                        FittingWorker(appContext, workerParameters, server.url("/").toString()) { account } else null
            }).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After fun cleanup() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
        server.shutdown()
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.cancel(key.hashCode())
        notifications.cancel(key.hashCode() xor Int.MIN_VALUE)
        for (suffix in listOf("json", "photo", "tmp")) File(context.noBackupFilesDir, "fitting-tasks/$key.$suffix").delete()
        photos.forEach { context.contentResolver.delete(it, null, null) }
        File(context.filesDir, "local-users/${account.userId}").deleteRecursively()
    }

    @Test fun virtualSelectionBackgroundCompletionNotificationAndReopeningUseOneGeneration() {
        compose.setContent { StyleMateTheme {
            var open by remember { mutableStateOf(false) }
            var notification by remember { mutableStateOf<String?>(null) }
            androidx.compose.foundation.layout.Column {
                Button(onClick = { open = true }) { Text("Open fitting") }
                Button(onClick = { notification = key }) { Text("Open notification result") }
            }
            if (open) FittingDialog(outfit, repository, account, { open = false })
            FittingNotificationResult(notification, account) { notification = null }
        } }
        compose.onNodeWithText("Open fitting").performClick()
        if (FittingNotifications.allowed(context)) {
            compose.onNodeWithContentDescription("피팅 완료 알림").assertIsOn()
            assertTrue(runBlocking { repository.notificationSettings().enabled })
        }
        compose.onNodeWithText("내 체형과 비슷한\n가상 모델에게 입히기").performClick()
        compose.onNodeWithText("남성").performClick()
        compose.onNodeWithText("키 (cm)").performTextInput("170")
        compose.onNodeWithText("몸무게 (kg)").performTextInput("66")
        compose.onNodeWithText("이 모델에게 입히기").performScrollTo().performClick()
        startConnectedWork()
        compose.waitUntil(10_000) { work.tasks.read(key)?.optString("state") == "running" }
        assertEquals(1, submissions.get())
        assertFalse(File(context.noBackupFilesDir, "fitting-tasks/$key.photo").exists())
        runBlocking {
            assertFalse(work.enqueue(outfit, repository, account.userId, "gallery") { error("Duplicate request read another photo") })
        }
        compose.onNodeWithText("Open fitting").performClick()
        compose.onNodeWithText("가상 피팅을 만드는 중입니다.").assertIsDisplayed()
        compose.onNodeWithText("확인").performClick()
        completed.set(true)
        compose.waitUntil(10_000) { work.tasks.read(key)?.optString("state") == "completed" }
        if (FittingNotifications.allowed(context)) compose.waitUntil(5_000) {
            context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == key.hashCode() }
        }
        repeat(2) {
            compose.onNodeWithText("Open fitting").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("생성된 피팅 이미지").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("생성된 피팅 이미지").assertIsDisplayed()
            if (it == 0 && Build.VERSION.SDK_INT >= 29) {
                val before = publishedResults()
                compose.onNodeWithText("저장").performClick()
                compose.waitUntil(5_000) { (publishedResults() - before).isNotEmpty() }
                val expected = checkNotNull(BitmapFactory.decodeByteArray(image, 0, image.size))
                val saved = try {
                    (publishedResults() - before).map { id ->
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    }.filter { uri ->
                        context.contentResolver.openInputStream(uri)!!.use { input ->
                            val actual = checkNotNull(BitmapFactory.decodeStream(input))
                            try { actual.sameAs(expected) } finally { actual.recycle() }
                        }
                    }
                } finally { expected.recycle() }
                assertEquals("Only the generated fixture image should be published", 1, saved.size)
                photos.add(saved.single())
                compose.onNodeWithText("저장").assertIsNotEnabled()
            }
            compose.onNodeWithText("확인").performClick()
        }
        compose.onNodeWithText("Open notification result").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("생성된 피팅 이미지").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("생성된 피팅 이미지").assertIsDisplayed()
        compose.onNodeWithText("확인").performClick()
        assertEquals(1, submissions.get())
    }

    private fun startConnectedWork() {
        val manager = WorkManager.getInstance(context)
        try {
            compose.waitUntil(5_000) { manager.getWorkInfosForUniqueWork("fitting:$key").get().isNotEmpty() }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError("No scheduled work; state=${work.tasks.read(key)?.optString("state")}; UI=${compose.onRoot(useUnmergedTree = true).printToString()}", error)
        }
        val request = manager.getWorkInfosForUniqueWork("fitting:$key").get().last()
        if (!request.state.isFinished) checkNotNull(WorkManagerTestInitHelper.getTestDriver(context)).setAllConstraintsMet(request.id)
    }

    @Test fun stoppedWorkIsReportedAsFailedAndNoLongerKeepsTheSourcePhoto() = runBlocking {
        assertTrue(work.enqueue(outfit, repository, account.userId, "gallery") { image })
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork("fitting:$key").result.get()
        val state = work.state(key)!!
        assertEquals("failed", state.getString("state"))
        assertTrue(state.getString("error").contains("중단"))
        assertFalse(File(context.noBackupFilesDir, "fitting-tasks/$key.photo").exists())
        assertNull(FittingTaskStore.job(state))
    }

    @Test fun photoPreparationFailureLeavesNoTaskOrScheduledWork() = runBlocking {
        assertNull(work.state(key))
        try { work.enqueue(outfit, repository, account.userId, "gallery") { throw IOException("synthetic decode failure") }; fail() }
        catch (_: IOException) {}
        assertNull(work.tasks.read(key))
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork("fitting:$key").get().isEmpty())
    }

    @Test fun notificationForAnotherAccountCannotOpenTheSavedImage() = runBlocking {
        work.tasks.create(key, account.userId, "gallery", outfit, image)
        repository.saveResult(outfit, image)
        work.tasks.finish(key)
        val other = checkNotNull(com.swpp.stylemate.authenticatedRepository().account())
        val handled = AtomicBoolean(false)
        compose.setContent { StyleMateTheme { FittingNotificationResult(key, other) { handled.set(true) } } }
        compose.waitUntil(5_000) { handled.get() }
        compose.onNodeWithContentDescription("생성된 피팅 이미지").assertDoesNotExist()
        assertArrayEquals(image, repository.result(outfit))
        assertEquals(0, server.requestCount)
    }

    private fun photoUri(bytes: ByteArray = image): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "fitting-test-${UUID.randomUUID()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StyleMateTest")
        }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        photos.add(uri)
        checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
        return uri
    }

    private fun publishedResults(): Set<Long> {
        val ids = mutableSetOf<Long>()
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.IS_PENDING} = 0",
            arrayOf("StyleMate-%.png", "Pictures/StyleMate/"), null)!!.use { cursor ->
            while (cursor.moveToNext()) ids.add(cursor.getLong(0))
        }
        return ids
    }

    private fun showGalleryResult(uri: Uri?, launches: AtomicInteger = AtomicInteger()): AtomicBoolean {
        val closed = AtomicBoolean(false)
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                                         options: ActivityOptionsCompat?) {
                check(contract is ActivityResultContracts.PickVisualMedia)
                launches.incrementAndGet()
                dispatchResult(requestCode, uri)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent { StyleMateTheme {
            var open by remember { mutableStateOf(true) }
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                if (open) FittingDialog(outfit, repository, account, { open = false; closed.set(true) })
            }
        } }
        return closed
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun selectedGalleryPhotoRunsThroughDecodingUploadAndResultStorage() {
        completed.set(true)
        val uri = photoUri()
        val decoded = runBlocking { com.swpp.stylemate.ui.profile.decodeDownscaled(context, uri) }
        assertNotNull("Gallery fixture must be decodable", decoded)
        decoded!!.recycle()
        val closed = showGalleryResult(uri)
        compose.onNodeWithText("내 갤러리에서\n사진 고르기").performClick()
        startConnectedWork()
        compose.waitUntil(10_000) { work.tasks.read(key)?.optString("state") == "completed" }
        assertTrue(closed.get())
        val payload = checkNotNull(uploaded)
        val person = java.util.Base64.getDecoder().decode(payload.getString("person"))
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(person, 0, person.size))
        try { assertEquals(24, bitmap.width); assertEquals(36, bitmap.height) } finally { bitmap.recycle() }
        assertTrue(payload.getBoolean("consent"))
        assertEquals("gallery", work.tasks.read(key)!!.getString("source"))
        val garments = payload.getJSONArray("garments")
        for (index in 0 until garments.length()) {
            assertArrayEquals(image, java.util.Base64.getDecoder().decode(garments.getJSONObject(index).getString("image")))
        }
        assertArrayEquals(image, runBlocking { repository.result(outfit) })
        assertEquals(1, submissions.get())
    }

    @Test fun cancellingTheGalleryPickerDoesNotUploadOrCreateWork() {
        val launches = AtomicInteger()
        val closed = showGalleryResult(null, launches)
        repeat(2) {
            compose.onNodeWithText("내 갤러리에서\n사진 고르기").performClick()
            compose.waitUntil(5_000) { launches.get() == it + 1 }
        }
        assertFalse(closed.get())
        assertNull(work.tasks.read(key))
        assertEquals(0, server.requestCount)
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun unreadableGalleryPhotoShowsAnErrorWithoutCreatingWork() {
        showGalleryResult(photoUri("invalid photo".toByteArray()))
        compose.onNodeWithText("내 갤러리에서\n사진 고르기").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("사진을 읽지 못했어요.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("내 갤러리에서\n사진 고르기").assertIsDisplayed()
        assertNull(work.tasks.read(key))
        assertEquals(0, server.requestCount)
    }
}

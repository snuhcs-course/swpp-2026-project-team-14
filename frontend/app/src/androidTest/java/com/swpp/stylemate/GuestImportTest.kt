// AI-generated with Codex, 2026-10-16
package com.swpp.stylemate

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.stylemate.data.*
import com.swpp.stylemate.data.wardrobe.WardrobeRepository
import com.swpp.stylemate.ui.profile.*
import com.swpp.stylemate.ui.theme.StyleMateTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class GuestImportTest {
    @get:Rule val compose = createComposeRule()

    @Test fun conflictChoiceRequiresAnExplicitKeepOrReplaceDecision() {
        val record = GuestImportRecord("profile", "style", StyleProfile().json(), "fingerprint")
        val snapshot = GuestImportSnapshot(listOf(record))
        var decision: Set<String>? = null
        val state = GuestImportState(GuestImportStage.CONFLICT, snapshot, GuestImportPlan(snapshot, mapOf(record.key to "version"), listOf(record)))
        compose.setContent { StyleMateTheme {
            GuestImportDialog(state, {}, { decision = it }, {}, {})
        } }
        compose.runOnIdle { assertNull(decision) }
        compose.onNodeWithText("선택대로 가져오기").performClick()
        compose.runOnIdle { assertEquals(emptySet<String>(), decision) }
        compose.onNodeWithContentDescription("스타일 프로필 로컬 값으로 교체").performClick()
        compose.onNodeWithText("선택대로 가져오기").performClick()
        compose.runOnIdle { assertEquals(setOf(record.key), decision) }
    }

    @Test fun liveServerImportResumesMapsClothesAndKeepsOriginalsOnPhone() = runBlocking {
        val username = InstrumentationRegistry.getArguments().getString("importTestUsername")
            ?: throw org.junit.AssumptionViolatedException("Requires an isolated username and development server")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "guest-import-test-${UUID.randomUUID()}").apply { mkdirs() }
        val scoped = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        val storage = KeystoreSessionStore(scoped)
        var failCache = false
        val auth = AuthRepository(BuildConfig.API_BASE_URL, storage, cacheUser = {
            if (failCache) throw java.io.IOException("테스트용 계정 캐시 오류")
        })
        try {
            val password = UUID.randomUUID().toString()
            auth.authenticate(username, password, password)
            val access = auth.account()!!
            val guest = LocalUserStore.inDirectory(root)
            guest.put("body", JSONObject("""{"height_cm":175,"weight_kg":68,"gender":"male","measurements":[],"clothing":{"top_loose":false,"bottom_loose":false}}"""))
            guest.saveStyle(StyleProfile(listOf("wide"), listOf("minimal")))
            val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
            val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray() }
            bitmap.recycle()
            val ids = listOf("top" to "tshirt", "bottom" to "jeans").map { (category, subcategory) ->
                val attributes = JSONObject().put("name", "테스트 $category").put("category", category).put("subcategory", subcategory)
                    .put("colors", JSONArray(listOf("#FFFFFF"))).put("styles", JSONArray())
                for (key in listOf("fit_type", "leg_shape", "rise_type", "skirt_shape")) attributes.put(key, JSONObject.NULL)
                val row = guest.createGarment(JSONObject().put("attributes", attributes), bytes)
                guest.saveGarment(row.getString("id"), JSONObject().put("notes", "가져오기 검사"))
                row.getString("id")
            }
            val outfitId = UUID.randomUUID().toString()
            val items = JSONArray(ids.mapIndexed { index, id -> JSONObject().put("garment_id", id).put("slot", if (index == 0) "top" else "bottom").put("note", "테스트") })
            guest.rememberOutfits(JSONArray().put(JSONObject().put("id", outfitId).put("saved", true).put("name", "테스트 코디").put("items", items)))
            val importer = GuestImportRepository(guest, access)
            val initial = importer.snapshot()
            assertEquals(5, initial.records.size)
            lateinit var importModel: GuestImportViewModel
            compose.runOnIdle {
                importModel = GuestImportViewModel(importer)
                assertEquals(GuestImportStage.HIDDEN, importModel.state.value.stage)
                importModel.open()
                importModel.close()
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(GuestImportStage.HIDDEN, importModel.state.value.stage)
                importModel.open()
            }
            compose.waitUntil { importModel.state.value.stage == GuestImportStage.CONSENT }
            compose.runOnIdle {
                importModel.close()
                assertEquals(GuestImportStage.HIDDEN, importModel.state.value.stage)
                assertEquals(GuestImportStage.HIDDEN, GuestImportViewModel(importer).state.value.stage)
                importModel.open()
            }
            compose.waitUntil { importModel.state.value.stage == GuestImportStage.CONSENT }
            compose.runOnIdle { importModel.close() }
            assertEquals(0, access.json("GET", "/api/wardrobe/items/").getJSONArray("items").length())
            val plan = importer.preview(initial)
            assertTrue(plan.conflicts.isEmpty())
            importer.importRecord(initial.records.first(), plan, false)
            val resumed = GuestImportRepository(LocalUserStore.inDirectory(root), access)
            val pending = resumed.snapshot()
            assertEquals(4, pending.records.size)
            compose.runOnIdle { failCache = true; importModel.open() }
            compose.waitUntil { importModel.state.value.stage == GuestImportStage.CONSENT }
            compose.runOnIdle { importModel.confirm(); importModel.confirm() }
            compose.waitUntil(10_000) { importModel.state.value.stage == GuestImportStage.RESULT }
            compose.runOnIdle {
                assertEquals(4, importModel.state.value.imported)
                assertTrue(importModel.state.value.refreshFailed)
                assertEquals(0, importModel.state.value.failed)
                failCache = false
                importModel.retry()
                importModel.retry()
            }
            compose.waitUntil(10_000) { importModel.state.value.stage == GuestImportStage.RESULT && !importModel.state.value.refreshFailed }
            assertTrue(resumed.snapshot().records.isEmpty())
            val clothes = access.json("GET", "/api/wardrobe/items/").getJSONArray("items")
            assertEquals(2, clothes.length())
            val serverIds = (0 until clothes.length()).map { clothes.getJSONObject(it).getString("id") }.toSet()
            assertTrue(serverIds.intersect(ids.toSet()).isEmpty())
            assertNotNull(WardrobeRepository.thumbnail(context, clothes.getJSONObject(0).getString("image_url"), access))
            val outfits = access.json("GET", "/api/outfits/").getJSONArray("outfits")
            assertEquals(1, outfits.length())
            val members = outfits.getJSONObject(0).getJSONArray("items")
            assertEquals(serverIds, (0 until members.length()).map { members.getJSONObject(it).getString("garment_id") }.toSet())
            assertEquals(listOf("wide"), auth.status.value.session!!.user.style.preferredFits)
            assertEquals(2, guest.garments().size)
            assertEquals(1, guest.outfits().size)
            guest.saveStyle(StyleProfile(listOf("wide"), listOf("minimal"), listOf("#1F2A44")))
            val conflict = resumed.preview(resumed.snapshot())
            assertEquals(1, conflict.conflicts.size)
            assertEquals("kept", resumed.importRecord(conflict.snapshot.records.single(), conflict, false))
            assertTrue(auth.status.value.session!!.user.style.favoriteColors.isEmpty())
            guest.saveStyle(StyleProfile(listOf("wide"), listOf("minimal"), listOf("#202020")))
            val replace = resumed.preview(resumed.snapshot())
            assertEquals("imported", resumed.importRecord(replace.snapshot.records.single(), replace, true))
            resumed.refreshAccount()
            assertEquals(listOf("#202020"), auth.status.value.session!!.user.style.favoriteColors)
            val garment = initial.records.first { it.kind == "garments" }
            assertEquals("unchanged", resumed.importRecord(garment, plan, false))
            assertEquals(2, access.json("GET", "/api/wardrobe/items/").getJSONArray("items").length())
            auth.logout()
        } finally {
            storage.clear()
            root.deleteRecursively()
        }
    }

    @Test fun localReadErrorCanBeRetriedAfterRepairWithoutUploading() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "guest-import-error-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(root, "guest/data.json").apply { parentFile!!.mkdirs(); writeText("broken") }
        val user = StyleProfile().json().put("id", UUID.randomUUID().toString()).put("username", "member_1").put("body_profile", JSONObject.NULL)
        val session = JSONObject().put("token", "a".repeat(43)).put("expires_at", java.time.Instant.now().plusSeconds(3600).toString()).put("user", user)
        val auth = AuthRepository("http://127.0.0.1:1", object : AuthSessionStore {
            override fun read() = session.toString()
            override fun write(value: String) = Unit
            override fun clear() = Unit
        })
        try {
            auth.restore()
            lateinit var model: GuestImportViewModel
            compose.runOnIdle {
                model = GuestImportViewModel(GuestImportRepository(LocalUserStore.inDirectory(root), auth.account()!!))
                model.open()
                model.open()
            }
            compose.waitUntil { model.state.value.stage == GuestImportStage.RESULT }
            compose.runOnIdle { assertTrue(model.state.value.errors.isNotEmpty()) }
            assertEquals("broken", file.readText())
            file.writeText("{}")
            compose.runOnIdle { model.retry() }
            compose.waitUntil { model.state.value.stage == GuestImportStage.RESULT }
            compose.runOnIdle {
                assertTrue(model.state.value.errors.isEmpty())
                assertEquals("가져올 새로운 데이터가 없어요.", model.state.value.message)
                model.close()
            }
        } finally { root.deleteRecursively() }
    }
}

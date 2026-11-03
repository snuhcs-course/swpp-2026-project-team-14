package com.swpp.stylemate.wardrobe

import com.swpp.stylemate.data.LocalUserStore
import com.swpp.stylemate.data.testAccount
import com.swpp.stylemate.ui.wardrobe.WardrobeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class WardrobeDeleteTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var store: LocalUserStore
    private lateinit var model: WardrobeViewModel
    private val deletes = AtomicInteger()
    private val lists = AtomicInteger()
    private var response = MockResponse().setBody("""{"deleted_id":"first"}""")

    private fun garment(id: String) = JSONObject().put("id", id).put("saved", true)
        .put("attributes", JSONObject().put("name", id).put("category", "top"))
    private fun outfit(id: String, garmentId: String) = JSONObject().put("id", id).put("saved", true)
        .put("items", JSONArray().put(JSONObject().put("garment_id", garmentId)))

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        store = LocalUserStore.inDirectory(temporary.root, "00000000-0000-0000-0000-000000000001")
        store.cacheGarments(JSONArray().put(garment("first")).put(garment("second")))
        store.rememberOutfits(JSONArray().put(outfit("linked", "first")).put(outfit("kept", "second")))
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.method == "DELETE" -> { deletes.incrementAndGet(); response }
                    request.path == "/api/wardrobe/items/" -> {
                        lists.incrementAndGet()
                        MockResponse().setBody(JSONObject().put("items", JSONArray()
                            .put(garment("first")).put(garment("second"))).put("next", JSONObject.NULL).toString())
                    }
                    else -> MockResponse().setBody("""{"enums":{}}""")
                }
            }
            start()
        }
        model = WardrobeViewModel(store, testAccount(server.url("/").toString()))
        runBlocking { withTimeout(5_000) { model.state.first { !it.loading && it.catalog != null } } }
    }

    @After fun tearDown() { server.shutdown(); Dispatchers.resetMain() }

    private fun settled() = runBlocking {
        withTimeout(5_000) { model.state.first { it.deletingId == null } }
    }

    @Test fun successDeletesOnlyTheSelectedGarmentAndItsLinkedCachedOutfits() {
        model.delete("first")
        val state = settled()
        assertNull(state.deleteError)
        assertEquals(listOf("second"), state.records.map { it.getString("id") })
        assertNull(store.garment("first"))
        assertNotNull(store.garment("second"))
        assertNull(store.outfit("linked"))
        assertNotNull(store.outfit("kept"))
        assertEquals(1, deletes.get())
    }

    @Test fun failurePreservesRowsAndCacheAndAllowsRetry() {
        response = MockResponse().setResponseCode(503).setBody("""{"error":"UNAVAILABLE"}""")
        model.delete("first")
        assertNotNull(settled().deleteError)
        assertEquals(2, model.state.value.records.size)
        assertNotNull(store.garment("first"))
        assertNotNull(store.outfit("linked"))
        response = MockResponse().setBody("""{"deleted_id":"first"}""")
        model.delete("first")
        assertNull(settled().deleteError)
        assertNull(store.garment("first"))
    }

    @Test fun pendingDeleteBlocksDuplicateRequestsRefreshAndSave() {
        response = MockResponse().setBody("""{"deleted_id":"first"}""").setBodyDelay(300, TimeUnit.MILLISECONDS)
        val previousLists = lists.get()
        model.delete("first")
        assertEquals("first", model.state.value.deletingId)
        model.delete("first")
        model.delete("second")
        model.refresh()
        model.save("second", JSONObject())
        settled()
        assertEquals(1, deletes.get())
        assertEquals(previousLists, lists.get())
        assertEquals(listOf("second"), model.state.value.records.map { it.getString("id") })
    }

    @Test fun alreadyDeletedResponseRemovesStaleCacheOnRetry() {
        response = MockResponse().setResponseCode(404).setBody("""{"error":"GARMENT_NOT_FOUND"}""")
        model.delete("first")
        assertNull(settled().deleteError)
        assertNull(store.garment("first"))
        assertNull(store.outfit("linked"))
    }

    @Test fun authenticationFailureKeepsCacheAndShowsAnError() {
        response = MockResponse().setResponseCode(401).setBody("""{"error":"AUTHENTICATION_REQUIRED"}""")
        model.delete("first")
        assertNotNull(settled().deleteError)
        assertNotNull(store.garment("first"))
        assertNotNull(store.outfit("linked"))
    }
}

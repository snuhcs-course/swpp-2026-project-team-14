// AI-generated with Codex, 2026-11-05
package com.swpp.stylemate.wardrobe

import androidx.lifecycle.ViewModelStore
import com.swpp.stylemate.data.AuthRepository
import com.swpp.stylemate.data.LocalUserStore
import com.swpp.stylemate.data.RoutedServer
import com.swpp.stylemate.data.await
import com.swpp.stylemate.data.failure
import com.swpp.stylemate.data.json
import com.swpp.stylemate.data.testAccount
import com.swpp.stylemate.ui.wardrobe.WardrobeState
import com.swpp.stylemate.ui.wardrobe.WardrobeViewModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Contracts and case partitions: docs/wardrobe/wardrobe-viewmodel-spec.md. */
@OptIn(ExperimentalCoroutinesApi::class)
class WardrobeViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var server: RoutedServer
    private lateinit var store: LocalUserStore
    private lateinit var model: WardrobeViewModel
    private val lifecycle = ViewModelStore()
    private val gates = mutableListOf<RequestGate>()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = RoutedServer()
        store = LocalUserStore.inDirectory(temporary.root, "00000000-0000-0000-0000-000000000001")
    }

    @After fun tearDown() {
        gates.forEach { it.release.countDown() }
        try {
            if (::model.isInitialized) settled()
        } finally {
            lifecycle.clear()
            try { server.shutdown() } finally { Dispatchers.resetMain() }
        }
    }

    private fun garment(id: String, name: String = id, saved: Boolean = true) = JSONObject()
        .put("id", id).put("saved", saved).put("notes", "")
        .put("attributes", JSONObject().put("name", name).put("category", "top"))

    private fun items(vararg records: JSONObject, next: String? = null) = json(JSONObject()
        .put("items", JSONArray(records.toList())).put("next", next ?: JSONObject.NULL).toString())

    private fun options(version: String = "current") = JSONObject().put("version", version)
        .put("enums", JSONObject())

    private fun payload(name: String = "Edited") = JSONObject()
        .put("attributes", JSONObject().put("name", name).put("category", "top"))
        .put("dimensions", JSONObject.NULL).put("notes", "Keep this note")

    private fun start() {
        model = WardrobeViewModel(store, testAccount(server.baseUrl))
        lifecycle.put("wardrobe", model)
    }

    private fun loaded(vararg records: JSONObject) {
        server.on("GET /api/wardrobe/items/", items(*records))
        server.on("GET /api/wardrobe/options/", json(options().toString()))
        start()
        assertNull(settled().error)
    }

    private fun settled(): WardrobeState = model.state.await {
        !it.loading && !it.saving && it.deletingId == null
    }

    private fun ids(state: WardrobeState = model.state.value) = state.records.map { it.getString("id") }

    private class RequestGate {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun awaitRequest() { assertTrue("The expected request did not arrive", arrived.await(5, TimeUnit.SECONDS)) }
    }

    /** Hold a specific response until the test has inspected the pending state. */
    private fun hold(method: String, path: String): RequestGate {
        val gate = RequestGate().also { gates += it }
        val delegate = server.server.dispatcher
        server.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == method && request.path == path) {
                    gate.arrived.countDown()
                    check(gate.release.await(5, TimeUnit.SECONDS)) { "The test did not release its request" }
                }
                return delegate.dispatch(request)
            }
        }
        return gate
    }

    @Test fun refresh_cachedItems_showsSavedCacheUntilRemoteCompletes() {
        // Given: one saved cached garment, one draft and cached editor options.
        store.cacheGarments(JSONArray().put(garment("cached")).put(garment("draft", saved = false)))
        store.put("catalog", options("cached"))
        server.on("GET /api/wardrobe/items/", items(garment("remote")))
        server.on("GET /api/wardrobe/options/", json(options("fresh").toString()))
        val gate = hold("GET", "/api/wardrobe/items/")

        // When: open the wardrobe while the server response is held.
        start()
        gate.awaitRequest()

        // Then: show saved cache during loading, then the server's definitive data.
        assertTrue(model.state.value.loading)
        assertEquals(listOf("cached"), ids())
        assertEquals("cached", model.state.value.catalog!!.getString("version"))
        gate.release.countDown()
        val state = settled()
        assertNull(state.error)
        assertEquals(listOf("remote"), ids(state))
        assertEquals(listOf("remote"), store.garments().map { it.getString("id") })
        assertEquals("fresh", state.catalog!!.getString("version"))
        assertEquals("fresh", store.get("catalog")!!.getString("version"))
    }

    @Test fun refresh_emptyRemoteList_clearsOldItemsAndCache() {
        store.cacheGarments(JSONArray().put(garment("old")))
        loaded()
        assertEquals(emptyList<String>(), ids())
        assertTrue(store.garments().isEmpty())
        assertNull(store.garment("old"))
        assertFalse(model.state.value.loading)
    }

    @Test fun refresh_pagedList_publishesAllItemsAfterLastPage() {
        store.cacheGarments(JSONArray().put(garment("cached")))
        server.on("GET /api/wardrobe/items/", items(garment("first"), next = "page_2"), items(garment("second")))
        server.on("GET /api/wardrobe/options/", json(options().toString()))
        val gate = hold("GET", "/api/wardrobe/items/?after=page_2")
        start()
        gate.awaitRequest()
        assertTrue(model.state.value.loading)
        assertEquals(listOf("cached"), ids())
        assertEquals(listOf("cached"), store.garments().map { it.getString("id") })
        gate.release.countDown()
        assertEquals(listOf("first", "second"), ids(settled()))
        assertEquals(listOf("first", "second"), store.garments().map { it.getString("id") })
        assertEquals(2, server.requests("GET /api/wardrobe/items/").size)
    }

    @Test fun refresh_listFailure_keepsCacheAndAllowsRetry() {
        store.cacheGarments(JSONArray().put(garment("cached")))
        server.on("GET /api/wardrobe/items/", failure(503, "UNAVAILABLE"), items(garment("remote")))
        server.on("GET /api/wardrobe/options/", json(options().toString()))
        start()
        val failed = settled()
        assertNotNull(failed.error)
        assertEquals(listOf("cached"), ids(failed))
        assertNotNull(store.garment("cached"))

        model.refresh()
        val retried = settled()
        assertNull(retried.error)
        assertEquals(listOf("remote"), ids(retried))
        assertNull(store.garment("cached"))
        assertNotNull(store.garment("remote"))
        assertEquals(2, server.requests("GET /api/wardrobe/items/").size)
    }

    @Test fun refresh_optionsFailureWithoutCache_keepsItemsAndReportsError() {
        server.on("GET /api/wardrobe/items/", items(garment("remote")))
        server.on("GET /api/wardrobe/options/", failure(503, "UNAVAILABLE"))
        start()
        val state = settled()
        assertNotNull(state.error)
        assertEquals(listOf("remote"), ids(state))
        assertNotNull(store.garment("remote"))
        assertNull(state.catalog)
    }

    @Test fun refresh_optionsFailureWithCache_usesCachedOptions() {
        store.put("catalog", options("cached"))
        server.on("GET /api/wardrobe/items/", items(garment("remote")))
        server.on("GET /api/wardrobe/options/", failure(503, "UNAVAILABLE"))
        start()
        val state = settled()
        assertNull(state.error)
        assertEquals(listOf("remote"), ids(state))
        assertEquals("cached", state.catalog!!.getString("version"))
        assertEquals("cached", store.get("catalog")!!.getString("version"))
    }

    @Test fun refresh_expiredSession_preservesCacheAndReportsExpiry() {
        store.cacheGarments(JSONArray().put(garment("cached")))
        store.put("catalog", options("cached"))
        server.on("GET /api/wardrobe/items/", failure(401, "AUTHENTICATION_REQUIRED"))
        start()
        val state = settled()
        assertEquals(AuthRepository.EXPIRED, state.error)
        assertEquals(listOf("cached"), ids(state))
        assertNotNull(store.garment("cached"))
        assertTrue(server.requests("GET /api/wardrobe/options/").isEmpty())
    }

    @Test fun refresh_invalidListResponse_keepsCacheAndReportsError() {
        store.cacheGarments(JSONArray().put(garment("cached")))
        server.on("GET /api/wardrobe/items/", json("""{"items":"not-an-array","next":null}"""))
        start()
        val state = settled()
        assertNotNull(state.error)
        assertEquals(listOf("cached"), ids(state))
        assertNotNull(store.garment("cached"))
        assertTrue(server.requests("GET /api/wardrobe/options/").isEmpty())
    }

    @Test fun refresh_whileLoading_blocksRefreshSaveAndDelete() {
        store.cacheGarments(JSONArray().put(garment("first")))
        server.on("GET /api/wardrobe/items/", items(garment("first")))
        server.on("GET /api/wardrobe/options/", json(options().toString()))
        val gate = hold("GET", "/api/wardrobe/items/")
        start()
        gate.awaitRequest()
        model.refresh()
        model.save("first", payload())
        model.delete("first")
        gate.release.countDown()
        assertEquals(listOf("first"), ids(settled()))
        assertEquals(1, server.requests("GET /api/wardrobe/items/").size)
        assertTrue(server.requests("PUT /api/wardrobe/items/first/").isEmpty())
        assertTrue(server.requests("DELETE /api/wardrobe/items/first/").isEmpty())
    }

    @Test fun save_existingItem_replacesOnlyItsRowUsingServerResult() {
        loaded(garment("first"), garment("second"))
        server.on("PUT /api/wardrobe/items/first/", json(garment("first", "Server-normalized name").toString()))
        model.save("first", payload("Edited"))
        val state = settled()
        assertNull(state.saveError)
        assertEquals("first", state.savedId)
        assertEquals(listOf("first", "second"), ids(state))
        assertEquals("Server-normalized name", state.records[0].getJSONObject("attributes").getString("name"))
        assertEquals("second", state.records[1].getJSONObject("attributes").getString("name"))
        assertEquals("Server-normalized name", store.garment("first")!!.getJSONObject("attributes").getString("name"))
        assertEquals("Edited", server.requests("PUT /api/wardrobe/items/first/").single()
            .getJSONObject("attributes").getString("name"))
    }

    @Test fun save_newItem_prependsSavedItemAndUpdatesCache() {
        loaded(garment("old"))
        server.on("PUT /api/wardrobe/items/new/", json(garment("new", "New garment").toString()))
        model.save("new", payload("New garment"))
        val state = settled()
        assertEquals(listOf("new", "old"), ids(state))
        assertEquals("new", state.savedId)
        assertTrue(store.garment("new")!!.getBoolean("saved"))
        assertNotNull(store.garment("old"))
    }

    @Test fun save_emptyWardrobeAndRepeatedId_keepsOneUpdatedItem() {
        loaded()
        server.on("PUT /api/wardrobe/items/first/", json(garment("first", "Version 1").toString()),
            json(garment("first", "Version 2").toString()))
        model.save("first", payload("Version 1"))
        assertEquals(listOf("first"), ids(settled()))
        model.save("first", payload("Version 2"))
        val state = settled()
        assertEquals(listOf("first"), ids(state))
        assertEquals("Version 2", state.records.single().getJSONObject("attributes").getString("name"))
        assertEquals(1, store.garments().size)
        assertEquals("Version 2", store.garment("first")!!.getJSONObject("attributes").getString("name"))
        assertEquals(2, server.requests("PUT /api/wardrobe/items/first/").size)
    }

    @Test fun save_failure_preservesItemsAndAllowsRetry() {
        loaded(garment("first"))
        server.on("PUT /api/wardrobe/items/first/", failure(400, "INVALID_GARMENT"),
            json(garment("first", "Edited").toString()))
        val edited = payload()
        model.save("first", edited)
        val failed = settled()
        assertNotNull(failed.saveError)
        assertNull(failed.savedId)
        assertEquals("first", failed.records.single().getJSONObject("attributes").getString("name"))
        assertEquals("first", store.garment("first")!!.getJSONObject("attributes").getString("name"))

        val gate = hold("PUT", "/api/wardrobe/items/first/")
        model.save("first", edited)
        gate.awaitRequest()
        assertTrue(model.state.value.saving)
        assertNull(model.state.value.saveError)
        gate.release.countDown()
        val retried = settled()
        assertNull(retried.saveError)
        assertEquals("first", retried.savedId)
        assertEquals("Edited", retried.records.single().getJSONObject("attributes").getString("name"))
        assertEquals(2, server.requests("PUT /api/wardrobe/items/first/").size)
        assertTrue(server.requests("PUT /api/wardrobe/items/first/").all {
            it.getJSONObject("attributes").getString("name") == "Edited" &&
                it.getString("notes") == "Keep this note" && it.isNull("dimensions")
        })
    }

    @Test fun save_whilePending_blocksSaveRefreshAndDelete() {
        loaded(garment("first"), garment("second"))
        server.on("PUT /api/wardrobe/items/first/", json(garment("first", "Edited").toString()))
        val gate = hold("PUT", "/api/wardrobe/items/first/")
        model.save("first", payload())
        gate.awaitRequest()
        assertTrue(model.state.value.saving)
        assertEquals("first", model.state.value.records[0].getJSONObject("attributes").getString("name"))
        assertEquals("first", store.garment("first")!!.getJSONObject("attributes").getString("name"))
        model.save("first", payload())
        model.save("second", payload())
        model.refresh()
        model.delete("first")
        gate.release.countDown()
        assertEquals(listOf("first", "second"), ids(settled()))
        assertEquals(1, server.requests("PUT /api/wardrobe/items/first/").size)
        assertEquals(1, server.requests("GET /api/wardrobe/items/").size)
        assertTrue(server.requests("PUT /api/wardrobe/items/second/").isEmpty())
        assertTrue(server.requests("DELETE /api/wardrobe/items/first/").isEmpty())
    }

    @Test fun save_expiredSession_keepsItemsAndReportsExpiry() {
        loaded(garment("first"))
        server.on("PUT /api/wardrobe/items/first/", failure(401, "AUTHENTICATION_REQUIRED"))
        model.save("first", payload())
        val state = settled()
        assertEquals(AuthRepository.EXPIRED, state.saveError)
        assertNull(state.savedId)
        assertEquals(listOf("first"), ids(state))
        assertEquals("first", store.garment("first")!!.getJSONObject("attributes").getString("name"))
    }

    @Test fun clearSaveState_clearsOutcomeWithoutChangingItems() {
        loaded(garment("first"))
        server.on("PUT /api/wardrobe/items/first/", failure(400, "INVALID_GARMENT"),
            json(garment("first", "Edited").toString()))
        model.save("first", payload())
        assertNotNull(settled().saveError)
        model.clearSaveState()
        assertNull(model.state.value.saveError)
        assertNull(model.state.value.savedId)
        assertEquals(listOf("first"), ids())

        model.save("first", payload())
        assertEquals("first", settled().savedId)
        model.clearSaveState()
        assertNull(model.state.value.savedId)
        assertNull(model.state.value.saveError)
        assertEquals(listOf("first"), ids())
        assertEquals("Edited", store.garment("first")!!.getJSONObject("attributes").getString("name"))
    }
}

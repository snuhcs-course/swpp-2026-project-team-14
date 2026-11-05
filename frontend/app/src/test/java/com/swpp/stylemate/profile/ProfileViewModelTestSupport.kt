// AI-generated with Codex, 2026-11-05
package com.swpp.stylemate.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.swpp.stylemate.data.AuthSessionStore
import com.swpp.stylemate.data.GuestImportPlan
import com.swpp.stylemate.data.GuestImportRecord
import com.swpp.stylemate.data.GuestImportSnapshot
import com.swpp.stylemate.data.RoutedServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertTrue
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
internal class ProfileDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    private val models = ViewModelStore()
    private val scopes = mutableListOf<Job>()
    private var next = 0
    fun <T : ViewModel> own(model: T): T = model.also {
        models.put("profile-${next++}", it)
        scopes += it.viewModelScope.coroutineContext[Job]!!
    }
    fun clear() { models.clear(); dispatcher.scheduler.runCurrent() }
    override fun starting(description: Description) { Dispatchers.setMain(dispatcher) }
    override fun finished(description: Description) {
        try {
            clear()
            runTest(dispatcher, timeout = 5.seconds) { scopes.joinAll() }
        } finally { Dispatchers.resetMain() }
    }
}

internal class MemoryAuthStore : AuthSessionStore {
    var value: String? = null
    var readFailure: Exception? = null
    var clearFailure: Exception? = null
    override fun read(): String? { readFailure?.let { throw it }; return value }
    override fun write(value: String) { this.value = value }
    override fun clear() { clearFailure?.let { throw it }; value = null }
}

internal class ProfileRequestGate(server: RoutedServer, method: String, path: String) {
    private val arrived = CountDownLatch(1)
    val release = CountDownLatch(1)
    init {
        val delegate = server.server.dispatcher
        server.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response = delegate.dispatch(request)
                if (request.method == method && request.path == path) {
                    arrived.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Test request was not released" }
                }
                return response
            }
        }
    }
    fun awaitRequest() { assertTrue("Expected request did not arrive", arrived.await(5, TimeUnit.SECONDS)) }
}

internal class FakeGuestImport {
    data class ImportCall(val record: GuestImportRecord, val replace: Boolean)
    var records = emptyList<GuestImportRecord>()
    val snapshots = mutableListOf<Boolean>()
    val previews = mutableListOf<GuestImportSnapshot>()
    val imports = mutableListOf<ImportCall>()
    var refreshes = 0
    var snapshotReply: suspend (Boolean) -> GuestImportSnapshot = { GuestImportSnapshot(records) }
    var previewReply: suspend (GuestImportSnapshot) -> GuestImportPlan = { snapshot ->
        GuestImportPlan(snapshot, snapshot.records.associate { it.key to null }, emptyList())
    }
    var importReply: suspend (GuestImportRecord, Boolean) -> String = { _, _ -> "imported" }
    var refreshReply: suspend () -> Unit = {}

    suspend fun snapshot(includeKept: Boolean): GuestImportSnapshot {
        snapshots += includeKept
        return snapshotReply(includeKept)
    }
    suspend fun preview(snapshot: GuestImportSnapshot): GuestImportPlan {
        previews += snapshot
        return previewReply(snapshot)
    }
    suspend fun importRecord(record: GuestImportRecord, plan: GuestImportPlan, replace: Boolean): String {
        check(record in plan.snapshot.records)
        imports += ImportCall(record, replace)
        return importReply(record, replace)
    }
    suspend fun refresh() { refreshes++; refreshReply() }
}

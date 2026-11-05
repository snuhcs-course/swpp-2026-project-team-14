// AI-generated with Codex, 2026-11-05
package com.swpp.stylemate.profile

import com.swpp.stylemate.data.AuthException
import com.swpp.stylemate.data.GuestImportPlan
import com.swpp.stylemate.data.GuestImportRecord
import com.swpp.stylemate.data.GuestImportSnapshot
import com.swpp.stylemate.ui.profile.GuestImportStage
import com.swpp.stylemate.ui.profile.GuestImportViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuestImportViewModelTest {
    @get:Rule internal val main = ProfileDispatcherRule(StandardTestDispatcher())
    private val api = FakeGuestImport()
    private fun model() = main.own(GuestImportViewModel(api::snapshot, api::preview, api::importRecord, api::refresh))
    private fun garment(id: String = "first") = GuestImportRecord("garments", id,
        JSONObject().put("attributes", JSONObject().put("name", "Garment $id")), "fingerprint-$id")
    private fun outfit(garmentId: String) = GuestImportRecord("outfits", "outfit", JSONObject()
        .put("name", "My outfit").put("items", JSONArray().put(JSONObject().put("garment_id", garmentId))), "outfit-fingerprint")
    private fun plan(snapshot: GuestImportSnapshot, conflicts: List<GuestImportRecord> = emptyList()) =
        GuestImportPlan(snapshot, snapshot.records.associate { it.key to "version-1" }, conflicts)
    private fun TestScope.open(model: GuestImportViewModel) { model.open(); advanceUntilIdle() }
    private fun TestScope.import(model: GuestImportViewModel) { open(model); model.confirm(); advanceUntilIdle() }

    @Test fun initialState_staysHiddenWithoutReadingOrImporting() = runTest(main.dispatcher) {
        val model = model()
        assertEquals(GuestImportStage.HIDDEN, model.state.value.stage)
        assertTrue(api.snapshots.isEmpty())
        assertTrue(api.previews.isEmpty())
        assertTrue(api.imports.isEmpty())
    }

    @Test fun open_nonemptySnapshotRequiresConsentAndIncludesPreviouslyKeptRecords() = runTest(main.dispatcher) {
        api.records = listOf(garment())
        val model = model()
        open(model)
        assertEquals(GuestImportStage.CONSENT, model.state.value.stage)
        assertEquals(api.records, model.state.value.snapshot!!.records)
        assertEquals(listOf(true), api.snapshots)
        assertEquals("프로필 0건 · 옷 1벌 · 코디 0개", model.state.value.snapshot!!.summary)
        assertTrue(api.previews.isEmpty())
        assertTrue(api.imports.isEmpty())
    }

    @Test fun open_emptySnapshotShowsResultWithoutPreviewOrImport() = runTest(main.dispatcher) {
        val model = model()
        open(model)
        assertEquals(GuestImportStage.RESULT, model.state.value.stage)
        assertEquals("가져올 새로운 데이터가 없어요.", model.state.value.message)
        assertTrue(api.previews.isEmpty())
        assertTrue(api.imports.isEmpty())
    }

    @Test fun open_snapshotFailureShowsRecoverableReadError() = runTest(main.dispatcher) {
        api.snapshotReply = { throw RuntimeException() }
        val model = model()
        open(model)
        assertEquals(GuestImportStage.RESULT, model.state.value.stage)
        assertEquals("기기 데이터를 읽지 못했어요. 다시 시도해 주세요.", model.state.value.message)
        assertEquals(listOf("로컬 저장소를 확인해 주세요."), model.state.value.errors)
        api.snapshotReply = { GuestImportSnapshot(listOf(garment())) }
        model.retry()
        advanceUntilIdle()
        assertEquals(GuestImportStage.CONSENT, model.state.value.stage)
        assertEquals(2, api.snapshots.size)
    }

    @Test fun close_pendingReadCancelsItAndIgnoresLateSnapshot() = runTest(main.dispatcher) {
        val result = CompletableDeferred<GuestImportSnapshot>()
        var cancelled = false
        api.snapshotReply = { try { result.await() } finally { cancelled = true } }
        val model = model()
        model.open()
        runCurrent()
        model.open()
        assertEquals(GuestImportStage.LOADING, model.state.value.stage)
        assertEquals(1, api.snapshots.size)
        model.close()
        result.complete(GuestImportSnapshot(listOf(garment())))
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals(GuestImportStage.HIDDEN, model.state.value.stage)
        assertNull(model.state.value.snapshot)
        assertTrue(model.state.value.errors.isEmpty())
    }

    @Test fun close_consentDiscardsReviewAndReopenReadsFreshSnapshot() = runTest(main.dispatcher) {
        api.records = listOf(garment())
        val model = model()
        open(model)
        model.close()
        assertEquals(GuestImportStage.HIDDEN, model.state.value.stage)
        assertNull(model.state.value.snapshot)
        api.records = listOf(garment("new"))
        open(model)
        assertEquals("new", model.state.value.snapshot!!.records.single().id)
        assertTrue(api.imports.isEmpty())
    }

    @Test fun confirm_withoutConflictsImportsInOrderAndCountsKeptRecords() = runTest(main.dispatcher) {
        val style = GuestImportRecord("profile", "style", JSONObject(), "style-fingerprint")
        api.records = listOf(style, garment(), outfit("first"))
        api.importReply = { record, _ -> if (record.kind == "profile") "kept" else "imported" }
        val model = model()
        import(model)
        val state = model.state.value
        assertEquals(GuestImportStage.RESULT, state.stage)
        assertEquals(api.records, api.imports.map { it.record })
        assertTrue(api.imports.none { it.replace })
        assertEquals(3, state.done)
        assertEquals(2, state.imported)
        assertEquals(1, state.kept)
        assertEquals(0, state.failed)
        assertEquals(1, api.refreshes)
        assertEquals("가져오기를 완료했어요.", state.message)
    }

    @Test fun confirm_conflictsWaitForExplicitKeepOrReplaceDecision() = runTest(main.dispatcher) {
        api.records = listOf(garment(), garment("second"))
        api.previewReply = { plan(it, it.records) }
        api.importReply = { _, replace -> if (replace) "imported" else "kept" }
        val model = model()
        open(model)
        model.confirm()
        advanceUntilIdle()
        assertEquals(GuestImportStage.CONFLICT, model.state.value.stage)
        assertTrue(api.imports.isEmpty())
        model.decide(setOf(api.records[0].key))
        advanceUntilIdle()
        assertEquals(listOf(true, false), api.imports.map { it.replace })
        assertEquals(1, model.state.value.imported)
        assertEquals(1, model.state.value.kept)
        model.decide(setOf(api.records[1].key))
        advanceUntilIdle()
        assertEquals(2, api.imports.size)
    }

    @Test fun confirm_previewFailurePreservesSnapshotForRetry() = runTest(main.dispatcher) {
        api.records = listOf(garment(), garment("second"))
        api.previewReply = { throw RuntimeException() }
        val model = model()
        import(model)
        assertEquals(GuestImportStage.RESULT, model.state.value.stage)
        assertEquals(2, model.state.value.failed)
        assertEquals(api.records, model.state.value.snapshot!!.records)
        assertEquals("가져오기 정보를 확인하지 못했어요.", model.state.value.message)
        assertTrue(api.imports.isEmpty())
    }

    @Test fun invalidStageActionsDoNotStartImportOrRetry() = runTest(main.dispatcher) {
        val model = model()
        model.confirm()
        model.decide(emptySet())
        model.retry()
        advanceUntilIdle()
        assertTrue(api.snapshots.isEmpty())
        api.records = listOf(garment())
        open(model)
        model.decide(emptySet())
        model.retry()
        advanceUntilIdle()
        assertEquals(1, api.snapshots.size)
        assertTrue(api.imports.isEmpty())
        model.confirm()
        advanceUntilIdle()
        model.confirm()
        advanceUntilIdle()
        assertEquals(1, api.imports.size)
    }

    @Test fun pendingPreviewBlocksCloseAndDuplicateConfirmation() = runTest(main.dispatcher) {
        api.records = listOf(garment())
        val response = CompletableDeferred<GuestImportPlan>()
        api.previewReply = { response.await() }
        val model = model()
        open(model)
        model.confirm()
        runCurrent()
        model.open()
        model.close()
        model.confirm()
        assertEquals(GuestImportStage.CHECKING, model.state.value.stage)
        assertTrue(model.state.value.busy)
        response.complete(plan(GuestImportSnapshot(api.records)))
        advanceUntilIdle()
        assertEquals(1, api.snapshots.size)
        assertEquals(1, api.previews.size)
        assertEquals(1, api.imports.size)
    }

    @Test fun pendingImportBlocksCloseAndDuplicateOperations() = runTest(main.dispatcher) {
        api.records = listOf(garment())
        val result = CompletableDeferred<String>()
        api.importReply = { _, _ -> result.await() }
        val model = model()
        open(model)
        model.confirm()
        runCurrent()
        model.open()
        model.close()
        model.confirm()
        model.decide(emptySet())
        model.retry()
        assertEquals(GuestImportStage.RUNNING, model.state.value.stage)
        assertTrue(model.state.value.busy)
        result.complete("imported")
        advanceUntilIdle()
        assertEquals(1, api.snapshots.size)
        assertEquals(1, api.previews.size)
        assertEquals(1, api.imports.size)
        assertEquals(1, model.state.value.imported)
    }

    @Test fun import_failedGarmentPreventsImportOfItsLinkedOutfit() = runTest(main.dispatcher) {
        api.records = listOf(garment(), outfit("first"))
        api.importReply = { _, _ -> error("Missing image") }
        val model = model()
        import(model)
        assertEquals(1, api.imports.size)
        assertEquals("garments", api.imports.single().record.kind)
        assertEquals(2, model.state.value.failed)
        assertEquals(2, model.state.value.done)
        assertEquals(2, model.state.value.errors.size)
        assertTrue(model.state.value.errors[1].contains("연결된 옷을 먼저 가져와야 해요."))
        assertEquals(1, api.refreshes)
    }

    @Test fun import_networkOrStorageFailureStopsRemainingRecordsAndRefresh() = runTest(main.dispatcher) {
        for (code in listOf("NETWORK", "HTTP_503", "IMPORT_STORAGE_FAILED")) {
            val source = FakeGuestImport().apply {
                records = listOf(garment(), garment("second"))
                importReply = { _, _ -> throw AuthException(code, "Service unavailable") }
            }
            val model = main.own(GuestImportViewModel(source::snapshot, source::preview, source::importRecord, source::refresh))
            import(model)
            assertEquals(code, 1, source.imports.size)
            assertEquals(code, 0, source.refreshes)
            assertEquals(code, 2, model.state.value.failed)
            assertEquals(code, 1, model.state.value.done)
            assertFalse(model.state.value.refreshFailed)
        }
    }

    @Test fun import_recordValidationFailureAllowsUnrelatedRecordsToContinue() = runTest(main.dispatcher) {
        api.records = listOf(garment(), garment("second"))
        api.importReply = { record, _ -> if (record.id == "first") error("Bad record") else "imported" }
        val model = model()
        import(model)
        assertEquals(2, api.imports.size)
        assertEquals(1, model.state.value.imported)
        assertEquals(1, model.state.value.failed)
        assertEquals(1, model.state.value.errors.size)
        assertEquals(1, api.refreshes)
    }

    @Test fun import_expiredOrChangedSessionCancelsWithoutFurtherRecords() = runTest(main.dispatcher) {
        for (code in listOf("AUTHENTICATION_REQUIRED", "SESSION_CHANGED")) {
            val source = FakeGuestImport().apply {
                records = listOf(garment(), garment("second"))
                importReply = { _, _ -> throw AuthException(code, "Session expired") }
            }
            val model = main.own(GuestImportViewModel(source::snapshot, source::preview, source::importRecord, source::refresh))
            import(model)
            assertEquals(code, 1, source.imports.size)
            assertEquals(code, 0, source.refreshes)
            assertNotEquals(GuestImportStage.RESULT, model.state.value.stage)
            assertTrue(model.state.value.errors.isEmpty())
        }
    }

    @Test fun retry_refreshFailureOnlyRefreshesWithoutImportingAgain() = runTest(main.dispatcher) {
        api.records = listOf(garment())
        api.refreshReply = { error("Offline") }
        val model = model()
        import(model)
        assertTrue(model.state.value.refreshFailed)
        assertEquals(1, model.state.value.imported)
        assertEquals(0, model.state.value.failed)
        val refresh = CompletableDeferred<Unit>()
        api.refreshReply = { refresh.await() }
        model.retry()
        runCurrent()
        assertEquals(GuestImportStage.CHECKING, model.state.value.stage)
        model.retry()
        model.close()
        refresh.complete(Unit)
        advanceUntilIdle()
        assertEquals(GuestImportStage.RESULT, model.state.value.stage)
        assertFalse(model.state.value.refreshFailed)
        assertTrue(model.state.value.errors.isEmpty())
        assertEquals(1, api.imports.size)
        assertEquals(2, api.refreshes)
        assertEquals(1, api.snapshots.size)
    }

    @Test fun retry_repeatedRefreshFailurePreservesPreviousCounts() = runTest(main.dispatcher) {
        api.records = listOf(garment())
        api.refreshReply = { throw RuntimeException() }
        val model = model()
        import(model)
        model.retry()
        advanceUntilIdle()
        val state = model.state.value
        assertEquals(GuestImportStage.RESULT, state.stage)
        assertTrue(state.refreshFailed)
        assertEquals(1, state.imported)
        assertEquals(0, state.failed)
        assertEquals(listOf("계정 정보를 새로고침하지 못했어요."), state.errors)
        assertEquals(1, api.imports.size)
    }

    @Test fun retry_failedRecordsUsesNewSnapshotWithoutReimportingCompletedItems() = runTest(main.dispatcher) {
        api.records = listOf(garment(), garment("second"))
        api.importReply = { record, _ -> if (record.id == "second") error("Bad record") else "imported" }
        val model = model()
        import(model)
        assertEquals(1, model.state.value.imported)
        assertEquals(1, model.state.value.failed)
        api.records = listOf(garment("second"))
        api.importReply = { _, _ -> "imported" }
        model.retry()
        advanceUntilIdle()
        assertEquals(GuestImportStage.CONSENT, model.state.value.stage)
        assertEquals(listOf("second"), model.state.value.snapshot!!.records.map { it.id })
        model.confirm()
        advanceUntilIdle()
        assertEquals(1, api.imports.count { it.record.id == "first" })
        assertEquals(2, api.imports.count { it.record.id == "second" })
        assertEquals(0, model.state.value.failed)
        assertEquals(1, model.state.value.imported)
    }

    @Test fun import_manyFailuresShowsOnlyFirstFiveWithoutLosingFailureCount() = runTest(main.dispatcher) {
        api.records = (1..7).map { garment("item-$it") }
        api.importReply = { _, _ -> throw RuntimeException() }
        val model = model()
        import(model)
        assertEquals(7, model.state.value.failed)
        assertEquals(7, model.state.value.done)
        assertEquals(5, model.state.value.errors.size)
        assertTrue(model.state.value.errors.all { it.contains("가져오지 못했어요.") })
        assertEquals(7, api.records.size)
    }

    @Test fun clearingViewModelCancelsPendingReadWithoutShowingFailure() = runTest(main.dispatcher) {
        val response = CompletableDeferred<GuestImportSnapshot>()
        var cancelled = false
        api.snapshotReply = { try { response.await() } finally { cancelled = true } }
        val model = model()
        model.open()
        runCurrent()
        main.clear()
        advanceUntilIdle()
        assertTrue(cancelled)
        assertNotEquals(GuestImportStage.RESULT, model.state.value.stage)
        assertTrue(model.state.value.errors.isEmpty())
    }
}

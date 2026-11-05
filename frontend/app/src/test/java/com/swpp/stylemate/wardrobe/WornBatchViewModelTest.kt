// AI-generated with Codex, 2026-11-05
package com.swpp.stylemate.wardrobe

import com.swpp.stylemate.ui.wardrobe.WornBatchViewModel
import com.swpp.stylemate.ui.wardrobe.canSaveWornBatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class WornBatchViewModelTest {
    @get:Rule internal val main = WardrobeDispatcherRule()
    private val api = FakeWornApi()
    private fun model() = main.own(WornBatchViewModel(api::upload, api::photo, api::generation))
    private fun TestScope.detect(model: WornBatchViewModel) {
        model.detect { byteArrayOf(1, 2, 3) }
        advanceUntilIdle()
    }
    private fun TestScope.prepare(model: WornBatchViewModel, vararg records: JSONObject) {
        detect(model)
        val ids = records.map { it.getString("id") }.toSet()
        (model.state.value.selected - ids).forEach(model::select)
        api.photoReply = { call ->
            check(call.action == "prepare")
            JSONObject().put("items", JSONArray(records.toList()))
        }
        model.generateSelected()
        advanceUntilIdle()
    }

    @Test fun detect_readyPhotoSelectsCandidatesAndUploadsEncodedBytesOnce() = runTest(main.dispatcher) {
        val model = model()
        detect(model)
        assertEquals(setOf(WORN_FIRST, WORN_SECOND), model.state.value.selected)
        assertEquals(WORN_PHOTO, model.state.value.photo!!.getString("id"))
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.error)
        assertEquals(1, api.uploads.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), api.uploads.single().first)
        assertTrue(api.photos.isEmpty())
    }

    @Test fun detect_pendingPhotoPollsExistingSourceWithoutReuploading() = runTest(main.dispatcher) {
        val model = model()
        api.uploadReply = { _, _ -> wornPhoto("detecting") }
        api.photoReply = { wornPhoto() }
        detect(model)
        assertEquals("ready", model.state.value.photo!!.getString("status"))
        assertEquals(1, api.uploads.size)
        assertEquals(listOf(""), api.photos.map { it.action })
        assertTrue(api.photos.all { it.body == null && it.id == WORN_PHOTO })
    }

    @Test fun detect_failedPhotoShowsReasonAndCanSelectAnotherPhoto() = runTest(main.dispatcher) {
        val model = model()
        api.uploadReply = { _, _ -> wornPhoto("failed", "IMAGE_NO_PERSON").put("candidates", JSONArray()) }
        detect(model)
        assertEquals("옷을 입은 사람이 보이는 사진을 골라 주세요.", model.state.value.error)
        assertTrue(model.state.value.selected.isEmpty())
        api.uploadReply = { _, _ -> wornPhoto() }
        detect(model)
        assertNull(model.state.value.error)
        assertEquals(2, model.state.value.selected.size)
        assertNotEquals(api.uploads[0].second, api.uploads[1].second)
    }

    @Test fun detect_encodingFailureKeepsErrorWithoutCallingService() = runTest(main.dispatcher) {
        val model = model()
        model.detect { error("Cannot encode image") }
        advanceUntilIdle()
        assertEquals("Cannot encode image", model.state.value.error)
        assertFalse(model.state.value.busy)
        assertTrue(api.uploads.isEmpty())
    }

    @Test fun refreshPhoto_lostResponseReusesUploadOperationId() = runTest(main.dispatcher) {
        val model = model()
        api.uploadReply = { _, _ -> error("Response lost") }
        detect(model)
        assertNull(model.state.value.photo)
        api.uploadReply = { _, _ -> wornPhoto() }
        model.refreshPhoto()
        advanceUntilIdle()
        assertEquals(api.uploads[0].second, api.uploads[1].second)
        assertEquals(WORN_PHOTO, model.state.value.photo!!.getString("id"))
        assertNull(model.state.value.error)
        api.photoReply = { wornPhoto() }
        model.refreshPhoto()
        advanceUntilIdle()
        assertEquals(2, api.uploads.size)
        assertEquals(1, api.photos.size)
    }

    @Test fun select_togglesCandidatesOnlyBeforeDraftsArePrepared() = runTest(main.dispatcher) {
        val model = model()
        detect(model)
        model.select(WORN_SECOND)
        assertEquals(setOf(WORN_FIRST), model.state.value.selected)
        model.select(WORN_SECOND)
        assertEquals(setOf(WORN_FIRST, WORN_SECOND), model.state.value.selected)
        api.photoReply = { JSONObject().put("items", JSONArray().put(wornDraft(WORN_FIRST, "ready"))) }
        model.generateSelected()
        advanceUntilIdle()
        model.select(WORN_FIRST)
        assertEquals(setOf(WORN_FIRST, WORN_SECOND), model.state.value.selected)
    }

    @Test fun generate_noSelectionDoesNotPrepareOrGenerate() = runTest(main.dispatcher) {
        val model = model()
        detect(model)
        model.select(WORN_FIRST)
        model.select(WORN_SECOND)
        model.generateSelected()
        advanceUntilIdle()
        assertNotNull(model.state.value.error)
        assertTrue(api.photos.isEmpty())
        assertTrue(api.generations.isEmpty())
        assertFalse(model.state.value.busy)
    }

    @Test fun generate_pendingDraftsAreSequentialAndOneFailurePreservesOtherResults() = runTest(main.dispatcher) {
        val model = model()
        api.generationReply = { call ->
            if (call.id == WORN_FIRST) wornGeneration() else wornGeneration("failed", "GENERATION_FAILED")
        }
        prepare(model, wornDraft(), wornDraft(WORN_SECOND))
        assertEquals(listOf(WORN_FIRST, WORN_SECOND), api.generations.map { it.id })
        assertEquals("generated", model.state.value.items[0].imageChoice)
        assertNull(model.state.value.items[1].imageChoice)
        assertEquals("ready", model.state.value.items[0].generation.getString("status"))
        assertFalse(canSaveWornBatch(model.state.value.items))
    }

    @Test fun generate_readyDraftsAreNotRegenerated() = runTest(main.dispatcher) {
        val model = model()
        prepare(model, wornDraft(status = "ready"))
        assertTrue(api.generations.isEmpty())
        assertEquals("generated", model.state.value.items.single().imageChoice)
        val requested = api.photos.single().body!!.getJSONArray("candidate_ids")
        assertEquals(listOf(WORN_FIRST), (0 until requested.length()).map { requested.getString(it) })
    }

    @Test fun generate_runningStatusUsesGetForPollingAndOnePaidPost() = runTest(main.dispatcher) {
        val model = model()
        api.generationReply = { call -> if (call.body != null) wornGeneration("running") else wornGeneration() }
        prepare(model, wornDraft())
        assertEquals(2, api.generations.size)
        assertNotNull(api.generations[0].body!!.getString("request_id"))
        assertNull(api.generations[1].body)
        assertEquals("generated", model.state.value.items.single().imageChoice)
    }

    @Test fun generate_creditErrorStopsRemainingPaidRequests() = runTest(main.dispatcher) {
        val model = model()
        api.generationReply = { wornGeneration("failed", "GENERATION_CREDITS_REQUIRED") }
        prepare(model, wornDraft(), wornDraft(WORN_SECOND))
        assertEquals(listOf(WORN_FIRST), api.generations.map { it.id })
        assertEquals("pending", model.state.value.items[1].generation.getString("status"))
        assertFalse(model.state.value.busy)
    }

    @Test fun generate_networkFailureStopsWithoutAutomaticRetry() = runTest(main.dispatcher) {
        val model = model()
        api.generationReply = { error("Response lost") }
        prepare(model, wornDraft(), wornDraft(WORN_SECOND))
        assertEquals(1, api.generations.size)
        assertEquals("Response lost", model.state.value.items[0].error)
        assertEquals("pending", model.state.value.items[1].generation.getString("status"))
        assertFalse(model.state.value.busy)
    }

    @Test fun refreshItem_preservesExplicitCropChoiceAndDoesNotGenerate() = runTest(main.dispatcher) {
        val model = model()
        prepare(model, wornDraft(status = "ready"))
        model.chooseImage(WORN_FIRST, "crop")
        model.refresh(WORN_FIRST)
        advanceUntilIdle()
        assertEquals("crop", model.state.value.items.single().imageChoice)
        assertEquals(1, api.generations.size)
        assertNull(api.generations.single().body)
    }

    @Test fun regenerate_usesNewRequestIdOnlyAfterExplicitAction() = runTest(main.dispatcher) {
        val model = model()
        prepare(model, wornDraft())
        val first = api.generations.single().body!!.getString("request_id")
        model.regenerate(WORN_FIRST)
        advanceUntilIdle()
        assertEquals(2, api.generations.size)
        assertNotEquals(first, api.generations.last().body!!.getString("request_id"))
        assertEquals("generated", model.state.value.items.single().imageChoice)
    }

    @Test fun edit_saveSendsOnlyIncludedApprovedItemsAndConfirmsLabels() = runTest(main.dispatcher) {
        val model = model()
        prepare(model, wornDraft(status = "ready"), wornDraft(WORN_SECOND, "ready"))
        model.include(WORN_SECOND, false)
        model.chooseImage(WORN_FIRST, "crop")
        model.editLabel(WORN_FIRST, "fit", "노멀")
        val payload = JSONObject(model.state.value.items[0].payload.toString()).put("notes", "My note")
        model.edit(WORN_FIRST, payload)
        api.photoReply = { call ->
            check(call.action == "save")
            JSONObject().put("items", call.body!!.getJSONArray("items"))
        }
        model.save()
        advanceUntilIdle()
        val rows = api.photos.last().body!!.getJSONArray("items")
        assertEquals(1, rows.length())
        val sent = rows.getJSONObject(0)
        assertEquals(WORN_FIRST, sent.getString("id"))
        assertEquals("crop", sent.getString("image_choice"))
        assertEquals("My note", sent.getString("notes"))
        assertEquals("노멀", sent.getJSONObject("labels").getString("fit"))
        assertTrue(sent.getJSONObject("labels").isNull("fit_basis"))
        assertTrue(sent.getJSONObject("labels").getBoolean("confirmed"))
        assertEquals(1, model.state.value.savedCount)
        assertEquals("Original note", model.state.value.items[1].payload.getString("notes"))
    }

    @Test fun save_unreviewedOrEmptySelectionDoesNotSendSave() = runTest(main.dispatcher) {
        val model = model()
        prepare(model, wornDraft(status = "failed"))
        model.save()
        advanceUntilIdle()
        assertNotNull(model.state.value.error)
        assertEquals(0, api.photos.count { it.action == "save" })
        model.include(WORN_FIRST, false)
        model.save()
        advanceUntilIdle()
        assertEquals(0, api.photos.count { it.action == "save" })
        assertEquals(0, model.state.value.savedCount)
    }

    @Test fun save_failureKeepsResultsForRetry() = runTest(main.dispatcher) {
        val model = model()
        prepare(model, wornDraft(status = "ready"))
        api.photoReply = { error("Save failed") }
        model.save()
        advanceUntilIdle()
        assertEquals("Save failed", model.state.value.error)
        assertEquals("generated", model.state.value.items.single().imageChoice)
        api.photoReply = { JSONObject().put("items", JSONArray().put(JSONObject().put("id", WORN_FIRST))) }
        model.save()
        advanceUntilIdle()
        assertEquals(1, model.state.value.savedCount)
        assertNull(model.state.value.error)
        assertEquals(2, api.photos.count { it.action == "save" })
    }

    @Test fun busy_blocksDuplicateDetectionSelectionEditingAndSave() = runTest(main.dispatcher) {
        val model = model()
        prepare(model, wornDraft(status = "ready"))
        val pending = CompletableDeferred<JSONObject>()
        api.generationReply = { pending.await() }
        model.regenerate(WORN_FIRST)
        runCurrent()
        model.detect { error("Duplicate detection") }
        model.regenerate(WORN_FIRST)
        model.select(WORN_FIRST)
        model.include(WORN_FIRST, false)
        model.chooseImage(WORN_FIRST, "crop")
        model.editLabel(WORN_FIRST, "fit", "노멀")
        model.edit(WORN_FIRST, JSONObject())
        model.save()
        assertTrue(model.state.value.busy)
        assertTrue(model.state.value.items.single().included)
        assertEquals("루즈", model.state.value.items.single().payload.getJSONObject("labels").getString("fit"))
        pending.complete(wornGeneration())
        advanceUntilIdle()
        assertEquals(1, api.generations.size)
        assertEquals(1, api.uploads.size)
        assertEquals(0, api.photos.count { it.action == "save" })
    }

    @Test fun reset_thenNewDetectionKeepsNewOperationBusyUntilItsOwnResponse() = runTest(main.dispatcher) {
        val model = model()
        val old = CompletableDeferred<JSONObject>()
        val current = CompletableDeferred<JSONObject>()
        api.uploadReply = { _, _ -> if (api.uploads.size == 1) old.await() else current.await() }
        model.detect { byteArrayOf(1) }
        runCurrent()
        model.reset()
        model.detect { byteArrayOf(2) }
        runCurrent()
        assertTrue(model.state.value.busy)
        assertNull(model.state.value.photo)
        old.complete(wornPhoto("failed", "IMAGE_NO_PERSON"))
        current.complete(wornPhoto())
        advanceUntilIdle()
        assertFalse(model.state.value.busy)
        assertEquals("ready", model.state.value.photo!!.getString("status"))
        assertNull(model.state.value.error)
        assertNotEquals(api.uploads[0].second, api.uploads[1].second)
    }

    @Test fun detect_pollLimitLeavesManualRefreshWithoutAnotherUpload() = runTest(main.dispatcher) {
        val model = model()
        api.uploadReply = { _, _ -> wornPhoto("detecting") }
        api.photoReply = { wornPhoto("detecting") }
        detect(model)
        assertEquals(35, api.photos.size)
        assertEquals(1, api.uploads.size)
        assertFalse(model.state.value.busy)
        assertEquals("detecting", model.state.value.photo!!.getString("status"))
    }

    @Test fun generate_pollLimitNeverRepeatsPaidPost() = runTest(main.dispatcher) {
        val model = model()
        api.generationReply = { wornGeneration("running") }
        prepare(model, wornDraft())
        assertEquals(1, api.generations.count { it.body != null })
        assertEquals(40, api.generations.count { it.body == null })
        assertNull(model.state.value.items.single().imageChoice)
        assertFalse(canSaveWornBatch(model.state.value.items))
        assertFalse(model.state.value.busy)
    }
}

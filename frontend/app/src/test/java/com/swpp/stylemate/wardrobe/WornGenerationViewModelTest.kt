// AI-generated with Codex, 2026-11-05
package com.swpp.stylemate.wardrobe

import com.swpp.stylemate.data.testAccount
import com.swpp.stylemate.ui.wardrobe.WornGenerationViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WornGenerationViewModelTest {
    @get:Rule internal val main = WardrobeDispatcherRule()
    private val api = FakeWornApi()
    private val deleted = mutableListOf<String?>()
    private var created = 0
    private fun model() = main.own(WornGenerationViewModel(
        account = testAccount("http://127.0.0.1"),
        createPhoto = { "capture-${++created}.jpg" },
        writePhoto = { _, _ -> error("Android image encoding is outside this JVM test") },
        deletePhoto = { deleted += it }, requestGeneration = api::generation,
    ))
    private fun TestScope.start(model: WornGenerationViewModel) {
        model.start { _, _ -> wornDraft() }
        advanceUntilIdle()
    }

    @Test fun start_analyzesOriginalThenGeneratesWithStableDraftId() = runTest(main.dispatcher) {
        val model = model()
        var analyzed: Pair<String, String>? = null
        model.start { photo, requestId -> analyzed = photo to requestId; wornDraft() }
        assertTrue(model.state.value.busy)
        advanceUntilIdle()
        assertEquals("capture-1.jpg", analyzed!!.first)
        assertEquals("capture-1.jpg", model.state.value.photo)
        assertEquals(WORN_FIRST, model.state.value.draft!!.getString("id"))
        assertEquals("ready", model.state.value.generation!!.getString("status"))
        assertEquals(WORN_FIRST, api.generations.single().id)
        assertNotNull(api.generations.single().body!!.getString("request_id"))
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.approved)
        assertNull(model.state.value.error)
    }

    @Test fun start_whilePendingOrAlreadyPreparedDoesNotAnalyzeAgain() = runTest(main.dispatcher) {
        val model = model()
        val response = CompletableDeferred<JSONObject>()
        var calls = 0
        model.start { _, _ -> calls++; response.await() }
        runCurrent()
        model.start { _, _ -> calls++; wornDraft() }
        model.refresh()
        model.regenerate()
        model.review("crop")
        assertEquals(1, calls)
        response.complete(wornDraft())
        advanceUntilIdle()
        model.start { _, _ -> calls++; wornDraft() }
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(1, created)
        assertEquals(1, api.generations.size)
    }

    @Test fun start_analysisFailureRetainsPhotoAndOperationIdForRetry() = runTest(main.dispatcher) {
        val model = model()
        val calls = mutableListOf<Pair<String, String>>()
        model.start { photo, id -> calls += photo to id; error("Analysis failed") }
        advanceUntilIdle()
        assertEquals("Analysis failed", model.state.value.error)
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.draft)
        assertTrue(api.generations.isEmpty())
        model.start { photo, id -> calls += photo to id; wornDraft() }
        advanceUntilIdle()
        assertEquals(calls[0], calls[1])
        assertEquals(1, created)
        assertNull(model.state.value.error)
        assertEquals("ready", model.state.value.generation!!.getString("status"))
    }

    @Test fun poll_runningStatusUsesGetAndDoesNotRegenerate() = runTest(main.dispatcher) {
        val model = model()
        api.generationReply = { call -> if (call.body != null) wornGeneration("running") else wornGeneration() }
        start(model)
        assertEquals(2, api.generations.size)
        assertNotNull(api.generations[0].body)
        assertNull(api.generations[1].body)
        assertEquals("ready", model.state.value.generation!!.getString("status"))
        assertFalse(model.state.value.busy)
    }

    @Test fun poll_limitShowsManualRefreshAndNeverRepeatsPaidPost() = runTest(main.dispatcher) {
        val model = model()
        api.generationReply = { wornGeneration("running") }
        start(model)
        assertEquals(1, api.generations.count { it.body != null })
        assertEquals(40, api.generations.count { it.body == null })
        assertEquals("아직 생성 중입니다. 결과 확인을 눌러 주세요. 자동 재생성은 하지 않아요.", model.state.value.error)
        assertFalse(model.state.value.busy)
    }

    @Test fun refresh_failureKeepsDraftAndCanRetryWithoutAnotherPaidPost() = runTest(main.dispatcher) {
        val model = model()
        start(model)
        api.generationReply = { error("Status unavailable") }
        model.refresh()
        advanceUntilIdle()
        assertEquals("Status unavailable", model.state.value.error)
        assertEquals(WORN_FIRST, model.state.value.draft!!.getString("id"))
        assertEquals("ready", model.state.value.generation!!.getString("status"))
        api.generationReply = { wornGeneration() }
        model.refresh()
        advanceUntilIdle()
        assertNull(model.state.value.error)
        assertEquals(1, api.generations.count { it.body != null })
        assertEquals(2, api.generations.count { it.body == null })
    }

    @Test fun regenerate_requiresExplicitActionAndCreatesNewOperationId() = runTest(main.dispatcher) {
        val model = model()
        start(model)
        val original = api.generations.single().body!!.getString("request_id")
        model.refresh()
        advanceUntilIdle()
        assertEquals(1, api.generations.count { it.body != null })
        model.regenerate()
        advanceUntilIdle()
        assertEquals(2, api.generations.count { it.body != null })
        assertNotEquals(original, api.generations.last().body!!.getString("request_id"))
    }

    @Test fun review_generatedApprovesOnlyAfterServiceSuccess() = runTest(main.dispatcher) {
        val model = model()
        start(model)
        api.generationReply = { call ->
            assertEquals("review", call.action)
            assertEquals("generated", call.body!!.getString("choice"))
            wornDraft(status = "ready")
        }
        model.review("generated")
        advanceUntilIdle()
        assertEquals(WORN_FIRST, model.state.value.approved!!.getString("id"))
        assertTrue(deleted.isEmpty())
        assertEquals(1, api.generations.count { it.action == "generation" })
    }

    @Test fun review_cropApprovesOriginalWithoutAnotherGeneration() = runTest(main.dispatcher) {
        val model = model()
        start(model)
        api.generationReply = { call ->
            assertEquals("crop", call.body!!.getString("choice"))
            wornDraft(status = "ready")
        }
        model.review("crop")
        advanceUntilIdle()
        assertNotNull(model.state.value.approved)
        assertEquals(1, api.generations.count { it.action == "generation" })
        assertTrue(deleted.isEmpty())
    }

    @Test fun review_rejectionDoesNotApproveTheDraft() = runTest(main.dispatcher) {
        val model = model()
        start(model)
        api.generationReply = { wornDraft(status = "ready") }
        model.review("reject")
        advanceUntilIdle()
        assertEquals("reject", api.generations.last().body!!.getString("choice"))
        assertNull(model.state.value.approved)
        assertFalse(model.state.value.busy)
    }

    @Test fun review_failureKeepsDraftAndAllowsRetry() = runTest(main.dispatcher) {
        val model = model()
        start(model)
        api.generationReply = { error("Review failed") }
        model.review("generated")
        advanceUntilIdle()
        assertEquals("Review failed", model.state.value.error)
        assertNull(model.state.value.approved)
        assertEquals(WORN_FIRST, model.state.value.draft!!.getString("id"))
        api.generationReply = { wornDraft(status = "ready") }
        model.review("generated")
        advanceUntilIdle()
        assertNotNull(model.state.value.approved)
        assertNull(model.state.value.error)
    }

    @Test fun reset_thenNewStartKeepsNewOperationBusyAndDeletesOnlyOldPhoto() = runTest(main.dispatcher) {
        val model = model()
        val old = CompletableDeferred<JSONObject>()
        val current = CompletableDeferred<JSONObject>()
        model.start { _, _ -> old.await() }
        runCurrent()
        model.reset()
        model.start { _, _ -> current.await() }
        runCurrent()
        assertEquals(listOf("capture-1.jpg"), deleted)
        assertEquals("capture-2.jpg", model.state.value.photo)
        assertTrue(model.state.value.busy)
        old.complete(wornDraft(WORN_SECOND))
        current.complete(wornDraft())
        advanceUntilIdle()
        assertEquals(WORN_FIRST, model.state.value.draft!!.getString("id"))
        assertEquals(1, api.generations.size)
        assertFalse(model.state.value.busy)
    }

    @Test fun transferred_doesNotDeletePhotoWhenViewModelIsCleared() = runTest(main.dispatcher) {
        val model = model()
        start(model)
        model.transferred()
        main.clear()
        advanceUntilIdle()
        assertTrue(deleted.filterNotNull().isEmpty())
        assertNull(model.state.value.photo)
        assertNull(model.state.value.draft)
        assertNull(model.state.value.approved)
    }

    @Test fun clearingViewModelDeletesTemporaryCapture() = runTest(main.dispatcher) {
        val model = model()
        start(model)
        main.clear()
        advanceUntilIdle()
        assertEquals(listOf("capture-1.jpg"), deleted)
    }
}

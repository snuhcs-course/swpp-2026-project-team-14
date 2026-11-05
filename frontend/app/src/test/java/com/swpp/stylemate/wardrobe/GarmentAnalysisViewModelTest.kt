// AI-generated with Codex, 2026-11-05
package com.swpp.stylemate.wardrobe

import com.swpp.stylemate.data.testAccount
import com.swpp.stylemate.ui.wardrobe.GarmentAnalysisViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GarmentAnalysisViewModelTest {
    @get:Rule internal val main = WardrobeDispatcherRule()
    private fun model() = main.own(GarmentAnalysisViewModel(testAccount("http://127.0.0.1")))

    @Test fun select_restoresDraftAndChangingPhotoClearsIt() = runTest(main.dispatcher) {
        val model = model()
        assertNull(model.state.value.photo)
        val draft = JSONObject().put("id", "restored")
        model.select("capture-1.jpg", draft)
        assertSame(draft, model.state.value.result)
        model.select("capture-1.jpg", JSONObject().put("id", "different"))
        assertSame(draft, model.state.value.result)
        model.select("capture-2.jpg")
        assertEquals("capture-2.jpg", model.state.value.photo)
        assertNull(model.state.value.result)
        assertNull(model.state.value.error)
        assertFalse(model.state.value.busy)
    }

    @Test fun analyze_successPublishesResultAndBlocksDuplicateRequest() = runTest(main.dispatcher) {
        val model = model()
        val response = CompletableDeferred<JSONObject>()
        var calls = 0
        model.analyze("capture-1.jpg") { calls++; response.await() }
        runCurrent()
        assertTrue(model.state.value.busy)
        model.analyze("capture-2.jpg") { calls++; JSONObject() }
        assertEquals(1, calls)
        val result = JSONObject().put("id", "draft")
        response.complete(result)
        advanceUntilIdle()
        assertEquals("capture-1.jpg", model.state.value.photo)
        assertSame(result, model.state.value.result)
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.error)
    }

    @Test fun analyze_failureKeepsPhotoAndRetryCanSucceed() = runTest(main.dispatcher) {
        val model = model()
        model.analyze("capture-1.jpg") { error("Service unavailable") }
        advanceUntilIdle()
        assertEquals("Service unavailable", model.state.value.error)
        assertEquals("capture-1.jpg", model.state.value.photo)
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.result)
        model.analyze("capture-1.jpg") { JSONObject().put("id", "retry") }
        assertNull(model.state.value.error)
        advanceUntilIdle()
        assertEquals("retry", model.state.value.result!!.getString("id"))
        assertFalse(model.state.value.busy)
    }

    @Test fun analyze_exceptionWithoutMessageShowsFallback() = runTest(main.dispatcher) {
        val model = model()
        model.analyze("capture-1.jpg") { throw RuntimeException() }
        advanceUntilIdle()
        assertEquals("분석하지 못했습니다.", model.state.value.error)
        assertFalse(model.state.value.busy)
    }

    @Test fun select_newPhotoCancelsOldAnalysisWithoutPublishingItsResult() = runTest(main.dispatcher) {
        val model = model()
        val response = CompletableDeferred<JSONObject>()
        var cancelled = false
        model.analyze("capture-1.jpg") {
            try { response.await() } finally { cancelled = true }
        }
        runCurrent()
        model.select("capture-2.jpg")
        response.complete(JSONObject().put("id", "late"))
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals("capture-2.jpg", model.state.value.photo)
        assertNull(model.state.value.result)
        assertNull(model.state.value.error)
        assertFalse(model.state.value.busy)
    }

    @Test fun select_nullCancelsAnalysisAndReturnsToEmptyState() = runTest(main.dispatcher) {
        val model = model()
        val response = CompletableDeferred<JSONObject>()
        model.analyze("capture-1.jpg") { response.await() }
        runCurrent()
        model.select(null)
        advanceUntilIdle()
        assertNull(model.state.value.photo)
        assertNull(model.state.value.result)
        assertNull(model.state.value.error)
        assertFalse(model.state.value.busy)
    }

    @Test fun clearingViewModelCancelsPendingAnalysis() = runTest(main.dispatcher) {
        val model = model()
        val response = CompletableDeferred<JSONObject>()
        var cancelled = false
        model.analyze("capture-1.jpg") {
            try { response.await() } finally { cancelled = true }
        }
        runCurrent()
        main.clear()
        advanceUntilIdle()
        assertTrue(cancelled)
        assertNull(model.state.value.result)
        assertNull(model.state.value.error)
    }
}

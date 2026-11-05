// AI-generated with Codex, 2026-11-05
package com.swpp.stylemate.wardrobe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
internal class WardrobeDispatcherRule : TestWatcher() {
    val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private var next = 0
    fun <T : ViewModel> own(model: T): T = model.also { models.put("model-${next++}", it) }
    fun clear() = models.clear()
    override fun starting(description: Description) { Dispatchers.setMain(dispatcher) }
    override fun finished(description: Description) {
        try { models.clear() } finally { Dispatchers.resetMain() }
    }
}

internal const val WORN_PHOTO = "00000000-0000-0000-0000-000000000010"
internal const val WORN_FIRST = "00000000-0000-0000-0000-000000000020"
internal const val WORN_SECOND = "00000000-0000-0000-0000-000000000021"

internal fun wornGeneration(status: String = "ready", error: String = "") = JSONObject()
    .put("status", status).put("error", error)

internal fun wornPhoto(status: String = "ready", error: String = "") = JSONObject()
    .put("id", WORN_PHOTO).put("status", status).put("error", error)
    .put("candidates", JSONArray().put(JSONObject().put("id", WORN_FIRST))
        .put(JSONObject().put("id", WORN_SECOND)))

internal fun wornDraft(id: String = WORN_FIRST, status: String = "pending") = JSONObject()
    .put("id", id).put("attributes", JSONObject().put("name", "Test garment").put("category", "top"))
    .put("notes", "Original note").put("labels", JSONObject().put("fit", "루즈").put("fit_basis", "Observed"))
    .put("generation", wornGeneration(status))

/** Controlled service responses: no HTTP, model calls or real image files. */
internal class FakeWornApi {
    data class Call(val id: String, val action: String, val body: JSONObject?)
    val uploads = mutableListOf<Pair<ByteArray, String>>()
    val photos = mutableListOf<Call>()
    val generations = mutableListOf<Call>()
    var uploadReply: suspend (ByteArray, String) -> JSONObject = { _, _ -> wornPhoto() }
    var photoReply: suspend (Call) -> JSONObject = { error("Unexpected photo request: $it") }
    var generationReply: suspend (Call) -> JSONObject = { wornGeneration() }

    suspend fun upload(bytes: ByteArray, id: String): JSONObject {
        uploads += bytes.copyOf() to id
        return uploadReply(bytes, id)
    }
    suspend fun photo(id: String, action: String, body: JSONObject?): JSONObject {
        val call = Call(id, action, body?.let { JSONObject(it.toString()) })
        photos += call
        return photoReply(call)
    }
    suspend fun generation(id: String, action: String, body: JSONObject?): JSONObject {
        val call = Call(id, action, body?.let { JSONObject(it.toString()) })
        generations += call
        return generationReply(call)
    }
}

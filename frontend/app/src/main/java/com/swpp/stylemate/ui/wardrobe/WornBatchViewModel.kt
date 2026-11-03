package com.swpp.stylemate.ui.wardrobe

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swpp.stylemate.data.AccountAccess
import com.swpp.stylemate.data.wardrobe.WornGenerationRepository
import com.swpp.stylemate.data.wardrobe.WornPhotoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

data class WornBatchItem(val record: JSONObject, val generation: JSONObject, val payload: JSONObject,
    val requestId: String = UUID.randomUUID().toString(), val included: Boolean = true,
    val imageChoice: String? = null, val error: String? = null) {
    val id: String get() = record.getString("id")
}

data class WornBatchState(val original: Bitmap? = null, val photo: JSONObject? = null,
    val selected: Set<String> = emptySet(), val items: List<WornBatchItem> = emptyList(),
    val busy: Boolean = false, val progress: String = "", val error: String? = null, val savedCount: Int = 0)

internal fun canSaveWornBatch(items: List<WornBatchItem>): Boolean {
    val chosen = items.filter { it.included }
    return chosen.isNotEmpty() && chosen.all { item ->
        item.imageChoice in setOf("generated", "crop") &&
            (item.imageChoice != "generated" || item.generation.optString("status") == "ready") &&
            item.generation.optString("status") != "running" &&
            item.payload.getJSONObject("attributes").optString("name").isNotBlank()
    }
}

internal fun wornBatchSaveProblem(items: List<WornBatchItem>): String? {
    val selected = items.filter { it.included }
    return when {
        selected.isEmpty() -> "등록할 옷을 하나 이상 선택해 주세요."
        selected.any { it.generation.optString("status") == "running" } -> "선택한 옷의 사진을 만들고 있어요. 완료되면 등록할 수 있어요."
        selected.any { it.imageChoice == null } -> "사진을 만들지 못한 옷이 있어요. 다시 만들거나, 착용 사진을 사용하거나, 선택에서 빼 주세요."
        selected.any { it.payload.getJSONObject("attributes").optString("name").isBlank() } -> "정보 수정에서 비어 있는 옷 이름을 입력해 주세요."
        else -> null
    }
}

internal fun detectionErrorMessage(code: String): String = when (code) {
    "IMAGE_MULTIPLE_PEOPLE" -> "한 사람만 나온 사진을 골라 주세요."
    "IMAGE_NO_PERSON" -> "옷을 입은 사람이 보이는 사진을 골라 주세요."
    "IMAGE_TARGET_UNCLEAR" -> "등록할 옷을 구분하지 못했어요. 옷이 더 잘 보이는 다른 사진을 골라 주세요."
    "AI_RATE_LIMITED", "AI_BUSY" -> "분석 요청이 많습니다. 잠시 후 다른 사진으로 다시 시도해 주세요."
    "AI_CREDITS_REQUIRED" -> "옷 분석 서비스의 잔액이 부족해요. 관리자에게 충전을 요청해 주세요."
    "AI_TIMEOUT", "DETECTION_INTERRUPTED" -> "옷 찾기가 시간 안에 끝나지 않았어요. 사진을 다시 선택해 주세요."
    "AI_NOT_CONFIGURED", "AI_AUTH_FAILED", "AI_MODEL_UNAVAILABLE" -> "서버의 옷 분석 API 설정을 확인해 주세요."
    "AI_REQUEST_INVALID" -> "서버 오류로 사진을 분석하지 못했어요. 잠시 후 다시 시도해 주세요."
    "AI_INVALID_RESPONSE" -> "분석 결과를 읽지 못했어요. 잠시 후 사진을 다시 선택해 주세요."
    "AI_PROVIDER_ERROR", "AI_UNAVAILABLE" -> "옷 분석 서비스에 연결하지 못했어요. 잠시 후 다시 시도해 주세요."
    else -> "옷 분석을 완료하지 못했어요. 잠시 후 다시 시도해 주세요."
}

class WornBatchViewModel(private val account: AccountAccess) : ViewModel() {
    private val mutable = MutableStateFlow(WornBatchState())
    val state = mutable.asStateFlow()
    private var uploadId = UUID.randomUUID().toString()
    private var upload: ByteArray? = null
    private var job: Job? = null

    private fun run(action: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        job = viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = mutable.value.copy(error = error.message ?: "요청을 처리하지 못했어요.") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    fun detect(bitmap: Bitmap) {
        if (mutable.value.busy) return
        uploadId = UUID.randomUUID().toString()
        upload = null
        mutable.value = WornBatchState(original = bitmap)
        run {
            mutable.value = mutable.value.copy(progress = "사진 속 옷을 찾고 있어요")
            upload = withContext(Dispatchers.IO) { ByteArrayOutputStream().also {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it))
            }.toByteArray() }
            uploadAndPoll()
        }
    }

    /** Reuse the same operation ID after a lost response; never reanalyze automatically. */
    fun refreshPhoto() = run {
        if (mutable.value.photo == null) uploadAndPoll()
        else {
            val result = WornPhotoRepository.request(account, mutable.value.photo!!.getString("id"))
            acceptPhoto(result)
        }
    }

    private suspend fun uploadAndPoll() {
        var result = WornPhotoRepository.upload(account, checkNotNull(upload), uploadId)
        acceptPhoto(result)
        repeat(35) {
            if (result.optString("status") != "detecting") return
            delay(1200)
            result = WornPhotoRepository.request(account, result.getString("id"))
            acceptPhoto(result)
        }
    }

    private fun acceptPhoto(result: JSONObject) {
        val candidates = result.optJSONArray("candidates") ?: JSONArray()
        mutable.value = mutable.value.copy(photo = result,
            selected = (0 until candidates.length()).map { candidates.getJSONObject(it).getString("id") }.toSet(),
            error = if (result.optString("status") == "failed") detectionErrorMessage(result.optString("error")) else null)
    }

    fun select(id: String) {
        if (!mutable.value.busy && mutable.value.items.isEmpty()) mutable.value = mutable.value.copy(
            selected = mutable.value.selected.let { if (id in it) it - id else it + id })
    }

    fun generateSelected() = run {
        val state = mutable.value
        check(state.selected.isNotEmpty())
        mutable.value = state.copy(busy = true, progress = "선택한 옷을 준비하고 있어요", error = null)
        val result = WornPhotoRepository.request(account, state.photo!!.getString("id"), "prepare",
            JSONObject().put("candidate_ids", JSONArray(state.selected.toList())))
        val records = result.getJSONArray("items")
        mutable.value = mutable.value.copy(items = (0 until records.length()).map { index ->
            val record = records.getJSONObject(index)
            val generation = record.getJSONObject("generation")
            WornBatchItem(record, generation, JSONObject()
                .put("attributes", JSONObject(record.getJSONObject("attributes").toString()))
                .put("dimensions", JSONObject.NULL).put("notes", record.optString("notes"))
                .put("labels", JSONObject(record.getJSONObject("labels").toString())),
                imageChoice = if (generation.optString("status") == "ready") "generated" else null)
        })
        // Each selected garment is a separate paid call. Never regenerate a completed draft.
        for ((index, item) in mutable.value.items.withIndex()) {
            mutable.value = mutable.value.copy(progress = "옷 분리·이미지 생성 중 · ${index + 1}/${records.length()}")
            if (item.generation.optString("status") == "pending") {
                try { generateOne(item.id, item.requestId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    update(item.id) { it.copy(error = error.message) }
                    break // An ambiguous network failure must be checked, not automatically retried.
                }
                val code = mutable.value.items.first { it.id == item.id }.generation.optString("error")
                if (code in setOf("GENERATION_RATE_LIMITED", "GENERATION_CREDITS_REQUIRED", "GENERATION_AUTH_FAILED", "GENERATION_NOT_CONFIGURED")) break
            }
        }
    }

    private fun update(id: String, action: (WornBatchItem) -> WornBatchItem) {
        mutable.value = mutable.value.copy(items = mutable.value.items.map { if (it.id == id) action(it) else it })
    }

    private suspend fun generateOne(id: String, requestId: String) {
        update(id) { it.copy(imageChoice = null, error = null, requestId = requestId) }
        var result = WornGenerationRepository.request(account, id, "generation", JSONObject().put("request_id", requestId))
        update(id) { it.copy(generation = result, imageChoice = if (result.optString("status") == "ready") "generated" else null) }
        repeat(40) {
            if (result.optString("status") != "running") return
            delay(1200)
            result = WornGenerationRepository.request(account, id, "generation")
            update(id) { it.copy(generation = result, imageChoice = if (result.optString("status") == "ready") "generated" else null) }
        }
    }

    fun refresh(id: String) = run {
        mutable.value = mutable.value.copy(progress = "생성 결과 확인 중")
        val result = WornGenerationRepository.request(account, id, "generation")
        update(id) { it.copy(generation = result, error = null,
            imageChoice = it.imageChoice ?: if (result.optString("status") == "ready") "generated" else null) }
    }

    fun regenerate(id: String) = run {
        mutable.value = mutable.value.copy(progress = "선택한 옷 이미지 생성 중")
        generateOne(id, UUID.randomUUID().toString())
    }

    fun include(id: String, included: Boolean) { if (!mutable.value.busy) update(id) { it.copy(included = included) } }
    fun chooseImage(id: String, choice: String?) { if (!mutable.value.busy) update(id) { it.copy(imageChoice = choice) } }
    fun editLabel(id: String, field: String, value: String) {
        if (mutable.value.busy) return
        update(id) {
            val payload = JSONObject(it.payload.toString())
            payload.getJSONObject("labels").put(field, value).put("${field}_basis", JSONObject.NULL)
            it.copy(payload = payload)
        }
    }
    fun edit(id: String, payload: JSONObject) { if (!mutable.value.busy) update(id) { it.copy(payload = payload) } }

    fun save() = run {
        val state = mutable.value
        check(canSaveWornBatch(state.items))
        mutable.value = state.copy(busy = true, progress = "옷장에 함께 저장 중", error = null)
        val rows = state.items.filter { it.included }.map { item -> JSONObject(item.payload.toString())
            .put("id", item.id).put("image_choice", item.imageChoice).apply {
                getJSONObject("labels").put("confirmed", true)
            } }
        val result = WornPhotoRepository.request(account, state.photo!!.getString("id"), "save",
            JSONObject().put("items", JSONArray(rows)))
        mutable.value = mutable.value.copy(savedCount = result.getJSONArray("items").length())
    }

    fun reset() {
        job?.cancel()
        upload = null
        uploadId = UUID.randomUUID().toString()
        mutable.value = WornBatchState()
    }
}

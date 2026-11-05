package com.swpp.stylemate.ui.wardrobe

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swpp.stylemate.data.AccountAccess
import com.swpp.stylemate.data.wardrobe.CaptureFiles
import com.swpp.stylemate.data.wardrobe.WornGenerationRepository
import com.swpp.stylemate.data.wardrobe.requestAnalysis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

data class WornGenerationState(val photo: String? = null, val draft: JSONObject? = null,
    val generation: JSONObject? = null, val busy: Boolean = false, val progress: String = "",
    val error: String? = null, val approved: JSONObject? = null)

class WornGenerationViewModel internal constructor(
    private val account: AccountAccess,
    private val createPhoto: () -> String,
    private val writePhoto: (String, Bitmap) -> Unit,
    private val deletePhoto: (String?) -> Unit,
    private val requestGeneration: suspend (String, String, JSONObject?) -> JSONObject,
) : ViewModel() {
    constructor(account: AccountAccess, files: CaptureFiles) : this(
        account, files::create, files::write, files::delete,
        { id, action, body -> WornGenerationRepository.request(account, id, action, body) },
    )
    private val mutable = MutableStateFlow(WornGenerationState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generationId = UUID.randomUUID().toString()
    private var uploadId = UUID.randomUUID().toString()

    private fun run(action: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        job = viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutable.value = mutable.value.copy(error = error.message ?: "요청을 처리하지 못했습니다.")
            }
            finally {
                if (currentCoroutineContext().isActive) mutable.value = mutable.value.copy(busy = false)
            }
        }
    }

    fun start(context: Context, crop: Bitmap, category: String) {
        if (mutable.value.draft != null || mutable.value.busy) return
        val application = context.applicationContext
        start { photo, requestId ->
            withContext(Dispatchers.IO) { writePhoto(photo, crop) }
            requestAnalysis(application, photo, account = account, targetCategory = category, requestId = requestId)
        }
    }

    internal fun start(analyze: suspend (String, String) -> JSONObject) {
        if (mutable.value.draft != null || mutable.value.busy) return
        run {
            val photo = mutable.value.photo ?: createPhoto()
            mutable.value = mutable.value.copy(photo = photo, progress = "원본의 옷 정보 분석 중")
            val draft = analyze(photo, uploadId)
            mutable.value = mutable.value.copy(draft = draft)
            generate()
        }
    }

    private suspend fun generate() {
        val id = checkNotNull(mutable.value.draft).getString("id")
        mutable.value = mutable.value.copy(progress = "옷 분리·펼친 이미지 생성 중", generation = null)
        val result = requestGeneration(id, "generation", JSONObject().put("request_id", generationId))
        mutable.value = mutable.value.copy(generation = result)
        poll(id)
    }

    private suspend fun poll(id: String) {
        repeat(40) {
            if (mutable.value.generation?.optString("status") != "running") return
            delay(1200)
            val result = requestGeneration(id, "generation", null)
            mutable.value = mutable.value.copy(generation = result)
        }
        mutable.value = mutable.value.copy(error = "아직 생성 중입니다. 결과 확인을 눌러 주세요. 자동 재생성은 하지 않아요.")
    }

    fun refresh() = run {
        val id = checkNotNull(mutable.value.draft).getString("id")
        mutable.value = mutable.value.copy(progress = "생성 결과 확인 중",
            generation = requestGeneration(id, "generation", null))
        poll(id)
    }

    fun regenerate() = run {
        generationId = UUID.randomUUID().toString() // Only explicit user confirmation starts another paid request.
        generate()
    }

    fun review(choice: String) = run {
        val result = requestGeneration(checkNotNull(mutable.value.draft).getString("id"),
            "review", JSONObject().put("choice", choice))
        mutable.value = mutable.value.copy(generation = result.getJSONObject("generation"),
            approved = if (choice == "reject") null else result)
    }

    fun transferred() { mutable.value = WornGenerationState() }
    fun reset() {
        job?.cancel()
        deletePhoto(mutable.value.photo)
        mutable.value = WornGenerationState()
        uploadId = UUID.randomUUID().toString()
        generationId = UUID.randomUUID().toString()
    }
    override fun onCleared() { deletePhoto(mutable.value.photo) }
}

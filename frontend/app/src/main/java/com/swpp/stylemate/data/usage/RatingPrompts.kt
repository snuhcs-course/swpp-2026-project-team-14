// AI-generated with Claude Code (Claude Opus 5.5), 2026-11-26, review pending (Kihwan Kim)
package com.swpp.stylemate.data.usage

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

/** 별점을 묻는 세 작업. [code]는 서버의 metrics.task 값이다. */
enum class RatingTask(val code: String, val label: String) {
    RECOMMENDATION("recommendation", "코디 추천"),
    GARMENT_REGISTRATION("garment_registration", "옷 등록"),
    VIRTUAL_FITTING("virtual_fitting", "가상 피팅"),
}

data class RatingRequest(val task: RatingTask, val owner: String, val showAfterMs: Long, val requestedAt: Long)

data class RatingAnswer(val wait: Double, val quality: Double, val convenience: Double)

/**
 * 서버가 rating_prompt=true를 준 작업의 별점 팝업 요청.
 *
 * 화면은 [request]만 부르고, 실제 표시는 MainTabs의 RatingPromptHost가 맡는다.
 * 한 번에 하나만 대기·표시하며, 대기 중인 요청은 [cancel]로 취소할 수 있다.
 */
object RatingPrompts {
    /** 지연 시간이 지난 뒤에도 이 시간 안에 띄우지 못하면 버린다 (예: 앱이 백그라운드였던 경우). */
    const val EXPIRE_MS = 30_000L

    @Volatile private var paused = false
    private val pendingFlow = MutableStateFlow<RatingRequest?>(null)
    private val visibleFlow = MutableStateFlow<RatingRequest?>(null)
    val pending: StateFlow<RatingRequest?> = pendingFlow.asStateFlow()
    val visible: StateFlow<RatingRequest?> = visibleFlow.asStateFlow()

    /** 온보딩처럼 묻지 않을 화면이 떠 있는 동안 새 요청을 받지 않는다. */
    fun setPaused(value: Boolean) { paused = value }

    fun request(task: RatingTask, owner: String, showAfterMs: Long = 0L, now: Long = System.currentTimeMillis()) {
        if (paused || visibleFlow.value != null) return
        pendingFlow.compareAndSet(null, RatingRequest(task, owner, showAfterMs, now))
    }

    /** 아직 띄우지 않은 요청만 취소한다. 이미 떠 있는 팝업은 건너뛰기·제출로만 닫힌다. */
    fun cancel(task: RatingTask) = pendingFlow.update { if (it?.task == task) null else it }

    fun expired(request: RatingRequest, now: Long = System.currentTimeMillis()) =
        now - request.requestedAt > request.showAfterMs + EXPIRE_MS

    /** 대기 중인 [request]를 표시 상태로 옮긴다. 그 사이 취소·교체됐으면 false. */
    fun show(request: RatingRequest): Boolean {
        if (!pendingFlow.compareAndSet(request, null)) return false
        visibleFlow.value = request
        return true
    }

    fun drop(request: RatingRequest) { pendingFlow.compareAndSet(request, null) }

    fun close() { visibleFlow.value = null }

    /** 제출·건너뛰기 결과를 기존 usage 큐로 보낸다. 서버는 이를 metrics/no_seen에 저장한다. */
    fun submit(context: Context, owner: String, task: RatingTask, answer: RatingAnswer?, noSeen: Boolean) {
        UsageMetrics.enqueue(context, owner, event(task, answer, noSeen))
    }

    /** 서버 usage 수집 API의 task_rating 이벤트. [answer]가 null이면 건너뛰기다. */
    fun event(task: RatingTask, answer: RatingAnswer?, noSeen: Boolean, now: Instant = Instant.now()): JSONObject {
        val scores = answer ?: RatingAnswer(0.0, 0.0, 0.0)
        val details = JSONObject().put("task", task.code).put("submitted", answer != null)
            .put("wait", scores.wait).put("quality", scores.quality).put("convenience", scores.convenience)
            .put("no_seen", noSeen)
        return JSONObject().put("id", UUID.randomUUID().toString()).put("attempt_id", UUID.randomUUID().toString())
            .put("session_id", UUID.randomUUID().toString()).put("name", "task_rating").put("phase", "finished")
            .put("outcome", "success").put("occurred_at", now.toString()).put("duration_ms", JSONObject.NULL)
            .put("error_code", "").put("details", details)
    }

    /** 별 줄에서 누른·끈 위치(0~1)를 0.5 단위 점수로 바꾼다. 맨 왼쪽은 0점, 맨 오른쪽은 5점. */
    fun halfStep(fraction: Float): Double = (fraction.coerceIn(0f, 1f) * 10f).roundToInt() / 2.0
}

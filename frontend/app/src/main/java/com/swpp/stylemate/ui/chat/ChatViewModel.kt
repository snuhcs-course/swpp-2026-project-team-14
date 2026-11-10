package com.swpp.stylemate.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swpp.stylemate.data.BodyProfile
import com.swpp.stylemate.data.BodyProfileJson
import com.swpp.stylemate.data.chat.ChatRepository
import com.swpp.stylemate.data.chat.ChatRequest
import com.swpp.stylemate.data.chat.ChatResult
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitApiException
import com.swpp.stylemate.data.recommendation.TasteStore
import com.swpp.stylemate.data.recommendation.Weather
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

val CHAT_DIRECTIONS = linkedMapOf(
    "brighter" to "밝게", "darker" to "차분하게", "warmer" to "따뜻하게", "cooler" to "시원하게",
    "formal" to "단정하게", "casual" to "편하게", "looser" to "넉넉하게", "different" to "다른 조합",
)
private val OPPOSITES = mapOf("brighter" to "darker", "darker" to "brighter", "warmer" to "cooler",
    "cooler" to "warmer", "formal" to "casual", "casual" to "formal")

/** What the chat looked like just before a request, so a request the user regrets can be taken back. */
data class ChatCheckpoint(val current: Outfit?, val lockedIds: Set<String>, val snapshots: List<Outfit>)

data class ChatTurn(
    val id: String = UUID.randomUUID().toString(),
    val request: ChatRequest,
    val result: ChatResult? = null,
    val error: String? = null,
    val loading: Boolean = true,
    val before: ChatCheckpoint? = null,
)

/** A save that can still be undone from the snackbar. */
data class SavedOutfit(val key: String, val outfitId: String)

data class ChatState(
    val source: Outfit? = null,
    val current: Outfit? = null,
    val body: JSONObject? = null,
    val weather: Weather? = null,
    val lockedIds: Set<String> = emptySet(),
    val directions: Set<String> = emptySet(),
    val draft: String = "",
    val turns: List<ChatTurn> = emptyList(),
    val snapshots: List<Outfit> = emptyList(),
    val savedKeys: Set<String> = emptySet(),
    val savingKeys: Set<String> = emptySet(),
    val saveErrors: Map<String, String> = emptyMap(),
    val feedbacks: Map<String, String> = emptyMap(),  // 코디 key -> liked / disliked
    val feedbackPending: Set<String> = emptySet(),
    val feedbackMessages: Map<String, String> = emptyMap(),  // 코디 key -> 저장 결과 안내 또는 오류
    val feedbackLoaded: Set<String> = emptySet(),
    val feedbackLoading: Set<String> = emptySet(),
    val notice: String? = null,
    val lastSaved: SavedOutfit? = null,
    val undoingSave: Boolean = false,
) {
    val busy get() = turns.any { it.loading }
    val canCancelLast get() = turns.lastOrNull()?.before != null && !undoingSave
    val allLocked get() = current?.items?.all { it.garmentId in lockedIds } == true
    val canSend get() = !busy && !allLocked && (draft.isNotBlank() || directions.isNotEmpty())
}

/** Sessions survive home navigation and configuration changes, scoped to the activity. */
class ChatViewModel(private val repository: ChatRepository, private val taste: TasteStore) : ViewModel() {
    private val sessions = mutableMapOf<String, ChatState>()
    private val saveIds = mutableMapOf<String, String>()
    private val jobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private var activeId: String? = null
    private val mutableState = MutableStateFlow(ChatState())
    val state = mutableState.asStateFlow()
    private val mutableVisible = MutableStateFlow(false)
    val visible = mutableVisible.asStateFlow()

    fun open(outfit: Outfit, profile: BodyProfile?, weather: Weather?) {
        if (profile == null) return
        val body = JSONObject(BodyProfileJson.encode(profile))
        activeId = outfit.id
        val session = sessions[outfit.id]?.takeIf { it.body?.toString() == body.toString() }
            ?: ChatState(source = outfit, current = outfit, body = body, weather = weather,
                savedKeys = if (outfit.saved) setOf(outfit.key) else emptySet())
        sessions[outfit.id] = session
        mutableState.value = session
        mutableVisible.value = true
    }

    fun close() { mutableVisible.value = false }

    private fun update(id: String? = activeId, change: (ChatState) -> ChatState) {
        val old = sessions[id] ?: return
        val new = change(old)
        sessions[id!!] = new
        if (activeId == id) mutableState.value = new
    }

    fun setDraft(value: String) = update { it.copy(draft = value.take(400)) }
    fun toggleLock(id: String) = update { state ->
        if (state.busy) state else state.copy(lockedIds =
            if (id in state.lockedIds) state.lockedIds - id else state.lockedIds + id, notice = null)
    }
    fun toggleDirection(code: String) = update { state ->
        val choices = if (code in state.directions) state.directions - code
            else (state.directions - listOfNotNull(OPPOSITES[code]).toSet()) + code
        state.copy(directions = choices)
    }

    fun send() {
        val s = mutableState.value
        if (!s.canSend) return
        val current = s.current ?: return
        val body = s.body ?: return
        val history = s.turns.flatMap { turn ->
            listOf("user" to conversationText(turn.request)) + listOfNotNull(turn.result?.let { "assistant" to it.message })
        }.takeLast(6)
        val request = ChatRequest(current, s.lockedIds, s.directions, s.draft.trim(), body, s.weather,
            taste.load(), history, (s.snapshots + current).map { it.key })
        val turn = ChatTurn(request = request, before = ChatCheckpoint(current, s.lockedIds, s.snapshots))
        update { it.copy(turns = it.turns + turn, draft = "", directions = emptySet(), notice = null) }
        execute(activeId!!, turn)
    }

    fun retry(id: String) {
        val s = mutableState.value
        val turn = s.turns.find { it.id == id } ?: return
        if (s.busy || s.turns.lastOrNull()?.id != id || turn.error == null || s.current?.key != turn.request.current.key
            || s.lockedIds != turn.request.lockedIds) return
        val pending = turn.copy(error = null, loading = true)
        update { it.copy(turns = it.turns.map { t -> if (t.id == id) pending else t }) }
        execute(activeId!!, pending)
    }

    fun unlockAndRetry(id: String) {
        val s = mutableState.value
        val turn = s.turns.find { it.id == id } ?: return
        if (s.busy || s.turns.lastOrNull()?.id != id || s.current?.key != turn.request.current.key) return
        val unlock = turn.result?.unlockIds ?: return
        val pending = turn.copy(request = turn.request.copy(lockedIds = s.lockedIds - unlock),
            result = null, error = null, loading = true)
        update { it.copy(lockedIds = it.lockedIds - unlock,
            turns = it.turns.map { t -> if (t.id == id) pending else t }) }
        execute(activeId!!, pending)
    }

    private fun execute(sessionId: String, turn: ChatTurn) {
        jobs[turn.id] = viewModelScope.launch {
            try {
                val result = repository.edit(turn.request)
                update(sessionId) { s ->
                    if (s.turns.none { it.id == turn.id }) return@update s  // the user took this request back
                    val undo = result.status == "undo"
                    val current = if (undo) s.snapshots.lastOrNull() ?: s.current else result.outfit ?: s.current
                    s.copy(current = current,
                        snapshots = when { undo -> s.snapshots.dropLast(1)
                            result.status == "updated" -> s.snapshots + listOfNotNull(s.current)
                            else -> s.snapshots },
                        lockedIds = s.lockedIds
                            .intersect(current?.items?.map { it.garmentId }?.toSet() ?: emptySet()),
                        turns = s.turns.map { t -> if (t.id == turn.id) turn.copy(loading = false,
                            result = if (undo && s.snapshots.isEmpty()) result.copy(message = "되돌릴 이전 코디가 없어요.") else result) else t })
                }
            } catch (e: CancellationException) { throw e }
            catch (e: OutfitApiException) {
                update(sessionId) { s -> s.copy(turns = s.turns.map { t ->
                    if (t.id == turn.id) turn.copy(loading = false, error = e.message) else t }) }
            } finally {
                jobs.remove(turn.id)
            }
        }
    }

    /**
     * Takes back the last request, even while it is still running: the outfit, the locks and the earlier
     * outfits go back to how they were before it, the request leaves the conversation (so it is not sent
     * as history again), and its text returns to the input box so it can be rewritten.
     */
    fun cancelLast() {
        val s = mutableState.value
        val last = s.turns.lastOrNull() ?: return
        val before = last.before ?: return
        if (s.undoingSave) return
        jobs.remove(last.id)?.cancel()
        update { state -> state.copy(current = before.current, lockedIds = before.lockedIds, snapshots = before.snapshots,
            turns = state.turns.dropLast(1), draft = last.request.text, directions = last.request.directions,
            notice = "보낸 요청을 취소하고 그 전 코디로 돌아왔어요.") }
    }

    fun restore(outfit: Outfit) {
        if (mutableState.value.busy) return
        update { s ->
            s.copy(current = outfit, snapshots = s.snapshots.takeWhile { it.key != outfit.key },
                lockedIds = s.lockedIds.intersect(outfit.items.map { it.garmentId }.toSet()),
                notice = "선택한 코디로 돌아왔어요. 이 코디를 기준으로 수정할게요.")
        }
    }

    fun undo() { mutableState.value.snapshots.lastOrNull()?.let(::restore) }

    fun save(outfit: Outfit) {
        val s = mutableState.value
        val sessionId = activeId ?: return
        if (outfit.key in s.savedKeys || outfit.key in s.savingKeys) return
        val saveId = saveIds.getOrPut(outfit.key) { UUID.randomUUID().toString() }
        update { it.copy(savingKeys = it.savingKeys + outfit.key, saveErrors = it.saveErrors - outfit.key) }
        viewModelScope.launch {
            try {
                val saved = repository.save(outfit, saveId)
                update(sessionId) { it.copy(savingKeys = it.savingKeys - outfit.key, savedKeys = it.savedKeys + outfit.key,
                    lastSaved = SavedOutfit(outfit.key, saved.id)) }
            } catch (e: CancellationException) { throw e }
            catch (e: OutfitApiException) {
                update(sessionId) { it.copy(savingKeys = it.savingKeys - outfit.key,
                    saveErrors = it.saveErrors + (outfit.key to (e.message ?: "저장하지 못했어요."))) }
            }
        }
    }

    fun saveNoticeShown() = update { it.copy(lastSaved = null) }

    /** Undo from the save snackbar: the outfit leaves the 코디북 and can be saved again later. */
    fun undoSave() {
        val sessionId = activeId ?: return
        val saved = mutableState.value.lastSaved ?: return
        update { it.copy(undoingSave = true, lastSaved = null) }
        viewModelScope.launch {
            try {
                repository.unsave(saved.outfitId)
                saveIds.remove(saved.key)  // a new save id, or the server would return the unsaved row as it is
                update(sessionId) { it.copy(undoingSave = false, savedKeys = it.savedKeys - saved.key,
                    notice = "코디북 저장을 취소했어요.") }
            } catch (e: CancellationException) { throw e }
            catch (e: OutfitApiException) {
                update(sessionId) { it.copy(undoingSave = false,
                    saveErrors = it.saveErrors + (saved.key to (e.message ?: "저장을 취소하지 못했어요."))) }
            }
        }
    }

    /** Each visible result reads the server rating before enabling its rating buttons. */
    fun loadFeedback(outfit: Outfit) {
        val sessionId = activeId ?: return
        val s = mutableState.value
        if (outfit.key in s.feedbackLoaded || outfit.key in s.feedbackLoading || outfit.key in s.feedbackPending) return
        update { it.copy(feedbackLoading = it.feedbackLoading + outfit.key,
            feedbackMessages = it.feedbackMessages - outfit.key) }
        viewModelScope.launch {
            try {
                val value = repository.feedbackState(outfit)
                update(sessionId) { it.copy(feedbackLoading = it.feedbackLoading - outfit.key,
                    feedbackLoaded = it.feedbackLoaded + outfit.key,
                    feedbacks = if (value == null) it.feedbacks - outfit.key else it.feedbacks + (outfit.key to value)) }
            } catch (e: CancellationException) { throw e }
            catch (e: OutfitApiException) {
                update(sessionId) { it.copy(feedbackLoading = it.feedbackLoading - outfit.key,
                    feedbackMessages = it.feedbackMessages + (outfit.key to (e.message ?: "만족도를 불러오지 못했어요."))) }
            }
        }
    }

    /** 채팅 추천 코디에 좋아요/별로예요. 같은 버튼을 다시 누르면 취소. */
    fun feedback(outfit: Outfit, value: String) {
        val sessionId = activeId ?: return
        val s = mutableState.value
        if (s.feedbackPending.isNotEmpty() || outfit.key in s.feedbackLoading) return
        val newValue = if (s.feedbacks[outfit.key] == value) null else value
        update { it.copy(feedbackPending = it.feedbackPending + outfit.key, feedbackMessages = it.feedbackMessages - outfit.key) }
        viewModelScope.launch {
            try {
                val (_, preference) = repository.feedback(outfit, newValue, taste.load())
                taste.save(preference)
                update(sessionId) { it.copy(feedbackPending = it.feedbackPending - outfit.key,
                    feedbackLoaded = it.feedbackLoaded + outfit.key,
                    feedbacks = if (newValue == null) it.feedbacks - outfit.key else it.feedbacks + (outfit.key to newValue),
                    feedbackMessages = when (newValue) {
                        "liked" -> it.feedbackMessages + (outfit.key to "비슷한 코디를 더 자주 추천할게요.")
                        "disliked" -> it.feedbackMessages + (outfit.key to "비슷한 코디는 덜 추천할게요.")
                        else -> it.feedbackMessages - outfit.key
                    }) }
            } catch (e: CancellationException) { throw e }
            catch (e: OutfitApiException) {
                update(sessionId) { it.copy(feedbackPending = it.feedbackPending - outfit.key,
                    feedbackMessages = it.feedbackMessages + (outfit.key to (e.message ?: "만족도를 저장하지 못했어요."))) }
            }
        }
    }

    companion object {
        // Lock annotations describe the UI at that moment, not words the user said.
        fun conversationText(request: ChatRequest): String = listOf(request.text,
            request.directions.mapNotNull(CHAT_DIRECTIONS::get).joinToString(" · "))
            .filter { it.isNotBlank() }.joinToString("\n")

        fun summary(request: ChatRequest): String = listOf(conversationText(request),
            request.current.items.filter { it.garmentId in request.lockedIds }.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "유지: ") { it.name }.orEmpty()).filter { it.isNotBlank() }.joinToString("\n")
    }
}

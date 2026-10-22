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

data class ChatTurn(
    val id: String = UUID.randomUUID().toString(),
    val request: ChatRequest,
    val result: ChatResult? = null,
    val error: String? = null,
    val loading: Boolean = true,
)

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
    val notice: String? = null,
) {
    val busy get() = turns.any { it.loading }
    val allLocked get() = current?.items?.all { it.garmentId in lockedIds } == true
    val canSend get() = !busy && !allLocked && (draft.isNotBlank() || directions.isNotEmpty())
}

/** Sessions survive home navigation and configuration changes, scoped to the activity. */
class ChatViewModel(private val repository: ChatRepository, private val taste: TasteStore) : ViewModel() {
    private val sessions = mutableMapOf<String, ChatState>()
    private val saveIds = mutableMapOf<String, String>()
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
        val turn = ChatTurn(request = request)
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
        viewModelScope.launch {
            try {
                val result = repository.edit(turn.request)
                update(sessionId) { s ->
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
            }
        }
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
                repository.save(outfit, saveId)
                update(sessionId) { it.copy(savingKeys = it.savingKeys - outfit.key, savedKeys = it.savedKeys + outfit.key) }
            } catch (e: CancellationException) { throw e }
            catch (e: OutfitApiException) {
                update(sessionId) { it.copy(savingKeys = it.savingKeys - outfit.key,
                    saveErrors = it.saveErrors + (outfit.key to (e.message ?: "저장하지 못했어요."))) }
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

// AI-generated with Codex, 2026-10-16
package com.swpp.stylemate.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swpp.stylemate.data.AuthException
import com.swpp.stylemate.data.GuestImportPlan
import com.swpp.stylemate.data.GuestImportRepository
import com.swpp.stylemate.data.GuestImportSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class GuestImportStage { HIDDEN, LOADING, CONSENT, CHECKING, CONFLICT, RUNNING, RESULT }
data class GuestImportState(val stage: GuestImportStage = GuestImportStage.HIDDEN,
    val snapshot: GuestImportSnapshot? = null, val plan: GuestImportPlan? = null,
    val done: Int = 0, val imported: Int = 0, val kept: Int = 0, val failed: Int = 0,
    val errors: List<String> = emptyList(), val message: String? = null, val refreshFailed: Boolean = false) {
    val busy get() = stage == GuestImportStage.CHECKING || stage == GuestImportStage.RUNNING
}

class GuestImportViewModel(private val repository: GuestImportRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(GuestImportState())
    val state = mutableState.asStateFlow()
    private var loading: Job? = null

    fun open() {
        if (state.value.busy || loading?.isActive == true) return
        mutableState.value = GuestImportState(stage = GuestImportStage.LOADING)
        loading = viewModelScope.launch {
            try {
                val snapshot = repository.snapshot(includeKept = true)
                val stage = if (snapshot.records.isEmpty()) GuestImportStage.RESULT else GuestImportStage.CONSENT
                mutableState.value = GuestImportState(stage, snapshot, message = if (snapshot.records.isEmpty()) "가져올 새로운 데이터가 없어요." else null)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.value = GuestImportState(GuestImportStage.RESULT,
                message = "기기 데이터를 읽지 못했어요. 다시 시도해 주세요.", errors = listOf(error.message ?: "로컬 저장소를 확인해 주세요.")) }
        }
    }

    fun close() {
        if (state.value.busy) return
        loading?.cancel()
        mutableState.value = GuestImportState()
    }

    fun retry() {
        val previous = state.value
        if (previous.stage != GuestImportStage.RESULT) return
        if (!previous.refreshFailed || previous.failed > 0) { open(); return }
        mutableState.value = previous.copy(stage = GuestImportStage.CHECKING)
        viewModelScope.launch {
            try {
                repository.refreshAccount()
                mutableState.value = previous.copy(errors = emptyList(), refreshFailed = false, message = "가져오기를 완료했어요.")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.value = previous.copy(errors = listOf(error.message ?: "계정 정보를 새로고침하지 못했어요.")) }
        }
    }

    fun confirm() {
        val snapshot = state.value.snapshot ?: return
        if (state.value.stage != GuestImportStage.CONSENT) return
        mutableState.value = state.value.copy(stage = GuestImportStage.CHECKING, errors = emptyList(), message = null)
        viewModelScope.launch {
            try {
                val plan = repository.preview(snapshot)
                if (plan.conflicts.isNotEmpty()) mutableState.value = state.value.copy(stage = GuestImportStage.CONFLICT, plan = plan)
                else execute(plan, emptySet())
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutableState.value = state.value.copy(stage = GuestImportStage.RESULT, failed = snapshot.records.size, message = error.message ?: "가져오기 정보를 확인하지 못했어요.")
            }
        }
    }

    fun decide(replace: Set<String>) {
        val plan = state.value.plan ?: return
        if (state.value.stage != GuestImportStage.CONFLICT) return
        mutableState.value = state.value.copy(stage = GuestImportStage.RUNNING)
        viewModelScope.launch { execute(plan, replace) }
    }

    private suspend fun execute(plan: GuestImportPlan, replace: Set<String>) {
        mutableState.value = state.value.copy(stage = GuestImportStage.RUNNING, done = 0, imported = 0, kept = 0, failed = 0, errors = emptyList())
        var imported = 0
        var kept = 0
        var networkFailed = false
        var refreshFailed = false
        val errors = mutableListOf<String>()
        val failedGarments = mutableSetOf<String>()
        for ((index, record) in plan.snapshot.records.withIndex()) {
            try {
                if (record.kind == "outfits") {
                    val items = record.value.getJSONArray("items")
                    check((0 until items.length()).none { items.getJSONObject(it).getString("garment_id") in failedGarments }) { "연결된 옷을 먼저 가져와야 해요." }
                }
                if (repository.importRecord(record, plan, record.key in replace) == "kept") kept++ else imported++
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (error is AuthException && error.code in setOf("AUTHENTICATION_REQUIRED", "SESSION_CHANGED")) throw CancellationException(error.message)
                if (record.kind == "garments") failedGarments += record.id
                errors += "${record.label}: ${error.message ?: "가져오지 못했어요."}"
                if (error is AuthException && (error.code == "NETWORK" || error.code.startsWith("HTTP_5") || error.code == "IMPORT_STORAGE_FAILED")) networkFailed = true
            }
            mutableState.value = state.value.copy(done = index + 1, imported = imported, kept = kept)
            if (networkFailed) break
        }
        if (!networkFailed) {
            try { repository.refreshAccount() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { refreshFailed = true; errors += "계정 정보 새로고침: ${error.message ?: "잠시 후 다시 확인해 주세요."}" }
        }
        val failed = plan.snapshot.records.size - imported - kept
        mutableState.value = state.value.copy(stage = GuestImportStage.RESULT, imported = imported, kept = kept, failed = failed,
            errors = errors.take(5), refreshFailed = refreshFailed, message = when {
                failed > 0 -> "가져오지 못한 항목은 이 기기에 남아 있어요. 다시 시도할 수 있어요."
                refreshFailed -> "데이터는 가져왔지만 계정 정보를 새로고침하지 못했어요."
                else -> "가져오기를 완료했어요."
            })
    }
}

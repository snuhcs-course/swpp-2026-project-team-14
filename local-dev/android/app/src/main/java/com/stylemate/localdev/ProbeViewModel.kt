package com.stylemate.localdev

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProbeState(
    val busy: Boolean = false,
    val message: String = "연결 확인을 눌러 로컬 환경을 테스트하세요.",
    val items: List<ProbeItem> = emptyList(),
)

class ProbeViewModel : ViewModel() {
    private val repository = ProbeRepository()
    private val mutableState = MutableStateFlow(ProbeState())
    val state = mutableState.asStateFlow()

    fun refresh(baseUrl: String) = execute {
        val message = repository.health(baseUrl)
        mutableState.value = ProbeState(message = message, items = repository.list(baseUrl))
    }

    fun save(baseUrl: String, name: String) = execute {
        repository.create(baseUrl, name)
        mutableState.value = ProbeState(message = "저장 완료", items = repository.list(baseUrl))
    }

    private fun execute(action: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true)
        viewModelScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    message = error.message ?: "연결에 실패했습니다. 로컬 서버를 확인하세요.",
                )
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }
}

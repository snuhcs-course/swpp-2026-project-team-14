// AI-generated with Codex, 2026-10-16
package com.swpp.stylemate.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swpp.stylemate.data.AuthException
import com.swpp.stylemate.data.AuthRepository
import com.swpp.stylemate.data.AuthSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthPage { NONE, LOGIN, REGISTER }
data class AuthUiState(val ready: Boolean = false, val session: AuthSession? = null,
    val page: AuthPage = AuthPage.NONE, val busy: Boolean = false, val error: String? = null,
    val retrySeconds: Int = 0, val notice: String? = null, val returnToProfile: Boolean = false)

class AuthViewModel(val repository: AuthRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(AuthUiState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.status.collectLatest { status ->
                mutableState.update { it.copy(session = status.session, notice = status.notice,
                    returnToProfile = it.returnToProfile || (it.session != null && status.session == null)) }
                val session = status.session
                if (session != null) {
                    while (repository.status.value.session?.user?.id == session.user.id) {
                        repository.expireIfNeeded()
                        delay(1_000)
                    }
                }
            }
        }
        viewModelScope.launch {
            try { repository.restore() }
            catch (_: Exception) { mutableState.update { it.copy(notice = "로그인 정보를 불러오지 못했어요. 다시 시도해 주세요.") } }
            finally { mutableState.update { it.copy(ready = true, session = repository.status.value.session) } }
            refreshSession()
        }
    }

    fun open(page: AuthPage) {
        if (!state.value.busy) mutableState.update { it.copy(page = page, error = null, returnToProfile = it.returnToProfile || page != AuthPage.NONE) }
    }

    fun submit(username: String, password: String, confirmation: String? = null) {
        if (state.value.busy || state.value.retrySeconds > 0) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                repository.authenticate(username, password, confirmation)
                mutableState.update { it.copy(page = AuthPage.NONE, session = repository.status.value.session, returnToProfile = true) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failure(error) }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    fun logout() {
        if (state.value.busy) return
        verification?.cancel()
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                repository.logout()
                mutableState.update { it.copy(returnToProfile = true) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.update { it.copy(notice = error.message ?: "로그아웃하지 못했어요. 다시 시도해 주세요.") } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    private fun failure(error: Exception) {
        val seconds = (error as? AuthException)?.retryAfter ?: 0
        mutableState.update { it.copy(error = error.message ?: "요청을 처리하지 못했어요. 다시 시도해 주세요.", retrySeconds = seconds) }
        if (seconds > 0) viewModelScope.launch {
            repeat(seconds) { delay(1_000); mutableState.update { it.copy(retrySeconds = (it.retrySeconds - 1).coerceAtLeast(0)) } }
        }
    }

    private var verification: Job? = null
    fun refreshSession() {
        repository.expireIfNeeded()
        if (verification?.isActive == true || state.value.busy || !state.value.ready || repository.status.value.session == null) return
        verification = viewModelScope.launch {
            try { repository.verify() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Offline sessions retain their cached account until expiry. */ }
        }
    }

    fun dismissNotice() {
        repository.dismissNotice()
        mutableState.update { it.copy(notice = null) }
    }
}

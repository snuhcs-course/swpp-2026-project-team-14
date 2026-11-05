// AI-generated with Codex, 2026-11-05
package com.swpp.stylemate.profile

import com.swpp.stylemate.data.AuthRepository
import com.swpp.stylemate.data.RoutedServer
import com.swpp.stylemate.data.await
import com.swpp.stylemate.data.failure
import com.swpp.stylemate.data.json
import com.swpp.stylemate.ui.profile.AuthPage
import com.swpp.stylemate.ui.profile.AuthViewModel
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    @get:Rule internal val main = ProfileDispatcherRule()
    private val storage = MemoryAuthStore()
    private lateinit var server: RoutedServer
    private lateinit var repository: AuthRepository
    private lateinit var model: AuthViewModel
    private val gates = mutableListOf<ProfileRequestGate>()
    private val epoch = Instant.parse("2026-11-05T00:00:00Z")
    private val userId = "00000000-0000-0000-0000-000000000001"
    private fun now() = epoch.plusMillis(main.dispatcher.scheduler.currentTime)
    private fun user() = JSONObject().put("id", userId).put("username", "member_1")
        .put("body_profile", JSONObject.NULL).put("preferred_fits", JSONArray().put("regular"))
        .put("preferred_styles", JSONArray()).put("favorite_colors", JSONArray())
    private fun session(lifetime: Long = 3600) = JSONObject().put("token", "a".repeat(43))
        .put("expires_at", now().plusSeconds(lifetime).toString()).put("user", user())

    @Before fun setUp() {
        server = RoutedServer()
        repository = AuthRepository(server.baseUrl, storage, now = ::now)
    }
    @After fun tearDown() {
        gates.forEach { it.release.countDown() }
        main.clear()
        server.shutdown()
    }
    private fun start(saved: String? = null) {
        storage.value = saved
        model = main.own(AuthViewModel(repository))
        model.state.await { it.ready }
    }
    private fun login(lifetime: Long = 3600) {
        start()
        model.open(AuthPage.LOGIN)
        server.on("POST /api/auth/login/", json(session(lifetime).toString()))
        model.submit("member_1", "password_123")
        model.state.await { !it.busy && it.session != null }
    }
    private fun hold(method: String, path: String) = ProfileRequestGate(server, method, path).also { gates += it }
    private fun advance(milliseconds: Long) {
        main.dispatcher.scheduler.advanceTimeBy(milliseconds)
        main.dispatcher.scheduler.runCurrent()
    }

    @Test fun restore_withoutSessionBecomesReadyWithoutNetworkRequests() {
        start()
        assertNull(model.state.value.session)
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.notice)
        assertEquals(0, server.server.requestCount)
    }

    @Test fun restore_validSessionVerifiesProfileAndPropagatesUpdatedAccount() {
        val updated = user().put("favorite_colors", JSONArray().put("#1F2A44"))
        server.on("GET /api/users/me/", json(JSONObject().put("user", updated).toString()))
        start(session().toString())
        val state = model.state.await { it.session?.user?.style?.favoriteColors == listOf("#1F2A44") }
        assertEquals(userId, state.session!!.user.id)
        assertFalse(state.returnToProfile)
        assertEquals(1, server.requests("GET /api/users/me/").size)
        assertNotNull(storage.value)
    }

    @Test fun restore_expiredSessionClearsStorageAndExplainsExpiry() {
        start(session(-1).toString())
        assertNull(model.state.value.session)
        assertNull(storage.value)
        assertEquals(AuthRepository.EXPIRED, model.state.value.notice)
        assertEquals(0, server.server.requestCount)
    }

    @Test fun restore_corruptSessionBecomesReadyWithRecoveryNotice() {
        start("broken session")
        assertNull(model.state.value.session)
        assertNull(storage.value)
        assertNotNull(model.state.value.notice)
        assertEquals(0, server.server.requestCount)
    }

    @Test fun restore_storageFailureDoesNotLeaveStartupBlocked() {
        storage.readFailure = IllegalStateException("Cannot read")
        storage.clearFailure = IllegalStateException("Cannot clear")
        start()
        assertTrue(model.state.value.ready)
        assertNull(model.state.value.session)
        assertEquals("로그인 정보를 불러오지 못했어요. 다시 시도해 주세요.", model.state.value.notice)
    }

    @Test fun open_changesPageAndClearsPreviousFormError() {
        start()
        model.open(AuthPage.LOGIN)
        model.submit("UPPER", "password_123")
        model.state.await { !it.busy && it.error != null }
        model.open(AuthPage.REGISTER)
        assertEquals(AuthPage.REGISTER, model.state.value.page)
        assertNull(model.state.value.error)
        assertTrue(model.state.value.returnToProfile)
        model.open(AuthPage.NONE)
        assertEquals(AuthPage.NONE, model.state.value.page)
        assertEquals(0, server.server.requestCount)
    }

    @Test fun submit_loginSuccessClosesFormAndPublishesSession() {
        login()
        val state = model.state.value
        assertEquals(AuthPage.NONE, state.page)
        assertEquals(userId, state.session!!.user.id)
        assertTrue(state.returnToProfile)
        assertNull(state.error)
        assertNotNull(storage.value)
        assertEquals(1, server.requests("POST /api/auth/login/").size)
    }

    @Test fun submit_registrationUsesConfirmationAndSignsIn() {
        start()
        model.open(AuthPage.REGISTER)
        server.on("POST /api/auth/register/", json(session().toString()).setResponseCode(201))
        model.submit("member_1", "password_123", "password_123")
        val state = model.state.await { !it.busy && it.session != null }
        assertEquals(AuthPage.NONE, state.page)
        assertEquals("password_123", server.requests("POST /api/auth/register/").single()
            .getString("password_confirmation"))
        assertTrue(state.returnToProfile)
        assertNull(state.error)
    }

    @Test fun submit_wrongCredentialsPreservesFormAndAllowsRetry() {
        start()
        model.open(AuthPage.LOGIN)
        server.on("POST /api/auth/login/", failure(401, "INVALID_CREDENTIALS"), json(session().toString()))
        model.submit("member_1", "password_123")
        val failed = model.state.await { !it.busy && it.error != null }
        assertEquals(AuthPage.LOGIN, failed.page)
        assertEquals("아이디 또는 비밀번호가 올바르지 않습니다.", failed.error)
        assertNull(failed.session)
        assertNull(failed.notice)
        model.submit("member_1", "password_123")
        assertNull(model.state.value.error)
        val retried = model.state.await { !it.busy && it.session != null }
        assertEquals(AuthPage.NONE, retried.page)
        assertEquals(2, server.requests("POST /api/auth/login/").size)
    }

    @Test fun submit_confirmationMismatchNeverSendsRegistration() {
        start()
        model.open(AuthPage.REGISTER)
        model.submit("member_1", "password_123", "different")
        val state = model.state.await { !it.busy && it.error != null }
        assertEquals(AuthPage.REGISTER, state.page)
        assertEquals("비밀번호 확인이 일치하지 않아요.", state.error)
        assertNull(state.session)
        assertEquals(0, server.server.requestCount)
    }

    @Test fun submit_pendingLoginBlocksDuplicateSubmissionNavigationAndLogout() {
        start()
        model.open(AuthPage.LOGIN)
        server.on("POST /api/auth/login/", json(session().toString()))
        val gate = hold("POST", "/api/auth/login/")
        model.submit("member_1", "password_123")
        gate.awaitRequest()
        model.submit("member_1", "password_123")
        model.open(AuthPage.REGISTER)
        model.logout()
        model.refreshSession()
        assertTrue(model.state.value.busy)
        assertEquals(AuthPage.LOGIN, model.state.value.page)
        gate.release.countDown()
        model.state.await { !it.busy && it.session != null }
        assertEquals(1, server.requests("POST /api/auth/login/").size)
        assertTrue(server.requests("POST /api/auth/logout/").isEmpty())
        assertTrue(server.requests("GET /api/users/me/").isEmpty())
    }

    @Test fun submit_rateLimitCountsDownAndBlocksRequestsUntilZero() {
        start()
        server.on("POST /api/auth/login/", MockResponse().setResponseCode(429).setHeader("Retry-After", "2")
            .setBody("""{"error":"LOGIN_LOCKED","retry_after":2}"""), json(session().toString()))
        model.submit("member_1", "password_123")
        model.state.await { !it.busy && it.retrySeconds == 2 }
        model.submit("member_1", "password_123")
        assertEquals(1, server.requests("POST /api/auth/login/").size)
        advance(1000)
        assertEquals(1, model.state.value.retrySeconds)
        model.submit("member_1", "password_123")
        assertEquals(1, server.requests("POST /api/auth/login/").size)
        advance(1000)
        assertEquals(0, model.state.value.retrySeconds)
        model.submit("member_1", "password_123")
        model.state.await { !it.busy && it.session != null }
        assertEquals(2, server.requests("POST /api/auth/login/").size)
    }

    @Test fun logout_successRevokesSessionAndReturnsToLoginFlow() {
        server.on("GET /api/users/me/", json(JSONObject().put("user", user()).toString()))
        start(session().toString())
        assertFalse(model.state.value.returnToProfile)
        server.on("POST /api/auth/logout/", MockResponse().setResponseCode(204))
        model.logout()
        val state = model.state.await { !it.busy && it.session == null }
        assertTrue(state.returnToProfile)
        assertNull(storage.value)
        assertEquals(1, server.requests("POST /api/auth/logout/").size)
    }

    @Test fun logout_failurePreservesSessionAndAllowsRetry() {
        login()
        server.on("POST /api/auth/logout/", failure(503, "UNAVAILABLE"), MockResponse().setResponseCode(204))
        model.logout()
        val failed = model.state.await { !it.busy && it.notice != null }
        assertNotNull(failed.session)
        assertNotNull(storage.value)
        model.logout()
        val retried = model.state.await { !it.busy && it.session == null }
        assertNull(storage.value)
        assertNull(retried.notice)
        assertEquals(2, server.requests("POST /api/auth/logout/").size)
    }

    @Test fun session_expiryAtBoundaryClearsSessionWithoutAnotherRequest() {
        login(lifetime = 3)
        advance(2000)
        assertNotNull(model.state.value.session)
        advance(1000)
        val state = model.state.await { it.session == null && it.notice == AuthRepository.EXPIRED }
        assertNull(storage.value)
        assertTrue(state.returnToProfile)
        assertEquals(1, server.server.requestCount)
    }

    @Test fun refreshSession_server401ExpiresTheDisplayedSession() {
        login()
        server.on("GET /api/users/me/", failure(401, "AUTHENTICATION_REQUIRED"))
        model.refreshSession()
        val state = model.state.await { it.session == null && it.notice == AuthRepository.EXPIRED }
        assertNull(storage.value)
        assertTrue(state.returnToProfile)
        assertEquals(1, server.requests("GET /api/users/me/").size)
    }

    @Test fun refreshSession_pendingVerificationIsNotDuplicatedAndLogoutCancelsIt() {
        login()
        server.on("GET /api/users/me/", json(JSONObject().put("user", user()).toString()))
        val gate = hold("GET", "/api/users/me/")
        model.refreshSession()
        gate.awaitRequest()
        model.refreshSession()
        model.refreshSession()
        server.on("POST /api/auth/logout/", MockResponse().setResponseCode(204))
        model.logout()
        model.state.await { !it.busy && it.session == null }
        gate.release.countDown()
        assertEquals(1, server.requests("GET /api/users/me/").size)
        assertNull(repository.status.value.session)
        assertNull(storage.value)
    }

    @Test fun dismissNoticeClearsBothRepositoryAndDisplayedNotice() {
        start(session(-1).toString())
        assertNotNull(model.state.value.notice)
        model.dismissNotice()
        assertNull(model.state.value.notice)
        assertNull(repository.status.value.notice)
    }

    @Test fun clearingViewModelDuringLoginDoesNotSaveLateSession() {
        start()
        server.on("POST /api/auth/login/", json(session().toString()))
        val gate = hold("POST", "/api/auth/login/")
        model.submit("member_1", "password_123")
        gate.awaitRequest()
        main.clear()
        gate.release.countDown()
        model.state.await { !it.busy }
        assertNull(storage.value)
        assertNull(repository.status.value.session)
        assertNull(model.state.value.error)
    }
}

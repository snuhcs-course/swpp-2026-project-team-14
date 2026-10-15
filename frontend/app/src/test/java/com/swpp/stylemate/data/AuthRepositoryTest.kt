// AI-generated with Codex, 2026-10-16
package com.swpp.stylemate.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

class AuthRepositoryTest {
    private val server = MockWebServer()
    private val storage = MemorySessionStore()
    private var time = Instant.parse("2026-10-16T00:00:00Z")
    private val firstId = UUID.randomUUID().toString()
    private lateinit var repository: AuthRepository

    @Before fun setup() { server.start(); repository = AuthRepository(server.url("/").toString(), storage, now = { time }) }
    @After fun close() { server.shutdown() }

    private fun user(id: String = firstId, name: String = "member_1") = JSONObject()
        .put("id", id).put("username", name).put("body_profile", JSONObject.NULL)
        .put("preferred_fits", org.json.JSONArray(listOf("regular"))).put("preferred_styles", org.json.JSONArray())
        .put("favorite_colors", org.json.JSONArray())
    private fun session(token: String = "a".repeat(43), id: String = firstId, name: String = "member_1") = JSONObject()
        .put("token", token).put("expires_at", time.plusSeconds(30 * 86400).toString().replace("Z", "+00:00")).put("user", user(id, name))
    private fun response(data: JSONObject, code: Int = 200) = MockResponse().setResponseCode(code).setBody(data.toString())
    private fun signIn(token: String = "a".repeat(43), id: String = firstId, name: String = "member_1") = runBlocking {
        server.enqueue(response(session(token, id, name)))
        repository.authenticate(name, "password_123")
    }

    @Test fun registrationUsesOnlyIdPasswordAndConfirmationAndPersistsSession() = runBlocking {
        server.enqueue(response(session(), 201))
        repository.authenticate("member_1", "password_123", "password_123")
        val request = server.takeRequest()
        assertEquals("/api/auth/register/", request.path)
        assertNull(request.getHeader("Authorization"))
        val payload = JSONObject(request.body.readUtf8())
        assertEquals(setOf("username", "password", "password_confirmation"), payload.keys().asSequence().toSet())
        assertEquals(firstId, repository.status.value.session!!.user.id)
        assertFalse(storage.value!!.contains("password_123"))
        assertFalse(repository.status.value.session.toString().contains("a".repeat(43)))
    }

    @Test fun correctedConfirmationCanRegisterThenLoginWithDjangoTimestamp() = runBlocking {
        try { repository.authenticate("member_1", "password_123", "different"); fail() }
        catch (error: AuthException) { assertEquals("PASSWORD_MISMATCH", error.code) }
        assertEquals(0, server.requestCount)
        val payload = session().put("expires_at", "2026-11-15T20:24:21.123456+00:00")
        server.enqueue(response(payload, 201))
        repository.authenticate("member_1", "password_123", "password_123")
        assertEquals(Instant.parse("2026-11-15T20:24:21.123456Z"), repository.status.value.session!!.expiresAt)
        server.enqueue(MockResponse().setResponseCode(204))
        repository.logout()
        server.enqueue(response(payload))
        repository.authenticate("member_1", "password_123")
        assertEquals(firstId, repository.status.value.session!!.user.id)
    }

    @Test fun restoredSessionSurvivesRestartAndMeUsesBearerToken() = runBlocking {
        signIn()
        server.takeRequest()
        val reopened = AuthRepository(server.url("/").toString(), storage, now = { time })
        reopened.restore()
        server.enqueue(response(JSONObject().put("user", user())))
        reopened.verify()
        assertEquals("/api/users/me/", server.takeRequest().let { assertEquals("Bearer ${"a".repeat(43)}", it.getHeader("Authorization")); it.path })
        assertEquals(firstId, reopened.status.value.session!!.user.id)
    }

    @Test fun expiryAtExactBoundaryClearsStorageWithoutRequest() = runBlocking {
        signIn()
        time = time.plusSeconds(30 * 86400)
        repository.expireIfNeeded()
        assertNull(repository.status.value.session)
        assertNull(storage.value)
        assertEquals(AuthRepository.EXPIRED, repository.status.value.notice)
        assertEquals(1, server.requestCount)
    }

    @Test fun expiredSessionIsNotRestored() = runBlocking {
        storage.value = session().toString()
        time = time.plusSeconds(31 * 86400)
        repository.restore()
        assertNull(repository.status.value.session)
        assertNull(storage.value)
        assertEquals(0, server.requestCount)
    }

    @Test fun revokedToken401SignsOutAndOtherFailuresDoNot() = runBlocking {
        signIn()
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        try { repository.verify(); fail() } catch (_: AuthException) {}
        assertNotNull(repository.status.value.session)
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"AUTHENTICATION_REQUIRED\"}"))
        try { repository.verify(); fail() } catch (error: AuthException) { assertEquals("AUTHENTICATION_REQUIRED", error.code) }
        assertNull(repository.status.value.session)
        assertNull(storage.value)
    }

    @Test fun old401CannotSignOutNewAccountAndOldRequestsAreRejected() = runBlocking {
        signIn()
        val old = repository.account()!!
        val second = UUID.randomUUID().toString()
        signIn("b".repeat(43), second, "member_2")
        try { old.checkResponse(401); fail() } catch (_: AuthException) {}
        assertEquals(second, repository.status.value.session!!.user.id)
        try { old.json("GET", "/api/users/me/"); fail() } catch (error: AuthException) { assertEquals("SESSION_CHANGED", error.code) }
        assertEquals(2, server.requestCount)
    }

    @Test fun logoutRevokesOnServerBeforeClearingAndFailureCanBeRetried() = runBlocking {
        signIn()
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        try { repository.logout(); fail() } catch (_: AuthException) {}
        assertNotNull(repository.status.value.session)
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(204))
        repository.logout()
        val request = server.takeRequest()
        assertEquals("/api/auth/logout/", request.path)
        assertEquals("Bearer ${"a".repeat(43)}", request.getHeader("Authorization"))
        assertNull(repository.status.value.session)
        assertNull(storage.value)
    }

    @Test fun rateLimitAndCredentialErrorsHaveActionableMessages() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "300")
            .setBody("{\"error\":\"LOGIN_LOCKED\",\"retry_after\":300}"))
        try { repository.authenticate("member_1", "password_123"); fail() }
        catch (error: AuthException) { assertEquals(300, error.retryAfter); assertEquals("LOGIN_LOCKED", error.code) }
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"INVALID_CREDENTIALS\"}"))
        try { repository.authenticate("member_1", "password_123"); fail() }
        catch (error: AuthException) { assertEquals("아이디 또는 비밀번호가 올바르지 않습니다.", error.message) }
        assertNull(repository.status.value.notice)
    }

    @Test fun invalidFormNeverCallsServerAndDoesNotTrimPassword() = runBlocking {
        for ((id, password, confirmation) in listOf(Triple("UPPER", "password_123", null), Triple("member_1", "short", null), Triple("member_1", "password_123", "different"))) {
            try { repository.authenticate(id, password, confirmation); fail() } catch (_: AuthException) {}
        }
        assertEquals(0, server.requestCount)
        val password = " password_123 "
        server.enqueue(response(session()))
        repository.authenticate("member_1", password)
        assertEquals(password, JSONObject(server.takeRequest().body.readUtf8()).getString("password"))
    }

    @Test fun corruptSessionOrMalformedLoginResponseNeverCreatesAccountState() = runBlocking {
        storage.value = "broken"
        repository.restore()
        assertNull(storage.value)
        assertNull(repository.status.value.session)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        try { repository.authenticate("member_1", "password_123"); fail() } catch (error: AuthException) { assertEquals("INVALID_RESPONSE", error.code) }
        assertNull(storage.value)
    }

    @Test fun profilePatchUpdatesOnlyAccountCacheAndEncryptedSessionPayload() = runBlocking {
        val cached = mutableListOf<AccountUser>()
        repository = AuthRepository(server.url("/").toString(), storage, now = { time }, cacheUser = cached::add)
        signIn()
        server.takeRequest()
        val updated = user().put("favorite_colors", org.json.JSONArray(listOf("#1F2A44")))
        server.enqueue(response(JSONObject().put("user", updated)))
        repository.account()!!.patchProfile(JSONObject().put("favorite_colors", org.json.JSONArray(listOf("#1F2A44"))))
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals(setOf("favorite_colors"), JSONObject(request.body.readUtf8()).keys().asSequence().toSet())
        assertEquals(listOf("#1F2A44"), cached.last().style.favoriteColors)
        assertEquals(listOf("#1F2A44"), AuthSession.parse(JSONObject(storage.value!!)).user.style.favoriteColors)
    }

    private class MemorySessionStore : AuthSessionStore {
        var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
        override fun clear() { value = null }
    }
}

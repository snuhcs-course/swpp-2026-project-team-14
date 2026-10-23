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
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import org.json.JSONArray
import com.swpp.stylemate.data.wardrobe.WardrobeRepository
import com.swpp.stylemate.data.recommendation.OutfitRepository
import com.swpp.stylemate.data.recommendation.OutfitApiException
import java.time.Instant
import java.util.UUID

class AuthRepositoryTest {
    @get:Rule val temporary = TemporaryFolder(File("build/account-list-tests").apply { mkdirs() })

    @Test fun accountWardrobeLoadsEveryPageBeforeReplacingCache() = runBlocking {
        signIn()
        server.takeRequest()
        val account = repository.account()!!
        val local = LocalUserStore.inDirectory(temporary.root, firstId)
        val cursor = UUID.randomUUID().toString()
        fun row(id: String) = JSONObject().put("id", id).put("saved", true).put("notes", "한".repeat(2000))
        // The combined list exceeds 256 KiB; each response remains bounded.
        val pages = (0 until 7).map { page -> JSONArray((0 until 20).map { row("$page-$it") }) }
        for ((index, rows) in pages.withIndex()) server.enqueue(response(JSONObject().put("items", rows)
            .put("next", if (index < pages.lastIndex) UUID.nameUUIDFromBytes("$index".toByteArray()).toString() else JSONObject.NULL)))
        val result = WardrobeRepository.json("/api/wardrobe/items/", store = local, account = account)
        assertEquals(140, result.getJSONArray("items").length())
        assertEquals(140, local.garments().size)
        repeat(7) { index ->
            val request = server.takeRequest()
            assertEquals("Bearer ${"a".repeat(43)}", request.getHeader("Authorization"))
            assertEquals(if (index == 0) "/api/wardrobe/items/" else "/api/wardrobe/items/?after=${UUID.nameUUIDFromBytes("${index - 1}".toByteArray())}", request.path)
        }
        server.enqueue(response(JSONObject().put("items", JSONArray().put(row("partial"))).put("next", cursor)))
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        try { WardrobeRepository.json("/api/wardrobe/items/", store = local, account = account); fail() }
        catch (_: AuthException) {}
        assertEquals(140, local.garments().size)
        assertNull(local.garment("partial"))
    }

    @Test fun editorOptionsUseAuthenticationAndNeverHideExpiryBehindCachedOptions() = runBlocking {
        signIn()
        server.takeRequest()
        val account = checkNotNull(repository.account())
        val local = LocalUserStore.inDirectory(temporary.root, firstId)
        server.enqueue(response(JSONObject().put("options", "current")))
        assertEquals("current", WardrobeRepository.json("/api/wardrobe/options/", store = local, account = account).getString("options"))
        assertEquals("Bearer ${"a".repeat(43)}", server.takeRequest().getHeader("Authorization"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        try { WardrobeRepository.json("/api/wardrobe/options/", store = local, account = account); fail("Cached options must not bypass expiry") }
        catch (error: AuthException) { assertEquals("AUTHENTICATION_REQUIRED", error.code) }
        assertNull(repository.status.value.session)
        assertEquals("current", local.get("catalog")!!.getString("options"))
    }

    @Test fun accountListsRejectRepeatedOrUnsafeCursorsAndKeepResponseLimit() = runBlocking {
        signIn()
        val account = repository.account()!!
        for (cursor in listOf("https://other.example/", "bad/id", "x".repeat(513))) {
            server.enqueue(response(JSONObject().put("items", JSONArray()).put("next", cursor)))
            try { account.list("/api/wardrobe/items/", "items"); fail() }
            catch (error: AuthException) { assertEquals("INVALID_RESPONSE", error.code) }
        }
        val repeated = UUID.randomUUID().toString()
        repeat(2) { server.enqueue(response(JSONObject().put("items", JSONArray()).put("next", repeated))) }
        try { account.list("/api/wardrobe/items/", "items"); fail() }
        catch (error: AuthException) { assertEquals("INVALID_RESPONSE", error.code) }
        server.enqueue(response(JSONObject().put("items", JSONArray()).put("padding", "x".repeat(256 * 1024))))
        try { account.list("/api/wardrobe/items/", "items"); fail() }
        catch (error: AuthException) { assertEquals("INVALID_RESPONSE", error.code) }
        assertNotNull(repository.status.value.session)
    }

    @Test fun savedAccountOutfitsLoadAllPages() = runBlocking {
        signIn()
        val cursor = UUID.randomUUID().toString()
        fun outfit(id: String) = JSONObject().put("id", id).put("items", JSONArray()).put("saved", true)
        server.enqueue(response(JSONObject().put("outfits", JSONArray().put(outfit(cursor))).put("next", cursor)))
        server.enqueue(response(JSONObject().put("outfits", JSONArray().put(outfit("second"))).put("next", JSONObject.NULL)))
        val outfits = OutfitRepository(checkNotNull(repository.account())).saved()
        assertEquals(listOf(cursor, "second"), outfits.map { it.id })
    }

    @Test fun savedAccountOutfitPageFailuresUseTheExceptionHandledByTheScreen() = runBlocking {
        for ((status, expected) in listOf(503 to "HTTP_503", 401 to "AUTHENTICATION_REQUIRED")) {
            signIn()
            val repo = OutfitRepository(checkNotNull(repository.account()))
            val cursor = UUID.randomUUID().toString()
            server.enqueue(response(JSONObject().put("outfits", JSONArray()).put("next", cursor)))
            server.enqueue(MockResponse().setResponseCode(status).setBody("{}"))
            try { repo.saved(); fail() }
            catch (error: OutfitApiException) { assertEquals(expected, error.code) }
            assertEquals(status != 401, repository.status.value.session != null)
        }
    }

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

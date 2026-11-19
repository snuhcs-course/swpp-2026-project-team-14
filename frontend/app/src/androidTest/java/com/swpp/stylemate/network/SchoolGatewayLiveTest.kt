package com.swpp.stylemate.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.stylemate.BuildConfig
import com.swpp.stylemate.data.AuthException
import com.swpp.stylemate.data.AuthRepository
import com.swpp.stylemate.data.AuthSessionStore
import com.swpp.stylemate.data.network.SchoolGateway
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/** Opt-in public-server smoke checks; never part of the offline CI test suite. */
@RunWith(AndroidJUnit4::class)
class SchoolGatewayLiveTest {
    private val base = "http://swpp26t14.147-46-242-169.sslip.io"

    @Before fun requireExplicitLiveRun() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifySchoolGateway") == "true")
        assertEquals(base, BuildConfig.API_BASE_URL)
    }

    private fun get(path: String): Pair<Int, JSONObject> {
        SchoolGateway.prepare(base + path, "POST") // Require a fresh probe for each independent smoke check.
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.instanceFollowRedirects = false
        return try {
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            status to stream.bufferedReader().use { JSONObject(it.readText()) }
        } finally { connection.disconnect() }
    }

    @Test fun healthReachesBackendAfterVerification() {
        val (status, json) = get("/healthz/")
        assertEquals(200, status)
        assertEquals("ok", json.getString("status"))
    }

    @Test fun protectedApiReturnsAuthenticationResponse() {
        val (status, json) = get("/api/users/me/")
        assertEquals(401, status)
        assertEquals("AUTHENTICATION_REQUIRED", json.getString("error"))
    }

    @Test fun loginPostReceivesCredentialErrorInsteadOfRedirect() = runBlocking {
        val storage = object : AuthSessionStore {
            override fun read(): String? = null
            override fun write(value: String) { fail("An invalid login must not save a session") }
            override fun clear() = Unit
        }
        val repository = AuthRepository(base, storage)
        try {
            repository.authenticate("gateway_probe_2026", "LiveConnectionProbe_2026")
            fail("Expected invalid credentials for the non-existent probe account")
        } catch (error: AuthException) { assertEquals("INVALID_CREDENTIALS", error.code) }
        assertNull(repository.status.value.session)
    }
}

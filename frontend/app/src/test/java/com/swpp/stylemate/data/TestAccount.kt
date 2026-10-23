package com.swpp.stylemate.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

internal const val TEST_TOKEN = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
internal fun testAccount(baseUrl: String): AccountAccess {
    val user = AccountUser(UUID.fromString("00000000-0000-0000-0000-000000000001").toString(), "member_1", null, StyleProfile())
    val saved = AuthSession(TEST_TOKEN, Instant.parse("2099-01-01T00:00:00Z"), user).json().toString()
    val repo = AuthRepository(baseUrl, object : AuthSessionStore {
        override fun read() = saved
        override fun write(value: String) = Unit
        override fun clear() = Unit
    })
    runBlocking { repo.restore() }
    return checkNotNull(repo.account())
}

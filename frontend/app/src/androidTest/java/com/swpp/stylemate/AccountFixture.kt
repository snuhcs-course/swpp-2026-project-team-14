package com.swpp.stylemate

import com.swpp.stylemate.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.time.Instant

internal const val ACCOUNT_ID = "00000000-0000-0000-0000-000000000001"
internal fun authenticatedRepository(client: OkHttpClient = OkHttpClient(), cacheUser: (AccountUser) -> Unit = {}): AuthRepository {
    val session = AuthSession("a".repeat(43), Instant.parse("2099-01-01T00:00:00Z"), AccountUser(ACCOUNT_ID, "member_1", null, StyleProfile()))
    val repo = AuthRepository("http://localhost", object : AuthSessionStore {
        override fun read() = session.json().toString()
        override fun write(value: String) = Unit
        override fun clear() = Unit
    }, client, cacheUser = cacheUser)
    runBlocking { repo.restore() }
    return repo
}

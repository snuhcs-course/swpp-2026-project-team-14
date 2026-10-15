// AI-generated with Codex, 2026-10-16
package com.swpp.stylemate.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

class AuthException(val code: String, message: String, val retryAfter: Int = 0) : Exception(message)

class AccountUser(val id: String, val username: String, val body: JSONObject?, val style: StyleProfile) {
    fun json() = style.json().put("id", id).put("username", username).put("body_profile", body ?: JSONObject.NULL)
    companion object {
        fun parse(json: JSONObject): AccountUser {
            val id = json.getString("id")
            require(UUID.fromString(id).toString() == id)
            val username = json.getString("username")
            require(username.matches(Regex("[a-z0-9_]{4,20}")))
            return AccountUser(id, username, json.optJSONObject("body_profile"), StyleProfile.decode(json))
        }
    }
}

class AuthSession(internal val token: String, val expiresAt: Instant, val user: AccountUser) {
    init { require(token.matches(Regex("[A-Za-z0-9_-]{43}"))) }
    internal fun json() = JSONObject().put("token", token).put("expires_at", expiresAt.toString()).put("user", user.json())
    override fun toString() = "AuthSession(user=${user.username}, expiresAt=$expiresAt)"
    companion object {
        // Some supported Android versions reject numeric UTC offsets in Instant.parse.
        fun parse(json: JSONObject) = AuthSession(json.getString("token"), OffsetDateTime.parse(json.getString("expires_at")).toInstant(), AccountUser.parse(json.getJSONObject("user")))
    }
}

data class AuthStatus(val session: AuthSession? = null, val notice: String? = null)

/** One repository owns the active session; requests carry the session they started with. */
class AuthRepository(
    baseUrl: String,
    private val storage: AuthSessionStore,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build(),
    private val now: () -> Instant = Instant::now,
    private val cacheUser: (AccountUser) -> Unit = {},
) {
    private val base = baseUrl.trimEnd('/')
    private val lock = Any()
    private val mutableStatus = MutableStateFlow(AuthStatus())
    val status = mutableStatus.asStateFlow()

    suspend fun restore() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            try {
                val saved = storage.read()?.let { AuthSession.parse(JSONObject(it)) }
                if (saved != null && !saved.expiresAt.isAfter(now())) {
                    storage.clear()
                    mutableStatus.value = AuthStatus(notice = EXPIRED)
                } else {
                    saved?.let { cacheUser(it.user) }
                    mutableStatus.value = AuthStatus(saved)
                }
            } catch (_: Exception) {
                storage.clear()
                mutableStatus.value = AuthStatus(notice = "저장된 로그인 정보를 읽지 못했어요. 다시 로그인해 주세요.")
            }
        }
    }

    suspend fun authenticate(username: String, password: String, confirmation: String? = null) {
        requireInput(username, password, confirmation)
        val data = JSONObject().put("username", username).put("password", password)
        confirmation?.let { data.put("password_confirmation", it) }
        val result = request("POST", if (confirmation == null) "/api/auth/login/" else "/api/auth/register/", data)
        val session = try { AuthSession.parse(result) } catch (_: Exception) { throw AuthException("INVALID_RESPONSE", "로그인 응답을 읽지 못했어요. 다시 시도해 주세요.") }
        if (!session.expiresAt.isAfter(now())) throw AuthException("INVALID_RESPONSE", "로그인 유효기간을 확인하지 못했어요.")
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                cacheUser(session.user)
                storage.write(session.json().toString())
                mutableStatus.value = AuthStatus(session)
            }
        }
    }

    fun account(): AccountAccess? = mutableStatus.value.session?.let { AccountAccess(this, it) }

    fun expireIfNeeded() {
        mutableStatus.value.session?.let { if (!it.expiresAt.isAfter(now())) invalidate(it) }
    }

    internal fun check(session: AuthSession) {
        synchronized(lock) {
            if (mutableStatus.value.session?.token != session.token) throw AuthException("SESSION_CHANGED", EXPIRED)
            if (!session.expiresAt.isAfter(now())) { invalidate(session); throw AuthException("AUTHENTICATION_REQUIRED", EXPIRED) }
        }
    }

    internal fun invalidate(session: AuthSession) = synchronized(lock) {
        if (mutableStatus.value.session?.token == session.token) {
            mutableStatus.value = AuthStatus(notice = EXPIRED)
            runCatching { storage.clear() }
        }
    }

    fun dismissNotice() { mutableStatus.value = mutableStatus.value.copy(notice = null) }

    suspend fun verify() {
        val session = mutableStatus.value.session ?: return
        refresh(session)
    }

    internal suspend fun refresh(session: AuthSession) {
        val result = request("GET", "/api/users/me/", session = session)
        updateUser(session, result.getJSONObject("user"))
    }

    internal suspend fun patch(session: AuthSession, data: JSONObject) {
        val result = request("PATCH", "/api/users/me/", data, session)
        updateUser(session, result.getJSONObject("user"))
    }

    private suspend fun updateUser(session: AuthSession, json: JSONObject) = withContext(Dispatchers.IO) {
        val user = AccountUser.parse(json)
        require(user.id == session.user.id)
        synchronized(lock) {
            check(session)
            val updated = AuthSession(session.token, session.expiresAt, user)
            cacheUser(user)
            storage.write(updated.json().toString())
            mutableStatus.value = AuthStatus(updated)
        }
    }

    /** Keep the session on network failure so the user can retry server-side token revocation. */
    suspend fun logout() {
        val session = mutableStatus.value.session ?: return
        try { request("POST", "/api/auth/logout/", JSONObject(), session) }
        catch (error: AuthException) { if (error.code != "AUTHENTICATION_REQUIRED") throw error }
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (mutableStatus.value.session?.token == session.token) {
                    storage.clear()
                    mutableStatus.value = AuthStatus()
                }
            }
        }
    }

    internal suspend fun request(method: String, path: String, data: JSONObject? = null, session: AuthSession? = null): JSONObject =
        send(method, path, data?.toString()?.toRequestBody("application/json".toMediaType()), session)

    internal suspend fun upload(path: String, metadata: JSONObject, image: ByteArray, session: AuthSession): JSONObject {
        require(image.isNotEmpty() && image.size <= 5 * 1024 * 1024)
        val payload = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("metadata", metadata.toString())
            .addFormDataPart("image", "garment.jpg", image.toRequestBody("image/jpeg".toMediaType())).build()
        return send("POST", path, payload, session)
    }

    private suspend fun send(method: String, path: String, payload: RequestBody?, session: AuthSession?): JSONObject = withContext(Dispatchers.IO) {
        require(path.startsWith("/api/"))
        session?.let(::check)
        val builder = Request.Builder().url(base + path).method(method, payload)
        session?.let { builder.header("Authorization", "Bearer ${it.token}") }
        val response = try {
            client.newCall(builder.build()).execute().use { result ->
                currentCoroutineContext().ensureActive()
                if (session != null && result.code == 401) {
                    invalidate(session)
                    throw AuthException("AUTHENTICATION_REQUIRED", EXPIRED)
                }
                val body = result.body?.byteStream()?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= MAX_RESPONSE) {
                        val size = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE + 1 - output.size()))
                        if (size < 0) break
                        output.write(buffer, 0, size)
                    }
                    output.toByteArray()
                } ?: byteArrayOf()
                if (body.size > MAX_RESPONSE) throw AuthException("INVALID_RESPONSE", "서버 응답이 너무 커요.")
                Triple(result.code, body.toString(Charsets.UTF_8), result.header("Retry-After")?.toIntOrNull())
            }
        } catch (error: IOException) { throw AuthException("NETWORK", "서버에 연결하지 못했어요. 연결을 확인하고 다시 시도해 주세요.") }
        currentCoroutineContext().ensureActive()
        val (code, text, retryHeader) = response
        session?.let(::check)
        val json = if (text.isBlank() && code == 204) JSONObject() else runCatching { JSONObject(text) }.getOrNull()
        if (code in 200..299 && json != null) return@withContext json
        val errorCode = json?.optString("error").orEmpty().ifEmpty { "HTTP_$code" }
        val retry = (json?.optInt("retry_after", retryHeader ?: 0) ?: retryHeader ?: 0).coerceIn(0, 3600)
        throw AuthException(errorCode, when (errorCode) {
            "INVALID_CREDENTIALS" -> "아이디 또는 비밀번호가 올바르지 않습니다."
            "USERNAME_TAKEN" -> "이미 사용 중인 아이디예요."
            "INVALID_USERNAME" -> "아이디는 영문 소문자·숫자·밑줄 4~20자로 입력해 주세요."
            "INVALID_PASSWORD" -> "비밀번호는 8~64자로 입력해 주세요."
            "PASSWORD_MISMATCH" -> "비밀번호 확인이 일치하지 않아요."
            "LOGIN_LOCKED" -> "로그인 시도가 잠시 제한됐어요. 잠시 후 다시 시도해 주세요."
            "TOO_MANY_REQUESTS" -> "요청이 너무 많아요. 잠시 후 다시 시도해 주세요."
            "INVALID_PROFILE" -> "입력한 프로필 정보를 확인해 주세요."
            "IMPORT_CHANGED" -> "계정 데이터가 변경됐어요. 다시 확인한 뒤 가져와 주세요."
            "INVALID_IMPORT" -> "가져올 데이터와 옷·코디 연결을 확인해 주세요."
            "IMPORT_STORAGE_FAILED" -> "사진을 보관하지 못했어요. 잠시 후 다시 시도해 주세요."
            "INVALID_IMAGE", "IMAGE_TOO_LARGE" -> "옷 사진의 형식이나 용량을 확인해 주세요."
            "GARMENT_CATEGORY_IN_USE" -> "코디에 사용 중인 옷은 분류를 바꿀 수 없어요."
            "GARMENT_CATEGORY_CHANGED" -> "옷 정보가 변경됐어요. 다시 시도해 주세요."
            else -> "요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요."
        }, retry)
    }

    companion object {
        const val EXPIRED = "로그인이 만료됐어요. 다시 로그인해 주세요."
        private const val MAX_RESPONSE = 256 * 1024
        fun requireInput(username: String, password: String, confirmation: String?) {
            if (!username.matches(Regex("[a-z0-9_]{4,20}"))) throw AuthException("INVALID_USERNAME", "아이디는 영문 소문자·숫자·밑줄 4~20자로 입력해 주세요.")
            if (password.codePointCount(0, password.length) !in 8..64) throw AuthException("INVALID_PASSWORD", "비밀번호는 8~64자로 입력해 주세요.")
            if (confirmation != null && password != confirmation) throw AuthException("PASSWORD_MISMATCH", "비밀번호 확인이 일치하지 않아요.")
        }
    }
}

/** Bound to one login; a late response can never expire or overwrite a newer login. */
class AccountAccess internal constructor(private val repository: AuthRepository, internal val session: AuthSession) {
    val userId get() = session.user.id
    val key get() = userId
    fun authorization(): String { repository.check(session); return "Bearer ${session.token}" }
    fun checkResponse(code: Int) {
        if (code == 401) { repository.invalidate(session); throw AuthException("AUTHENTICATION_REQUIRED", AuthRepository.EXPIRED) }
        repository.check(session)
    }
    suspend fun json(method: String, path: String, data: JSONObject? = null) = repository.request(method, path, data, session)
    suspend fun list(path: String, key: String): org.json.JSONArray {
        require(path in setOf("/api/wardrobe/items/", "/api/outfits/"))
        val rows = org.json.JSONArray()
        val cursors = mutableSetOf<String>()
        var nextPath = path
        do {
            val page = json("GET", nextPath)
            val items = page.getJSONArray(key)
            for (index in 0 until items.length()) rows.put(items.getJSONObject(index))
            val next = if (page.isNull("next")) null else page.getString("next")
            if (next == null) break
            if (next.length !in 1..512 || !next.matches(Regex("[A-Za-z0-9_-]+")) || !cursors.add(next)) {
                throw AuthException("INVALID_RESPONSE", "목록을 읽지 못했어요. 다시 시도해 주세요.")
            }
            nextPath = "$path?after=$next"
        } while (true)
        return rows
    }
    suspend fun patchProfile(data: JSONObject) = repository.patch(session, data)
    suspend fun refreshProfile() = repository.refresh(session)
    suspend fun upload(path: String, metadata: JSONObject, image: ByteArray) = repository.upload(path, metadata, image, session)
}

package com.swpp.stylemate.data.network

import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Verifies the school's HTTP gateway without redirecting or replaying an application request. */
object SchoolGateway {
    private val verifier = SchoolGatewayVerifier(
        "http://swpp26t14.147-46-242-169.sslip.io",
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build(),
    )

    fun prepare(url: String, method: String = "GET") = verifier.prepare(url, method)

    fun onResponse(url: String, status: Int) = verifier.onResponse(url, status)
}

/** The injectable origin belongs only to this internal verifier, never the production allowlist. */
internal class SchoolGatewayVerifier(
    baseOrigin: String,
    private val client: OkHttpClient,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val origin = baseOrigin.toHttpUrl().also {
        require(it.encodedPath == "/" && it.query == null && it.fragment == null
            && it.username.isEmpty() && it.password.isEmpty())
    }
    private val health = origin.newBuilder().encodedPath("/healthz/").build()
    private val lock = Any()
    private var readyAt: Long? = null
    @Volatile private var completedVerification = 0L
    private var lastFailure: IOException? = null

    fun prepare(url: String, method: String = "GET") {
        if (!matches(url)) return
        val readOnly = method.equals("GET", true) || method.equals("HEAD", true)
        val observedVerification = completedVerification
        synchronized(lock) {
            val age = readyAt?.let { nanoTime() - it }
            val waitedForVerification = observedVerification != completedVerification
            if ((readOnly || waitedForVerification) && age != null && age >= 0 && age < CACHE_NANOS) return
            // All callers already waiting for this verification share its success or failure.
            if (waitedForVerification) lastFailure?.let { throw it }
            readyAt = null
            lastFailure = null
            try {
                verify()
                readyAt = nanoTime()
            } catch (error: IOException) {
                lastFailure = error
                throw error
            } finally {
                completedVerification++
            }
        }
    }

    fun onResponse(url: String, status: Int) {
        if (status == 302 && matches(url)) synchronized(lock) { readyAt = null }
    }

    private fun matches(url: String): Boolean = url.toHttpUrlOrNull()?.let(::sameOrigin) == true

    private fun sameOrigin(url: HttpUrl) =
        url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port

    private fun verify() {
        val startedAt = nanoTime()
        var current = health
        var previous: HttpUrl? = null
        var redirects = 0
        val followed = mutableSetOf<Pair<HttpUrl, HttpUrl>>()
        while (true) {
            val remaining = VERIFY_NANOS - (nanoTime() - startedAt).coerceAtLeast(0)
            if (remaining <= 0) throw SocketTimeoutException("학교 서버 연결 확인 시간이 초과됐어요.")
            val request = Request.Builder().url(current).get()
                .header("Cache-Control", "no-cache")
                .apply { previous?.let { header("Referer", it.toString()) } }
                .build()
            val call = client.newCall(request)
            val configuredTimeout = client.callTimeoutMillis.toLong().takeIf { it > 0 }
                ?.let(TimeUnit.MILLISECONDS::toNanos) ?: remaining
            call.timeout().timeout(minOf(remaining, configuredTimeout), TimeUnit.NANOSECONDS)
            call.execute().use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body ?: throw failure()
                        if (body.contentLength() > MAX_BODY_BYTES) throw failure()
                        val output = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(1024)
                            while (output.size() <= MAX_BODY_BYTES) {
                                val count = input.read(buffer, 0, minOf(buffer.size, MAX_BODY_BYTES + 1 - output.size()))
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                        if (output.size() > MAX_BODY_BYTES) throw failure()
                        val valid = try {
                            val json = JSONTokener(output.toString(Charsets.UTF_8.name()))
                            val value = json.nextValue() as? JSONObject
                            value?.opt("status") == "ok" && json.nextClean() == '\u0000'
                        } catch (_: Exception) { false }
                        if (!valid) throw failure()
                        return
                    }
                    302 -> {
                        if (redirects >= MAX_REDIRECTS) throw failure()
                        val next = response.header("Location")?.let(current::resolve) ?: throw failure()
                        if (!sameOrigin(next) || next.username.isNotEmpty() || next.password.isNotEmpty()
                            || next.query != null || next.fragment != null || !HEALTH_PATH.matches(next.encodedPath)
                            || next == current || !followed.add(current to next)) throw failure()
                        previous = current
                        current = next
                        redirects++
                    }
                    else -> throw failure()
                }
            }
        }
    }

    private fun failure() = IOException("학교 서버 연결 확인을 완료하지 못했어요.")

    private companion object {
        const val MAX_BODY_BYTES = 4096
        const val MAX_REDIRECTS = 3
        val CACHE_NANOS = TimeUnit.SECONDS.toNanos(10)
        val VERIFY_NANOS = TimeUnit.SECONDS.toNanos(10)
        val HEALTH_PATH = Regex("/(?:[0-9]{12}/)?healthz/")
    }
}

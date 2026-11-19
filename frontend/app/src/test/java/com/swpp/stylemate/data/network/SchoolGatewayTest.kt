package com.swpp.stylemate.data.network

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class SchoolGatewayTest {
    private val server = MockWebServer()
    private val clock = AtomicLong()
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS).build()

    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }

    private fun verifier(customClient: OkHttpClient = client) =
        SchoolGatewayVerifier(server.url("/").toString(), customClient, clock::get)
    private fun target(path: String = "/api/outfits/") = server.url(path).toString()
    private fun ok(body: String = """{"status":"ok"}""") = MockResponse()
        .setHeader("Content-Type", "application/json").setBody(body)
    private fun redirect(location: String) = MockResponse().setResponseCode(302).setHeader("Location", location)

    @Test fun normalReadinessUsesOnlyTheHealthEndpoint() {
        server.enqueue(ok())
        verifier().prepare(target())
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/healthz/", request.path)
        assertNull(request.getHeader("Referer"))
        assertEquals(1, server.requestCount)
    }

    @Test fun challengeCanReturnToTheOriginalHealthUrlWithAutomaticStyleReferers() {
        server.enqueue(redirect("/907856341290/healthz/"))
        server.enqueue(redirect(server.url("/healthz/").toString()))
        server.enqueue(ok())
        verifier().prepare(target())
        val first = server.takeRequest(1, TimeUnit.SECONDS)!!
        val second = server.takeRequest(1, TimeUnit.SECONDS)!!
        val third = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals(listOf("/healthz/", "/907856341290/healthz/", "/healthz/"),
            listOf(first.path, second.path, third.path))
        assertNull(first.getHeader("Referer"))
        assertEquals(server.url("/healthz/").toString(), second.getHeader("Referer"))
        assertEquals(server.url("/907856341290/healthz/").toString(), third.getHeader("Referer"))
    }

    @Test fun redirectsCannotLeaveTheOriginOrTheTwoAllowedHealthPathShapes() {
        val foreign = MockWebServer().apply { start() }
        try {
            val badLocations = listOf(
                foreign.url("/healthz/").toString(),
                "https://${server.hostName}:${server.port}/healthz/",
                "http://evil.example/healthz/",
                "//evil.example/healthz/",
                "/api/auth/login/",
                "/healthz",
                "/12345678901/healthz/",
                "/1234567890123/healthz/",
                "/123456789012/api/users/me/",
                "/123456789012/healthz/?token=secret",
                "/healthz/#fragment",
                "http://name:secret@${server.hostName}:${server.port}/healthz/",
            )
            for (location in badLocations) {
                val before = server.requestCount
                server.enqueue(redirect(location))
                assertThrows("Must reject $location", IOException::class.java) { verifier().prepare(target()) }
                assertEquals(before + 1, server.requestCount)
            }
            assertEquals(0, foreign.requestCount)
        } finally { foreign.shutdown() }
    }

    @Test fun threeRedirectsAreAllowedButAFourthIsRejected() {
        server.enqueue(redirect("/111111111111/healthz/"))
        server.enqueue(redirect("/222222222222/healthz/"))
        server.enqueue(redirect("/333333333333/healthz/"))
        server.enqueue(ok())
        verifier().prepare(target())
        assertEquals(4, server.requestCount)
        server.enqueue(redirect("/111111111111/healthz/"))
        server.enqueue(redirect("/222222222222/healthz/"))
        server.enqueue(redirect("/333333333333/healthz/"))
        server.enqueue(redirect("/444444444444/healthz/"))
        assertThrows(IOException::class.java) { verifier().prepare(target()) }
        assertEquals(8, server.requestCount)
    }

    @Test fun cyclicAndSelfRedirectsFailWithoutUnboundedRequests() {
        server.enqueue(redirect("/111111111111/healthz/"))
        server.enqueue(redirect("/healthz/"))
        server.enqueue(redirect("/111111111111/healthz/"))
        assertThrows(IOException::class.java) { verifier().prepare(target()) }
        assertTrue(server.requestCount <= 4)
        server.enqueue(redirect("/healthz/"))
        assertThrows(IOException::class.java) { verifier().prepare(target()) }
        assertEquals(4, server.requestCount)
    }

    @Test fun originalApiPathQueryCredentialsAndPostBodyNeverEnterTheProbe() {
        server.enqueue(redirect("/123456789012/healthz/"))
        server.enqueue(redirect("/healthz/"))
        server.enqueue(ok())
        val original = server.url("/api/auth/login/?token=never-forward-this").newBuilder()
            .username("private-user").password("private-password").fragment("private-fragment").build()
        verifier().prepare(original.toString(), "POST")
        repeat(3) {
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals(0L, request.bodySize)
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
            assertFalse(request.path!!.contains("/api/"))
            assertFalse(request.toString().contains("private"))
            assertFalse(request.getHeader("Referer").orEmpty().contains("never-forward"))
            assertFalse(request.getHeader("Referer").orEmpty().contains("private"))
        }
    }

    @Test fun only200WithACompleteStatusOkJsonObjectMarksReadiness() {
        val bodies = listOf("", "not json", "[]", "{}", """{"status":"error"}""",
            """{"status":1}""", """{"status":"ok"}trailing""")
        for (body in bodies) {
            server.enqueue(ok(body))
            assertThrows(IOException::class.java) { verifier().prepare(target()) }
        }
        for (status in listOf(204, 301, 307, 401, 500)) {
            server.enqueue(ok().setResponseCode(status))
            assertThrows(IOException::class.java) { verifier().prepare(target()) }
        }
        server.enqueue(MockResponse().setResponseCode(302))
        assertThrows(IOException::class.java) { verifier().prepare(target()) }
    }

    @Test fun healthBodyLimitAccepts4096BytesAndRejectsKnownOrChunkedLargerBodies() {
        val json = """{"status":"ok"}"""
        val limit = json + " ".repeat(4096 - json.length)
        server.enqueue(ok(limit))
        verifier().prepare(target())
        server.enqueue(ok(limit + " "))
        assertThrows(IOException::class.java) { verifier().prepare(target()) }
        server.enqueue(MockResponse().setChunkedBody(limit + " ", 512))
        assertThrows(IOException::class.java) { verifier().prepare(target()) }
        assertEquals(3, server.requestCount)
    }

    @Test fun stalledHealthProbeTimesOutAndDoesNotBecomeReady() {
        val fast = client.newBuilder().callTimeout(100, TimeUnit.MILLISECONDS).build()
        val gateway = verifier(fast)
        server.enqueue(ok().setHeadersDelay(500, TimeUnit.MILLISECONDS))
        assertThrows(IOException::class.java) { gateway.prepare(target()) }
        server.enqueue(ok())
        gateway.prepare(target())
        assertEquals(2, server.requestCount)
    }

    @Test fun totalVerificationDeadlineAlsoBoundsARedirectChain() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                clock.addAndGet(TimeUnit.SECONDS.toNanos(11))
                return redirect("/123456789012/healthz/")
            }
        }
        assertThrows(IOException::class.java) { verifier().prepare(target()) }
        assertEquals(1, server.requestCount)
    }

    @Test fun getAndHeadShareReadinessForTenSecondsUsingTheMonotonicClock() {
        val gateway = verifier()
        server.enqueue(ok())
        gateway.prepare(target(), "GET")
        gateway.prepare(target("/api/weather/"), "HEAD")
        clock.set(TimeUnit.SECONDS.toNanos(10) - 1)
        gateway.prepare(target())
        assertEquals(1, server.requestCount)
        clock.incrementAndGet()
        server.enqueue(ok())
        gateway.prepare(target())
        assertEquals(2, server.requestCount)
    }

    @Test fun everyMutatingRequestGetsFreshVerificationEvenWhenReadsAreCached() {
        val gateway = verifier()
        repeat(5) { server.enqueue(ok()) }
        gateway.prepare(target())
        for (method in listOf("POST", "POST", "PUT", "DELETE")) gateway.prepare(target(), method)
        gateway.prepare(target(), "HEAD")
        assertEquals(5, server.requestCount)
    }

    @Test fun onlySchoolOrigin302InvalidatesReadiness() {
        val gateway = verifier()
        server.enqueue(ok())
        gateway.prepare(target())
        gateway.onResponse("http://other.example/api/outfits/", 302)
        gateway.onResponse(target(), 200)
        gateway.prepare(target())
        assertEquals(1, server.requestCount)
        gateway.onResponse(target(), 302)
        server.enqueue(ok())
        gateway.prepare(target())
        assertEquals(2, server.requestCount)
    }

    @Test fun failedFreshVerificationClearsOlderReadiness() {
        val gateway = verifier()
        server.enqueue(ok())
        gateway.prepare(target())
        server.enqueue(ok("{}"))
        assertThrows(IOException::class.java) { gateway.prepare(target(), "POST") }
        server.enqueue(ok())
        gateway.prepare(target())
        assertEquals(3, server.requestCount)
    }

    @Test fun concurrentReadersShareOneVerification() {
        val gateway = verifier()
        val threads = 8
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        server.enqueue(ok().setBodyDelay(100, TimeUnit.MILLISECONDS))
        try {
            val tasks = (1..threads).map { pool.submit {
                ready.countDown()
                check(start.await(3, TimeUnit.SECONDS))
                gateway.prepare(target())
            } }
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            start.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, server.requestCount)
        } finally { start.countDown(); pool.shutdownNow() }
    }

    private fun concurrentWriters(response: MockResponse, shouldFail: Boolean): SchoolGatewayVerifier {
        val gateway = verifier(client.newBuilder().callTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS).build())
        val probeStarted = CountDownLatch(1)
        val finishProbe = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                probeStarted.countDown()
                check(finishProbe.await(5, TimeUnit.SECONDS))
                return response
            }
        }
        val url = target()
        fun writer() = Thread {
            try { gateway.prepare(url, "POST") }
            catch (error: Throwable) { errors.add(error) }
        }.apply { isDaemon = true }
        val first = writer()
        val waiters = listOf(writer(), writer())
        try {
            first.start()
            assertTrue(probeStarted.await(3, TimeUnit.SECONDS))
            waiters.forEach { it.start() }
            // Keep the HTTP response pending until both concurrent callers are waiting.
            for (thread in waiters) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (thread.isAlive && thread.state !in setOf(Thread.State.BLOCKED, Thread.State.WAITING)
                    && System.nanoTime() < deadline) Thread.sleep(1)
                assertTrue(thread.state in setOf(Thread.State.BLOCKED, Thread.State.WAITING))
            }
            finishProbe.countDown()
            for (thread in listOf(first) + waiters) {
                thread.join(5_000)
                assertFalse(thread.isAlive)
            }
            assertEquals(1, server.requestCount)
            assertEquals(if (shouldFail) 3 else 0, errors.size)
            assertTrue(errors.all { it is IOException })
        } finally {
            finishProbe.countDown()
            first.interrupt()
            waiters.forEach { it.interrupt() }
        }
        return gateway
    }

    @Test fun concurrentWritersShareTheInFlightSuccessButANewWriteVerifiesAgain() {
        val gateway = concurrentWriters(ok(), shouldFail = false)
        gateway.prepare(target(), "POST")
        assertEquals(2, server.requestCount)
    }

    @Test fun concurrentWritersShareTheInFlightFailureButANewWriteVerifiesAgain() {
        val gateway = concurrentWriters(ok().setResponseCode(503), shouldFail = true)
        assertThrows(IOException::class.java) { gateway.prepare(target(), "POST") }
        assertEquals(2, server.requestCount)
    }

    @Test fun otherOriginsIncludingLocalDebugNeverPerformAProbe() {
        val gateway = verifier()
        for (url in listOf("http://other.example/api/", "https://${server.hostName}:${server.port}/api/", "invalid")) {
            gateway.prepare(url, "POST")
        }
        SchoolGateway.prepare(target(), "POST")
        assertEquals(0, server.requestCount)
    }
}

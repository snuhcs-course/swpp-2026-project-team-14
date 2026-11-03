package com.swpp.stylemate.data

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject

/**
 * ViewModel 테스트용 가짜 서버: "METHOD 경로" 별로 응답 큐를 둔다.
 * 요청 도착 순서와 무관하게 경로마다 정한 응답이 나가고, 받은 요청은 경로별로 기록한다.
 */
internal class RoutedServer {
    val server = MockWebServer()
    private val responses = ConcurrentHashMap<String, ConcurrentLinkedQueue<MockResponse>>()
    private val received = ConcurrentHashMap<String, ConcurrentLinkedQueue<JSONObject>>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val route = "${request.method} ${request.path?.substringBefore('?')}"
                val body = request.body.readUtf8()
                received.getOrPut(route) { ConcurrentLinkedQueue() }
                    .add(if (body.isBlank()) JSONObject() else JSONObject(body))
                return responses[route]?.poll() ?: MockResponse().setResponseCode(404).setBody("""{"error":"NOT_FOUND"}""")
            }
        }
        server.start()
    }

    val baseUrl: String get() = server.url("/").toString()

    fun on(route: String, vararg queued: MockResponse) {
        responses.getOrPut(route) { ConcurrentLinkedQueue() }.addAll(queued)
    }

    fun requests(route: String): List<JSONObject> = received[route]?.toList().orEmpty()

    fun shutdown() = server.shutdown()
}

internal fun json(body: String, delayMs: Long = 0) =
    MockResponse().setBody(body).also { if (delayMs > 0) it.setBodyDelay(delayMs, TimeUnit.MILLISECONDS) }

internal fun failure(code: Int, error: String, hint: String = "") =
    MockResponse().setResponseCode(code).setBody(JSONObject().put("error", error).put("hint", hint).toString())

/** 실제 IO 스레드를 거친 상태 변화를 시간 제한 안에서 기다린다. */
internal fun <T> Flow<T>.await(predicate: (T) -> Boolean): T = runBlocking { withTimeout(5_000) { first(predicate) } }

/** 서버 형식의 코디 JSON (OutfitRepository.parseOutfit 입력). */
internal fun outfitJson(id: String, top: String, bottom: String, feedback: String? = null, saved: Boolean = false) =
    JSONObject().put("id", id).put("source", "recommended").put("saved", saved).put("name", "")
        .put("key", "w:$top+w:$bottom").put("score", 70).put("feedback", feedback ?: JSONObject.NULL)
        .put("created_at", "2026-10-30T09:00:00").put("reasons", org.json.JSONArray())
        .put("items", org.json.JSONArray()
            .put(JSONObject().put("garment_id", top).put("slot", "top").put("name", "상의 $top").put("image_url", "/$top.png").put("note", ""))
            .put(JSONObject().put("garment_id", bottom).put("slot", "bottom").put("name", "하의 $bottom").put("image_url", "/$bottom.png").put("note", "")))

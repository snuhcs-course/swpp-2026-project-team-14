package com.swpp.stylemate.data.recommendation

/**
 * Error text for the outfit and chat APIs. Each one says what happened, that nothing was lost when that
 * matters, and what to do next (HE: a bare "잠시 후 다시 시도해 주세요" did not say why or what to do).
 */
object ErrorMessages {
    const val NETWORK = "서버에 연결하지 못했어요. 와이파이나 모바일 데이터가 켜져 있는지 확인하고 다시 시도해 주세요."
    const val TIMEOUT = "서버 응답이 늦어지고 있어요. 요청이 몰렸을 수 있으니 30초쯤 뒤에 다시 시도해 주세요."
    const val BUSY = "지금 요청이 많아 서버가 바빠요. 30초쯤 뒤에 다시 시도해 주세요."
    const val UNAVAILABLE = "추천 기능을 잠시 쓸 수 없어요. 서버가 다시 준비되는 중일 수 있으니 1분쯤 뒤에 다시 시도해 주세요."
    const val SERVER = "서버에 잠깐 문제가 생겼어요. 내 옷장과 저장한 코디는 그대로 있어요. 잠시 후 다시 시도해 주세요."
    const val INVALID_RESPONSE = "서버 응답을 읽지 못했어요. 앱을 최신 버전으로 업데이트한 뒤 다시 시도해 주세요."

    /** The message for a failed call, or null to keep the one the caller already has (4xx with its own wording). */
    fun forCode(code: String): String? = when {
        code == "NETWORK" -> NETWORK
        code == "TIMEOUT" -> TIMEOUT
        code == "INVALID_RESPONSE" -> INVALID_RESPONSE
        code == "HTTP_429" || code == "TOO_MANY_REQUESTS" -> BUSY
        code == "HTTP_503" || code.endsWith("_UNAVAILABLE") -> UNAVAILABLE
        code.startsWith("HTTP_5") || code == "SERVER_ERROR" -> SERVER
        else -> null
    }
}

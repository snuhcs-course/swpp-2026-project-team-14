package com.swpp.stylemate.data

import com.swpp.stylemate.data.recommendation.ErrorMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ErrorMessagesTest {
    @Test fun eachServerFailureSaysWhatHappenedAndWhatToDo() {
        assertEquals(ErrorMessages.NETWORK, ErrorMessages.forCode("NETWORK"))
        assertEquals(ErrorMessages.TIMEOUT, ErrorMessages.forCode("TIMEOUT"))
        assertEquals(ErrorMessages.BUSY, ErrorMessages.forCode("HTTP_429"))
        assertEquals(ErrorMessages.UNAVAILABLE, ErrorMessages.forCode("HTTP_503"))
        assertEquals(ErrorMessages.UNAVAILABLE, ErrorMessages.forCode("EXTERNAL_MODEL_UNAVAILABLE"))
        assertEquals(ErrorMessages.SERVER, ErrorMessages.forCode("HTTP_500"))
        assertEquals(ErrorMessages.SERVER, ErrorMessages.forCode("HTTP_502"))
        assertEquals(ErrorMessages.INVALID_RESPONSE, ErrorMessages.forCode("INVALID_RESPONSE"))
        // 4xx with their own wording keep it
        assertNull(ErrorMessages.forCode("INVALID_FEEDBACK"))
        assertNull(ErrorMessages.forCode("WARDROBE_TOO_SMALL"))
    }
}

package com.swpp.stylemate.chat

import com.swpp.stylemate.data.chat.ChatRequest
import com.swpp.stylemate.data.chat.ChatResult
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.OutfitGarment
import com.swpp.stylemate.ui.chat.ChatTurn
import com.swpp.stylemate.ui.chat.changedOutfitMessage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatPresentationTest {
    private val top = OutfitGarment("t", "top", "검정 티", "", "")
    private val pants = OutfitGarment("p", "bottom", "청바지", "", "")
    private val skirt = OutfitGarment("s", "bottom", "회색 스커트", "", "")
    private fun outfit(vararg items: OutfitGarment) = Outfit("id", items.joinToString { it.garmentId }, null,
        items.toList(), emptyList(), false, "", null, "")
    private fun turn(before: Outfit, after: Outfit?, status: String = "updated", message: String = "하의를 바꿨어요. 잠근 옷은 유지했어요."): ChatTurn {
        val request = ChatRequest(before, setOf("t"), emptySet(), "하의만 바꿔줘", JSONObject(), null, null, emptyList(), emptyList())
        return ChatTurn(request = request, loading = false,
            result = ChatResult(status, message, after, setOf("s"), setOf("t"), emptySet(), emptyList()))
    }
    @Test fun changedReplyNamesTheActualBeforeAndAfterWithoutLockMetadata() {
        val message = changedOutfitMessage(turn(outfit(top, pants), outfit(top, skirt)))
        assertEquals("코디를 바꿨어요.\n하의: 청바지 → 회색 스커트", message)
        assertFalse(message.contains("유지"))
    }
    @Test fun unchangedAndClarificationKeepTheActionableServerMessage() {
        for (status in listOf("unchanged", "clarify", "unlock")) {
            assertEquals("조건을 확인해 주세요.", changedOutfitMessage(turn(outfit(top, pants), null, status, "조건을 확인해 주세요.")))
        }
    }
    @Test fun anUpdatedEnvelopeWithIdenticalGarmentsDoesNotClaimAChange() {
        assertEquals("바뀐 옷이 없어 코디를 유지했어요.", changedOutfitMessage(turn(outfit(top, pants), outfit(top, pants))))
    }
    @Test fun removingOuterwearIsExplicit() {
        val coat = OutfitGarment("c", "outerwear", "롱 코트", "", "")
        assertEquals("코디를 바꿨어요.\n아우터 제외: 롱 코트", changedOutfitMessage(turn(outfit(top, pants, coat), outfit(top, pants))))
    }
}

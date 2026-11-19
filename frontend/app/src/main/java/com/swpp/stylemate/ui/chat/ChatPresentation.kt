package com.swpp.stylemate.ui.chat

import com.swpp.stylemate.data.recommendation.Outfit

internal data class OutfitVersion(val label: String, val outfit: Outfit)

internal fun outfitVersions(state: ChatState): List<OutfitVersion> = buildList {
    state.source?.let { add(OutfitVersion("수정 전", it)) }
    state.turns.filter { it.result?.status == "updated" }.forEachIndexed { index, turn ->
        turn.result?.outfit?.let { add(OutfitVersion("수정 ${index + 1}", it)) }
    }
}.distinctBy { it.outfit.key }

/** Describe actual before/after garments without repeating lock metadata or recommendation reasons. */
internal fun changedOutfitMessage(turn: ChatTurn): String {
    val result = turn.result ?: return ""
    if (result.status != "updated") return result.message
    val after = result.outfit ?: return result.message
    val before = turn.request.current
    val changes = listOf("top" to "상의", "bottom" to "하의", "outerwear" to "아우터").mapNotNull { (slot, label) ->
        val old = before.items.firstOrNull { it.slot == slot }
        val new = after.items.firstOrNull { it.slot == slot }
        when {
            old?.garmentId == new?.garmentId -> null
            old == null -> "$label 추가: ${new!!.name}"
            new == null -> "$label 제외: ${old.name}"
            else -> "$label: ${old.name} → ${new.name}"
        }
    }
    return if (changes.isEmpty()) "바뀐 옷이 없어 코디를 유지했어요."
        else "코디를 바꿨어요.\n" + changes.joinToString("\n")
}

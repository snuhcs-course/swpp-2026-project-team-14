package com.swpp.stylemate.data.wardrobe

import com.swpp.stylemate.data.AccountAccess
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object WornPhotoRepository {
    suspend fun upload(account: AccountAccess, bytes: ByteArray, requestId: String): JSONObject {
        UUID.fromString(requestId)
        return wornRequest(account, "/api/wardrobe/worn-photos/?request_id=$requestId", bytes, "image/jpeg")
    }

    suspend fun request(account: AccountAccess, id: String, action: String = "", body: JSONObject? = null): JSONObject {
        UUID.fromString(id)
        require(action in setOf("", "prepare", "save"))
        val suffix = if (action.isBlank()) "" else "$action/"
        return wornRequest(account, "/api/wardrobe/worn-photos/$id/$suffix", body?.toString()?.toByteArray(Charsets.UTF_8))
    }
}

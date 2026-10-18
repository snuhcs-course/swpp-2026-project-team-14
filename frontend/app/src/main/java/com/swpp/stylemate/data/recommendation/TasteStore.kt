// AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.data.recommendation

import android.content.Context
import com.swpp.stylemate.data.LocalUserStore
import org.json.JSONException
import org.json.JSONObject

/**
 * The taste profile learned from likes and dislikes (backend recommendation/preference.py). The
 * server is stateless: the app keeps it and sends it with every recommendation request.
 */
interface TasteStore {
    fun load(): JSONObject?
    fun save(taste: JSONObject?)
}

class InMemoryTasteStore(private var taste: JSONObject? = null) : TasteStore {
    override fun load(): JSONObject? = taste
    override fun save(taste: JSONObject?) {
        this.taste = taste
    }
}

class LocalTasteStore(context: Context, accountId: String) : TasteStore {
    private val store = LocalUserStore.forContext(context, accountId)
    override fun load(): JSONObject? = store.get("taste")
    override fun save(taste: JSONObject?) = store.put("taste", taste)
}

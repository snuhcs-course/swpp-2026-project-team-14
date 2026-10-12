// AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.data.recommendation

import android.content.Context
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

class SharedPrefsTasteStore(context: Context) : TasteStore {
    private val prefs = context.applicationContext.getSharedPreferences("outfit_taste", Context.MODE_PRIVATE)

    override fun load(): JSONObject? = prefs.getString(KEY, null)?.let {
        try { JSONObject(it) } catch (e: JSONException) { null }
    }

    override fun save(taste: JSONObject?) {
        prefs.edit().apply { if (taste == null) remove(KEY) else putString(KEY, taste.toString()) }.apply()
    }

    private companion object {
        const val KEY = "taste_v1"
    }
}

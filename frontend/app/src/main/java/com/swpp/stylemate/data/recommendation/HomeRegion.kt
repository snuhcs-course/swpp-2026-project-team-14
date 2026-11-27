package com.swpp.stylemate.data.recommendation

import com.swpp.stylemate.data.LocalUserStore
import org.json.JSONObject

/** A place whose weather home uses: a listed city (its city hall) or a place found by search, with [area]. */
data class Region(val name: String, val lat: Double, val lon: Double, val area: String = "")

/**
 * The region whose weather home recommends for (HE: it was always Seoul). Kept per account on the device,
 * like the calendar switch; only the coordinates go to the weather API. Trips are asked for in chat.
 */
object HomeRegion {
    val SEOUL = Region("서울", 37.5665, 126.9780)
    val OPTIONS = listOf(
        SEOUL, Region("인천", 37.4563, 126.7052), Region("수원", 37.2636, 127.0286),
        Region("춘천", 37.8813, 127.7298), Region("강릉", 37.7519, 128.8761), Region("대전", 36.3504, 127.3845),
        Region("청주", 36.6424, 127.4890), Region("전주", 35.8242, 127.1480), Region("광주", 35.1595, 126.8526),
        Region("대구", 35.8714, 128.6014), Region("포항", 36.0190, 129.3435), Region("울산", 35.5384, 129.3114),
        Region("부산", 35.1796, 129.0756), Region("창원", 35.2280, 128.6811), Region("제주", 33.4996, 126.5312),
    )
    private const val KEY = "home_region"

    fun of(name: String?): Region? = OPTIONS.firstOrNull { it.name == name }

    /** A listed city by name, or a searched place kept with its coordinates (older saves hold only a name). */
    fun load(store: LocalUserStore): Region {
        val saved = store.get(KEY) ?: return SEOUL
        val name = saved.optString("name")
        if (saved.has("lat") && saved.has("lon") && name.isNotBlank()) {
            val lat = saved.optDouble("lat"); val lon = saved.optDouble("lon")
            if (lat in 33.0..38.9 && lon in 124.5..131.0) return Region(name, lat, lon, saved.optString("area"))
        }
        return of(name) ?: SEOUL
    }

    fun save(store: LocalUserStore, region: Region) = store.put(KEY, JSONObject().put("name", region.name)
        .put("lat", region.lat).put("lon", region.lon).put("area", region.area))
}

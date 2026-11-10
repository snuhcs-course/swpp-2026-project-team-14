package com.swpp.stylemate.data.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

data class CalendarEvent(val title: String, val begin: Long, val end: Long, val location: String?, val allDay: Boolean)

/**
 * Today's events from the calendars already on the phone (Google, Samsung, Outlook…), through Android's
 * calendar provider. Only READ_CALENDAR is needed; there is no account sign-in. Event titles stay on the
 * device: the app sends at most the occasion word derived from them ([EventOccasion]).
 */
object DeviceCalendar {
    private const val MAX_EVENTS = 5

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Events that have not ended yet, from now until the end of today. Empty without the permission. */
    suspend fun today(context: Context, now: Long = System.currentTimeMillis(),
                      zone: ZoneId = ZoneId.systemDefault()): List<CalendarEvent> = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext emptyList()
        val dayStart = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, dayStart)
            ContentUris.appendId(it, dayEnd)
        }.build()
        val projection = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY)
        try {
            context.contentResolver.query(uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null,
                "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val title = cursor.getString(0)?.trim().orEmpty()
                        val end = cursor.getLong(2)
                        if (title.isNotEmpty() && end > now) add(CalendarEvent(title, cursor.getLong(1), end,
                            cursor.getString(3)?.trim()?.ifEmpty { null }, cursor.getInt(4) == 1))
                    }
                }.take(MAX_EVENTS)
            }.orEmpty()
        } catch (_: SecurityException) {
            emptyList()  // permission revoked while the app was open
        }
    }
}

/** Maps an event title to the occasions the chat parser already understands (backend `recommendation/intent.py`). */
object EventOccasion {
    private val WORDS = linkedMapOf(
        "출근" to listOf("출근", "회사", "오피스", "면접", "회의", "미팅", "발표"),
        "데이트" to listOf("데이트", "소개팅"),
        "운동" to listOf("운동", "헬스", "러닝", "등산", "요가", "필라테스"),
        "여행" to listOf("여행", "나들이"),
        "모임" to listOf("모임", "파티", "결혼식", "하객", "생일"),
    )

    fun of(title: String): String? = WORDS.entries.firstOrNull { (_, words) -> words.any { it in title } }?.key

    /** The first event today that names an occasion. */
    fun of(events: List<CalendarEvent>): String? = events.firstNotNullOfOrNull { of(it.title) }
}

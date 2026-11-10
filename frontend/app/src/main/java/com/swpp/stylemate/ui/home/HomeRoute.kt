// AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.data.calendar.CalendarEvent
import com.swpp.stylemate.data.calendar.DeviceCalendar
import com.swpp.stylemate.data.calendar.EventOccasion
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.swpp.stylemate.data.BodyProfile
import com.swpp.stylemate.data.BodyProfileJson
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.Weather
import com.swpp.stylemate.data.recommendation.OutfitRepository
import com.swpp.stylemate.data.recommendation.LocalTasteStore
import com.swpp.stylemate.ui.LocalAccountAccess
import com.swpp.stylemate.data.LocalUserStore
import com.swpp.stylemate.data.tryon.FittingRepository
import com.swpp.stylemate.ui.tryon.FittingDialog
import com.swpp.stylemate.ui.tryon.FittingPreview
import com.swpp.stylemate.ui.tryon.LocalFittingNotificationKey
import org.json.JSONObject

@Composable
fun HomeRoute(profile: BodyProfile?, onSetupProfile: () -> Unit, onOpenWardrobe: () -> Unit, modifier: Modifier = Modifier,
              onChat: (Outfit, Weather?) -> Unit, wardrobeRevision: Int = 0) {
    val context = LocalContext.current.applicationContext
    val account = checkNotNull(LocalAccountAccess.current)
    val fitting = remember(account) { FittingRepository(LocalUserStore.forContext(context, account.userId), account) }
    var selected by remember { mutableStateOf<Outfit?>(null) }
    val notification = LocalFittingNotificationKey.current
    LaunchedEffect(notification) { if (notification != null) selected = null }
    val model: HomeViewModel = viewModel(factory = viewModelFactory {
        initializer { HomeViewModel(OutfitRepository(account), LocalTasteStore(context, account.userId)) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val body = remember(profile) { profile?.let { JSONObject(BodyProfileJson.encode(it)) } }
    // Today's events from the phone's own calendars: one permission, no account sign-in.
    var calendarAllowed by remember { mutableStateOf(DeviceCalendar.hasPermission(context)) }
    var events by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        calendarAllowed = granted
    }
    LaunchedEffect(calendarAllowed) {
        if (!calendarAllowed) return@LaunchedEffect
        events = DeviceCalendar.today(context)
        model.setOccasion(EventOccasion.of(events))
    }
    LaunchedEffect(body?.toString(), wardrobeRevision) { model.ensureLoaded(body, wardrobeRevision) }
    HomeScreen(
        state = state,
        onRefresh = { model.load(body) },
        onNext = model::next,
        onLike = { model.feedback("liked") },
        onDislike = { model.feedback("disliked") },
        onSave = model::toggleSaved,
        onSetupProfile = onSetupProfile,
        onOpenWardrobe = onOpenWardrobe,
        onMessageShown = model::messageShown,
        onChat = { state.current?.let { onChat(it, state.weather) } },
        modifier = modifier,
        fitting = { outfit, previewModifier ->
            FittingPreview(onExpand = { selected = outfit }, modifier = previewModifier)
        },
        todayPlan = {
            TodayPlan(calendarAllowed, events, onConnect = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) })
        },
    )
    selected?.let { FittingDialog(it, fitting, account, onClose = { selected = null }, profile = profile) }
}

/** One line about today's schedule, or a button that asks for the calendar permission. */
@Composable
internal fun TodayPlan(allowed: Boolean, events: List<CalendarEvent>, onConnect: () -> Unit) {
    val time = remember { DateTimeFormatter.ofPattern("HH:mm") }
    when {
        !allowed -> TextButton(onClick = onConnect, contentPadding = PaddingValues(0.dp)) {
            Text("캘린더 연결하고 오늘 일정에 맞춰 받기", style = MaterialTheme.typography.bodyMedium)
        }
        events.isEmpty() -> Text("오늘 남은 일정이 없어요", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> {
            val first = events.first()
            val start = if (first.allDay) "종일" else Instant.ofEpochMilli(first.begin).atZone(ZoneId.systemDefault()).format(time)
            // Event facts only: home scoring does not use the occasion yet, so the line does not promise it.
            Text(listOfNotNull("오늘 $start ${first.title}", first.location,
                if (events.size > 1) "외 ${events.size - 1}개" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun CodiBookRoute(modifier: Modifier = Modifier, wardrobeRevision: Int = 0, profile: BodyProfile? = null) {
    val context = LocalContext.current.applicationContext
    val account = checkNotNull(LocalAccountAccess.current)
    val fitting = remember(account) { FittingRepository(LocalUserStore.forContext(context, account.userId), account) }
    var selected by remember { mutableStateOf<Outfit?>(null) }
    val notification = LocalFittingNotificationKey.current
    LaunchedEffect(notification) { if (notification != null) selected = null }
    val model: CodiBookViewModel = viewModel(factory = viewModelFactory {
        initializer { CodiBookViewModel(OutfitRepository(account)) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(wardrobeRevision) { model.invalidateWardrobe(wardrobeRevision); model.load() }
    CodiBookScreen(state, onRemove = model::remove, onRetry = model::load, modifier = modifier, onTryOn = { selected = it })
    selected?.let { FittingDialog(it, fitting, account, onClose = { selected = null }, profile = profile) }
}

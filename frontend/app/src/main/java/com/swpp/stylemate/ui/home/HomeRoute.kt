// AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.home

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.data.calendar.CalendarConnection
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
    // Today's outfit-related events from the phone's own calendars, once connected in My Profile.
    val store = remember(account) { LocalUserStore.forContext(context, account.userId) }
    var events by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }
    LaunchedEffect(account.userId) {
        events = if (CalendarConnection.enabled(context, store)) EventOccasion.relevant(DeviceCalendar.today(context)) else emptyList()
        model.setOccasion(EventOccasion.of(events))
    }
    LaunchedEffect(body?.toString(), wardrobeRevision) { model.ensureLoaded(body, wardrobeRevision) }
    HomeScreen(
        state = state,
        onRefresh = { model.load(body) },
        onSelect = model::select,
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
        todayPlan = if (events.isEmpty()) null else {{ TodayPlan(events) }},
    )
    selected?.let { FittingDialog(it, fitting, account, onClose = { selected = null }, profile = profile) }
}

/** Today's next outfit-related event, e.g. "오늘 14:00 회사 면접 · 강남역". */
@Composable
internal fun TodayPlan(events: List<CalendarEvent>) {
    val time = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val first = events.first()
    val start = if (first.allDay) "종일" else Instant.ofEpochMilli(first.begin).atZone(ZoneId.systemDefault()).format(time)
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.DateRange, contentDescription = "오늘 일정", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            // Event facts only: home scoring does not use the occasion yet, so the line does not promise it.
            Text(listOfNotNull("오늘 $start ${first.title}", first.location,
                if (events.size > 1) "외 ${events.size - 1}개" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
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

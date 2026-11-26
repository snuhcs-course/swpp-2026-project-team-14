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
import androidx.compose.material.icons.filled.Person
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.font.FontWeight
import com.swpp.stylemate.data.StyleProfile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
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
import com.swpp.stylemate.data.recommendation.HomeRegion
import com.swpp.stylemate.data.recommendation.Outfit
import com.swpp.stylemate.data.recommendation.Weather
import com.swpp.stylemate.data.recommendation.OutfitRepository
import com.swpp.stylemate.data.recommendation.LocalTasteStore
import com.swpp.stylemate.ui.LocalAccountAccess
import com.swpp.stylemate.data.LocalUserStore
import com.swpp.stylemate.data.tryon.FittingRepository
import com.swpp.stylemate.data.tryon.FittingWork
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.swpp.stylemate.ui.tryon.FittingDialog
import com.swpp.stylemate.ui.tryon.FittingPreview
import com.swpp.stylemate.ui.tryon.LocalFittingNotificationKey
import org.json.JSONObject

@Composable
fun HomeRoute(profile: BodyProfile?, onSetupProfile: () -> Unit, onOpenWardrobe: () -> Unit, modifier: Modifier = Modifier,
              onChat: (Outfit, Weather?) -> Unit, wardrobeRevision: Int = 0, style: StyleProfile = StyleProfile()) {
    val context = LocalContext.current.applicationContext
    val account = checkNotNull(LocalAccountAccess.current)
    val store = remember(account) { LocalUserStore.forContext(context, account.userId) }
    val fitting = remember(account) { FittingRepository(LocalUserStore.forContext(context, account.userId), account) }
    var selected by remember { mutableStateOf<Outfit?>(null) }
    val notification = LocalFittingNotificationKey.current
    LaunchedEffect(notification) { if (notification != null) selected = null }
    val model: HomeViewModel = viewModel(factory = viewModelFactory {
        initializer {
            HomeViewModel(OutfitRepository(account), LocalTasteStore(context, account.userId)).apply {
                // Restore the region before the first weather/recommendation request.
                setRegion(HomeRegion.load(store), reload = false)
            }
        }
    })
    val state by model.state.collectAsStateWithLifecycle()
    // Without a body profile (analysis skipped in onboarding) outfits still come, from the preferred fit and styles.
    val body = remember(profile, style) { BodyProfileJson.request(profile, style) }
    // Today's outfit-related events from the phone's own calendars, once connected in My Profile.
    var events by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }
    LaunchedEffect(account.userId) {
        events = if (CalendarConnection.enabled(context, store)) EventOccasion.relevant(DeviceCalendar.today(context)) else emptyList()
        model.setOccasion(EventOccasion.of(events))
    }
    LaunchedEffect(body?.toString(), wardrobeRevision) { model.ensureLoaded(body, wardrobeRevision) }
    // Each return to 홈 (from 코디북, chat or another tab) re-checks which cards are saved.
    LaunchedEffect(Unit) { model.syncSaved() }
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
        fitting = { outfit, previewModifier, enabled ->
            Button(onClick = { selected = outfit }, enabled = enabled,
                modifier = previewModifier.heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) { Text("가상 피팅") }
        },
        todayPlan = if (events.isEmpty()) null else {{ TodayPlan(events) }},
        hint = if (profile == null) {{ BodyHint(onSetupProfile) }} else null,
        onRegion = { name -> HomeRegion.of(name)?.let { HomeRegion.save(store, it); model.setRegion(it) } },
    )
    selected?.let { FittingDialog(it, fitting, account, onClose = { selected = null }, profile = profile) }
}

/** Today's next outfit-related event, e.g. "오늘 14:00 회사 면접 · 강남역". */
@Composable
internal fun TodayPlan(events: List<CalendarEvent>) {
    val time = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val first = events.first()
    val start = if (first.allDay) "종일" else Instant.ofEpochMilli(first.begin).atZone(ZoneId.systemDefault()).format(time)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
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

/** Shown while there is no body profile: the outfits do not use the body yet, and one tap starts the analysis. */
@Composable
internal fun BodyHint(onSetupProfile: () -> Unit) {
    Surface(onClick = onSetupProfile, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Person, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("체형 분석을 하면 내 몸에 맞는 핏으로 골라 드려요", Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("체형 분석", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold)
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val work = remember(context) { FittingWork(context) }
    var fittingStatuses by remember(account.userId) { mutableStateOf<Map<String, CodiBookFittingStatus>>(emptyMap()) }
    LaunchedEffect(fitting, state.outfits, selected, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                val statuses = buildMap {
                    for (outfit in state.outfits) {
                        try {
                            val task = work.state(fitting.taskKey(outfit))?.takeIf { it.optString("owner") == account.userId }
                            val source = fitting.latestSource(outfit)
                            put(outfit.id, when {
                                task?.optString("state") in listOf("queued", "running") -> CodiBookFittingStatus.GENERATING
                                task?.optString("state") == "failed" -> CodiBookFittingStatus.FAILED
                                source != null && fitting.hasResult(outfit, source) -> CodiBookFittingStatus.READY
                                else -> CodiBookFittingStatus.NONE
                            })
                        } catch (error: CancellationException) { throw error }
                        catch (_: Exception) { /* Leave unknown local state unlabelled; opening fitting can retry. */ }
                    }
                }
                fittingStatuses = statuses
                if (statuses.values.none { it == CodiBookFittingStatus.GENERATING }) break
                delay(1_500)
            } while (isActive)
        }
    }
    CodiBookScreen(state, onRemove = model::remove, onRetry = model::load, modifier = modifier,
        onTryOn = { selected = it }, fittingStatuses = fittingStatuses, onClearRemoveError = model::clearRemoveError)
    selected?.let { FittingDialog(it, fitting, account, onClose = { selected = null }, profile = profile) }
}

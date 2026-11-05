// AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.home

import androidx.compose.runtime.Composable
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
    )
    selected?.let { FittingDialog(it, fitting, account, onClose = { selected = null }, profile = profile) }
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

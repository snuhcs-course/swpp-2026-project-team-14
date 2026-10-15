// AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.swpp.stylemate.BuildConfig
import com.swpp.stylemate.data.BodyProfile
import com.swpp.stylemate.data.BodyProfileJson
import com.swpp.stylemate.data.recommendation.OutfitRepository
import com.swpp.stylemate.data.recommendation.LocalTasteStore
import com.swpp.stylemate.data.LocalUserStore
import org.json.JSONObject

@Composable
fun HomeRoute(profile: BodyProfile?, onSetupProfile: () -> Unit, onOpenWardrobe: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val model: HomeViewModel = viewModel(factory = viewModelFactory {
        initializer { HomeViewModel(OutfitRepository(BuildConfig.API_BASE_URL, local = LocalUserStore.forContext(context)), LocalTasteStore(context)) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val body = remember(profile) { profile?.let { JSONObject(BodyProfileJson.encode(it)) } }
    LaunchedEffect(body?.toString()) { model.ensureLoaded(body) }
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
        modifier = modifier,
    )
}

@Composable
fun CodiBookRoute(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val model: CodiBookViewModel = viewModel(factory = viewModelFactory {
        initializer { CodiBookViewModel(OutfitRepository(BuildConfig.API_BASE_URL, local = LocalUserStore.forContext(context))) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { model.load() }
    CodiBookScreen(state, onRemove = model::remove, onRetry = model::load, modifier = modifier)
}

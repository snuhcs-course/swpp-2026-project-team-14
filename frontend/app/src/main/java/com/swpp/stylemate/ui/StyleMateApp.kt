// AI-generated with Claude Code (Dongkun Moon) and Codex (Hyeon U Jeong), 2026-09-24, reviewed by Dongkun Moon and Hyeon U Jeong
package com.swpp.stylemate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.swpp.stylemate.data.LocalBodyProfileStore
import com.swpp.stylemate.ui.home.CodiBookRoute
import com.swpp.stylemate.ui.home.HomeRoute
import com.swpp.stylemate.ui.profile.AnalyzingScreen
import com.swpp.stylemate.ui.profile.BodyProfileViewModel
import com.swpp.stylemate.ui.profile.CaptureGuideScreen
import com.swpp.stylemate.ui.profile.MyProfileScreen
import com.swpp.stylemate.ui.profile.PhotoInputScreen
import com.swpp.stylemate.ui.profile.ReviewScreen
import com.swpp.stylemate.ui.profile.SetupStep
import com.swpp.stylemate.ui.wardrobe.WardrobeRoute

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("홈", Icons.Filled.Home),
    WARDROBE("옷장", Icons.AutoMirrored.Filled.List),
    CODIBOOK("코디북", Icons.Filled.Star),
    PROFILE("마이프로필", Icons.Filled.Person),
}

/** The app's view model, with the body profile kept on the device. */
@Composable
private fun defaultBodyProfileViewModel(): BodyProfileViewModel {
    val context = LocalContext.current.applicationContext
    return viewModel(factory = viewModelFactory { initializer { BodyProfileViewModel(store = LocalBodyProfileStore(context)) } })
}

@Composable
fun StyleMateApp(viewModel: BodyProfileViewModel = defaultBodyProfileViewModel()) {
    val app by viewModel.app.collectAsStateWithLifecycle()
    if (app.storageError != null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(app.storageError!!)
                TextButton(onClick = viewModel::reloadLocalData) { Text("다시 불러오기") }
            }
        }
        return
    }
    if (app.setupVisible) {
        ProfileSetupFlow(viewModel, canCancel = app.profile != null)
    } else {
        MainTabs(viewModel)
    }
}

@Composable
private fun ProfileSetupFlow(viewModel: BodyProfileViewModel, canCancel: Boolean) {
    val state by viewModel.setup.collectAsStateWithLifecycle()
    when (state.step) {
        SetupStep.GUIDE -> {
            BackHandler(enabled = canCancel) { viewModel.cancelSetup() }
            CaptureGuideScreen(
                onStart = viewModel::startCapture,
                onSkip = if (canCancel) viewModel::cancelSetup else viewModel::skipSetup,
            )
        }
        SetupStep.INPUT -> {
            BackHandler { viewModel.backToGuide() }
            PhotoInputScreen(
                state = state,
                onBack = viewModel::backToGuide,
                onPhoto = viewModel::setPhoto,
                onPhotoError = viewModel::showError,
                onHeight = viewModel::setHeight,
                onWeight = viewModel::setWeight,
                onGender = viewModel::setGender,
                onAnalyze = viewModel::analyze,
            )
        }
        SetupStep.ANALYZING -> AnalyzingScreen()
        SetupStep.REVIEW -> {
            BackHandler { viewModel.startCapture() }
            ReviewScreen(
                state = state,
                onRetake = viewModel::startCapture,
                onEdit = viewModel::editMeasurement,
                onFit = viewModel::setPreferredFit,
                onToggleStyle = viewModel::toggleStyle,
                onConfirm = viewModel::confirmProfile,
            )
        }
    }
}

@Composable
private fun MainTabs(viewModel: BodyProfileViewModel) {
    val app by viewModel.app.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.WARDROBE) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            Tab.HOME -> HomeRoute(app.profile, onSetupProfile = viewModel::startReanalysis,
                onOpenWardrobe = { tab = Tab.WARDROBE }, modifier = Modifier.padding(padding).consumeWindowInsets(padding))
            Tab.WARDROBE -> Box(Modifier.padding(padding).consumeWindowInsets(padding)) { WardrobeRoute() }
            Tab.CODIBOOK -> CodiBookRoute(Modifier.padding(padding).consumeWindowInsets(padding))
            Tab.PROFILE -> MyProfileScreen(
                profile = app.profile?.copy(preferredFit = app.style.legacyFit(), preferredStyles = app.style.styleLabels()),
                onReanalyze = viewModel::startReanalysis,
                onDelete = viewModel::deleteProfile,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

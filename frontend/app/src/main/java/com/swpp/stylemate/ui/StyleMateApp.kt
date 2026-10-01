package com.swpp.stylemate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.swpp.stylemate.ui.profile.AnalyzingScreen
import com.swpp.stylemate.ui.profile.BodyProfileViewModel
import com.swpp.stylemate.ui.profile.CaptureGuideScreen
import com.swpp.stylemate.ui.profile.MyProfileScreen
import com.swpp.stylemate.ui.profile.PhotoInputScreen
import com.swpp.stylemate.ui.profile.ReviewScreen
import com.swpp.stylemate.ui.profile.SetupStep

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("홈", Icons.Filled.Home),
    WARDROBE("옷장", Icons.AutoMirrored.Filled.List),
    PROFILE("마이프로필", Icons.Filled.Person),
}

@Composable
fun StyleMateApp(viewModel: BodyProfileViewModel = viewModel()) {
    val app by viewModel.app.collectAsStateWithLifecycle()
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
    var tab by rememberSaveable { mutableStateOf(Tab.PROFILE) }

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
            Tab.HOME -> Placeholder("오늘의 코디", "홈 대시보드는 P8 담당 화면이에요.", Modifier.padding(padding))
            Tab.WARDROBE -> Placeholder("내 옷장", "옷장 화면은 P11·P12 담당 화면이에요.", Modifier.padding(padding))
            Tab.PROFILE -> MyProfileScreen(
                profile = app.profile,
                onReanalyze = viewModel::startReanalysis,
                onDelete = viewModel::deleteProfile,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun Placeholder(title: String, note: String, modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
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
import com.swpp.stylemate.data.AccountAccess
import com.swpp.stylemate.data.AuthRepository
import com.swpp.stylemate.data.KeystoreSessionStore
import com.swpp.stylemate.data.GuestImportRepository
import com.swpp.stylemate.data.chat.ChatRepository
import com.swpp.stylemate.data.recommendation.LocalTasteStore
import com.swpp.stylemate.ui.chat.ChatRoute
import com.swpp.stylemate.ui.chat.ChatViewModel
import com.swpp.stylemate.ui.profile.GuestImportViewModel
import com.swpp.stylemate.ui.profile.GuestImportDialog
import com.swpp.stylemate.data.LocalUserStore
import com.swpp.stylemate.BuildConfig
import com.swpp.stylemate.ui.profile.AuthViewModel
import com.swpp.stylemate.ui.profile.AuthPage
import com.swpp.stylemate.ui.profile.LoginScreen
import com.swpp.stylemate.data.RemoteBodyAnalyzer
import com.swpp.stylemate.ui.profile.RegistrationScreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.swpp.stylemate.ui.home.CodiBookRoute
import com.swpp.stylemate.ui.home.HomeRoute
import com.swpp.stylemate.ui.profile.AnalyzingScreen
import com.swpp.stylemate.ui.profile.BodyProfileViewModel
import com.swpp.stylemate.ui.profile.CaptureGuideScreen
import com.swpp.stylemate.ui.profile.MyProfileScreen
import com.swpp.stylemate.ui.profile.PhotoInputScreen
import com.swpp.stylemate.ui.profile.ReviewScreen
import com.swpp.stylemate.ui.profile.SetupStep
import com.swpp.stylemate.ui.profile.OnboardingRoute
import com.swpp.stylemate.ui.wardrobe.WardrobeRoute

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("홈", Icons.Filled.Home),
    WARDROBE("옷장", Icons.AutoMirrored.Filled.List),
    CODIBOOK("코디북", Icons.Filled.Star),
    PROFILE("마이프로필", Icons.Filled.Person),
}

val LocalAccountAccess = staticCompositionLocalOf<AccountAccess?> { null }

@Composable
private fun defaultBodyProfileViewModel(account: AccountAccess): BodyProfileViewModel {
    val context = LocalContext.current.applicationContext
    return viewModel(factory = viewModelFactory { initializer { BodyProfileViewModel(analyzer = RemoteBodyAnalyzer(BuildConfig.API_BASE_URL, account = account), store = LocalBodyProfileStore(context, account.userId), account = account) } })
}

@Composable
private fun defaultAuthViewModel(): AuthViewModel {
    val context = LocalContext.current.applicationContext
    return viewModel(factory = viewModelFactory { initializer {
        AuthViewModel(AuthRepository(BuildConfig.API_BASE_URL, KeystoreSessionStore(context), cacheUser = { user ->
            LocalUserStore.forContext(context, user.id).cacheAccountProfile(user)
        }))
    } })
}

@Composable
fun StyleMateApp(viewModel: BodyProfileViewModel? = null, authModel: AuthViewModel = defaultAuthViewModel()) {
    val context = LocalContext.current.applicationContext
    val auth by authModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, authModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) authModel.refreshSession() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!auth.ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val account = remember(auth.session) { authModel.repository.account() }
    if (account == null) {
        if (auth.page == AuthPage.REGISTER) {
            RegistrationScreen(auth, { authModel.open(AuthPage.LOGIN) }, { id, password, confirmation -> authModel.submit(id, password, confirmation) })
        } else {
            LoginScreen(auth, { authModel.open(AuthPage.REGISTER) }, { id, password -> authModel.submit(id, password) })
        }
    } else {
        val scope = account.userId
        val owner = remember(scope) { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
        DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalAccountAccess provides account) {
            key(scope) {
                val profileModel = viewModel ?: defaultBodyProfileViewModel(account)
                LaunchedEffect(auth.session?.user?.json()?.toString()) { profileModel.refreshAccountProfile() }
                val importModel: GuestImportViewModel = viewModel(factory = viewModelFactory {
                    initializer { GuestImportViewModel(GuestImportRepository(LocalUserStore.legacyGuestForContext(context), account)) }
                })
                AppContent(profileModel, authModel, importModel)
                val importing by importModel.state.collectAsStateWithLifecycle()
                GuestImportDialog(importing, importModel::confirm, importModel::decide, importModel::retry, importModel::close)
            }
        }
    }
    auth.notice?.let { message ->
        AlertDialog(onDismissRequest = authModel::dismissNotice, text = { Text(message) },
            confirmButton = { TextButton(onClick = authModel::dismissNotice) { Text("확인") } })
    }
}

@Composable
private fun AppContent(viewModel: BodyProfileViewModel, authModel: AuthViewModel, importModel: GuestImportViewModel) {
    val app by viewModel.app.collectAsStateWithLifecycle()
    val onboarding by viewModel.onboarding.collectAsStateWithLifecycle()
    if (app.storageError != null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(app.storageError!!)
                TextButton(onClick = viewModel::reloadLocalData) { Text("다시 불러오기") }
            }
        }
        return
    }
    if (!onboarding.completed) {
        OnboardingRoute(viewModel)
    } else if (app.setupVisible) {
        ProfileSetupFlow(viewModel, canCancel = true)
    } else {
        MainTabs(viewModel, authModel, importModel)
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
            BackHandler { if (!state.saving) viewModel.startCapture() }
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
private fun MainTabs(viewModel: BodyProfileViewModel, authModel: AuthViewModel, importModel: GuestImportViewModel) {
    val app by viewModel.app.collectAsStateWithLifecycle()
    val auth by authModel.state.collectAsStateWithLifecycle()
    val account = checkNotNull(LocalAccountAccess.current)
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val context = LocalContext.current.applicationContext
    val chat: ChatViewModel = viewModel(factory = viewModelFactory {
        initializer { ChatViewModel(ChatRepository(BuildConfig.API_BASE_URL, account = account),
            LocalTasteStore(context, account.userId)) }
    })
    val chatting by chat.visible.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!chatting) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
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
                onOpenWardrobe = { tab = Tab.WARDROBE }, modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                onChat = { outfit, weather -> chat.open(outfit, app.profile, weather) })
            Tab.WARDROBE -> Box(Modifier.padding(padding).consumeWindowInsets(padding)) { WardrobeRoute() }
            Tab.CODIBOOK -> CodiBookRoute(Modifier.padding(padding).consumeWindowInsets(padding))
            Tab.PROFILE -> MyProfileScreen(
                profile = app.profile,
                style = app.style,
                onSaveStyle = { style ->
                    run {
                        val patch = org.json.JSONObject()
                        val values = style.json()
                        if (style.preferredFits != app.style.preferredFits) patch.put("preferred_fits", values.getJSONArray("preferred_fits"))
                        if (style.preferredStyles != app.style.preferredStyles) patch.put("preferred_styles", values.getJSONArray("preferred_styles"))
                        if (style.favoriteColors != app.style.favoriteColors) patch.put("favorite_colors", values.getJSONArray("favorite_colors"))
                        if (patch.length() > 0) account.patchProfile(patch)
                        viewModel.refreshAccountProfile()
                        null
                    }
                },
                onReanalyze = viewModel::startReanalysis,
                onDelete = viewModel::deleteProfile,
                modifier = Modifier.padding(padding),
                username = auth.session?.user?.username,
                onLogout = authModel::logout,
                onImport = importModel::open,
                authBusy = auth.busy,
                onDeleteRemote = viewModel::deleteAccountProfile,
            )
        }
    }
    if (chatting) ChatRoute(chat)
    }
}

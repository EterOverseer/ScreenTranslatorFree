package com.example.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.feature.history.HistoryScreen
import com.example.feature.history.HistoryViewModel
import com.example.feature.home.HomeScreen
import com.example.feature.home.HomeViewModel
import com.example.feature.privacy.PrivacyScreen
import com.example.feature.privacy.PrivacyViewModel
import com.example.feature.settings.SettingsScreen
import com.example.feature.settings.SettingsViewModel
import com.example.feature.translator.TranslatorScreen
import com.example.feature.translator.TranslatorViewModel
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.CharcoalVoid
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricCyanContainer
import com.example.ui.theme.TextSecondary
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
object TranslatorRoute

@Serializable
object HistoryRoute

@Serializable
object SettingsRoute

@Serializable
object PrivacyRoute

private data class NavTab(
    val route: Any,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

@Composable
fun AppNavigation(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    initialDeepLinkTab: String?,
    onConsumeDeepLink: () -> Unit,
    onRequestStartCapture: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestAccessibilityPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    LaunchedEffect(initialDeepLinkTab) {
        if (initialDeepLinkTab == "history") {
            navigateToSingleTop(navController, HistoryRoute)
            onConsumeDeepLink()
        }
    }

    val tabs = listOf(
        NavTab(HomeRoute, "หน้าแรก", Icons.Rounded.Dashboard, Icons.Outlined.Dashboard, "nav_home"),
        NavTab(TranslatorRoute, "ควบคุม", Icons.Rounded.Translate, Icons.Outlined.Translate, "nav_translator"),
        NavTab(HistoryRoute, "ประวัติ", Icons.Rounded.History, Icons.Outlined.History, "nav_history"),
        NavTab(SettingsRoute, "ตั้งค่า", Icons.Rounded.Tune, Icons.Outlined.Tune, "nav_settings"),
        NavTab(PrivacyRoute, "ปลอดภัย", Icons.Rounded.Shield, Icons.Outlined.Shield, "nav_privacy")
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            containerColor = CharcoalVoid,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        containerColor = CharcoalSurface,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        tabs.forEach { tab ->
                            val selected = currentDestination?.hasRoute(tab.route::class) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateToSingleTop(navController, tab.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label, maxLines = 1) },
                                modifier = Modifier.testTag(tab.testTag),
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = ElectricCyan,
                                    selectedTextColor = ElectricCyan,
                                    indicatorColor = ElectricCyanContainer,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                )
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(containerColor = CharcoalSurface) {
                        tabs.forEach { tab ->
                            val selected = currentDestination?.hasRoute(tab.route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToSingleTop(navController, tab.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.testTag),
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = ElectricCyan,
                                    selectedTextColor = ElectricCyan,
                                    indicatorColor = ElectricCyanContainer,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                )
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = HomeRoute,
                    modifier = Modifier.weight(1f)
                ) {
                    composable<HomeRoute> {
                        val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        HomeScreen(
                            uiState = state,
                            onRequestStartCapture = onRequestStartCapture,
                            onStopCapture = { vm.stopTranslationSession() },
                            onAcknowledgeAiConsent = { vm.acknowledgeAiConsent() },
                            onRequestOverlayPermission = onRequestOverlayPermission,
                            onRequestAccessibilityPermission = onRequestAccessibilityPermission,
                            onRequestNotificationPermission = onRequestNotificationPermission,
                            onNavigateToTranslator = { navigateToSingleTop(navController, TranslatorRoute) },
                            onNavigateToHistory = { navigateToSingleTop(navController, HistoryRoute) },
                            onNavigateToSettings = { navigateToSingleTop(navController, SettingsRoute) },
                            onNavigateToPrivacy = { navigateToSingleTop(navController, PrivacyRoute) }
                        )
                    }

                    composable<TranslatorRoute> {
                        val vm: TranslatorViewModel = viewModel(factory = TranslatorViewModel.factory(container))
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        TranslatorScreen(
                            uiState = state,
                            onRequestStartCapture = onRequestStartCapture,
                            onManualCapture = { vm.triggerManualCapture() },
                            onPauseCapture = { vm.pauseCapture() },
                            onResumeCapture = { vm.resumeCapture() },
                            onStopCapture = { vm.stopCapture() },
                            onCancelJob = { vm.cancelActiveJob() },
                            onSelectCaptureMode = { vm.setCaptureMode(it) },
                            onSelectReadingDirection = { vm.setReadingDirection(it) },
                            onEditSourceText = { vm.updateEditableSource(it) },
                            onRetranslateEdited = { vm.translateEditedText(it) },
                            onRequestOverlayPermission = onRequestOverlayPermission
                        )
                    }

                    composable<HistoryRoute> {
                        val vm: HistoryViewModel = viewModel(factory = HistoryViewModel.factory(container))
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        HistoryScreen(
                            uiState = state,
                            onSearchChange = { vm.onSearchQueryChange(it) },
                            onFilterChange = { vm.onLanguageFilterChange(it) },
                            onDeleteItem = { vm.deleteItem(it) },
                            onClearAll = { vm.clearAll() }
                        )
                    }

                    composable<SettingsRoute> {
                        val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        SettingsScreen(
                            uiState = state,
                            onSetSourceLanguage = { vm.setSourceLanguage(it) },
                            onSetTranslationMode = { vm.setTranslationMode(it) },
                            onSetHybridPriority = { vm.setHybridPriority(it) },
                            onSetCaptureMode = { vm.setCaptureMode(it) },
                            onSetReadingDirection = { vm.setReadingDirection(it) },
                            onSetAutoDelayMs = { vm.setAutoDelayMs(it) },
                            onSetCooldownMs = { vm.setCooldownMs(it) },
                            onSetAiEnabled = { vm.setAiEnabled(it) },
                            onSetCustomNote = { vm.setCustomMangaPromptNote(it) },
                            onSetOverlayEnabled = { vm.setOverlayEnabled(it) },
                            onSetBubbleSize = { vm.setBubbleSize(it) },
                            onSetBubbleOpacity = { vm.setBubbleOpacity(it) },
                            onResetBubblePosition = { vm.resetBubblePosition() },
                            onSetSaveHistoryImages = { vm.setSaveHistoryImages(it) },
                            onDownloadModel = { vm.downloadOfflineModel(it) },
                            onDeleteModel = { vm.deleteOfflineModel(it) },
                            onClearCache = { vm.clearTranslationCache() },
                            onResetSettings = { vm.resetSettings() },
                            onSetTranslationDisplayMode = { vm.setTranslationDisplayMode(it) },
                            onSetTranslationBackgroundTreatment = { vm.setTranslationBackgroundTreatment(it) },
                            onRequestOverlayPermission = onRequestOverlayPermission
                        )
                    }

                    composable<PrivacyRoute> {
                        val vm: PrivacyViewModel = viewModel(factory = PrivacyViewModel.factory(container))
                        val state by vm.uiState.collectAsStateWithLifecycle()
                        PrivacyScreen(
                            uiState = state,
                            onRefresh = { vm.refreshDiagnostics() },
                            onClearErrors = { vm.clearErrors() }
                        )
                    }
                }
            }
        }
    }
}

private fun navigateToSingleTop(navController: NavHostController, route: Any) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

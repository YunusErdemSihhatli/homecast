package io.github.yunuserdemsihhatli.homecast

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.yunuserdemsihhatli.homecast.browser.BrowserHost
import io.github.yunuserdemsihhatli.homecast.browser.BrowserScreen
import io.github.yunuserdemsihhatli.homecast.browser.BrowserViewModel
import io.github.yunuserdemsihhatli.homecast.browser.SharedLink
import io.github.yunuserdemsihhatli.homecast.capture.VideoCandidate
import io.github.yunuserdemsihhatli.homecast.remote.RemoteScreen
import io.github.yunuserdemsihhatli.homecast.remote.RemoteViewModel
import io.github.yunuserdemsihhatli.homecast.service.BatteryOptimization
import io.github.yunuserdemsihhatli.homecast.service.HomeCastService
import io.github.yunuserdemsihhatli.homecast.ui.theme.HomeCastTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

const val ROUTE_BROWSER = "browser"
const val ROUTE_REMOTE = "remote"

/**
 * The single activity. It declares the common configuration changes in the manifest so it is not recreated by them,
 * and owns the browser's WebView ([browserHost]) for its whole lifetime.
 */
class MainActivity : ComponentActivity() {
    /** A link shared with or opened in the app, waiting for the browser to load it. */
    private val sharedUrl = MutableStateFlow<String?>(null)

    /** A screen another component asked to show (the playback notification opens the remote). */
    private val requestedRoute = MutableStateFlow<String?>(null)

    /** The battery optimisation explanation is showing (asked once at first launch, again from the remote's menu). */
    private val showBatteryPrompt = MutableStateFlow(false)

    /** Asked once; whatever the answer, playback works (only the notification is missing without it). */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { askBatteryOptimisation() }

    @VisibleForTesting
    internal val browserViewModel: BrowserViewModel by viewModels { BrowserViewModel.Factory }

    @VisibleForTesting
    internal lateinit var browserHost: BrowserHost
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as HomeCastApp).graph
        browserHost = BrowserHost(this, graph.hostBlocklistAsync, graph.candidateStore, browserViewModel)
        // After recreation the browser reloads its own page; the original intent was already handled.
        if (savedInstanceState == null) handleIntent(intent)
        if (savedInstanceState == null) onboardOnce()
        setContent {
            HomeCastTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeCastNavHost(
                        viewModel = browserViewModel,
                        host = browserHost,
                        sharedUrl = sharedUrl,
                        onSharedUrlLoaded = { sharedUrl.value = null },
                        requestedRoute = requestedRoute,
                        onRouteShown = { requestedRoute.value = null },
                        onOpenBatterySettings = { showBatteryPrompt.value = true },
                    )
                    val batteryPrompt by showBatteryPrompt.collectAsStateWithLifecycle()
                    if (batteryPrompt) {
                        BatteryPromptDialog(
                            onOpenSettings = {
                                showBatteryPrompt.value = false
                                openBatterySettings()
                            },
                            onDismiss = { showBatteryPrompt.value = false },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        browserHost.onStart()
    }

    override fun onStop() {
        browserHost.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        browserHost.destroy()
        super.onDestroy()
    }

    /** First launch only: ask for notifications (Android 13+), then explain battery optimisation if it applies. */
    private fun onboardOnce() {
        val prefs = getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ONBOARDED, false)) return
        prefs.edit { putBoolean(KEY_ONBOARDED, true) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askBatteryOptimisation()
        }
    }

    private fun askBatteryOptimisation() {
        if (!BatteryOptimization.isIgnoring(this)) showBatteryPrompt.value = true
    }

    /** The system exemption dialog, or the settings list when HomeCast is already exempt or the dialog is missing. */
    private fun openBatterySettings() {
        if (!BatteryOptimization.isIgnoring(this)) {
            try {
                startActivity(BatteryOptimization.requestIntent(this))
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No battery optimisation request dialog", e)
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No battery optimisation settings", e)
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getStringExtra(HomeCastService.EXTRA_OPEN) == ROUTE_REMOTE) requestedRoute.value = ROUTE_REMOTE
        handleLink(intent)
    }

    private fun handleLink(intent: Intent?) {
        val url =
            when (intent?.action) {
                Intent.ACTION_SEND -> SharedLink.firstWebUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
                Intent.ACTION_VIEW -> SharedLink.webUrlOrNull(intent.dataString)
                else -> null
            }
        if (url != null) sharedUrl.value = url
    }

    internal companion object {
        private const val TAG = "MainActivity"

        /** First-launch prompts already shown; instrumented tests set it to keep dialogs off the screen. */
        const val ONBOARDING_PREFS = "onboarding"
        const val KEY_ONBOARDED = "onboarded"
    }
}

@Composable
private fun HomeCastNavHost(
    viewModel: BrowserViewModel,
    host: BrowserHost,
    sharedUrl: StateFlow<String?>,
    onSharedUrlLoaded: () -> Unit,
    requestedRoute: StateFlow<String?>,
    onRouteShown: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    val navController = rememberNavController()

    /** A "Play on TV" from the browser, waiting for the remote screen to start it. */
    var pendingPlay by remember { mutableStateOf<VideoCandidate?>(null) }
    val pendingUrl by sharedUrl.collectAsStateWithLifecycle()
    val pendingRoute by requestedRoute.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRoute) {
        pendingRoute?.let { route ->
            navController.navigate(route) { launchSingleTop = true }
            onRouteShown()
        }
    }
    LaunchedEffect(pendingUrl) {
        // Navigating while already on the browser would replace its entry and drop the page being opened.
        if (pendingUrl != null && navController.currentDestination?.route != ROUTE_BROWSER) {
            navController.navigate(ROUTE_BROWSER) {
                popUpTo(ROUTE_BROWSER)
                launchSingleTop = true
            }
        }
    }
    NavHost(navController = navController, startDestination = ROUTE_BROWSER) {
        composable(ROUTE_BROWSER) {
            BrowserScreen(
                viewModel = viewModel,
                host = host,
                requestedUrl = pendingUrl,
                onRequestedUrlLoaded = onSharedUrlLoaded,
                onOpenRemote = { navController.navigate(ROUTE_REMOTE) { launchSingleTop = true } },
                onPlayOnTv = { candidate ->
                    // The phone stays silent while the TV plays.
                    host.pausePageMedia()
                    // Started by the remote once it is on screen, so it is there to report a failure.
                    pendingPlay = candidate
                    navController.navigate(ROUTE_REMOTE) { launchSingleTop = true }
                },
            )
        }
        composable(ROUTE_REMOTE) {
            RemoteScreen(
                viewModel = viewModel(factory = RemoteViewModel.Factory),
                pendingPlay = pendingPlay,
                onStartPlay = { candidate ->
                    pendingPlay = null
                    viewModel.playOnTv(candidate)
                },
                onBackToBrowser = {
                    if (!navController.popBackStack(ROUTE_BROWSER, inclusive = false)) {
                        navController.navigate(ROUTE_BROWSER) { launchSingleTop = true }
                    }
                },
                onOpenBatterySettings = onOpenBatterySettings,
            )
        }
    }
}

@Composable
private fun BatteryPromptDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.battery_title)) },
        text = { Text(stringResource(R.string.battery_text)) },
        confirmButton = {
            TextButton(
                onClick = onOpenSettings,
            ) { Text(stringResource(R.string.battery_open_settings)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.battery_not_now)) } },
    )
}

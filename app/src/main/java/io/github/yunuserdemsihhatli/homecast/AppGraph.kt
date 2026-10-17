package io.github.yunuserdemsihhatli.homecast

import android.content.Context
import android.util.Log
import io.github.yunuserdemsihhatli.homecast.browser.HostBlocklist
import io.github.yunuserdemsihhatli.homecast.capture.CandidateStore
import io.github.yunuserdemsihhatli.homecast.cast.CastClient
import io.github.yunuserdemsihhatli.homecast.cast.CastDiscovery
import io.github.yunuserdemsihhatli.homecast.cast.DeviceStore
import io.github.yunuserdemsihhatli.homecast.cast.PrefsDeviceStore
import io.github.yunuserdemsihhatli.homecast.cast.protocol.CastChannel
import io.github.yunuserdemsihhatli.homecast.playback.NetworkAddresses
import io.github.yunuserdemsihhatli.homecast.playback.PlaybackController
import io.github.yunuserdemsihhatli.homecast.proxy.ProxyServer
import io.github.yunuserdemsihhatli.homecast.service.HomeCastService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Logs uncaught coroutine failures of [AppGraph.appScope] instead of letting them crash the process. */
internal val loggingExceptionHandler =
    CoroutineExceptionHandler { _, e -> Log.e("HomeCast", "Unhandled failure in a background task", e) }

/**
 * App-wide singletons. Everything except the cheap network-address tracker is created lazily on first use, so
 * constructing the graph in [HomeCastApp.onCreate] does no disk or network work on the main thread.
 */
class AppGraph(
    context: Context,
) {
    private val appContext = context.applicationContext

    /**
     * Lives as long as the process; one failing child does not cancel the others, and an exception a child does not
     * handle is logged instead of crashing the app.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + loggingExceptionHandler)

    val httpClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** Reads a large bundled asset: first access must happen off the main thread. */
    val hostBlocklist: HostBlocklist by lazy { HostBlocklist.load(appContext) }

    /** [hostBlocklist] loading on an IO thread, started on first access (the browser screen). */
    val hostBlocklistAsync: Deferred<HostBlocklist> by lazy { appScope.async(Dispatchers.IO) { hostBlocklist } }

    val candidateStore: CandidateStore by lazy { CandidateStore() }

    /**
     * Started lazily by [playback] and then kept running for the app lifetime (the playback service never stops it),
     * so its port, and the proxy URLs the TV re-LOADs, stay valid across stop/play.
     */
    val proxy: ProxyServer by lazy { ProxyServer(httpClient) }

    val cast: CastClient by lazy {
        CastClient(scope = appScope, channelFactory = { CastChannel(it.host, it.port) })
    }

    val deviceStore: DeviceStore by lazy {
        PrefsDeviceStore(appContext.getSharedPreferences("devices", Context.MODE_PRIVATE))
    }

    /**
     * Eager (registering the callback does no I/O) so the Wi-Fi address is known by the time the user first taps
     * "Play on TV".
     */
    val networkAddresses = NetworkAddresses(appContext)

    val discovery: CastDiscovery by lazy { CastDiscovery(appContext) }

    val playback: PlaybackController by lazy {
        PlaybackController(
            scope = appScope,
            proxy = proxy,
            cast = cast,
            wifi = networkAddresses,
            devices = deviceStore,
            serviceStarter = { HomeCastService.start(appContext) },
            serviceStopper = { HomeCastService.release(appContext) },
            discover = { discovery.devices() },
        )
    }
}

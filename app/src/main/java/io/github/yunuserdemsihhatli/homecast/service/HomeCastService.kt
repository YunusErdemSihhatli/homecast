// androidx.media (MediaSessionCompat & co.) is deprecated in favour of media3-session, which is overkill here.
@file:Suppress("DEPRECATION")

package io.github.yunuserdemsihhatli.homecast.service

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.VolumeProviderCompat
import io.github.yunuserdemsihhatli.homecast.AppGraph
import io.github.yunuserdemsihhatli.homecast.HomeCastApp
import io.github.yunuserdemsihhatli.homecast.MainActivity
import io.github.yunuserdemsihhatli.homecast.R
import io.github.yunuserdemsihhatli.homecast.ROUTE_REMOTE
import io.github.yunuserdemsihhatli.homecast.cast.CastRemote
import io.github.yunuserdemsihhatli.homecast.cast.ConnectionState
import io.github.yunuserdemsihhatli.homecast.cast.MediaStatus
import io.github.yunuserdemsihhatli.homecast.cast.PlayerState
import io.github.yunuserdemsihhatli.homecast.playback.NowPlaying
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import androidx.media.app.NotificationCompat as MediaNotificationCompat

/**
 * Keeps HomeCast alive while something plays on the TV: a `mediaPlayback` foreground service holding a Wi-Fi lock
 * and a partial wake lock, so the phone-side proxy keeps serving and the Cast connection stays up with the screen
 * off. It also owns the media notification and the [MediaSessionCompat] (lock-screen controls, and hardware volume
 * keys that move the TV volume through a remote [VolumeProviderCompat]).
 *
 * Started by [PlaybackController][io.github.yunuserdemsihhatli.homecast.playback.PlaybackController] when a play
 * begins ([start]) and released when it stops ([release]); it also releases itself whenever nothing is playing.
 * While something is "now playing" but the TV is not using it ([KeepAwakePolicy]: no media, media ended, or the
 * connection failed two minutes ago), the service goes idle: the locks are released and the notification is left as
 * a dismissable "stopped" one, so the phone can sleep; "now playing" is kept for Play again. When the TV plays again
 * the locks and the foreground are taken back.
 * Releasing never calls `PlaybackController.stop()` (which is what calls [release]), and never stops the proxy: the
 * proxy runs for the whole app lifetime so its port, and with it the URLs the TV re-LOADs, stay the same.
 */
class HomeCastService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var graph: AppGraph
    private lateinit var session: MediaSessionCompat
    private lateinit var volumeProvider: VolumeProviderCompat
    private val volumeRequests = Channel<Int>(Channel.CONFLATED)
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** The last media status seen and when it arrived, to estimate the current position for ±10 s skips. */
    private var lastStatus: MediaStatus? = null
    private var lastStatusAt = SystemClock.elapsedRealtime()
    private var shownContent: NotificationContent? = null
    private var shownMetadata: Pair<String, Long>? = null

    /** True while not in the foreground: before the first start, and from a release until the next start. */
    private var released = true
    private var latestStartId = 0

    /** Started, but the TV is not playing ([KeepAwakePolicy.Decision.Idle]): no locks, not in the foreground. */
    private var idle = false
    private val keepAwake = KeepAwakePolicy(clock = SystemClock::elapsedRealtime)
    private var recheckJob: Job? = null

    private val cast: CastRemote get() = graph.cast

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        graph = (application as HomeCastApp).graph
        createChannel()
        volumeProvider =
            object : VolumeProviderCompat(
                VOLUME_CONTROL_ABSOLUTE,
                MediaSessionBridge.VOLUME_STEPS,
                MediaSessionBridge.volumeToSteps(cast.volume.value?.level),
            ) {
                override fun onSetVolumeTo(volume: Int) = requestVolume(volume)

                override fun onAdjustVolume(direction: Int) {
                    if (direction != 0) requestVolume(currentVolume + direction)
                }
            }
        session =
            MediaSessionCompat(this, TAG).apply {
                setCallback(SessionCallback())
                setSessionActivity(openRemoteIntent())
                setPlaybackToRemote(volumeProvider)
            }
        // The foreground is entered in onStartCommand, which always follows; see start().
        observe()
        isRunning = true
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_RELEASE -> release()
            ACTION_STOP -> stopPlayback()
            ACTION_PLAY -> command { it.play() }
            ACTION_PAUSE -> command { it.pause() }
            ACTION_REWIND -> skip(-SKIP_SECONDS)
            ACTION_FORWARD -> skip(SKIP_SECONDS)
            else -> start()
        }
        // An action intent can create a fresh instance (e.g. a stale notification button); never keep that one alive.
        if (released) stopSelf(latestStartId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        releaseLocks()
        // Stopped by the system while idle: its detached notification would otherwise outlive it.
        if (idle) getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        session.release()
        super.onDestroy()
    }

    /**
     * Handles a plain start, which always comes from `startForegroundService`. Every such start must be acknowledged
     * with `startForeground`, even one that arrives after playback stopped again (stop, play, stop in quick
     * succession): the service is never stopped while a start is unacknowledged, so the foreground is entered first
     * and only then left again if nothing is playing.
     */
    private fun start() {
        val foreground = enterForeground()
        if (!foreground || !hasWork()) return release()
        released = false
        idle = false
        acquireLocks()
        session.isActive = true
        refresh()
    }

    /** Something is playing, or a play is starting. */
    private fun hasWork() = graph.playback.nowPlaying.value != null || graph.playback.starting.value

    /** Applies the current state: release when there is nothing to do, else go idle or awake per [keepAwake]. */
    private fun refresh() {
        if (released) return // nothing to show; the next start() refreshes
        if (!hasWork()) return release()
        val media = cast.media.value
        val connection = cast.connection.value
        recheckJob?.cancel()
        when (val decision = keepAwake.evaluate(media, connection, graph.playback.starting.value)) {
            KeepAwakePolicy.Decision.Idle -> {
                goIdle()
            }

            is KeepAwakePolicy.Decision.Awake -> {
                wakeUp()
                decision.recheckInMillis?.let { delayMillis ->
                    recheckJob =
                        scope.launch {
                            delay(delayMillis)
                            refresh()
                        }
                }
            }
        }
        val playing = graph.playback.nowPlaying.value ?: return
        updateSession(playing, media)
        showNotification(contentOf(playing, media, connection))
    }

    /** Lets the phone sleep while keeping the (now dismissable) notification and "now playing". */
    private fun goIdle() {
        if (idle) return
        idle = true
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        shownContent = null // re-post as the non-ongoing variant
    }

    /** Takes the foreground and the locks back after [goIdle]. */
    private fun wakeUp() {
        if (!idle) return
        idle = false
        // Refused in the background on Android 12+; the locks still help until the user opens the app again.
        enterForeground()
        acquireLocks()
    }

    /**
     * Leaves the foreground and stops; playback itself is left alone (see the class comment). Safe to repeat: [start]
     * may have just entered the foreground on an instance that was already released.
     */
    private fun release() {
        released = true
        idle = false
        recheckJob?.cancel()
        releaseLocks()
        session.isActive = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // An idle service's notification is detached from it, so STOP_FOREGROUND_REMOVE alone would leave it behind.
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        shownContent = null
        // Only the newest start id stops the service, so a start already queued behind this release still wins.
        stopSelf(latestStartId)
    }

    private fun enterForeground(): Boolean =
        try {
            val type =
                if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.Q
                ) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                }
            val content = currentContent()
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(content), type)
            shownContent = content
            true
        } catch (e: IllegalStateException) {
            // Defensive only: start() runs for startForegroundService starts, for which the system allows
            // startForeground, and a refused start already throws in HomeCastService.start(). Should a device refuse
            // anyway, release instead of crashing; nothing else could acknowledge the start.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "Not allowed to start the playback service in the foreground", e)
                false
            } else {
                throw e
            }
        }

    private fun observe() {
        scope.launch {
            val state =
                combine(graph.playback.nowPlaying, cast.media, cast.connection, graph.playback.starting) { p, m, c, _ ->
                    Triple(p, m, c)
                }
            state.collect { (_, media, _) ->
                if (media !== lastStatus) {
                    lastStatus = media
                    lastStatusAt = SystemClock.elapsedRealtime()
                }
                refresh()
            }
        }
        scope.launch {
            cast.volume.collect { volumeProvider.currentVolume = MediaSessionBridge.volumeToSteps(it?.level) }
        }
        scope.launch {
            // Conflated and sequential, so holding a volume key cannot reorder or pile up requests.
            for (steps in volumeRequests) {
                try {
                    cast.setVolume(MediaSessionBridge.stepsToVolume(steps))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failed volume step must not end the loop (and with it every later volume key press).
                    Log.w(TAG, "Volume change failed", e)
                }
            }
        }
    }

    private fun requestVolume(steps: Int) {
        val clamped = steps.coerceIn(0, MediaSessionBridge.VOLUME_STEPS)
        volumeProvider.currentVolume = clamped
        volumeRequests.trySend(clamped)
    }

    private fun updateSession(
        playing: NowPlaying,
        media: MediaStatus?,
    ) {
        val mapped = MediaSessionBridge.toPlaybackState(media, playing.session.isLive)
        val speed = if (mapped.state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f
        session.setPlaybackState(
            PlaybackStateCompat
                .Builder()
                .setActions(mapped.actions)
                .apply {
                    MediaSessionBridge
                        .customActions(
                            playing.session.isLive,
                        ).forEach { addCustomAction(customAction(it)) }
                }.setState(mapped.state, mapped.positionMs, speed, lastStatusAt)
                .build(),
        )
        val durationMs = media?.durationSeconds?.let { (it * 1000).toLong() } ?: -1L
        val metadata = playing.title to durationMs
        if (metadata != shownMetadata) {
            shownMetadata = metadata
            session.setMetadata(
                MediaMetadataCompat
                    .Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, playing.title)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                    .build(),
            )
        }
    }

    /**
     * Android 13+ builds the media controls from the session's playback state and ignores the notification's
     * actions, so ±10 s and Stop must also be custom session actions there.
     */
    private fun customAction(id: String): PlaybackStateCompat.CustomAction {
        val (icon, name) =
            when (id) {
                MediaSessionBridge.CUSTOM_REWIND -> R.drawable.ic_rewind_10 to R.string.notification_action_rewind
                MediaSessionBridge.CUSTOM_FORWARD -> R.drawable.ic_forward_10 to R.string.notification_action_forward
                else -> R.drawable.ic_stop to R.string.notification_action_stop
            }
        return PlaybackStateCompat.CustomAction.Builder(id, getString(name), icon).build()
    }

    private fun currentContent(): NotificationContent {
        val playing = graph.playback.nowPlaying.value
        return if (playing == null) {
            NotificationContent(
                title = getString(R.string.app_name),
                deviceName = null,
                playing = false,
                live = false,
                state = NotificationState.STARTING,
            )
        } else {
            contentOf(playing, cast.media.value, cast.connection.value)
        }
    }

    private fun contentOf(
        playing: NowPlaying,
        media: MediaStatus?,
        connection: ConnectionState,
    ) = NotificationContent(
        title = playing.title,
        deviceName = (connection as? ConnectionState.Connected)?.device?.name,
        playing = media?.playerState == PlayerState.PLAYING || media?.playerState == PlayerState.BUFFERING,
        live = playing.session.isLive,
        state =
            when {
                !idle -> NotificationState.ACTIVE
                connection is ConnectionState.Failed -> NotificationState.DISCONNECTED
                else -> NotificationState.STOPPED
            },
    )

    /** Re-posts the notification only when what it shows changes, not on every position update. */
    private fun showNotification(content: NotificationContent) {
        if (content == shownContent) return
        shownContent = content
        // The foreground notification is shown even without POST_NOTIFICATIONS; updates are best effort.
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(content: NotificationContent): Notification {
        val idleState = content.state == NotificationState.STOPPED || content.state == NotificationState.DISCONNECTED
        val builder =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_cast)
                .setContentTitle(content.title)
                .setContentText(
                    when (content.state) {
                        NotificationState.STARTING -> {
                            getString(R.string.notification_starting)
                        }

                        NotificationState.STOPPED -> {
                            getString(R.string.notification_stopped)
                        }

                        NotificationState.DISCONNECTED -> {
                            getString(R.string.notification_disconnected)
                        }

                        NotificationState.ACTIVE -> {
                            content.deviceName?.let { getString(R.string.notification_casting_to, it) }
                                ?: getString(R.string.notification_casting)
                        }
                    },
                ).setContentIntent(openRemoteIntent())
                .setOngoing(!idleState)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (idleState) {
            // Nothing to control on the TV; the content intent opens the remote, which offers Play again.
            builder.addAction(action(R.drawable.ic_stop, R.string.notification_action_stop, ACTION_STOP))
            return builder
                .setStyle(
                    MediaNotificationCompat
                        .MediaStyle()
                        .setMediaSession(session.sessionToken)
                        .setShowActionsInCompactView(0),
                ).build()
        }
        if (!content.live) {
            builder.addAction(
                action(R.drawable.ic_rewind_10, R.string.notification_action_rewind, ACTION_REWIND),
            )
        }
        if (content.playing) {
            builder.addAction(action(R.drawable.ic_pause, R.string.notification_action_pause, ACTION_PAUSE))
        } else {
            builder.addAction(action(R.drawable.ic_play, R.string.notification_action_play, ACTION_PLAY))
        }
        if (!content.live) {
            builder.addAction(
                action(R.drawable.ic_forward_10, R.string.notification_action_forward, ACTION_FORWARD),
            )
        }
        builder.addAction(action(R.drawable.ic_stop, R.string.notification_action_stop, ACTION_STOP))
        val compact = if (content.live) intArrayOf(0, 1) else intArrayOf(0, 1, 2) // live: play/pause + stop
        return builder
            .setStyle(
                MediaNotificationCompat
                    .MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(*compact),
            ).build()
    }

    private fun action(
        @DrawableRes icon: Int,
        @StringRes title: Int,
        action: String,
    ): NotificationCompat.Action {
        val intent = Intent(this, HomeCastService::class.java).setAction(action)
        val pending = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action(icon, getString(title), pending)
    }

    private fun openRemoteIntent(): PendingIntent {
        val intent =
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_OPEN, ROUTE_REMOTE)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_playback),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_playback_description)
                setShowBadge(false)
            }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** No timeout: held exactly while playing, released on release and in [onDestroy]. */
    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val wifi =
            wifiLock ?: applicationContext
                .getSystemService(WifiManager::class.java)
                ?.createWifiLock(wifiLockMode(), TAG)
                ?.apply { setReferenceCounted(false) }
                .also { wifiLock = it }
        if (wifi?.isHeld == false) wifi.acquire()
        val wake =
            wakeLock ?: getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                ?.apply { setReferenceCounted(false) }
                .also { wakeLock = it }
        if (wake?.isHeld == false) wake.acquire()
    }

    private fun releaseLocks() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun command(block: suspend (CastRemote) -> Unit) {
        if (released || idle) return
        graph.appScope.launch {
            try {
                block(cast)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Media command failed", e)
            }
        }
    }

    private fun skip(deltaSeconds: Double) {
        if (graph.playback.nowPlaying.value
                ?.session
                ?.isLive != false
        ) {
            return
        }
        val media = cast.media.value ?: return
        val target =
            MediaSessionBridge.skipTargetSeconds(
                media,
                SystemClock.elapsedRealtime() - lastStatusAt,
                deltaSeconds,
            )
        command { it.seek(target) }
    }

    /** Stops playback for real; [PlaybackController.stop] then releases this service. */
    private fun stopPlayback() {
        graph.appScope.launch { graph.playback.stop() }
    }

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() = command { it.play() }

        override fun onPause() = command { it.pause() }

        override fun onSeekTo(pos: Long) = command { it.seek(pos / 1000.0) }

        override fun onFastForward() = skip(SKIP_SECONDS)

        override fun onRewind() = skip(-SKIP_SECONDS)

        override fun onStop() = stopPlayback()

        override fun onCustomAction(
            action: String?,
            extras: Bundle?,
        ) {
            when (action) {
                MediaSessionBridge.CUSTOM_REWIND -> skip(-SKIP_SECONDS)
                MediaSessionBridge.CUSTOM_FORWARD -> skip(SKIP_SECONDS)
                MediaSessionBridge.CUSTOM_STOP -> stopPlayback()
            }
        }
    }

    /** What the notification shows; it is re-posted only when this changes. */
    private data class NotificationContent(
        val title: String,
        val deviceName: String?,
        val playing: Boolean,
        val live: Boolean,
        val state: NotificationState,
    )

    private enum class NotificationState { STARTING, ACTIVE, STOPPED, DISCONNECTED }

    companion object {
        /** Extra on the notification's content intent naming the screen to open; Task 13's navigation reads it. */
        const val EXTRA_OPEN = "open"

        private const val TAG = "HomeCastService"
        private const val WAKE_LOCK_TAG = "HomeCast:playback"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val SKIP_SECONDS = 10.0
        private const val ACTION_PREFIX = "io.github.yunuserdemsihhatli.homecast.service."
        private const val ACTION_RELEASE = ACTION_PREFIX + "RELEASE"
        private const val ACTION_STOP = ACTION_PREFIX + "STOP"
        private const val ACTION_PLAY = ACTION_PREFIX + "PLAY"
        private const val ACTION_PAUSE = ACTION_PREFIX + "PAUSE"
        private const val ACTION_REWIND = ACTION_PREFIX + "REWIND"
        private const val ACTION_FORWARD = ACTION_PREFIX + "FORWARD"

        /** True between [onCreate] and [onDestroy]; [release] has nothing to do otherwise. */
        @Volatile private var isRunning = false

        /** Starts the service in the foreground; call when playback on the TV begins. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, HomeCastService::class.java))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) when the app is in the background: playback
                // still works, only without screen-off protection.
                Log.w(TAG, "Could not start the playback service", e)
            }
        }

        /** Leaves the foreground and stops the service without touching playback. */
        fun release(context: Context) {
            if (!isRunning) return
            val intent = Intent(context, HomeCastService::class.java).setAction(ACTION_RELEASE)
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                // No stopService() fallback: it could tear the service down while a startForegroundService start is
                // still unacknowledged. The service releases itself anyway once nothing is playing.
                Log.w(TAG, "Could not deliver release to the playback service", e)
            }
        }

        @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF is the only high-performance mode before API 29.
        private fun wifiLockMode(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
    }
}

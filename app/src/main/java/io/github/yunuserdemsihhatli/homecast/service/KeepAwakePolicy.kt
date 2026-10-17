package io.github.yunuserdemsihhatli.homecast.service

import io.github.yunuserdemsihhatli.homecast.cast.ConnectionState
import io.github.yunuserdemsihhatli.homecast.cast.MediaStatus
import io.github.yunuserdemsihhatli.homecast.cast.isEnded

/**
 * Decides whether the playback service should keep the phone awake (wake lock, Wi-Fi lock, foreground) for the TV.
 *
 * Awake while a play is starting, while the TV has media that has not ended, and while reconnecting. Not awake when
 * the TV has no media or its media ended (FINISHED, ERROR, CANCELLED), or once the connection has been
 * [ConnectionState.Failed] for [failedGraceMillis] (the user may still reconnect within that time). Stateful only for
 * that grace period, measured with [clock].
 */
class KeepAwakePolicy(
    private val clock: () -> Long,
    private val failedGraceMillis: Long = FAILED_GRACE_MILLIS,
) {
    sealed interface Decision {
        /** Keep the locks; evaluate again after [recheckInMillis] if set, even if nothing else changes. */
        data class Awake(
            val recheckInMillis: Long? = null,
        ) : Decision

        data object Idle : Decision
    }

    private var failedSince: Long? = null

    fun evaluate(
        media: MediaStatus?,
        connection: ConnectionState,
        starting: Boolean,
    ): Decision {
        if (connection !is ConnectionState.Failed) failedSince = null
        return when {
            starting -> {
                Decision.Awake()
            }

            connection is ConnectionState.Failed -> {
                val now = clock()
                val since = failedSince ?: now.also { failedSince = it }
                val left = failedGraceMillis - (now - since)
                if (left > 0) Decision.Awake(recheckInMillis = left) else Decision.Idle
            }

            connection is ConnectionState.Connecting -> {
                Decision.Awake()
            }

            media == null || media.isEnded -> {
                Decision.Idle
            }

            else -> {
                Decision.Awake()
            }
        }
    }

    companion object {
        const val FAILED_GRACE_MILLIS = 2 * 60_000L
    }
}

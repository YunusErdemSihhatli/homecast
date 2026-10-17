package io.github.yunuserdemsihhatli.homecast.ui

import android.content.res.Resources
import io.github.yunuserdemsihhatli.homecast.R
import io.github.yunuserdemsihhatli.homecast.playback.PlaybackError
import io.github.yunuserdemsihhatli.homecast.proxy.ProxyException

/** The user-facing text for a "Play on TV" failure. */
fun PlaybackError.message(resources: Resources): String =
    when (this) {
        PlaybackError.NoWifi -> {
            resources.getString(R.string.error_no_wifi)
        }

        PlaybackError.NoDevice -> {
            resources.getString(R.string.error_no_device)
        }

        PlaybackError.InvalidSubtitle -> {
            resources.getString(R.string.error_invalid_subtitle)
        }

        is PlaybackError.CannotReachTv -> {
            resources.getString(R.string.error_cannot_reach_tv, deviceName)
        }

        is PlaybackError.Cast -> {
            resources.getString(R.string.error_cast, message)
        }

        is PlaybackError.Internal -> {
            resources.getString(R.string.error_internal, message)
        }

        is PlaybackError.Proxy -> {
            resources.getString(
                when (reason) {
                    ProxyException.Reason.UPSTREAM_REJECTED -> R.string.error_upstream_rejected
                    ProxyException.Reason.UPSTREAM_NOT_FOUND -> R.string.error_upstream_not_found
                    ProxyException.Reason.UPSTREAM_UNREACHABLE -> R.string.error_upstream_unreachable
                    ProxyException.Reason.NOT_MEDIA -> R.string.error_not_media
                    ProxyException.Reason.UNSUPPORTED_DASH -> R.string.error_unsupported_dash
                },
            )
        }
    }

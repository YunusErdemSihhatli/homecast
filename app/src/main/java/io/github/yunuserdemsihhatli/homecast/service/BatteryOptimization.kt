package io.github.yunuserdemsihhatli.homecast.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Battery optimisation (Doze / app standby) can cut the phone-side proxy off while the screen is off, which stalls
 * the TV mid-stream. These helpers let the UI ask the user to exempt HomeCast.
 */
object BatteryOptimization {
    fun isIgnoring(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * The system dialog that exempts this app. Lint's BatteryLife check is suppressed on purpose: the app serves the
     * video to the TV from the phone, so being suspended in Doze breaks its core function.
     */
    @SuppressLint("BatteryLife")
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
}

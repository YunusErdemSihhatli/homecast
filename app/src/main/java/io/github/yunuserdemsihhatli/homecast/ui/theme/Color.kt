package io.github.yunuserdemsihhatli.homecast.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal val LightColors =
    lightColorScheme(
        primary = Color(0xFF1B4B8F),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD7E3FF),
        onPrimaryContainer = Color(0xFF001B3E),
        secondary = Color(0xFF565F71),
        background = Color(0xFFFAF9FD),
        surface = Color(0xFFFAF9FD),
    )

internal val DarkColors =
    darkColorScheme(
        primary = Color(0xFFABC7FF),
        onPrimary = Color(0xFF002F65),
        primaryContainer = Color(0xFF00458E),
        onPrimaryContainer = Color(0xFFD7E3FF),
        secondary = Color(0xFFBEC6DC),
        background = Color(0xFF121316),
        surface = Color(0xFF121316),
    )

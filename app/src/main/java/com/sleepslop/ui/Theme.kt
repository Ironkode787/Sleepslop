package com.sleepslop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Midnight palette — the app is intentionally always dark.
val Night = Color(0xFF0B1026)
val NightSurface = Color(0xFF161C3F)
val NightSurfaceHigh = Color(0xFF222A57)
val Periwinkle = Color(0xFF9DAEFF)
val Lavender = Color(0xFFC3A6FF)
val Moonlight = Color(0xFFEDEBFF)
val Mist = Color(0xFF8E94B8)
val Aurora = Color(0xFF6FE3C5)

private val SleepslopColors = darkColorScheme(
    primary = Periwinkle,
    onPrimary = Night,
    secondary = Lavender,
    onSecondary = Night,
    tertiary = Aurora,
    background = Night,
    onBackground = Moonlight,
    surface = NightSurface,
    onSurface = Moonlight,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = Mist,
    outline = Color(0xFF3A4270),
)

@Composable
fun SleepslopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SleepslopColors,
        content = content
    )
}

package com.sleepslop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.sleepslop.R
import com.sleepslop.audio.Sound

// Midnight palette — the app is intentionally always dark.
val Night = Color(0xFF0B1026)
val NightSurface = Color(0xFF161C3F)
val NightSurfaceHigh = Color(0xFF222A57)
val Periwinkle = Color(0xFF9DAEFF)
val Lavender = Color(0xFFC3A6FF)
val Moonlight = Color(0xFFEDEBFF)
val Mist = Color(0xFF8E94B8)
val Aurora = Color(0xFF6FE3C5)

/** Rounded display face (Comfortaa, SIL OFL 1.1) for headings and labels. */
val Display = FontFamily(
    Font(R.font.comfortaa, FontWeight.Normal),
    Font(R.font.comfortaa_semibold, FontWeight.SemiBold),
)

private val SleepslopTypography = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold),
        headlineMedium = t.headlineMedium.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontFamily = Display),
        titleMedium = t.titleMedium.copy(fontFamily = Display),
        titleSmall = t.titleSmall.copy(fontFamily = Display),
        labelLarge = t.labelLarge.copy(fontFamily = Display),
    )
}

/**
 * Each sound's moonlit accent — dim pastels that glow against the night
 * without ever getting loud. Used for card borders, sliders, constellation
 * stars, and activity bars.
 */
val Sound.accent: Color
    get() = when (this) {
        Sound.WHITE -> Color(0xFFDCE3F7)
        Sound.PINK -> Color(0xFFF5A8C7)
        Sound.BROWN -> Color(0xFFD2A06E)
        Sound.DEEP -> Color(0xFFA78BFA)
        Sound.RAIN -> Color(0xFF7FB8F0)
        Sound.OCEAN -> Color(0xFF5FD4D0)
        Sound.WIND -> Color(0xFF9BD8A5)
        Sound.FOREST -> Color(0xFF7FCB8F)
        Sound.FIRE -> Color(0xFFFF9E6B)
        Sound.FAN -> Color(0xFF8FD3E8)
        Sound.FANSIM -> Color(0xFF7EC8E3)
        Sound.TRAIN -> Color(0xFFD4A373)
        Sound.CRICKETS -> Color(0xFFB5E48C)
        Sound.FROGS -> Color(0xFF76C893)
        Sound.OWL -> Color(0xFFC9ADA7)
        Sound.THUNDER -> Color(0xFFA5A8D6)
        Sound.CHIMES -> Color(0xFFF2C6DE)
        Sound.DRIPS -> Color(0xFF86C5DA)
        Sound.HEARTBEAT -> Color(0xFFF08080)
        Sound.PURR -> Color(0xFFE8B4BC)
        Sound.CLOCK -> Color(0xFFD9C5A0)
        Sound.CAFE -> Color(0xFFD4A276)
        Sound.FOGHORN -> Color(0xFF94A8C3)
        Sound.BIRDS -> Color(0xFFFFD97D)
    }

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
        typography = SleepslopTypography,
        content = content
    )
}

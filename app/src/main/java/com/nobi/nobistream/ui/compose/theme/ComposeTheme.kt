package com.nobi.nobistream.ui.compose.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// v2.6.0: the mock's palette, verbatim (Color.kt). This IS the theme —
// the old graphite theme is deleted. No graphite hexes anywhere.
// Dark background #090C12, surface #121722, card #1A2030,
// primary violet #7C66FF, secondary cyan #00E5FF, gold #FFC048,
// success #00D68F, coral #FF4D6D.
// Light background #F6F8FC, surface #FFFFFF, card #EEF1F8,
// primary #5A45E6, secondary #0097A7.
// AMOLED background #000000, surface #07090E, surfaceVariant #121620.
val NobiDarkBg = Color(0xFF090C12)
val NobiDarkSurface = Color(0xFF121722)
val NobiDarkCard = Color(0xFF1A2030)
val NobiPrimaryViolet = Color(0xFF7C66FF)
val NobiAccentCyan = Color(0xFF00E5FF)
val NobiGold = Color(0xFFFFC048)
val NobiSuccess = Color(0xFF00D68F)
val NobiCoral = Color(0xFFFF4D6D)

val NobiLightBg = Color(0xFFF6F8FC)
val NobiLightSurface = Color(0xFFFFFFFF)
val NobiLightCard = Color(0xFFEEF1F8)
val NobiPrimaryLight = Color(0xFF5A45E6)
val NobiAccentLight = Color(0xFF0097A7)

private val DarkColorScheme = darkColorScheme(
    primary = NobiPrimaryViolet,
    onPrimary = Color.White,
    secondary = NobiAccentCyan,
    onSecondary = Color(0xFF002026),
    tertiary = NobiGold,
    background = NobiDarkBg,
    onBackground = Color(0xFFF0F4FF),
    surface = NobiDarkSurface,
    onSurface = Color(0xFFECEFF8),
    surfaceVariant = NobiDarkCard,
    onSurfaceVariant = Color(0xFFA6B0C6),
    error = NobiCoral
)

private val LightColorScheme = lightColorScheme(
    primary = NobiPrimaryLight,
    onPrimary = Color.White,
    secondary = NobiAccentLight,
    onSecondary = Color.White,
    tertiary = Color(0xFFE68A00),
    background = NobiLightBg,
    onBackground = Color(0xFF111520),
    surface = NobiLightSurface,
    onSurface = Color(0xFF151924),
    surfaceVariant = NobiLightCard,
    onSurfaceVariant = Color(0xFF535D73),
    error = NobiCoral
)

/**
 * v2.6.0 theme, ported verbatim from the mock's Theme.kt.
 * [darkTheme] comes from SettingsStore.themeMode (Dark / Light /
 * System) resolved at the root; [amoledMode] is SettingsStore.amoled
 * and only applies while the effective theme is dark.
 */
@Composable
fun NobiStreamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoledMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val baseScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val colorScheme = if (darkTheme && amoledMode) {
        baseScheme.copy(
            background = Color.Black,
            surface = Color(0xFF07090E),
            surfaceVariant = Color(0xFF121620)
        )
    } else {
        baseScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            // v2.6.1 belt-and-braces: the decor view itself carries
            // the theme background, so no unthemed Compose host can
            // ever show a dark window behind light-theme content
            // (the 2.6.0 Channel/Search/Settings bug) again.
            window.decorView.setBackgroundColor(colorScheme.background.toArgb())
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

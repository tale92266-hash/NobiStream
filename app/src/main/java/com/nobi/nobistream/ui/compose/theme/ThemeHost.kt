package com.nobi.nobistream.ui.compose.theme

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nobi.nobistream.data.SettingsStore

/**
 * v2.6.1: resolves the effective dark theme from SettingsStore
 * themeMode — Dark -> true, Light -> false, System -> system.
 * The same rule lives in NobiComposeRoot and ControlScreen; keep
 * all three in sync.
 */
fun resolveDarkTheme(themeMode: String, systemDark: Boolean): Boolean =
    when (themeMode) {
        "Dark" -> true
        "Light" -> false
        else -> systemDark
    }

/**
 * v2.6.1: AppCompat night mode for the Views screens (Login,
 * Continue All) and the app theme (values vs values-night
 * palettes). Applied at Application start and whenever themeMode
 * changes; the player forces dark itself (cinema).
 */
fun applyNightModeFromTheme(themeMode: String) {
    AppCompatDelegate.setDefaultNightMode(
        when (themeMode) {
            "Dark" -> AppCompatDelegate.MODE_NIGHT_YES
            "Light" -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    )
}

/**
 * v2.6.1 shared theme host for the standalone Compose activities
 * (Channel, Search, Settings). Root cause of the 2.6.0 bug: those
 * activities called setContent with NO NobiStreamTheme wrapper, so
 * Compose fell back to the default LIGHT MaterialTheme (dark
 * onSurface text, light lavender surfaceVariant cards) on a DARK
 * window background — dark headings on dark bg, unreadable. Only
 * NobiComposeRoot applied the theme. This host collects
 * themeMode + amoled, resolves the effective theme, wraps the
 * content in [NobiStreamTheme], and paints the root background.
 * MainActivity keeps its own root (already themed) — do not wrap
 * it twice.
 */
@Composable
fun NobiThemeHost(
    settingsStore: SettingsStore,
    content: @Composable () -> Unit
) {
    val themeMode by settingsStore.themeMode
        .collectAsStateWithLifecycle(initialValue = "System")
    val amoled by settingsStore.amoled
        .collectAsStateWithLifecycle(initialValue = false)
    val darkTheme = resolveDarkTheme(themeMode, isSystemInDarkTheme())

    NobiStreamTheme(darkTheme = darkTheme, amoledMode = amoled) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            content()
        }
    }
}

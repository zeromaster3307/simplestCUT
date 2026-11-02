package app.simplestcut.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.simplestcut.settings.ThemeMode

/** Hell/dunkel nach Einstellung (Standard: wie das System), mit den Systemfarben (Material You). */
@Composable
fun SimplestCutTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}

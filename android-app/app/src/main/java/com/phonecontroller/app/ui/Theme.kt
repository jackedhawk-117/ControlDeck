package com.phonecontroller.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Visual direction (spec §9): dark, minimalist, modern gaming aesthetic,
 * high contrast, configurable accent. v0.1 ships a single dark scheme with
 * a red-orange gaming accent.
 */
private val Accent = Color(0xFFFF5A36)
private val AccentDim = Color(0xFF7A2E1C)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1A0A06),
    primaryContainer = AccentDim,
    onPrimaryContainer = Color(0xFFFFD9CF),
    secondary = Color(0xFF7CD3FF),
    onSecondary = Color(0xFF00344A),
    background = Color(0xFF0B0D10),
    onBackground = Color(0xFFE4E6EA),
    surface = Color(0xFF131519),
    onSurface = Color(0xFFE4E6EA),
    surfaceVariant = Color(0xFF1C1F24),
    onSurfaceVariant = Color(0xFF9AA1AC),
    error = Color(0xFFFF6B5E),
    onError = Color(0xFF2B0500),
)

@Composable
fun PhoneControllerTheme(content: @Composable () -> Unit) {
    // The app targets a dedicated dark gaming look in both light and dark
    // system settings.
    MaterialTheme(colorScheme = DarkScheme) {
        androidx.compose.material3.Surface(
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}

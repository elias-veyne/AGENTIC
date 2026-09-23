package com.jarves.mh.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Agentic glassmorphism palette (matches approved HTML demo)
val AgenticBlue = Color(0xFF54CCFF)
val AgenticViolet = Color(0xFF7C6CFF)
val AgenticBg = Color(0xFF05070D)
val AgenticSurface = Color(0xFF0C1119)
val AgenticSurfaceVariant = Color(0xFF141B27)
val AgenticOutline = Color(0xFF223042)
val AgenticText = Color(0xFFE8EEF7)
val AgenticTextDim = Color(0xFF93A1B5)

val PocketOrange = AgenticBlue
val PocketBlue = AgenticViolet
val PocketGreen = Color(0xFF69D69E)
val PocketBackground = AgenticBg
val PocketSurface = AgenticSurface
val PocketSurfaceVariant = AgenticSurfaceVariant
val PocketOutline = AgenticOutline

private val DarkColors = darkColorScheme(
    primary = AgenticBlue,
    onPrimary = Color(0xFF002030),
    primaryContainer = Color(0xFF0E2A3A),
    onPrimaryContainer = Color(0xFFBFEAff),
    secondary = AgenticViolet,
    onSecondary = Color(0xFF12002B),
    tertiary = PocketGreen,
    onTertiary = Color(0xFF00391E),
    background = AgenticBg,
    onBackground = AgenticText,
    surface = AgenticSurface,
    onSurface = AgenticText,
    surfaceVariant = AgenticSurfaceVariant,
    onSurfaceVariant = AgenticTextDim,
    outline = AgenticOutline,
    outlineVariant = Color(0xFF1B2635),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6E9E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6F0FF),
    onPrimaryContainer = Color(0xFF062E44),
    secondary = Color(0xFF5A48C9),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF1B8A5A),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F8FC),
    onBackground = Color(0xFF0C1119),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0C1119),
    surfaceVariant = Color(0xFFE7EEF6),
    onSurfaceVariant = Color(0xFF4C5A6B),
    outline = Color(0xFFC3D0DC),
    outlineVariant = Color(0xFFDCE5EE),
)

enum class AppThemeMode { SYSTEM, DARK, LIGHT }

@Composable
fun PocketTheme(themeMode: AppThemeMode = AppThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val isDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = if (isDark) DarkColors else LightColors,
        content = content,
    )
}

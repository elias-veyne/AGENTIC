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

// Devil's Portfolio AMOLED Glass Theme Palette
// Pure black background with glassmorphism effects
val DevilBg = Color(0xFF000000)           // Pure AMOLED black
val DevilSurface = Color(0xFF0A0F14)      // Very dark surface
val DevilSurfaceVariant = Color(0xFF121820) // Surface variant
val DevilSurfaceBright = Color(0xFF1A2230) // Brighter surface

// Accent colors
val DevilPrimary = Color(0xFF38BDF8)      // Bright blue
val DevilSecondary = Color(0xFFBAE6FD)    // Ice blue
val DevilTertiary = Color(0xFF69D69E)     // Green accent
val DevilAccent = Color(0xFF6366F1)       // Purple accent

// Text colors
val DevilText = Color(0xFFBAE6FD)         // Ice blue text
val DevilTextMuted = Color(0xFF7DD3FC)    // Muted text
val DevilTextDim = Color(0xFF4B5563)      // Dim text

// Border/outline
val DevilOutline = Color(0xFF2A3B55)
val DevilOutlineVariant = Color(0xFF1E2E45)

// Glass surface colors
val GlassSurface = Color(0x1CFFFFFF)      // 11% white
val GlassSurfaceStrong = Color(0x24FFFFFF)  // 14% white
val GlassBorder = Color(0x38FFFFFF)       // 22% white
val GlassHalo = Color(0x4D38BDF8)         // Blue halo

// Color scheme
private val DevilDarkColors = darkColorScheme(
    primary = DevilPrimary,
    onPrimary = Color(0xFF001A2E),
    primaryContainer = Color(0xFF0E2B45),
    onPrimaryContainer = Color(0xFFCFEFFF),
    secondary = DevilSecondary,
    onSecondary = Color(0xFF0A2033),
    secondaryContainer = Color(0xFF1E3A5F),
    onSecondaryContainer = Color(0xFFE0F2FE),
    tertiary = DevilTertiary,
    onTertiary = Color(0xFF00391E),
    tertiaryContainer = Color(0xFF115B3E),
    onTertiaryContainer = Color(0xFFA7F3D0),
    background = DevilBg,
    onBackground = DevilText,
    surface = DevilSurface,
    onSurface = DevilText,
    surfaceVariant = DevilSurfaceVariant,
    onSurfaceVariant = DevilTextMuted,
    surfaceTint = DevilPrimary,
    outline = DevilOutline,
    outlineVariant = DevilOutlineVariant,
)

private val DevilLightColors = lightColorScheme(
    primary = DevilPrimary,
    onPrimary = Color(0xFF001A2E),
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0C3A59),
    secondary = DevilSecondary,
    onSecondary = Color(0xFF0A2033),
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0C3A59),
    tertiary = DevilTertiary,
    onTertiary = Color(0xFF00391E),
    tertiaryContainer = Color(0xFFD1F4E0),
    onTertiaryContainer = Color(0xFF0C3A2B),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    outline = DevilOutline,
    outlineVariant = Color(0xFFCBD5E1),
)

enum class AppThemeMode { SYSTEM, DARK, LIGHT }

@Composable
fun DevilTheme(themeMode: AppThemeMode = AppThemeMode.DARK, content: @Composable () -> Unit) {
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
        colorScheme = if (isDark) DevilDarkColors else DevilLightColors,
        content = content,
    )
}

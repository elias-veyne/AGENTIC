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

// ---------------------------------------------------------------------------
// Agentic v2 theme — "Aurora Obsidian".
//
// The v1 glassmorphism palette leaned on a single sky-blue accent. The new
// identity keeps the near-black canvas (it is what makes the glass surfaces
// read) but moves to a two-pole accent system: an electric iris violet for the
// primary rail and a mint-teal for success/confirmation states. The pair is
// deliberately complementary so a completed todo checkmark never blends into
// the surrounding chrome.
// ---------------------------------------------------------------------------

// Primary rail — electric iris. Cooler and more saturated than the old sky blue.
val AgenticPrimary = Color(0xFF8B7CFF)
val AgenticPrimaryDeep = Color(0xFF5A4FD8)
// Secondary pole — cyan iris, used for secondary surfaces and highlights.
val AgenticSecondary = Color(0xFF5EEAD4)
// Confirmation / completion — mint. This is the "done" color everywhere.
val AgenticAccent = Color(0xFF4ADE80)
// Warning / escalation — amber, for escalated shards and blocked todos.
val AgenticWarning = Color(0xFFFBBF24)

val AgenticBg = Color(0xFF07080D)
val AgenticSurface = Color(0xFF0D0F18)
val AgenticSurfaceVariant = Color(0xFF151826)
val AgenticOutline = Color(0xFF2E3350)
val AgenticText = Color(0xFFE6E9F5)
val AgenticTextDim = Color(0xFF9AA0BF)

// Legacy aliases kept so existing call sites compile while they migrate.
val AgenticBlue = AgenticPrimary
val AgenticViolet = AgenticSecondary
val PocketOrange = AgenticPrimary
val PocketBlue = AgenticSecondary
val PocketGreen = AgenticAccent
val PocketBackground = AgenticBg
val PocketSurface = AgenticSurface
val PocketSurfaceVariant = AgenticSurfaceVariant
val PocketOutline = AgenticOutline

private val DarkColors = darkColorScheme(
    primary = AgenticPrimary,
    onPrimary = Color(0xFF14102B),
    primaryContainer = Color(0xFF241E4A),
    onPrimaryContainer = Color(0xFFE5E0FF),
    secondary = AgenticSecondary,
    onSecondary = Color(0xFF062A25),
    tertiary = AgenticAccent,
    onTertiary = Color(0xFF042715),
    background = AgenticBg,
    onBackground = AgenticText,
    surface = AgenticSurface,
    onSurface = AgenticText,
    surfaceVariant = AgenticSurfaceVariant,
    onSurfaceVariant = AgenticTextDim,
    outline = AgenticOutline,
    outlineVariant = Color(0xFF222842),
    error = Color(0xFFF87171),
    onError = Color(0xFF370B0B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5A4FD8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8E5FF),
    onPrimaryContainer = Color(0xFF1B1547),
    secondary = Color(0xFF0D9488),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF15803D),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F7FC),
    onBackground = Color(0xFF0E1120),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0E1120),
    surfaceVariant = Color(0xFFE9EBF5),
    onSurfaceVariant = Color(0xFF4C5170),
    outline = Color(0xFFC6CADF),
    outlineVariant = Color(0xFFE0E3F0),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
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

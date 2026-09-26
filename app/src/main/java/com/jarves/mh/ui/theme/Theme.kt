package com.jarves.mh.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorPalette = darkColorScheme(
    primary = Color(0xFF54CCFF),
    onPrimary = Color(0xFF0A1A2A),
    primaryContainer = Color(0xFF1A3A5A),
    onPrimaryContainer = Color(0xFFE5F5FF),
    secondary = Color(0xFF7C6CFF),
    onSecondary = Color(0xFF151035),
    secondaryContainer = Color(0xFF2A2555),
    onSecondaryContainer = Color(0xFFECE7FF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFEFF3FB),
    surface = Color(0xFF05070D),
    onSurface = Color(0xFFEFF3FB),
    surfaceVariant = Color(0xFF1A2535),
    onSurfaceVariant = Color(0xFF9FB2D4),
    error = Color(0xFFCF6679),
    onError = Color(0xFF000000),
    outline = Color(0xFF556575)
)

@Composable
fun AgenticTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val supportsDynamicTheming = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    
    val colorScheme = when {
        dynamicColor && supportsDynamicTheming -> dynamicDarkColorScheme(context)
        darkTheme -> DarkColorPalette
        else -> dynamicLightColorScheme(context)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

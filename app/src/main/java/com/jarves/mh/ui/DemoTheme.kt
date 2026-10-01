package com.jarves.mh.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object DemoColors {
    val bg = Color(0xFF000000)
    val bg1 = Color(0xFF05070D)
    val surface = Color(0x0FFFFFFF)
    val surface2 = Color(0x1AFFFFFF)
    val surfaceInset = Color(0x29FFFFFF)
    val border = Color(0x1FFFFFFF)
    val borderGlow = Color(0x42FFFFFF)
    val primary = Color(0xFF54CCFF)
    val primary2 = Color(0xFF7C6CFF)
    val text = Color(0xFFEFF3FB)
    val textMuted = Color(0xFF9FB2D4)
    val ok = Color(0xFF4CC2A8)
    val warn = Color(0xFFFFB454)
    val glassBlur = 18f
    val glassSaturate = 165f
    val radius = 20.dp
    val radiusSmall = 13.dp
    val radiusXs = 11.dp
    val shadow = Color(0x99000000)
    val ambientGlow = 0.14f
}

private val AmbientGlowBrush = Brush.radialGradient(
    colors = listOf(
        DemoColors.primary.copy(alpha = DemoColors.ambientGlow),
        Color.Transparent
    ),
    center = androidx.compose.ui.geometry.Offset(20f, 10f),
    radius = 460f
)

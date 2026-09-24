package com.jarves.mh.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The ambient canvas: near-black base with two large soft radial glows (cyan
 * top-left, violet bottom-right) and a subtle bottom vignette. Every glass
 * surface floats on top of this, which is what makes the translucency visible.
 */
@Composable
fun GlassBackground(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(color = Glass.Bg)

                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF54CCFF).copy(alpha = 0.13f), Color.Transparent),
                        center = Offset(size.width * 0.15f, size.height * -0.05f),
                        radius = size.maxDimension * 0.62f,
                    ),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF7C6CFF).copy(alpha = 0.11f), Color.Transparent),
                        center = Offset(size.width * 0.9f, size.height * 1.05f),
                        radius = size.maxDimension * 0.58f,
                    ),
                )
                // Vignette to deepen the bottom edge under the nav bar.
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f)),
                        startY = size.height * 0.72f,
                        endY = size.height,
                    ),
                )
            },
    )
}

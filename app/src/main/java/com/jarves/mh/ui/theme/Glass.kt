package com.jarves.mh.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Glassmorphism design tokens, mirroring the approved HTML demo exactly.
 * Surfaces are translucent white stacked over the near-black canvas, with a
 * soft inner highlight along the top edge and a coloured radial halo that
 * blooms behind each card.
 */
object Glass {
    val Bg = Color(0xFF05070D)
    val Surface = Color(0x0EFFFFFF)         // rgba(255,255,255,.055)
    val SurfaceStrong = Color(0x17FFFFFF)   // rgba(255,255,255,.09)
    val SurfaceInset = Color(0x2EFFFFFF)    // rgba(255,255,255,.18) top hairline
    val Border = Color(0x1AFFFFFF)          // rgba(255,255,255,.1)
    val BorderGlow = Color(0x38FFFFFF)      // rgba(255,255,255,.22)
    val Primary = Color(0xFF54CCFF)
    val Violet = Color(0xFF7C6CFF)
    val Text = Color(0xFFEEF3FB)
    val TextMuted = Color(0xFF9FB2D4)
    val Ok = Color(0xFF4CC2A8)
    val Warn = Color(0xFFFFB454)

    val Radius = 20.dp
    val RadiusCard = 16.dp
    val RadiusChip = 13.dp

    /** Two-stop radial halo that sits behind a card, tinted per-accent. */
    data class Halo(
        val g1: Color = Color(0x4D54CCFF),   // primary at ~30%
        val g2: Color = 0x387C6CFF,         // violet at ~22%
    )

    val BlueHalo = Halo()
    val VioletHalo = Halo(g1 = 0x4D7C6CFF, g2 = 0x3854CCFF)
    val TealHalo = Halo(g1 = 0x4D4CC2A8, g2 = 0x3854CCFF)
}

/**
 * The glass card from the approved demo: translucent surface, hairline border
 * that brightens on hover, an inset top highlight, a coloured radial halo that
 * blooms on interaction, and a lift + press scale modeled on the CSS
 * transitions (`transform .25s cubic-bezier(.22,1,.36,1)`).
 *
 * On API 31+ the card blurs what's behind it (backdrop-filter equivalent);
 * older API levels fall back to the translucent layering alone.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    halo: Glass.Halo = Glass.Halo(),
    radius: Dp = Glass.RadiusCard,
    blurred: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()

    val lift by animateFloatAsState(
        targetValue = when {
            pressed -> -2f
            hovered -> -5f
            else -> 0f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "glassLift",
    )
    val haloAlpha by animateFloatAsState(
        targetValue = if (hovered) 0.9f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "halo",
    )
    val borderColor by animateColorAsState(
        targetValue = if (hovered) Glass.BorderGlow else Glass.Border,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "glassBorder",
    )

    val shape = RoundedCornerShape(radius)
    val base = modifier
        .graphicsLayer { translationY = lift }
        .clip(shape)
        .then(
            if (blurred && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                Modifier.blur(20.dp)
            } else Modifier
        )
        .background(Glass.Surface)
        .border(BorderStroke(1.dp, borderColor), shape)
        .drawBehind {
            // Bloom: two radial glows anchored off-card, fading to transparent.
            if (haloAlpha > 0.01f) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(halo.g1.copy(alpha = halo.g1.alpha * haloAlpha), Color.Transparent),
                        center = Offset(size.width * 0.2f, size.height * 0.1f),
                        radius = size.maxDimension * 0.7f,
                    ),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(halo.g2.copy(alpha = halo.g2.alpha * haloAlpha), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.9f),
                        radius = size.maxDimension * 0.6f,
                    ),
                )
            }
            // Inset top highlight: a bright hairline along the top edge.
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Glass.SurfaceInset.copy(alpha = 0.85f), Color.Transparent),
                    startY = 0f,
                    endY = size.height * 0.22f,
                ),
            )
        }
        .shadow(
            elevation = if (hovered) 22.dp else 12.dp,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = if (hovered) 0.5f else 0.35f),
            spotColor = if (hovered) Glass.Primary.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.35f),
        )

    Box(
        modifier = if (onClick != null) {
            base.clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
        } else {
            base.hoverable(interactionSource)
        },
    ) {
        content()
    }
}

/**
 * Icon tile used inside stat cards: a 135deg gradient tinted by the card
 * accent, a faint inner highlight and a coloured glow (`.stat-ico`).
 */
@Composable
fun GlassIconTile(
    tint1: Color,
    tint2: Color,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(11.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(tint1, tint2),
                    start = Offset(0f, 0f),
                    end = Offset(size.value, size.value),
                ),
            )
            .border(BorderStroke(1.dp, Color(0x24FFFFFF)), RoundedCornerShape(11.dp))
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0x33FFFFFF), Color.Transparent),
                        endY = this.size.height * 0.5f,
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

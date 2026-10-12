package com.jarves.mh.ui.theme

import android.os.Build
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Glassmorphism design tokens, mirroring the approved HTML demo exactly.
 * Surfaces are translucent white stacked over the near-black canvas, with a
 * soft inner highlight along the top edge and a coloured radial halo that
 * blooms behind each card.
 */
object Glass {
    val Bg = AgenticBg                     // near-black obsidian canvas
    val Surface = Color(0x1CFFFFFF)         // rgba(255,255,255,.11)
    val SurfaceStrong = Color(0x24FFFFFF)   // rgba(255,255,255,.14)
    val SurfaceInset = Color(0x59FFFFFF)    // rgba(255,255,255,.35) top highlight
    val Border = Color(0x38FFFFFF)          // rgba(255,255,255,.22)
    val BorderGlow = Color(0x66A9B2FF)      // brighter iris-white when hovered
    val Primary = AgenticPrimary            // electric iris
    val Violet = AgenticSecondary           // cyan iris
    val Text = AgenticText                  // near-white with a cool bias
    val TextMuted = AgenticTextDim          // muted slate
    val TextBody = AgenticTextDim           // body-size slate
    val Ok = AgenticAccent                  // mint — "done" everywhere
    val Warn = AgenticWarning               // amber — escalation/blocked

    val Radius = 20.dp
    val RadiusCard = 16.dp
    val RadiusChip = 13.dp

    /** Two-stop radial halo that sits behind a card, tinted per-accent. */
    data class Halo(
        val g1: Color = Color(0x4D8B7CFF.toInt()),   // electric iris ~30%
        val g2: Color = (0x385EEAD4.toInt()),        // cyan iris ~22%
    )

    val BlueHalo = Halo()
    val VioletHalo = Halo(g1 = Color(0x4D8B7CFF.toInt()), g2 = Color(0x385EEAD4.toInt()))
    val TealHalo = Halo(g1 = Color(0x4D8B7CFF.toInt()), g2 = Color(0x384ADE80.toInt()))
    // Mod 4: the mint halo backs the Plan-Driven mode and its checklist, so the
    // to-do surface reads as the same green the UI uses for "done" everywhere.
    val MintHalo = Halo(g1 = Color(0x4D4ADE80.toInt()), g2 = Color(0x385EEAD4.toInt()))

    // Icon-tile gradient pairs on the Aurora Obsidian ramp. The primary iris
    // carries most of the UI; cyan and mint provide the per-card accent
    // variation while staying on-palette.
    val TileBlue = 0x3D8B7CFF to 0x1A8B7CFF
    val TileIce = 0x3D5EEAD4 to 0x1A5EEAD4
    val TileOk = 0x3D4ADE80 to 0x1A8B7CFF
}

/**
 * The glass card from the approved demo: translucent surface, hairline border
 * that brightens on hover, an inset top highlight, a coloured radial halo that
 * blooms on interaction, and a lift + press scale modeled on the CSS
 * transitions (`transform .25s cubic-bezier(.22,1,.36,1)`).
 *
 * On API 31+ the card CAN blur what's behind it (backdrop-filter equivalent)
 * when [blurred] is requested; by default it does not, because stacking a
 * screen full of translucent 20dp-blurred cards reads as a hazy screen on-device.
 * The translucent layering alone delivers the glass look.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    halo: Glass.Halo = Glass.Halo(),
    radius: Dp = Glass.RadiusCard,
    /**
     * Backdrop blur behind the card (backdrop-filter equivalent). Off by default:
     * a 20dp blur over the near-black canvas, applied to every translucent card,
     * read on-device as a whole-app haze. The approved glass look comes entirely
     * from the translucent surface, hairline border, inner highlight and halo, so
     * blur is optional and only where explicitly requested.
     */
    blurred: Boolean = false,
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

// --- cyberpunk accents -------------------------------------------------

/**
 * A brighter, neon-edged card for the home dashboard tiles. Same AMOLED black
 * base, but with a visible blue hairline tracing the border, a soft outer bloom,
 * and an inner scanline tint — the "little cyberpunk thing" on top of the glass.
 *
 * Uses the same solid near-black surface as the onboarding and API-key screens
 * (`colorScheme.surface`, `#0A0F18`) rather than the translucent `Glass.Surface`,
 * so dashboard tiles read as the same panels as those screens.
 */
@Composable
fun NeonGlassCard(
    modifier: Modifier = Modifier,
    accent: Color = Glass.Primary,
    radius: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val glow by animateFloatAsState(
        targetValue = if (hovered || pressed) 1f else 0.45f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "neonGlow",
    )
    val shape = RoundedCornerShape(radius)
    val base = modifier
        .graphicsLayer { translationY = if (pressed) -2f else 0f }
        .clip(shape)
        .background(MaterialTheme.colorScheme.surface)
        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)), shape)
        .drawBehind {
            // Outer neon bloom hugging the border.
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.28f * glow), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.5f),
                    radius = size.maxDimension * 0.75f,
                ),
            )
            // Scanline: one thin brighter band a third of the way down.
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, accent.copy(alpha = 0.06f), Color.Transparent),
                    startY = size.height * 0.28f,
                    endY = size.height * 0.38f,
                ),
            )
        }
        .shadow(
            elevation = if (hovered) 20.dp else 10.dp,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = 0.4f),
            spotColor = accent.copy(alpha = 0.16f),
        )
    Box(
        modifier = if (onClick != null) {
            base.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
        } else {
            base.hoverable(interactionSource)
        },
    ) {
        content()
    }
}

/**
 * The "blue text + white glow" half of the approved AMOLED spec.
 *
 * The reference template draws each blue label with layered white
 * `text-shadow`s (3px/7px/14px at .45/.25/.12 alpha) so the blue reads as
 * luminous against pure black. Compose has no text-shadow, so this re-creates
 * it by drawing blurred white copies of the text behind a crisp blue pass.
 *
 * Use only for short labels (headings, chips, names). It is a multi-pass
 * overdraw and is wasted on long body text, where [Glass.TextBody] alone is
 * legible. The glow passes are skipped below API 31, where `Modifier.blur`
 * is unavailable; the crisp blue text remains.
 */
@Composable
fun GlowText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Glass.Text,
    glow: Color = Color.White,
    style: TextStyle = TextStyle.Default,
    textAlign: TextAlign? = null,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Layered white glow: wide+faint, then medium, then tight.
            Text(
                text = text,
                color = glow.copy(alpha = 0.12f),
                style = style,
                textAlign = textAlign,
                modifier = Modifier.blur(7.dp),
            )
            Text(
                text = text,
                color = glow.copy(alpha = 0.22f),
                style = style,
                textAlign = textAlign,
                modifier = Modifier.blur(3.5.dp),
            )
            Text(
                text = text,
                color = glow.copy(alpha = 0.40f),
                style = style,
                textAlign = textAlign,
                modifier = Modifier.blur(1.5.dp),
            )
        }
        // Crisp blue core on top.
        Text(
            text = text,
            color = color,
            style = style,
            textAlign = textAlign,
        )
    }
}

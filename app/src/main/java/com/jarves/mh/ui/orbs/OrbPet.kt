package com.jarves.mh.ui.orbs

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Pet/orb glow tint per agent state, mirroring the approved HTML demo. */
object OrbTints {
    const val IDLE = 0xFFA6E3A1
    const val THINKING = 0xFFCBA6F7
    const val SEARCHING = 0xFF89B4FA
    const val CODING = 0xFF89B4FA
    const val CONNECTING = 0xFFFAB387
    const val COMPOSING = 0xFF94E2D5
    const val AWAITING = 0xFFA69EFF
    const val PEER = 0xFFA69EFF
    const val PRIMARY = 0xFF54CCFF

    fun forState(state: OrbState): Int = when (state) {
        OrbState.BREATHING -> IDLE
        OrbState.SOLVING -> THINKING
        OrbState.SEARCHING -> SEARCHING
        OrbState.WORKING -> CODING
        OrbState.CONNECTING -> CONNECTING
        OrbState.COMPOSING -> COMPOSING
        OrbState.LISTENING -> AWAITING
        OrbState.WEAVING -> THINKING
        OrbState.SHAPING -> PRIMARY
    }
}

/**
 * Draws a single AgenticOrbs creature on a transparent Canvas. The orb mode is
 * derived from [state] and animated forever; only one pet should be visible at
 * a time (the latest agent reply), per the approved demo.
 */
@Composable
fun OrbPet(
    state: OrbState = OrbState.BREATHING,
    modifier: Modifier = Modifier,
    tint: Int? = null,
    dark: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "orb")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 60_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orbTime",
    )

    val resolvedTint = tint ?: OrbTints.forState(state)
    val preset = OrbPresets.resolve(state, ORB_LOGICAL_SIZE)

    Canvas(modifier = modifier) {
        val sizePx = minOf(size.width, size.height)
        val frame = OrbFrames.frame(preset.mode, sizePx, t * preset.speed, preset.opts)
        drawFrame(frame, dark, resolvedTint)
    }
}

private const val ORB_LOGICAL_SIZE = 64

private fun DrawScope.drawFrame(frame: OrbFrame, dark: Boolean, tint: Int) {
    val native = drawContext.canvas.nativeCanvas
    val paint = android.graphics.Paint().apply { isAntiAlias = true }
    for (line in frame.lines) {
        paint.color = inkColor(line.white, line.a, dark, tint)
        paint.strokeWidth = line.w
        native.drawLine(line.x1, line.y1, line.x2, line.y2, paint)
    }
    for (dot in frame.dots) {
        paint.color = inkColor(dot.white, dot.a, dark, tint)
        paint.style = android.graphics.Paint.Style.FILL
        native.drawCircle(dot.x, dot.y, dot.r, paint)
    }
}

private fun inkColor(w: Float, alpha: Float, dark: Boolean, tint: Int): Int {
    val r: Int; val g: Int; val b: Int
    if (tint == 0) {
        val v = ((if (dark) 1 - w else w) * 255).toInt()
        r = v; g = v; b = v
    } else {
        val tr = ((tint shr 16) and 0xFF).toFloat()
        val tg = ((tint shr 8) and 0xFF).toFloat()
        val tb = (tint and 0xFF).toFloat()
        fun ramp(c: Float) = if (dark) c * (1 - w) else c + (255 - c) * w
        r = ramp(tr).toInt(); g = ramp(tg).toInt(); b = ramp(tb).toInt()
    }
    val ai = (alpha.coerceIn(0f, 1f) * 255).toInt()
    return (ai shl 24) or (r shl 16) or (g shl 8) or b
}

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The AgenticOrbs blob pet, ported from the approved `styles.css`.
 *
 * A pure-CSS creature in the web build: an egg-shaped body with an inset glow,
 * a glowing antenna, two expressive eyes and two feet. Its [PetState] sets a
 * `--glow`/`--fill` colour pair plus a small choreography of keyframes. This
 * port re-draws the same geometry on a Canvas and derives every animation from
 * a single clock so the motion stays in lock-step with the reference.
 *
 * Coordinate space is the web pet's 170 x 190 logical box; the drawing is
 * scaled to fit whatever size the caller gives it.
 */
@Composable
fun AgentPet(
    state: PetState = PetState.IDLE,
    modifier: Modifier = Modifier,
    /** Override the glow colour (ARGB); defaults to the mood's colour. */
    glowOverride: Int? = null,
) {
    val transition = rememberInfiniteTransition(label = "agentPet")
    // One slow clock; every keyframe below reads its own period from it.
    val clock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 60_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "petClock",
    )

    Canvas(modifier = modifier) {
        val mood = PET_MOODS.getValue(state)
        val glow = glowOverride ?: mood.glow
        drawPet(state, glow, mood.fill, clock * PET_CLOCK_SECONDS)
    }
}

/** The 8 moods of the creature, mirroring the CSS state classes. */
enum class PetState {
    IDLE, THINKING, SEARCHING, CODING, CONNECTING, WEAVING, COMPOSING, AWAITING;

    companion object {
        /** Map a harness [OrbState] onto the equivalent creature mood. */
        fun fromOrbState(orb: OrbState): PetState = when (orb) {
            OrbState.BREATHING -> IDLE
            OrbState.SOLVING -> THINKING
            OrbState.SEARCHING -> SEARCHING
            OrbState.WORKING -> CODING
            OrbState.CONNECTING -> CONNECTING
            OrbState.WEAVING -> WEAVING
            OrbState.COMPOSING -> COMPOSING
            OrbState.LISTENING -> AWAITING
            OrbState.SHAPING -> IDLE
        }
    }
}

private data class PetMood(val glow: Int, val fill: Int)

/** --glow / --fill pairs straight from styles.css (Catppuccin Mocha). */
private val PET_MOODS = mapOf(
    PetState.IDLE to PetMood(0xFFA6E3A1.toInt(), 0xFF1C2A22.toInt()),
    PetState.THINKING to PetMood(0xFFCBA6F7.toInt(), 0xFF241C33.toInt()),
    PetState.SEARCHING to PetMood(0xFF89B4FA.toInt(), 0xFF182338.toInt()),
    PetState.CODING to PetMood(0xFF89B4FA.toInt(), 0xFF182338.toInt()),
    PetState.CONNECTING to PetMood(0xFFFAB387.toInt(), 0xFF33261C.toInt()),
    PetState.WEAVING to PetMood(0xFFF5C2E7.toInt(), 0xFF331C2C.toInt()),
    PetState.COMPOSING to PetMood(0xFF94E2D5.toInt(), 0xFF15302C.toInt()),
    PetState.AWAITING to PetMood(0xFFA69EFF.toInt(), 0xFF221F38.toInt()),
)

// Logical geometry, in the web pet's 170 x 190 coordinate space.
private const val PET_W = 170f
private const val PET_H = 190f
private const val BODY_L = 10f
private const val BODY_T = 30f
private const val BODY_R = 160f
private const val BODY_B = 160f
private const val BODY_W = BODY_R - BODY_L
private const val BODY_H = BODY_B - BODY_T
private const val BODY_CX = (BODY_L + BODY_R) / 2f

// Egg outline: border-radius 50% 50% 46% 46% / 58% 58% 42% 42%, as
// (rx, ry) fractions per corner: TL, TR, BR, BL.
private val EGG_RX = floatArrayOf(0.50f, 0.50f, 0.46f, 0.46f)
private val EGG_RY = floatArrayOf(0.58f, 0.58f, 0.42f, 0.42f)
// morph keyframe (thinking): 46% 54% 52% 48% / 52% 48% 52% 48%
private val MORPH_RX = floatArrayOf(0.46f, 0.54f, 0.52f, 0.48f)
private val MORPH_RY = floatArrayOf(0.52f, 0.48f, 0.52f, 0.48f)
// morphSlow keyframe (weaving): 52% 48% 46% 54% / 54% 46% 54% 46%
private val MORPH_SLOW_RX = floatArrayOf(0.52f, 0.48f, 0.46f, 0.54f)
private val MORPH_SLOW_RY = floatArrayOf(0.54f, 0.46f, 0.54f, 0.46f)

// Eyes sit at 44% of the pet height, 24px apart, 30 x 38 (36 x 46 when awaiting).
private const val EYES_CY = PET_H * 0.44f
private const val EYE_GAP = 24f
private const val EYE_W = 30f
private const val EYE_H = 38f
private const val EYE_W_BIG = 36f
private const val EYE_H_BIG = 46f
private const val PUPIL_W = 14f
private const val PUPIL_H = 16f
private const val PUPIL_W_BIG = 18f
private const val PUPIL_H_BIG = 20f
private const val PUPIL_SEARCH_W = 18f
private const val PUPIL_SEARCH_H = 18f

// Feet hang below the body, 24 x 11, 30px apart.
private const val FOOT_W = 24f
private const val FOOT_H = 11f
private const val FOOT_GAP = 30f
private const val FOOT_BOTTOM = PET_H - 6f

// Antenna: 3 x 26 rod rising 24px above the body, 12px glowing tip.
private const val ANTENNA_TOP = BODY_T - 24f
private const val ANTENNA_H = 26f
private const val ANTENNA_W = 3f
private const val TIP_SIZE = 12f

private const val PET_CLOCK_SECONDS = 60f
private const val TAU = 2f * PI.toFloat()

private fun drawPet(state: PetState, glow: Int, fill: Int, t: Float) {
    val canvas = drawContext.canvas.nativeCanvas
    val w = size.width
    val h = size.height
    val s = minOf(w / PET_W, h / PET_H)
    val ox = (w - PET_W * s) / 2f
    val oy = (h - PET_H * s) / 2f

    canvas.save()
    canvas.translate(ox, oy)
    canvas.scale(s, s)

    // .pet { animation: float } — whole creature eases up and back.
    canvas.translate(0f, -10f * ramp(t, 3.5f))

    // .body transform: jiggle (coding) + breatheBody (awaiting).
    val bodyScale = when (state) {
        PetState.CODING -> {
            val phase = cycle(t, 0.5f)
            1f + 0.01f * sin(TAU * phase)
        }
        PetState.AWAITING -> 1.015f + 0.015f * sin(TAU * t / 2.2f)
        else -> 1f
    }
    val bodyRot = if (state == PetState.CODING) {
        -sin(TAU * cycle(t, 0.5f)) // -1deg at 25%, +1deg at 75%
    } else 0f

    canvas.save()
    canvas.translate(BODY_CX, (BODY_T + BODY_B) / 2f)
    canvas.rotate(bodyRot)
    canvas.scale(bodyScale, bodyScale)
    canvas.translate(-BODY_CX, -(BODY_T + BODY_B) / 2f)

    drawBody(canvas, state, glow, fill, t)
    drawAntenna(canvas, state, glow, t)
    drawEyes(canvas, state, glow, t)
    drawFeet(canvas, glow, fill)

    canvas.restore()
    canvas.restore()
}

private fun drawBody(canvas: android.graphics.Canvas, state: PetState, glow: Int, fill: Int, t: Float) {
    val paint = android.graphics.Paint().apply { isAntiAlias = true }
    val path = eggPath(state, t)

    // box-shadow: 0 0 28px var(--glow) — outer bloom.
    paint.color = 0
    paint.setShadowLayer(28f, 0f, 0f, glow)
    canvas.drawPath(path, paint)

    // box-shadow: 0 16px 34px rgba(0,0,0,.55) + the solid fill.
    paint.color = fill
    paint.setShadowLayer(34f, 0f, 16f, (0.55f * 255).toInt().shl(24))
    canvas.drawPath(path, paint)
    paint.setShadowLayer(0f, 0f, 0f, 0)

    // Inset 2px glow border.
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = 2f
    paint.color = glow
    canvas.drawPath(path, paint)
    paint.style = android.graphics.Paint.Style.FILL

    // radial-gradient(circle at 35% 30%, #ffffff18, transparent 60%) — sheen.
    canvas.save()
    canvas.clipPath(path)
    paint.color = 0x18FFFFFF
    paint.shader = android.graphics.RadialGradient(
        BODY_L + BODY_W * 0.35f,
        BODY_T + BODY_H * 0.30f,
        BODY_W * 0.60f,
        intArrayOf(0x18FFFFFF, 0x00000000),
        null,
        android.graphics.Shader.TileMode.CLAMP,
    )
    canvas.drawPath(path, paint)
    paint.shader = null
    canvas.restore()
}

private fun drawAntenna(canvas: android.graphics.Canvas, state: PetState, glow: Int, t: Float) {
    val paint = android.graphics.Paint().apply { isAntiAlias = true }
    val cx = BODY_CX

    // Rod: gradient from glow (bottom) to transparent (top).
    paint.shader = android.graphics.LinearGradient(
        cx, ANTENNA_TOP + ANTENNA_H,
        cx, ANTENNA_TOP,
        glow, 0,
        android.graphics.Shader.TileMode.CLAMP,
    )
    val rod = android.graphics.RectF(
        cx - ANTENNA_W / 2f, ANTENNA_TOP,
        cx + ANTENNA_W / 2f, ANTENNA_TOP + ANTENNA_H,
    )
    canvas.drawRoundRect(rod, 3f, 3f, paint)
    paint.shader = null

    // Tip dot. tipPulse (1.6s, 2.4s when idle) or tipBlink (0.6s when connecting).
    val tipCx = cx
    val tipCy = ANTENNA_TOP - TIP_SIZE / 2f
    var tipScale = 1f
    var tipAlpha = 1f
    when (state) {
        PetState.IDLE -> {
            val p = ramp(t, 2.4f)
            tipScale = 1f + 0.35f * p
            tipAlpha = 1f - 0.25f * p
        }
        PetState.CONNECTING -> {
            tipAlpha = 1f - 0.75f * ramp(t, 0.6f)
        }
        else -> {
            val p = ramp(t, 1.6f)
            tipScale = 1f + 0.35f * p
            tipAlpha = 1f - 0.25f * p
        }
    }
    val r = TIP_SIZE / 2f * tipScale
    paint.color = (tipAlpha.coerceIn(0f, 1f) * 255).toInt().shl(24) or (glow and 0x00FFFFFF)
    paint.setShadowLayer(14f, 0f, 0f, glow)
    canvas.drawCircle(tipCx, tipCy, r, paint)
    paint.setShadowLayer(0f, 0f, 0f, 0)
}

private fun drawEyes(canvas: android.graphics.Canvas, state: PetState, glow: Int, t: Float) {
    val big = state == PetState.AWAITING
    val baseW = if (big) EYE_W_BIG else EYE_W
    val baseH = if (big) EYE_H_BIG else EYE_H

    // .eyes container offset: wander / scan / scanFast / drift / waveRipple / typeBob.
    val eyeDX: Float
    val eyeDY: Float
    when (state) {
        PetState.THINKING -> {
            eyeDX = track(t, 2.6f, 0f, 0f, .25f, -8f, .5f, 8f, .75f, 6f, 1f, 0f)
            eyeDY = track(t, 2.6f, 0f, 0f, .25f, 3f, .5f, -3f, .75f, 5f, 1f, 0f)
        }
        PetState.SEARCHING -> {
            eyeDX = track(t, 0.7f, 0f, -12f, .5f, 12f, 1f, -12f)
            eyeDY = 0f
        }
        PetState.CODING -> {
            eyeDX = track(t, 1f, 0f, 0f, .3f, -8f, .7f, 8f, 1f, 0f)
            eyeDY = 2f * cycle(t, 0.35f) // typeBob 0 -> 2px
        }
        PetState.WEAVING -> {
            eyeDX = 0f
            eyeDY = -4f * ramp(t, 3f) // drift
        }
        PetState.COMPOSING -> {
            eyeDX = 0f
            eyeDY = -6f * ramp(t, 1.8f) // waveRipple
        }
        else -> {
            eyeDX = 0f
            eyeDY = 0f
        }
    }

    // Per-eye vertical squeeze.
    val squishY: Float = when (state) {
        PetState.IDLE -> blinkScale(t) // blink every 4s
        PetState.THINKING -> 1f - 0.3f * (0.5f + 0.5f * sin(TAU * t / 2f)) // squint
        PetState.WEAVING -> 0.725f - 0.175f * sin(TAU * t / 0.9f) // focusSquint .9 <-> .55
        PetState.COMPOSING -> 0.925f - 0.075f * sin(TAU * t / 1.2f) // happySqueeze
        PetState.AWAITING -> 1.04f + 0.04f * sin(TAU * t / 1.8f) // bigBreathe
        else -> 1f
    }
    // .connecting pulseWidth: width breathes 30 <-> 22.
    val widthScale = if (state == PetState.CONNECTING) {
        (26f + 4f * cos(TAU * t / 1.4f)) / EYE_W
    } else 1f

    val spread = EYE_GAP / 2f + baseW / 2f
    for (side in -1..1 step 2) {
        val cx = BODY_CX + side * spread + eyeDX
        val cy = EYES_CY + eyeDY
        val ew = baseW * widthScale
        val eh = baseH * squishY
        drawEye(canvas, glow, cx, cy, ew, eh, state, t)
    }
}

private fun drawEye(
    canvas: android.graphics.Canvas,
    glow: Int,
    cx: Float,
    cy: Float,
    w: Float,
    h: Float,
    state: PetState,
    t: Float,
) {
    val paint = android.graphics.Paint().apply { isAntiAlias = true }
    val rect = android.graphics.RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)

    // box-shadow: 0 0 12px glow, 0 0 24px glow — two bloom passes.
    paint.color = 0
    paint.setShadowLayer(24f, 0f, 0f, glow)
    canvas.drawOval(rect, paint)
    paint.setShadowLayer(12f, 0f, 0f, glow)
    canvas.drawOval(rect, paint)

    // White sclera.
    paint.setShadowLayer(0f, 0f, 0f, 0)
    paint.color = 0xFFFFFFFF.toInt()
    canvas.drawOval(rect, paint)

    // Pupil, offset toward the lower lid.
    val big = state == PetState.AWAITING
    val pw = when {
        state == PetState.SEARCHING -> PUPIL_SEARCH_W
        big -> PUPIL_W_BIG
        else -> PUPIL_W
    }
    val ph = when {
        state == PetState.SEARCHING -> PUPIL_SEARCH_H
        big -> PUPIL_H_BIG
        else -> PUPIL_H
    }
    paint.color = 0xFF12121C.toInt()
    canvas.drawOval(
        android.graphics.RectF(
            cx - pw / 2f, cy - ph / 2f + h * 0.05f,
            cx + pw / 2f, cy + ph / 2f + h * 0.05f,
        ),
        paint,
    )

    // Sparkle.
    paint.color = 0xE6FFFFFF.toInt()
    canvas.drawCircle(
        cx - w * 0.22f, cy - h * 0.24f, 2.5f, paint,
    )
}

private fun drawFeet(canvas: android.graphics.Canvas, glow: Int, fill: Int) {
    val paint = android.graphics.Paint().apply { isAntiAlias = true }
    val total = FOOT_W * 2f + FOOT_GAP
    for (side in -1..1 step 2) {
        val cx = BODY_CX + side * (total / 2f - FOOT_W / 2f)
        val rect = android.graphics.RectF(
            cx - FOOT_W / 2f, FOOT_BOTTOM - FOOT_H,
            cx + FOOT_W / 2f, FOOT_BOTTOM,
        )
        paint.color = 0
        paint.setShadowLayer(10f, 0f, 0f, glow)
        canvas.drawRoundRect(rect, FOOT_W / 2f, FOOT_W / 2f, paint)
        paint.setShadowLayer(0f, 0f, 0f, 0)
        paint.color = fill
        canvas.drawRoundRect(rect, FOOT_W / 2f, FOOT_W / 2f, paint)
    }
}

/**
 * Build the egg outline, applying the morph keyframes (thinking / weaving)
 * by interpolating each corner radius.
 */
private fun eggPath(state: PetState, t: Float): android.graphics.Path {
    val rx: FloatArray
    val ry: FloatArray
    when (state) {
        PetState.THINKING -> {
            val m = ramp(t, 5f)
            rx = lerpArr(EGG_RX, MORPH_RX, m)
            ry = lerpArr(EGG_RY, MORPH_RY, m)
        }
        PetState.WEAVING -> {
            val m = ramp(t, 4f)
            rx = lerpArr(EGG_RX, MORPH_SLOW_RX, m)
            ry = lerpArr(EGG_RY, MORPH_SLOW_RY, m)
        }
        else -> {
            rx = EGG_RX
            ry = EGG_RY
        }
    }
    val radii = FloatArray(8)
    for (i in 0..3) {
        radii[i * 2] = rx[i] * BODY_W
        radii[i * 2 + 1] = ry[i] * BODY_H
    }
    return android.graphics.Path().apply {
        addRoundRect(android.graphics.RectF(BODY_L, BODY_T, BODY_R, BODY_B), radii, android.graphics.Path.Direction.CW)
    }
}

// --- animation helpers -------------------------------------------------

/** Cycle position in [0,1). */
private fun cycle(t: Float, period: Float): Float = (t % period / period + 1f) % 1f

/** Smooth 0 -> 1 -> 0 pulse once per [period] (ease-in-out, like the CSS transitions). */
private fun ramp(t: Float, period: Float): Float {
    val p = cycle(t, period)
    val tri = if (p < 0.5f) p * 2f else 1f - (p - 0.5f) * 2f
    return (1f - cos(PI.toFloat() * tri)) / 2f
}

/** Linear interpolation across (phase, value) keyframes. */
private fun track(t: Float, period: Float, vararg points: Float): Float {
    val n = points.size / 2
    val phase = cycle(t, period)
    var lo = 0
    var hi = n - 1
    for (i in 0 until n - 1) {
        if (phase >= points[i * 2] && phase <= points[(i + 1) * 2]) {
            lo = i
            hi = i + 1
            break
        }
    }
    val t0 = points[lo * 2]
    val t1 = points[hi * 2]
    val v0 = points[lo * 2 + 1]
    val v1 = points[hi * 2 + 1]
    val f = if (t1 > t0) (phase - t0) / (t1 - t0) else 0f
    return v0 + (v1 - v0) * f
}

/** blink: scaleY(1) except a quick dip to 0.1 at 95% of a 4s cycle. */
private fun blinkScale(t: Float): Float {
    val p = cycle(t, 4f)
    val d = abs(p - 0.95f) / 0.03f
    if (d >= 1f) return 1f
    return 1f - 0.9f * cos(d * PI.toFloat() / 2f)
}

private fun lerpArr(a: FloatArray, b: FloatArray, f: Float): FloatArray =
    FloatArray(a.size) { i -> a[i] + (b[i] - a[i]) * f }

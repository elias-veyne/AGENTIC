package com.jarves.mh.ui.orbs

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

internal data class OrbDot(
    val x: Float,
    val y: Float,
    val z: Float,
    val r: Float,
    val white: Float,
    val a: Float = 1f,
)

internal data class OrbLine(
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    val w: Float, val white: Float, val a: Float = 1f,
)

internal data class OrbFrame(val dots: List<OrbDot>, val lines: List<OrbLine>)

internal enum class OrbMode {
    ORBITS, GLOBE, RUBIK, WAVE, WEB, BRAID, RIBBON, RING, MORPH;

    companion object {
        fun fromState(state: OrbState): OrbMode = when (state) {
            OrbState.WORKING -> ORBITS
            OrbState.SEARCHING -> GLOBE
            OrbState.SOLVING -> RUBIK
            OrbState.LISTENING -> WAVE
            OrbState.CONNECTING -> WEB
            OrbState.WEAVING -> BRAID
            OrbState.COMPOSING -> RIBBON
            OrbState.BREATHING -> RING
            OrbState.SHAPING -> MORPH
        }
    }
}

enum class OrbState {
    WORKING, SEARCHING, SOLVING, LISTENING,
    CONNECTING, WEAVING, COMPOSING, BREATHING, SHAPING
}

internal fun radiusScale(size: Float, pow: Float): Float =
    Math.pow((size / 300f).toDouble(), pow.toDouble()).toFloat()

internal fun hashD(a: Float, b: Float): Float {
    val h = sin(a * 12.9898f + b * 78.233f) * 43758.5453f
    return h - floor(h)
}

internal fun vnoise(x: Float, y: Float): Float {
    val xi = floor(x); val yi = floor(y)
    var fx = x - xi; var fy = y - yi
    fx = fx * fx * (3 - 2 * fx)
    fy = fy * fy * (3 - 2 * fy)
    val a = hashD(xi, yi)
    val b = hashD(xi + 1, yi)
    val c = hashD(xi, yi + 1)
    val d = hashD(xi + 1, yi + 1)
    return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy
}

internal fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
internal fun frac(x: Float) = x - floor(x)

internal fun fibDir(i: Int, n: Int): FloatArray {
    val golden = (PI * (3 - sqrt(5.0))).toFloat()
    val y = 1f - 2f * (i + 0.5f) / n
    val rad = sqrt(1 - y * y)
    val a = i * golden
    return floatArrayOf(rad * cos(a), y, rad * sin(a))
}

private fun angleDelta(a: Float, b: Float) = atan2(sin(a - b), cos(a - b))

/** Project a 3D point through a yaw/tilt camera into screen + depth. */
internal class Proj(yaw: Float, tilt: Float, val cx: Float, val cy: Float, val scale: Float) {
    private val st = sin(tilt); private val ct = cos(tilt)
    private val sy = sin(yaw); private val cyw = cos(yaw)

    fun project(x: Float, y: Float, z: Float): FloatArray {
        val x1 = x * cyw + z * sy
        val z1 = -x * sy + z * cyw
        val y1 = y * ct - z1 * st
        val z2 = y * st + z1 * ct
        return floatArrayOf(cx + x1 * scale, cy - y1 * scale, z2)
    }
}

private fun inkColor(w: Float, alpha: Float, dark: Boolean, tint: Int?): Int {
    val r: Int; val g: Int; val b: Int
    if (tint == null) {
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

internal fun finalizeFrame(dots: MutableList<OrbDot>, lines: MutableList<OrbLine>, rMin: Float): OrbFrame {
    val visible = dots.filter { it.a >= 0.02f }.map { it.copy(r = maxOf(rMin, it.r)) }
        .sortedBy { it.z }
    return OrbFrame(visible, lines.filter { it.a >= 0.02f })
}

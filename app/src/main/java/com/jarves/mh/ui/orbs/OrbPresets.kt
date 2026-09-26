package com.jarves.mh.ui.orbs

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class OrbOpts(
    var orbitN: Int = 12, var ghostN: Int = 40, var ghostR: Float = 0.9f, var ghostA: Float = 0.5f,
    var particles: Int = 3, var partR: Float = 1.2f, var partRDepth: Float = 1.6f,
    var latRings: Int = 17, var lonDensity: Int = 44, var moveCount: Int = 14,
    var rings: Int = 15, var lanes: Int = 5, var segs: Int = 88,
    var nodeN: Int = 30, var signals: Int = 5, var nodeR: Float = 1.4f, var nodeRDepth: Float = 1.8f,
    var strandN: Int = 52, var turns: Int = 3,
    var rBase: Float = 0.6f, var rDepth: Float = 1.7f, var rActive: Float = 0.3f,
    var rDot: Float = 0.021f, var iconD: Float = 1f,
    var rBoost: Float = 1f, var inkFar: Float = 0.62f, var inkSpan: Float = 0.54f,
    var thr: Float = 0.72f, var lineW: Float = 0.8f,
    var rsPow: Float = 0.6f, var rMin: Float = 0.3f,
    var scanMul: Float = 1f, var dimBase: Float = 0f,
    var spin: Float = 0f, var bandMul: Float = 1f, var wobMul: Float = 1f,
    var spread: Float = 1f, var faceOn: Float = 0f,
)

private val COUNT_PAIRS = listOf("latRings" to "lonDensity", "rings" to "lonDensity", "lanes" to "segs")
private val COUNT_KEYS = listOf("orbitN", "ghostN", "nodeN", "strandN", "signals")

private fun scaleCounts(o: OrbOpts, scale: Float) {
    val rt = sqrt(scale)
    val done = mutableSetOf<String>()
    for ((a, b) in COUNT_PAIRS) {
        val va = o.getInt(a); val vb = o.getInt(b)
        if (va != null && vb != null && a !in done && b !in done) {
            val sa = sqrt(scale); val sb = rt
            o.setInt(a, maxOf(3, (va * sa).toInt())); o.setInt(b, maxOf(3, (vb * sb).toInt()))
            done += a; done += b
        }
    }
    for (k in COUNT_KEYS) o.getInt(k)?.let { o.setInt(k, maxOf(1, (it * rt).toInt())) }
    o.getInt("iconD")?.let { o.setInt("iconD", maxOf(1, (it * rt).toInt())) }
}

private val RADIUS_KEYS = listOf(
    "rBase", "rDepth", "rActive", "rDot", "ghostR", "partR", "partRDepth",
    "nodeR", "nodeRDepth"
)

private fun scaleRadii(o: OrbOpts, scale: Float) {
    for (k in RADIUS_KEYS) o.getFloat(k)?.let { o.setFloat(k, it * scale) }
}

private fun OrbOpts.getInt(key: String): Int? = when (key) {
    "orbitN" -> orbitN; "ghostN" -> ghostN; "nodeN" -> nodeN; "strandN" -> strandN
    "signals" -> signals; "latRings" -> latRings; "lonDensity" -> lonDensity
    "moveCount" -> moveCount; "rings" -> rings; "lanes" -> lanes; "segs" -> segs
    "particles" -> particles; "iconD" -> iconD.toInt(); else -> null
}

private fun OrbOpts.setInt(key: String, v: Int) = when (key) {
    "orbitN" -> orbitN = v; "ghostN" -> ghostN = v; "nodeN" -> nodeN = v; "strandN" -> strandN = v
    "signals" -> signals = v; "latRings" -> latRings = v; "lonDensity" -> lonDensity = v
    "moveCount" -> moveCount = v; "rings" -> rings = v; "lanes" -> lanes = v; "segs" -> segs = v
    "particles" -> particles = v; else -> {}
}

private fun OrbOpts.getFloat(key: String): Float? = when (key) {
    "rBase" -> rBase; "rDepth" -> rDepth; "rActive" -> rActive; "rDot" -> rDot
    "ghostR" -> ghostR; "partR" -> partR; "partRDepth" -> partRDepth
    "nodeR" -> nodeR; "nodeRDepth" -> nodeRDepth; else -> null
}

private fun OrbOpts.setFloat(key: String, v: Float) = when (key) {
    "rBase" -> rBase = v; "rDepth" -> rDepth = v; "rActive" -> rActive = v; "rDot" -> rDot = v
    "ghostR" -> ghostR = v; "partR" -> partR = v; "partRDepth" -> partRDepth = v
    "nodeR" -> nodeR = v; "nodeRDepth" -> nodeRDepth = v; else -> {}
}

internal data class ResolvedPreset(val mode: OrbMode, val speed: Float, val opts: OrbOpts)

internal object OrbPresets {
    fun resolve(state: OrbState, size: Int): ResolvedPreset {
        val mode = OrbMode.fromState(state)
        val key = "${state.name}-$size"
        return cache.getOrPut(key) {
            val preset = presetFor(mode, size)
            val opts = baseProfile(mode)
            if (preset.count != 1f) scaleCounts(opts, preset.count)
            if (preset.size != 1f) scaleRadii(opts, preset.size)
            preset.extra?.let { opts.applyExtra(it) }
            ResolvedPreset(mode, preset.speed, opts)
        }
    }

    private val cache = mutableMapOf<String, ResolvedPreset>()

    private data class SizePreset(val speed: Float, val count: Float, val size: Float, val extra: Map<String, Float>? = null)

    private fun presetFor(mode: OrbMode, size: Int): SizePreset {
        val table = PRESETS[mode] ?: error("no presets for $mode")
        val s = when {
            size >= 56 -> 64
            size >= 28 -> 32
            else -> 20
        }
        return table[s] ?: table.values.first()
    }

    private fun OrbOpts.applyExtra(e: Map<String, Float>) {
        e["scanMul"]?.let { scanMul = it }; e["dimBase"]?.let { dimBase = it }
        e["spin"]?.let { spin = it }; e["bandMul"]?.let { bandMul = it }
        e["wobMul"]?.let { wobMul = it }; e["spread"]?.let { spread = it }
        e["faceOn"]?.let { faceOn = it }
    }

    private fun baseProfile(mode: OrbMode): OrbOpts = when (mode) {
        OrbMode.GLOBE -> OrbOpts(latRings = 17, lonDensity = 44, rBase = 0.6f, rDepth = 1.7f, rBoost = 1f, inkFar = 0.62f, inkSpan = 0.54f)
        OrbMode.ORBITS -> OrbOpts(orbitN = 12, ghostN = 40, ghostR = 0.9f, ghostA = 0.5f, particles = 3, partR = 1.2f, partRDepth = 1.6f)
        OrbMode.RUBIK -> OrbOpts(latRings = 15, lonDensity = 40, moveCount = 14, rBase = 0.6f, rDepth = 1.7f, rActive = 0.3f, inkFar = 0.62f, inkSpan = 0.54f)
        OrbMode.WAVE -> OrbOpts(rings = 15, lonDensity = 40, rBase = 0.6f, rDepth = 1.7f)
        OrbMode.WEB -> OrbOpts(nodeN = 30, thr = 0.72f, signals = 5, nodeR = 1.4f, nodeRDepth = 1.8f, lineW = 0.8f)
        OrbMode.BRAID -> OrbOpts(strandN = 52, turns = 3, ghostN = 150, rBase = 1.2f, rDepth = 1.8f)
        OrbMode.RIBBON -> OrbOpts(lanes = 5, segs = 88, ghostN = 150, rBase = 1.1f, rDepth = 1.7f)
        OrbMode.RING -> OrbOpts(lanes = 5, segs = 88, ghostN = 0, faceOn = 1f, rBase = 1.1f, rDepth = 1.7f)
        OrbMode.MORPH -> OrbOpts(rDot = 0.021f, iconD = 1f, rMin = 0.25f)
    }

    private val PRESETS: Map<OrbMode, Map<Int, SizePreset>> = mapOf(
        OrbMode.ORBITS to mapOf(
            64 to SizePreset(1.885f, 1f, 1f),
            32 to SizePreset(2.9072f, 0.4251f, 1.6849f),
            20 to SizePreset(3.9f, 0.238f, 2.4f),
        ),
        OrbMode.GLOBE to mapOf(
            64 to SizePreset(2.015f, 0.42f, 1.15f, mapOf("scanMul" to 4.08f, "dimBase" to 0.45f)),
            32 to SizePreset(2.3803f, 0.1839f, 1.4769f, mapOf("scanMul" to 4.2301f, "dimBase" to 0.45f)),
            20 to SizePreset(2.665f, 0.105f, 1.75f, mapOf("scanMul" to 4.335f, "dimBase" to 0.45f)),
        ),
        OrbMode.RUBIK to mapOf(
            64 to SizePreset(1.82f, 0.35f, 1.05f),
            32 to SizePreset(1.8964f, 0.1537f, 1.4951f),
            20 to SizePreset(1.95f, 0.088f, 1.9f),
        ),
        OrbMode.WAVE to mapOf(
            64 to SizePreset(4.388f, 0.341f, 1f),
            32 to SizePreset(4.1512f, 0.169f, 1.3232f),
            20 to SizePreset(3.998f, 0.105f, 1.6f),
        ),
        OrbMode.WEB to mapOf(
            64 to SizePreset(3.315f, 1.35f, 0.95f),
            32 to SizePreset(5.0104f, 0.4942f, 1.2571f),
            20 to SizePreset(6.63f, 0.25f, 1.52f),
        ),
        OrbMode.BRAID to mapOf(
            64 to SizePreset(1.625f, 0.5f, 1f),
            32 to SizePreset(2.2234f, 0.2056f, 1.2011f),
            20 to SizePreset(2.75f, 0.1125f, 1.36f),
        ),
        OrbMode.RIBBON to mapOf(
            64 to SizePreset(2.34f, 0.25f, 0.85f, mapOf("spin" to 0f, "bandMul" to 3.9f, "wobMul" to 1f)),
            32 to SizePreset(2.7776f, 0.0969f, 0.9766f, mapOf("spin" to 0f, "bandMul" to 4.49f, "wobMul" to 1f)),
            20 to SizePreset(3.12f, 0.051f, 1.073f, mapOf("spin" to 0f, "bandMul" to 4.94f, "wobMul" to 1f)),
        ),
        OrbMode.RING to mapOf(
            64 to SizePreset(3.24f, 0.25f, 0.956f, mapOf("spin" to 0f, "bandMul" to 3.627f, "wobMul" to 0.368f)),
            32 to SizePreset(3.5517f, 0.0678f, 1.31f, mapOf("spin" to 0f, "bandMul" to 3.8265f, "wobMul" to 0.4751f)),
            20 to SizePreset(3.78f, 0.028f, 1.622f, mapOf("spin" to 0f, "bandMul" to 3.968f, "wobMul" to 0.565f)),
        ),
        OrbMode.MORPH to mapOf(
            64 to SizePreset(2.405f, 0.702f, 0.395f, mapOf("spread" to 1.45f)),
            32 to SizePreset(2.2057f, 0.5937f, 0.6916f, mapOf("spread" to 1.45f)),
            20 to SizePreset(2.08f, 0.53f, 1.011f, mapOf("spread" to 1.45f)),
        ),
    )
}

internal object OrbFrames {
    fun frame(mode: OrbMode, size: Float, t: Float, o: OrbOpts): OrbFrame = when (mode) {
        OrbMode.ORBITS -> frameOrbits(size, t, o)
        OrbMode.GLOBE -> frameGlobe(size, t, o)
        OrbMode.RUBIK -> frameRubik(size, t, o)
        OrbMode.WAVE -> frameWave(size, t, o)
        OrbMode.WEB -> frameWeb(size, t, o)
        OrbMode.BRAID -> frameBraid(size, t, o)
        OrbMode.RIBBON, OrbMode.RING -> frameRibbon(size, t, o)
        OrbMode.MORPH -> frameMorph(size, t, o)
    }

    private fun frameOrbits(size: Float, t: Float, o: OrbOpts): OrbFrame {
        val cx = size / 2f; val cy = size / 2f
        val R = size / 2f * 0.82f
        val pt = Proj(t * 0.12f, 0.3f, cx, cy, 1f)
        val rs = radiusScale(size, o.rsPow)
        val dots = mutableListOf<OrbDot>()
        val orbitN = o.orbitN; val ghostN = o.ghostN; val particles = o.particles
        for (orb in 0 until orbitN) {
            val h1 = hashD(orb.toFloat(), 1.7f)
            val h2 = hashD(orb.toFloat(), 5.2f)
            val h3 = hashD(orb.toFloat(), 8.9f)
            val ro = R * (0.45f + 0.52f * h1)
            val th = h1 * 2f * PI.toFloat()
            val phi = acos(2 * h2 - 1)
            val nx = sin(phi) * cos(th); val ny = cos(phi); val nz = sin(phi) * sin(th)
            var ux = -ny; var uy = nx; val uz = 0f
            val ul = maxOf(1e-6f, sqrt(ux * ux + uy * uy))
            ux /= ul; uy /= ul
            val vx = ny * uz - nz * uy; val vy = nz * ux - nx * uz; val vz = nx * uy - ny * ux
            val speed = (0.25f + 0.55f * h3) * if (h3 > 0.5f) 1f else -1f
            for (k in 0 until ghostN) {
                val a = k.toFloat() / ghostN * 2f * PI.toFloat()
                val ca = cos(a); val sa = sin(a)
                val p = pt((ux * ca + vx * sa) * ro, (uy * ca + vy * sa) * ro, (uz * ca + vz * sa) * ro)
                val depth = (p[2] / ro + 1) / 2
                dots.add(OrbDot(p[0], p[1], p[2], o.ghostR * rs, 0.72f, o.ghostA * (0.4f + 0.6f * depth)))
            }
            for (m in 0 until particles) {
                val a = t * speed + m.toFloat() / particles * 2f * PI.toFloat() + h2 * 6
                val ca = cos(a); val sa = sin(a)
                val p = pt((ux * ca + vx * sa) * ro, (uy * ca + vy * sa) * ro, (uz * ca + vz * sa) * ro)
                val depth = (p[2] / ro + 1) / 2
                dots.add(OrbDot(p[0], p[1], p[2], (o.partR + o.partRDepth * depth) * rs, 0.3f - 0.22f * depth))
            }
        }
        return finalizeFrame(dots, mutableListOf(), o.rMin)
    }

    private fun frameGlobe(size: Float, t: Float, o: OrbOpts): OrbFrame {
        val cx = size / 2f; val cy = size / 2f
        val R = size / 2f * 0.78f
        val pt = Proj(t * 0.1f, 0.35f, cx, cy, 1f)
        val rs = radiusScale(size, o.rsPow)
        val dots = mutableListOf<OrbDot>()
        val lines = mutableListOf<OrbLine>()
        val latN = o.latRings; val lonN = o.lonDensity
        for (ring in 0 until latN) {
            val lat = -1f + 2f * (ring + 0.5f) / latN
            val rr = R * sqrt(1 - lat * lat) * (o.rBase + o.rDepth * (1 - abs(lat)))
            val y0 = R * lat * 1.1f
            for (j in 0 until lonN) {
                val a = j.toFloat() / lonN * 2f * PI.toFloat() + t * 0.05f
                val p = pt(cos(a) * rr, y0, sin(a) * rr)
                val depth = (p[2] / R + 1) / 2
                val ink = o.inkFar + o.inkSpan * depth
                dots.add(OrbDot(p[0], p[1], p[2], 1.1f * rs, ink, 0.55f + 0.45f * depth))
            }
        }
        return finalizeFrame(dots, lines, o.rMin)
    }

    private fun frameRubik(size: Float, t: Float, o: OrbOpts): OrbFrame {
        val cx = size / 2f; val cy = size / 2f
        val R = size / 2f * 0.78f
        val pt = Proj(t * 0.12f, 0.35f, cx, cy, 1f)
        val rs = radiusScale(size, o.rsPow)
        val dots = mutableListOf<OrbDot>()
        val latN = o.latRings; val lonN = o.lonDensity
        for (ring in 0 until latN) {
            val lat = -1f + 2f * (ring + 0.5f) / latN
            val rr = R * sqrt(1 - lat * lat) * (o.rBase + o.rDepth * (1 - abs(lat)))
            val y0 = R * lat * 1.1f
            for (j in 0 until lonN) {
                val a = j.toFloat() / lonN * 2f * PI.toFloat()
                val wob = sin(t * 1.5f + ring + j * 0.3f) * o.rActive
                val p = pt(cos(a) * rr, y0 + wob * R * 0.1f, sin(a) * rr)
                val depth = (p[2] / R + 1) / 2
                val active = abs(wob) > o.rActive * 0.6f
                val ink = if (active) 0.95f else o.inkFar + o.inkSpan * depth
                dots.add(OrbDot(p[0], p[1], p[2], (if (active) 1.8f else 1.1f) * rs, ink, 0.55f + 0.45f * depth))
            }
        }
        return finalizeFrame(dots, mutableListOf(), o.rMin)
    }

    private fun frameWave(size: Float, t: Float, o: OrbOpts): OrbFrame {
        val cx = size / 2f; val cy = size / 2f
        val R = size / 2f * 0.78f
        val pt = Proj(t * 0.15f, 0.5f, cx, cy, 1f)
        val rs = radiusScale(size, o.rsPow)
        val dots = mutableListOf<OrbDot>()
        for (ring in 0 until o.rings) {
            val f = ring.toFloat() / (o.rings - 1)
            val rr = R * (o.rBase + o.rDepth * f)
            val yWave = sin(t * 2f + f * 6f) * R * 0.18f
            for (j in 0 until o.lonDensity) {
                val a = j.toFloat() / o.lonDensity * 2f * PI.toFloat()
                val p = pt(cos(a) * rr, yWave + sin(a) * rr * 0.15f, sin(a) * rr)
                val depth = (p[2] / R + 1) / 2
                dots.add(OrbDot(p[0], p[1], p[2], (1.2f - f * 0.5f) * rs, o.inkFar + o.inkSpan * depth, 0.6f))
            }
        }
        return finalizeFrame(dots, mutableListOf(), o.rMin)
    }

    private fun frameWeb(size: Float, t: Float, o: OrbOpts): OrbFrame {
        val cx = size / 2f; val cy = size / 2f
        val R = size / 2f * 0.72f
        val pt = Proj(t * 0.08f, 0.3f, cx, cy, 1f)
        val rs = radiusScale(size, o.rsPow)
        val nodes = mutableListOf<Array<Float>>()
        for (i in 0 until o.nodeN) {
            val d = fibDir(i, o.nodeN)
            val rr = R * (0.35f + 0.65f * hashD(i.toFloat(), 3.3f))
            val p = pt(d[0] * rr, d[1] * rr, d[2] * rr)
            nodes.add(arrayOf(p[0], p[1], p[2]))
        }
        val dots = mutableListOf<OrbDot>()
        val lines = mutableListOf<OrbLine>()
        for (i in nodes.indices) {
            val ni = nodes[i]
            dots.add(OrbDot(ni[0], ni[1], ni[2], (o.nodeR + o.nodeRDepth * ((ni[2] / R + 1) / 2)) * rs, 0.85f))
            for (j in i + 1 until nodes.size) {
                val nj = nodes[j]
                val dx = ni[0] - nj[0]; val dy = ni[1] - nj[1]
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < R * o.thr) {
                    val a = (1 - dist / (R * o.thr)) * 0.5f
                    lines.add(OrbLine(ni[0], ni[1], nj[0], nj[1], o.lineW * rs * 1.5f, 0.7f, a))
                }
            }
        }
        for (s in 0 until o.signals) {
            val f = (t * 0.5f + s.toFloat() / o.signals) % 1f
            val i = (f * nodes.size).toInt() % nodes.size
            val j = (i + 1) % nodes.size
            val ni = nodes[i]; val nj = nodes[j]
            dots.add(OrbDot(lerp(ni[0], nj[0], frac(f * nodes.size)), lerp(ni[1], nj[1], frac(f * nodes.size)), 0f, o.nodeR * 1.6f * rs, 1f))
        }
        return finalizeFrame(dots, lines, o.rMin)
    }

    private fun frameBraid(size: Float, t: Float, o: OrbOpts): OrbFrame {
        val cx = size / 2f; val cy = size / 2f
        val R = size / 2f * 0.76f
        val pt = Proj(t * 0.4f, 0.3f, cx, cy, 1f)
        val rs = radiusScale(size, o.rsPow)
        val dots = mutableListOf<OrbDot>()
        // Ghost dots (spherical distribution)
        val ghostN = o.ghostN
        for (i in 0 until ghostN) {
            val d = fibDir(i, ghostN)
            val z = d[2] * R
            val p = pt(d[0] * R, d[1] * R, z)
            val depth = (p[2] / R + 1) / 2
            dots.add(OrbDot(
                p[0], p[1], p[2],
                0.8f * rs,
                0.78f,
                0.1f + 0.22f * depth
            ))
        }
        // Main strands (demo's surf-based weaving)
        val strandN = o.strandN
        val turns = o.turns
        for (s in 0 until 3) {
            val phase = s / 3f * 2f * PI.toFloat()
            for (i in 0 until strandN) {
                val u = (frac(i / strandN.toFloat() + t * 0.045f) * 2f - 1f) * 0.96f
                val surf = sqrt(maxOf(0f, 1f - u * u))
                val endFade = minOf(1f, (1f - abs(u)) / 0.1f)
                val a = u * PI.toFloat() * turns + phase
                val weave = 1f + 0.075f * sin(u * PI.toFloat() * turns * 2f + phase * 2f + t * 0.8f)
                val rr = surf * R * weave
                val p = pt(cos(a) * rr, u * R * weave, sin(a) * rr)
                val depth = (p[2] / R + 1) / 2
                dots.add(OrbDot(
                    p[0], p[1], p[2],
                    ((o.rBase + o.rDepth * depth) * rs),
                    0.55f - 0.45f * depth,
                    endFade * (0.45f + 0.55f * depth)
                ))
            }
        }
        return finalizeFrame(dots, mutableListOf(), o.rMin)
    }

    private fun frameRibbon(size: Float, t: Float, o: OrbOpts): OrbFrame {
        val cx = size / 2f; val cy = size / 2f
        val R = size / 2f * 0.7f
        val tilt = if (o.faceOn > 0.5f) 0.0001f else 0.45f
        val pt = Proj(t * 0.1f + o.spin, tilt, cx, cy, 1f)
        val rs = radiusScale(size, o.rsPow)
        val dots = mutableListOf<OrbDot>()
        val lanes = o.lanes; val segs = o.segs
        for (lane in 0 until lanes) {
            val lf = lane.toFloat() / lanes
            for (j in 0 until segs) {
                val f = j.toFloat() / segs
                val a = f * 2f * PI.toFloat()
                val band = sin(a * o.bandMul + t * 1.2f + lf * PI.toFloat()) * 0.5f + 0.5f
                val wob = cos(a * 2f + t * 0.6f) * o.wobMul
                val rr = R * (o.rBase + o.rDepth * band * 0.35f) * (1 + wob * 0.18f)
                val yOff = (lf - 0.5f) * R * 0.55f
                val x = cos(a) * rr
                val y = if (o.faceOn > 0.5f) yOff + sin(a) * rr * 0.12f else yOff + sin(a) * rr * 0.45f
                val z = if (o.faceOn > 0.5f) sin(a) * rr else wob * R * 0.2f
                val p = pt(x, y, z)
                val depth = (p[2] / R + 1) / 2
                dots.add(OrbDot(p[0], p[1], p[2], (1.3f - lf * 0.3f) * rs, o.inkFar + o.inkSpan * depth, 0.7f))
            }
        }
        return finalizeFrame(dots, mutableListOf(), o.rMin)
    }

    private fun frameMorph(size: Float, t: Float, o: OrbOpts): OrbFrame {
        val cx = size / 2f; val cy = size / 2f
        val R = size / 2f * 0.72f
        val pt = Proj(t * 0.05f, 0.35f, cx, cy, 1f)
        val dots = mutableListOf<OrbDot>()
        val n = 220
        for (i in 0 until n) {
            val d = fibDir(i, n)
            val phase = (sin(t * 0.7f + i * 0.11f) * 0.5f + 0.5f)
            val rr = R * (0.25f + 0.75f * phase) * o.spread
            val wob = vnoise(d[0] * 3f + t * 0.4f, d[1] * 3f + t * 0.4f)
            val p = pt(d[0] * rr + wob * R * 0.1f, d[1] * rr, d[2] * rr)
            dots.add(OrbDot(p[0], p[1], p[2], size * o.rDot * (0.6f + 0.4f * phase), 0.8f, 0.4f + 0.6f * phase))
        }
        return finalizeFrame(dots, mutableListOf(), o.rMin)
    }
}

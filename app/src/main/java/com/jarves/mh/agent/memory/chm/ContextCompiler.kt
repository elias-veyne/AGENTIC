package com.jarves.mh.agent.memory.chm

/**
 * CHM — Context Compiler.
 *
 * Assembles the model's context window from the memory graph instead of the
 * raw transcript. The Mission Brief is pinned at both ends; the body is
 * trust-weighted, so high-trust Hebbian-strengthened memories lead and
 * retrieved low-trust rumination is explicitly marked as such.
 *
 * The budget goal: after ~20 turns the average prompt should shrink 30–50%,
 * because low-trust rumination is pruned and high-trust memories are
 * compressed into frames rather than replayed verbatim.
 */
class ContextCompiler(
    private val store: BiTemporalStore,
    private val frames: EpisodicFrameRecorder,
    private val graph: HebbianGraph,
) {

    data class Compiled(
        /** The CHM block to splice into the outgoing prompt. Empty when CHM has nothing to say. */
        val block: String,
        /** Nodes actually injected — the Hebbian graph strengthens exactly these. */
        val injectedNodes: List<com.jarves.mh.agent.memory.model.MemoryNode>,
        /** True when the watchdog fired and the block is a forced re-anchor. */
        val reAnchored: Boolean,
        val driftScore: Float,
    )

    /**
     * Hybrid retrieval: BM25 over FTS5, expanded by Hebbian graph traversal,
     * filtered bi-temporally and ranked by trust.
     */
    fun compile(
        taskId: String,
        query: String,
        budget: Budget = Budget(),
        counters: DriftWatchdog.Counters = DriftWatchdog.Counters(),
        now: Long = System.currentTimeMillis(),
    ): Compiled {
        val current = store.current(taskId)
        if (current.isEmpty()) return Compiled("", emptyList(), false, 0f)

        // 1. Seed with BM25 retrieval.
        val seeds = store.search(taskId, query, budget.seedLimit).toMutableList()
        // Always carry the mission + active step: they anchor every turn.
        val objective = current.firstOrNull { it.pinned }
        current.firstOrNull { it.kind == com.jarves.mh.agent.memory.model.MemoryKind.PLAN_STEP }
            ?.let { step -> if (step !in seeds) seeds.add(step) }
        if (objective != null && objective !in seeds) seeds.add(0, objective)

        // 2. Expand each seed along its strongest Hebbian edges — the learned
        // associative path. This is the pattern-completion step.
        val byId = current.associateBy { it.id }
        val expanded = LinkedHashSet<com.jarves.mh.agent.memory.model.MemoryNode>()
        seeds.forEach { seed ->
            expanded.add(seed)
            graph.neighbours(seed, budget.expansionPerSeed).forEach { edge ->
                val otherId = if (edge.sourceId == seed.id) edge.targetId else edge.sourceId
                byId[otherId]?.let { expanded.add(it) }
            }
        }

        // 3. Trust-weighted ranking. Bi-temporal filter keeps only memories
        // valid at this task's start, so superseded beliefs don't leak in.
        val ranked = expanded
            .filter { it.invalidAt == null }
            .sortedWith(compareByDescending<com.jarves.mh.agent.memory.model.MemoryNode> { it.trust }
                .thenByDescending { it.salience })
            .take(budget.maxMemories)

        val highTrust = ranked.filter { it.trust >= TRUST_INJECT_FLOOR }
        val lowTrust = ranked.filter { it.trust < TRUST_INJECT_FLOOR }

        // 4. Drift watchdog. Fires on the composite signal, not on instinct.
        val ctx = DriftWatchdog.Context(
            taskId = taskId,
            objective = objective,
            activeStep = ranked.firstOrNull { it.kind == com.jarves.mh.agent.memory.model.MemoryKind.PLAN_STEP },
            focusPath = ranked,
            counters = counters,
            highTrust = highTrust,
            lowTrust = lowTrust,
        )
        val score = DriftWatchdog(frames, graph).evaluate(ctx)
        if (score.triggered) {
            val anchor = DriftWatchdog(frames, graph).reAnchor(score, ctx)
            counters.turnsSincePlanAdvance = 0
            counters.tokensSinceRefocus = 0
            counters.lastRefocusAt = now
            return Compiled(anchor, ranked, reAnchored = true, driftScore = score.value)
        }

        // 5. Normal compilation.
        val recentFrames = frames.recent(taskId, now - RECENT_FRAME_WINDOW_MS, budget.recentFrames)
        val block = buildString {
            appendLine("<chm_context>")
            appendLine("# Mission Brief (pinned, trust=1.0)")
            appendLine(objective?.content ?: "(no objective recorded)")
            appendLine()
            if (highTrust.isNotEmpty()) {
                appendLine("# High-trust memories")
                highTrust.forEach { node ->
                    appendLine("- [trust ${"%.2f".format(node.trust)}] ${node.content}")
                }
                appendLine()
            }
            if (recentFrames.isNotEmpty()) {
                appendLine("# Recent activity (last ${recentFrames.size} frames)")
                recentFrames.forEach { frame ->
                    val glyph = when (frame.outcome) {
                        com.jarves.mh.agent.memory.model.FrameOutcome.RELEVANT -> "+"
                        com.jarves.mh.agent.memory.model.FrameOutcome.IRRELEVANT -> "-"
                        com.jarves.mh.agent.memory.model.FrameOutcome.ERROR -> "!"
                        com.jarves.mh.agent.memory.model.FrameOutcome.NEUTRAL -> "="
                    }
                    appendLine("$glyph ${frame.tool.ifBlank { frame.type.name }}: ${frame.resultDigest}")
                }
                appendLine()
            }
            if (lowTrust.isNotEmpty()) {
                appendLine("# Low-trust (unconfirmed — treat as provisional)")
                lowTrust.take(LOW_TRUST_DISPLAY_LIMIT).forEach { node ->
                    appendLine("- [trust ${"%.2f".format(node.trust)}] ${node.content}")
                }
                appendLine()
            }
            appendLine("# Mission Brief (repeated — this is the goal)")
            appendLine(objective?.content ?: "(no objective recorded)")
            appendLine("</chm_context>")
        }
        return Compiled(block, ranked, reAnchored = false, driftScore = score.value)
    }

    /** Character budget so CHM can never starve the conversation history. */
    data class Budget(
        val seedLimit: Int = 8,
        val expansionPerSeed: Int = 3,
        val maxMemories: Int = 16,
        val recentFrames: Int = 8,
        val maxChars: Int = 4000,
    )

    companion object {
        const val TRUST_INJECT_FLOOR = 0.5f
        const val LOW_TRUST_DISPLAY_LIMIT = 4
        const val RECENT_FRAME_WINDOW_MS = 10 * 60 * 1000L
    }
}

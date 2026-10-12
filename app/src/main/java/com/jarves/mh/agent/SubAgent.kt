package com.jarves.mh.agent

import java.util.concurrent.atomic.AtomicInteger

/**
 * Mod 3 — OpenCode-style named sub-agents.
 *
 * The v1 engine shard pool was anonymous: workers were labelled "Sub-Agent 1" /
 * "Sub-Agent 2" and shards were handed out by index parity alone. OpenCode-style
 * sub-agents are instead *named specialists* the orchestrator can address by
 * identity, so the chat can show "the Researcher is reading the codebase" rather
 * than a generic "sub-agent is working".
 *
 * A [SubAgent] is the identity half of that: a stable name, a one-line specialty,
 * and the key-scope that owns its API credentials. Execution still flows through
 * the existing shard executor; this layer only decides *who* a shard is addressed
 * to and how it is labelled in the UI.
 */
data class SubAgent(
    /** Stable identifier used in logs and activity items. */
    val id: String,
    /** Human-facing name shown in the chat (e.g. "Researcher"). */
    val name: String,
    /** One-line description of what this specialist is for. */
    val specialty: String,
    /**
     * Credential scope this agent draws its API key from. Keeping this on the
     * identity (rather than deriving it from a shard index) means routing a
     * shard to a specialist never silently changes which key it uses.
     */
    val keyScope: String,
) {
    companion object {
        /**
         * The default specialist roster.
         *
         * Two agents map to the two existing key scopes ([AGENT2_SCOPE]/
         * [AGENT3_SCOPE]) so the key routing in `runRealShard` keeps working
         * unchanged; the rest fall back to the head agent's own provider.
         */
        val DefaultRoster: List<SubAgent> = listOf(
            SubAgent("architect", "Architect", "Breaks the request into a plan and owns the design", AGENT2_SCOPE),
            SubAgent("researcher", "Researcher", "Reads the codebase and gathers context", AGENT3_SCOPE),
            SubAgent("implementer", "Implementer", "Writes and refactors the code", AGENT2_SCOPE),
            SubAgent("reviewer", "Reviewer", "Verifies the diff and catches regressions", AGENT3_SCOPE),
        )

        /** Lookup that never throws; callers fall back to [fallback] instead. */
        fun byId(roster: List<SubAgent>, id: String): SubAgent? = roster.firstOrNull { it.id == id }
    }
}

/**
 * Assigns each shard to a named specialist from [roster].
 *
 * Assignment prefers a keyword match between the instruction and each agent's
 * specialty vocabulary, so a "read the codebase" shard genuinely lands on the
 * Researcher. When nothing matches it falls back to round-robin so the load is
 * still spread across the roster instead of piling onto one agent.
 */
class SubAgentRouter(private val roster: List<SubAgent> = SubAgent.DefaultRoster) {
    private val counter = AtomicInteger(0)

    /** Instruction → specialist. Deterministic per process for a given phrase. */
    fun assignFor(instruction: String): SubAgent {
        val lowered = instruction.lowercase()
        roster.forEach { agent ->
            if (KEYWORDS[agent.id]?.any { lowered.contains(it) } == true) return agent
        }
        // Round-robin fallback: keeps an even spread when no keyword matches.
        return roster[counter.getAndIncrement() % roster.size]
    }

    private companion object {
        // Coarse vocabulary per specialist. Deliberately short: the point is a
        // sensible default, not a classifier. Anything ambiguous falls through
        // to round-robin.
        val KEYWORDS = mapOf(
            "architect" to listOf("plan", "design", "architect", "break down", "decompose", "structure"),
            "researcher" to listOf("research", "read", "explore", "understand", "investigate", "find", "search", "codebase", "context"),
            "implementer" to listOf("implement", "write", "build", "refactor", "add", "create", "fix", "change", "update", "code"),
            "reviewer" to listOf("review", "verify", "test", "check", "lint", "regression", "inspect", "diff"),
        )
    }
}

/** Credential scopes shared with the ViewModel's key routing. */
private const val AGENT2_SCOPE = "agent2"
private const val AGENT3_SCOPE = "agent3"

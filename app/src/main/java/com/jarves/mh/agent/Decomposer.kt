package com.jarves.mh.agent

/**
 * Turns one top-level request into N sub-instructions for parallel workers.
 *
 * V3 replaces the old hardcoded ui_/backend_ 2-way split with an LLM-driven
 * decomposer. The real implementation asks the model how to break the task up
 * into focused, parallelizable shards; tests inject a fixed plan.
 */
fun interface Decomposer {
    /** @return an ordered list of decomposition children (id + focused instruction). */
    suspend fun decompose(instruction: String): List<DecomposedChild>

    data class DecomposedChild(
        val id: String,
        val instruction: String,
    ) {
        companion object {
            /** Hardcoded fallback decomposer: splits into UI + backend halves. */
            val Default = Decomposer { instruction ->
                listOf(
                    DecomposedChild("ui", "Build/refactor the user-facing behavior and layout. $instruction"),
                    DecomposedChild("backend", "Build/refactor the data, persistence, and backend wiring. $instruction"),
                )
            }
        }
    }
}

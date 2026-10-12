package com.jarves.mh.agent

enum class AgentMode {
    SIMPLE,
    AGENTIC,
    /**
     * Mod 4 — plan-driven long-horizon execution.
     *
     * The agent first produces an ordered to-do list for the request, then the
     * harness drives the model on each outstanding item in turn until nothing
     * is left. Progress persists between items and each completion is published
     * to the UI as it happens, which is what makes a task "long-term" rather
     * than merely fanned-out.
     */
    LONGTASK,
    COOPERATIVE;

    fun label(): String = when (this) {
        SIMPLE -> "Simple"
        AGENTIC -> "Agentic"
        LONGTASK -> "Plan-Driven"
        COOPERATIVE -> "Cooperative"
    }
}

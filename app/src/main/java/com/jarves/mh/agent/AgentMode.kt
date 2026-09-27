package com.jarves.mh.agent

enum class AgentMode {
    SIMPLE,
    AGENTIC,
    COOPERATIVE;

    fun label(): String = when (this) {
        SIMPLE -> "Simple"
        AGENTIC -> "Agentic"
        COOPERATIVE -> "Cooperative"
    }
}

package com.jarves.mh.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentModeRoundTripTest {

    /**
     * The persisted preference stores the enum name; onboarding restores it with
     * valueOf(). Any unknown string must fall back to SIMPLE rather than crash.
     */
    @Test
    fun persistedNamesRoundTripToModes() {
        for (mode in AgentMode.entries) {
            val restored = runCatching { AgentMode.valueOf(mode.name) }.getOrDefault(AgentMode.SIMPLE)
            assertEquals(mode, restored)
        }
    }

    @Test
    fun unknownPersistedValueFallsBackToSimple() {
        val restored = runCatching { AgentMode.valueOf("NOPE") }.getOrDefault(AgentMode.SIMPLE)
        assertEquals(AgentMode.SIMPLE, restored)
    }
}

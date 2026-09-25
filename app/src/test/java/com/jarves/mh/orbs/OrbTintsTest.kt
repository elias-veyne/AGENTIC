package com.jarves.mh.orbs

import com.jarves.mh.model.ActivityItem
import com.jarves.mh.ui.orbs.OrbState
import com.jarves.mh.ui.orbs.OrbTints
import com.jarves.mh.ui.orbs.orbStateForActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OrbTintsTest {

    @Test
    fun weavingHasItsOwnPinkTint() {
        // The demo's GLOW.weaving is #F5C2E7; it must not reuse THINKING purple.
        assertEquals(0xFFF5C2E7.toInt(), OrbTints.WEAVING)
        assertNotEquals(OrbTints.THINKING, OrbTints.WEAVING)
        assertEquals(OrbTints.WEAVING, OrbTints.forState(OrbState.WEAVING))
    }

    @Test
    fun eachStateMapsToADistinctTint() {
        val states = OrbState.entries.filter { it != OrbState.SHAPING }
        val tints = states.map(OrbTints::forState).toSet()
        assertEquals("SEARCHING and WORKING intentionally share a tint", states.size - 1, tints.size)
    }

    @Test
    fun idleWhenNothingRunning() {
        assertEquals(
            OrbState.BREATHING,
            orbStateForActivity(thinkingActive = false, liveProcess = emptyList(), isRunning = false),
        )
    }

    @Test
    fun reasoningMapsToSolving() {
        val process = listOf(ActivityItem(title = "Think", detail = "planning", isComplete = false))
        assertEquals(
            OrbState.SOLVING,
            orbStateForActivity(thinkingActive = true, liveProcess = process, isRunning = true),
        )
    }

    @Test
    fun aCommandMapsToWorking() {
        val process = listOf(
            ActivityItem(title = "Think", detail = "planning", isComplete = true),
            ActivityItem(title = "Running Bash", detail = "npm install", isComplete = false, isCommand = true),
        )
        assertEquals(
            OrbState.WORKING,
            orbStateForActivity(thinkingActive = false, liveProcess = process, isRunning = true),
        )
    }

    @Test
    fun fileChangesMapToWeaving() {
        val process = listOf(
            ActivityItem(title = "Files changed", detail = "edited Main.kt", isComplete = false),
        )
        assertEquals(
            OrbState.WEAVING,
            orbStateForActivity(thinkingActive = false, liveProcess = process, isRunning = true),
        )
    }

    @Test
    fun searchMapsToSearching() {
        val process = listOf(
            ActivityItem(title = "Running Grep", detail = "searching for usages", isComplete = false),
        )
        assertEquals(
            OrbState.SEARCHING,
            orbStateForActivity(thinkingActive = false, liveProcess = process, isRunning = true),
        )
    }

    @Test
    fun completedItemsAreSkippedInFavourOfLiveOnes() {
        val process = listOf(
            ActivityItem(title = "Files changed", detail = "done editing", isComplete = true),
            ActivityItem(title = "Think", detail = "planning next step", isComplete = false),
        )
        assertEquals(
            OrbState.SOLVING,
            orbStateForActivity(thinkingActive = true, liveProcess = process, isRunning = true),
        )
    }
}

package com.jarves.mh.model

import com.jarves.mh.model.MemoryPlanner.DeviceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RAM tiering and the load refusal arithmetic from PRD section 2.4.
 *
 * A load that does not fit does not fail slowly - the kernel kills the process and
 * takes the session with it - so these numbers decide whether a model is usable on a
 * given device at all.
 */
class MemoryPlannerTest {

    private val planner = MemoryPlanner()

    private val gb = 1_000_000_000L

    // --- tier selection -----------------------------------------------------

    @Test
    fun `tier boundaries are inclusive at the floor`() {
        assertEquals(DeviceTier.ENTRY, DeviceTier.forRam(3_200_000_000L))
        assertEquals(DeviceTier.MID, DeviceTier.forRam(4_500_000_000L))
        assertEquals(DeviceTier.HIGH, DeviceTier.forRam(8_000_000_000L))
    }

    @Test
    fun `a device one byte below a boundary stays in the lower tier`() {
        assertEquals(DeviceTier.ENTRY, DeviceTier.forRam(4_499_999_999L))
        assertEquals(DeviceTier.MID, DeviceTier.forRam(7_999_999_999L))
    }

    @Test
    fun `a device below the entry floor still gets the entry tier`() {
        // There is no tier below Entry, so refusing outright would leave a small phone
        // with no local option at all. canLoad is what refuses a load, not this.
        assertEquals(DeviceTier.ENTRY, DeviceTier.forRam(2_000_000_000L))
        assertEquals(DeviceTier.ENTRY, DeviceTier.forRam(0))
    }

    @Test
    fun `a very large device does not fall off the end of the table`() {
        assertEquals(DeviceTier.HIGH, DeviceTier.forRam(24 * gb))
    }

    // --- recommended profiles ----------------------------------------------

    @Test
    fun `the entry tier is the small model with no draft head`() {
        val profile = planner.recommend(3_500_000_000L)

        assertEquals(DeviceTier.ENTRY, profile.tier)
        assertEquals("IQ4_XS", profile.quantisation)
        assertEquals(4_096, profile.contextTokens)
        assertFalse(
            "a draft head is a second model; the small tier cannot carry one",
            profile.speculativeDraft,
        )
    }

    @Test
    fun `the mid tier is the 35B at q4 with a draft head`() {
        val profile = planner.recommend(6_000_000_000L)

        assertEquals(DeviceTier.MID, profile.tier)
        assertEquals("Q4_K_M", profile.quantisation)
        assertEquals(8_192, profile.contextTokens)
        assertTrue(profile.speculativeDraft)
    }

    @Test
    fun `the high tier is the 35B at q5 with the longest context`() {
        val profile = planner.recommend(12_000_000_000L)

        assertEquals(DeviceTier.HIGH, profile.tier)
        assertEquals("Q5_K_M", profile.quantisation)
        assertEquals(16_384, profile.contextTokens)
        assertTrue(profile.speculativeDraft)
    }

    // --- projection arithmetic ---------------------------------------------

    @Test
    fun `kv cache scales with context length`() {
        assertEquals(1_000L, planner.kvCacheBytes(contextTokens = 100, kvBytesPerToken = 10))
        assertEquals(0L, planner.kvCacheBytes(contextTokens = 0, kvBytesPerToken = 10))
    }

    @Test
    fun `projected rss is the weighted size plus the kv cache`() {
        // PRD 2.4.2: weight size * 1.15, plus the KV estimate.
        assertEquals(2_150L, planner.projectedRss(weightBytes = 1_000, contextTokens = 100, kvBytesPerToken = 10))
    }

    @Test
    fun `a longer context raises the projection`() {
        val short = planner.projectedRss(1_000, contextTokens = 100, kvBytesPerToken = 10)
        val long = planner.projectedRss(1_000, contextTokens = 1_000, kvBytesPerToken = 10)
        assertTrue(long > short)
    }

    @Test
    fun `load headroom is the prd multiplier`() {
        assertEquals(1.15, MemoryPlanner.WEIGHT_OVERHEAD, 0.0001)
        assertEquals(1.2, MemoryPlanner.LOAD_HEADROOM, 0.0001)
    }

    // --- refusal ------------------------------------------------------------

    @Test
    fun `a load that exactly meets the headroom is allowed`() {
        // 2150 * 1.2 == 2580 exactly. Floating point makes this an equality test, so
        // it is worth pinning rather than leaving to chance.
        assertTrue(planner.canLoad(availableRamBytes = 2_580, projectedBytes = 2_150))
    }

    @Test
    fun `one byte short of the headroom is refused`() {
        assertFalse(planner.canLoad(availableRamBytes = 2_579, projectedBytes = 2_150))
    }

    @Test
    fun `a weight file larger than the device is refused`() {
        val projected = planner.projectedRss(20_000_000_000L, 8_192, 1_024)
        assertFalse(planner.canLoad(availableRamBytes = 6 * gb, projectedBytes = projected))
    }

    @Test
    fun `no refusal is returned when the load fits`() {
        assertNull(
            planner.refusalOrNull(
                availableRamBytes = 8 * gb,
                weightBytes = 1_000,
                contextTokens = 4_096,
                kvBytesPerToken = 1_024,
            )
        )
    }

    @Test
    fun `a refusal on the mid tier suggests the entry tier`() {
        val refusal = planner.refusalOrNull(
            availableRamBytes = 5 * gb,
            weightBytes = 20_000_000_000L,
            contextTokens = 8_192,
            kvBytesPerToken = 1_024,
        )

        assertNotNull(refusal)
        assertEquals(DeviceTier.ENTRY, refusal!!.suggested.tier)
    }

    @Test
    fun `a refusal on the high tier suggests the mid tier`() {
        val refusal = planner.refusalOrNull(
            availableRamBytes = 9 * gb,
            weightBytes = 20_000_000_000L,
            contextTokens = 16_384,
            kvBytesPerToken = 1_024,
        )

        assertNotNull(refusal)
        assertEquals(DeviceTier.MID, refusal!!.suggested.tier)
    }

    @Test
    fun `a refusal on the entry tier has nothing cheaper to offer`() {
        // Suggesting another profile that will also not fit is worse than saying so,
        // so the suggestion is the same tier and the caller reports it as such.
        val refusal = planner.refusalOrNull(
            availableRamBytes = 3 * gb,
            weightBytes = 20_000_000_000L,
            contextTokens = 4_096,
            kvBytesPerToken = 1_024,
        )

        assertNotNull(refusal)
        assertEquals(DeviceTier.ENTRY, refusal!!.suggested.tier)
    }

    @Test
    fun `a refusal reports the numbers behind it`() {
        val refusal = planner.refusalOrNull(
            availableRamBytes = 3 * gb,
            weightBytes = 20_000_000_000L,
            contextTokens = 4_096,
            kvBytesPerToken = 1_024,
        )!!

        assertEquals(3 * gb, refusal.availableRamBytes)
        assertTrue("projection must exceed available", refusal.projectedBytes > refusal.availableRamBytes)
    }
}

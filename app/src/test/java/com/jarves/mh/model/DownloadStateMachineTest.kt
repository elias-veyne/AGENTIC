package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download lifecycle, replayed as event sequences.
 *
 * The retry budget is the behaviour worth guarding: it is the rule most easily broken
 * by a later change, and a regression there either burns a user's data twice or
 * permanently poisons a good model.
 */
class DownloadStateMachineTest {

    private val machine = DownloadStateMachine()

    private fun happyPathChunks(count: Int = 4): List<DownloadEvent> =
        (0 until count).flatMap { listOf(DownloadEvent.ChunkStarted(it), DownloadEvent.ChunkCompleted(it)) }

    // --- happy path ---------------------------------------------------------

    @Test
    fun `a clean download ends ready`() {
        val status = machine.run(
            DownloadEvent.Start,
            DownloadEvent.MetadataResolved(4),
            *happyPathChunks(4).toTypedArray(),
            DownloadEvent.Verified(needsMove = false),
        )

        assertEquals(DownloadState.READY, status.state)
        assertTrue(status.state.isTerminal)
        assertNull(status.detail)
    }

    @Test
    fun `a verified file that needs relocating goes through moving`() {
        val status = machine.run(
            DownloadEvent.Start,
            DownloadEvent.MetadataResolved(2),
            DownloadEvent.ChunkStarted(0), DownloadEvent.ChunkCompleted(0),
            DownloadEvent.ChunkStarted(1), DownloadEvent.ChunkCompleted(1),
            DownloadEvent.Verified(needsMove = true),
        )

        assertEquals(DownloadState.MOVING, status.state)
        assertEquals(DownloadState.READY, machine.reduce(status, DownloadEvent.Moved).state)
    }

    @Test
    fun `verification does not start until every chunk has landed`() {
        val partial = machine.run(
            DownloadEvent.Start,
            DownloadEvent.MetadataResolved(3),
            DownloadEvent.ChunkStarted(0), DownloadEvent.ChunkCompleted(0),
            DownloadEvent.ChunkStarted(1), DownloadEvent.ChunkCompleted(1),
        )

        assertEquals(DownloadState.DOWNLOADING, partial.state)
        assertEquals(2, partial.chunks.count { it == ChunkState.DONE })
    }

    @Test
    fun `progress reflects finished chunks`() {
        val status = machine.run(
            DownloadEvent.Start,
            DownloadEvent.MetadataResolved(4),
            DownloadEvent.ChunkStarted(0), DownloadEvent.ChunkCompleted(0),
            DownloadEvent.ChunkStarted(1), DownloadEvent.ChunkCompleted(1),
        )

        assertEquals(0.5f, status.progress, 0.001f)
    }

    @Test
    fun `progress of an unplanned download is zero rather than a division by zero`() {
        assertEquals(0f, DownloadStatus().progress, 0.001f)
    }

    // --- the single automatic retry ----------------------------------------

    @Test
    fun `one checksum mismatch triggers exactly one re-download`() {
        val first = machine.run(
            DownloadEvent.Start,
            DownloadEvent.MetadataResolved(1),
            DownloadEvent.ChunkStarted(0), DownloadEvent.ChunkCompleted(0),
            DownloadEvent.ChecksumMismatch("aaa", "bbb"),
        )

        assertEquals(DownloadState.METADATA, first.state)
        assertEquals(1, first.attempt)
        assertTrue("a re-fetch is expected", first.chunks.isEmpty())
    }

    @Test
    fun `a second mismatch marks the model corrupt`() {
        // The PRD allows exactly one re-download. A mirror serving poisoned bytes
        // must not be able to cost the user their data allowance repeatedly.
        val status = machine.run(
            DownloadEvent.Start,
            DownloadEvent.MetadataResolved(1),
            DownloadEvent.ChunkStarted(0), DownloadEvent.ChunkCompleted(0),
            DownloadEvent.ChecksumMismatch("aaa", "bbb"),
            DownloadEvent.MetadataResolved(1),
            DownloadEvent.ChunkStarted(0), DownloadEvent.ChunkCompleted(0),
            DownloadEvent.ChecksumMismatch("aaa", "ccc"),
        )

        assertEquals(DownloadState.FAILED_CORRUPT, status.state)
        assertTrue(status.state.isTerminal)
        assertEquals(2, status.attempt)
    }

    @Test
    fun `a network error does not consume the checksum retry budget`() {
        // A flaky connection must not burn the single re-download, or a perfectly
        // good model gets reported corrupt because the signal dropped once.
        val afterNetworkError = machine.run(
            DownloadEvent.Start,
            DownloadEvent.MetadataResolved(2),
            DownloadEvent.ChunkStarted(0),
            DownloadEvent.ChunkFailed(0, "connection reset"),
        )

        assertEquals(DownloadState.FAILED_RETRYABLE, afterNetworkError.state)
        assertEquals(0, afterNetworkError.attempt)
    }

    @Test
    fun `an unreadable file stays retryable and keeps the budget`() {
        // Nothing about the bytes has been disproven - the transfer was simply
        // incomplete - so this must not count against the mismatch allowance.
        val status = machine.run(
            DownloadEvent.Start,
            DownloadEvent.MetadataResolved(1),
            DownloadEvent.ChunkStarted(0), DownloadEvent.ChunkCompleted(0),
            DownloadEvent.Unreadable("volume unmounted"),
        )

        assertEquals(DownloadState.FAILED_RETRYABLE, status.state)
        assertEquals(0, status.attempt)
    }

    @Test
    fun `a retryable failure can be resumed`() {
        val failed = machine.run(
            DownloadEvent.Start,
            DownloadEvent.MetadataResolved(2),
            DownloadEvent.ChunkFailed(0, "timeout"),
        )

        assertEquals(DownloadState.METADATA, machine.reduce(failed, DownloadEvent.Retry).state)
    }

    @Test
    fun `a failed chunk is recorded on the plan`() {
        val status = machine.run(
            DownloadEvent.Start,
            DownloadEvent.MetadataResolved(3),
            DownloadEvent.ChunkStarted(1),
            DownloadEvent.ChunkFailed(1, "timeout"),
        )

        assertEquals(ChunkState.FAILED, status.chunks[1])
    }

    // --- blocked states -----------------------------------------------------

    @Test
    fun `insufficient storage blocks then resumes from metadata`() {
        val blocked = machine.run(DownloadEvent.Start, DownloadEvent.InsufficientStorage)

        assertEquals(DownloadState.WAITING_STORAGE, blocked.state)

        val resumed = machine.reduce(blocked, DownloadEvent.BecameAvailable)
        assertEquals(DownloadState.METADATA, resumed.state)
    }

    @Test
    fun `resuming from a blocked state discards the stale chunk plan`() {
        // A chunk left RUNNING by a dropped connection must not be mistaken for work
        // already done when the download resumes.
        val blocked = machine.run(
            DownloadEvent.Start,
            DownloadEvent.MetadataResolved(4),
            DownloadEvent.ChunkStarted(0),
            DownloadEvent.NoConnectivity,
        )

        assertTrue(machine.reduce(blocked, DownloadEvent.BecameAvailable).chunks.isEmpty())
    }

    @Test
    fun `no connectivity blocks the download`() {
        val status = machine.run(DownloadEvent.Start, DownloadEvent.NoConnectivity)
        assertEquals(DownloadState.WAITING_NETWORK, status.state)
    }

    // --- cancellation -------------------------------------------------------

    @Test
    fun `cancel is terminal and outranks an in-flight mismatch`() {
        val verifying = machine.run(
            DownloadEvent.Start,
            DownloadEvent.MetadataResolved(1),
            DownloadEvent.ChunkStarted(0), DownloadEvent.ChunkCompleted(0),
        )

        val cancelled = machine.reduce(verifying, DownloadEvent.Cancel)
        assertEquals(DownloadState.CANCELED, cancelled.state)
        assertTrue(cancelled.state.isTerminal)
    }

    @Test
    fun `cancel works from the queued state`() {
        assertEquals(DownloadState.CANCELED, machine.reduce(DownloadStatus(), DownloadEvent.Cancel).state)
    }

    // --- robustness ---------------------------------------------------------

    @Test
    fun `an out of range chunk index is ignored`() {
        // A duplicate progress callback must not grow the plan, or the denominator of
        // progress would change and the bar would jump backwards.
        val status = machine.run(
            DownloadEvent.Start,
            DownloadEvent.MetadataResolved(2),
            DownloadEvent.ChunkStarted(0), DownloadEvent.ChunkCompleted(0),
        )

        val outOfRange = machine.reduce(status, DownloadEvent.ChunkStarted(99))
        assertEquals(2, outOfRange.chunks.size)
        assertEquals(0.5f, outOfRange.progress, 0.001f)
    }

    @Test
    fun `terminal states are exactly the three that cannot advance`() {
        assertTrue(DownloadState.READY.isTerminal)
        assertTrue(DownloadState.CANCELED.isTerminal)
        assertTrue(DownloadState.FAILED_CORRUPT.isTerminal)

        assertFalse(DownloadState.FAILED_RETRYABLE.isTerminal)
        assertFalse(DownloadState.WAITING_STORAGE.isTerminal)
        assertFalse(DownloadState.WAITING_NETWORK.isTerminal)
        assertFalse(DownloadState.DOWNLOADING.isTerminal)
    }

    @Test
    fun `the retry budget is configurable`() {
        // A mirror known to be mid-republish may warrant more than one retry.
        val generous = DownloadStateMachine(maxChecksumRetries = 2)
        val status = DownloadStatus(state = DownloadState.VERIFYING, attempt = 1)
        val next = generous.reduce(status, DownloadEvent.ChecksumMismatch("a", "b"))

        assertEquals(DownloadState.METADATA, next.state)
        assertEquals(2, next.attempt)
    }
}

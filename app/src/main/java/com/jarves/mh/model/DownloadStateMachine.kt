package com.jarves.mh.model

/**
 * The lifecycle of one model download.
 *
 * Names follow the PRD's state list exactly so the two can be diffed against each
 * other. [FAILED_RETRYABLE] and [FAILED_CORRUPT] are kept apart because they mean
 * opposite things to the user: one is worth trying again, the other means the bytes
 * on the mirror are wrong and retrying will only spend the user's data twice.
 */
enum class DownloadState {
    /** Accepted, nothing started. */
    QUEUED,

    /** Resolving size, mirrors and the signed manifest. */
    METADATA,

    /** Fetching chunks. */
    DOWNLOADING,

    /** Digesting the assembled file against the manifest. */
    VERIFYING,

    /** Relocating a verified file from cache into its final home. */
    MOVING,

    /** Installed and verified. Terminal. */
    READY,

    /** Transient failure; a retry may succeed. */
    FAILED_RETRYABLE,

    /** The bytes did not match the manifest after the allowed retries. */
    FAILED_CORRUPT,

    /** Withdrawn by the user. Terminal. */
    CANCELED,

    /** Blocked on free space. Resumes from [METADATA]. */
    WAITING_STORAGE,

    /** Blocked on connectivity. Resumes from [METADATA]. */
    WAITING_NETWORK,
    ;

    /** True when no further transition is possible. */
    val isTerminal: Boolean get() = this == READY || this == CANCELED || this == FAILED_CORRUPT
}

/** Per-chunk progress within [DownloadState.DOWNLOADING]. */
enum class ChunkState { PENDING, RUNNING, DONE, FAILED }

/**
 * An input to the download state machine.
 *
 * Events are the only way state changes, and [DownloadStateMachine.reduce] is pure, so
 * the entire policy - including the retry budget - is testable by replaying event
 * sequences with no network and no filesystem.
 */
sealed interface DownloadEvent {
    /** Begin resolving metadata. */
    data object Start : DownloadEvent

    /** Size, mirrors and manifest are known. */
    data class MetadataResolved(val chunkCount: Int) : DownloadEvent

    /** No volume can hold the file. */
    data object InsufficientStorage : DownloadEvent

    /** No usable connectivity. */
    data object NoConnectivity : DownloadEvent

    /** Either of the above cleared. */
    data object BecameAvailable : DownloadEvent

    data class ChunkStarted(val index: Int) : DownloadEvent
    data class ChunkCompleted(val index: Int) : DownloadEvent
    data class ChunkFailed(val index: Int, val reason: String) : DownloadEvent

    /** Digest matched. [needsMove] is true when the file is not already in its final home. */
    data class Verified(val needsMove: Boolean) : DownloadEvent

    /** Digest did not match. The file has already been quarantined. */
    data class ChecksumMismatch(val expected: String, val actual: String) : DownloadEvent

    /** The assembled file could not be read - a transfer fault, not corruption. */
    data class Unreadable(val reason: String) : DownloadEvent

    data object Moved : DownloadEvent

    /** Retry a [DownloadState.FAILED_RETRYABLE] download. */
    data object Retry : DownloadEvent

    data object Cancel : DownloadEvent
}

/**
 * The complete, self-describing state of one download.
 *
 * [attempt] counts verification failures, not network errors. The PRD allows one
 * automatic re-download after a checksum mismatch, and a transient network error must
 * not consume that budget - otherwise a flaky connection would burn the single retry
 * and a perfectly good model would be reported corrupt.
 */
data class DownloadStatus(
    val state: DownloadState = DownloadState.QUEUED,
    val chunks: List<ChunkState> = emptyList(),
    val attempt: Int = 0,
    val detail: String? = null,
) {
    /** Fraction of chunks finished, 0f to 1f. Zero when no chunks are planned. */
    val progress: Float
        get() = if (chunks.isEmpty()) 0f else chunks.count { it == ChunkState.DONE }.toFloat() / chunks.size

    /** The state with a fresh chunk plan for [count] chunks. */
    fun planned(count: Int): DownloadStatus =
        copy(state = DownloadState.DOWNLOADING, chunks = List(count) { ChunkState.PENDING }, detail = null)
}

/**
 * The download policy, as a pure reducer.
 *
 * ## Why a reducer
 *
 * The interesting behaviour is not any single transition but the interaction between
 * state, the retry budget and the filesystem move - which is exactly the logic that
 * goes wrong when it is spread across a coroutine with a `when` on a mutable field.
 * Reducing events over an immutable [DownloadStatus] makes the whole thing replayable
 * and lets the retry rule be asserted directly.
 *
 * ## The one automatic retry
 *
 * A checksum mismatch quarantines the file and allows exactly one re-download, after
 * which the model is reported corrupt. Two rather than one would let a mirror serving
 * poisoned bytes cost the user their data allowance twice; none would mean a single
 * corrupted chunk during a long transfer on mobile data permanently poisons a
 * perfectly good model.
 */
class DownloadStateMachine(
    private val maxChecksumRetries: Int = 1,
) {
    /**
     * Fold one [event] into [current].
     *
     * Branch style matters here, and both wrong forms fail loudly rather than
     * silently, which is the only reason it is worth stating.
     *
     * The parameterless `data object`s (`Start`, `Moved`, `Cancel`) are matched by
     * equality: `DownloadEvent.Start ->`.
     *
     * The `data class` events must be matched by type: `is DownloadEvent.MetadataResolved ->`.
     * Writing `DownloadEvent.MetadataResolved ->` is an equality test against the class
     * itself, which is never true, so the branch can never match and the `when` is
     * rejected as non-exhaustive. Dropping the qualifier to `is MetadataResolved` also
     * fails, because the subtypes are nested inside [DownloadEvent] and so are not in
     * unqualified scope from this class.
     */
    fun reduce(current: DownloadStatus, event: DownloadEvent): DownloadStatus = when (event) {
        DownloadEvent.Start -> current.copy(state = DownloadState.METADATA, detail = null)

        is DownloadEvent.MetadataResolved -> current.planned(event.chunkCount)

        DownloadEvent.InsufficientStorage ->
            current.copy(state = DownloadState.WAITING_STORAGE, detail = "insufficient free space")

        DownloadEvent.NoConnectivity ->
            current.copy(state = DownloadState.WAITING_NETWORK, detail = "no connectivity")

        // Both blocked states resume from METADATA rather than DOWNLOADING, and the
        // stale chunk plan is discarded: a chunk left RUNNING by a dropped connection
        // must not be mistaken for work already done when the download resumes.
        DownloadEvent.BecameAvailable -> current.copy(state = DownloadState.METADATA, chunks = emptyList(), detail = null)

        is DownloadEvent.ChunkStarted -> current.withChunk(event.index, ChunkState.RUNNING)

        is DownloadEvent.ChunkCompleted -> current.withChunk(event.index, ChunkState.DONE).let { next ->
            // Verification starts only once every chunk has landed.
            if (next.chunks.isNotEmpty() && next.chunks.all { it == ChunkState.DONE }) {
                next.copy(state = DownloadState.VERIFYING, detail = null)
            } else {
                next
            }
        }

        is DownloadEvent.ChunkFailed -> current
            .withChunk(event.index, ChunkState.FAILED)
            .copy(state = DownloadState.FAILED_RETRYABLE, detail = event.reason)

        is DownloadEvent.Verified -> if (event.needsMove) {
            current.copy(state = DownloadState.MOVING, detail = null)
        } else {
            current.copy(state = DownloadState.READY, detail = null)
        }

        DownloadEvent.Moved -> current.copy(state = DownloadState.READY, detail = null)

        is DownloadEvent.ChecksumMismatch -> {
            val nextAttempt = current.attempt + 1
            if (current.attempt < maxChecksumRetries) {
                // Budget left: re-fetch. attempt is carried forward so the next
                // mismatch has nothing left to spend.
                current.copy(
                    state = DownloadState.METADATA,
                    chunks = emptyList(),
                    attempt = nextAttempt,
                    detail = "checksum mismatch, re-downloading",
                )
            } else {
                // attempt is advanced here too. It is used in the message, so
                // leaving it at its old value would mean a retry log reads "after 1
                // attempt(s)" for a download that was fetched twice.
                current.copy(
                    state = DownloadState.FAILED_CORRUPT,
                    attempt = nextAttempt,
                    detail = "checksum mismatch after $nextAttempt attempt(s)",
                )
            }
        }

        // A read failure is a transfer fault. It is retryable but does not consume the
        // checksum budget, because nothing about the bytes has been disproven.
        is DownloadEvent.Unreadable -> current.copy(state = DownloadState.FAILED_RETRYABLE, detail = event.reason)

        DownloadEvent.Retry -> current.copy(state = DownloadState.METADATA, detail = null)

        // Cancellation outranks everything, including an in-flight mismatch.
        DownloadEvent.Cancel -> current.copy(state = DownloadState.CANCELED, detail = null)
    }

    /** Replay [events] from a fresh [DownloadStatus]. Convenience for tests and retries. */
    fun run(vararg events: DownloadEvent): DownloadStatus =
        events.fold(DownloadStatus()) { acc, event -> reduce(acc, event) }

    /**
     * Apply [event] only when [index] is in range.
     *
     * A chunk index from a resumed or duplicated progress callback must not grow the
     * plan, or a single duplicate event would silently change the denominator of
     * [DownloadStatus.progress] and make the bar jump backwards.
     */
    private fun DownloadStatus.withChunk(index: Int, chunk: ChunkState): DownloadStatus {
        if (index !in chunks.indices) return this
        return copy(chunks = chunks.toMutableList().also { it[index] = chunk })
    }
}

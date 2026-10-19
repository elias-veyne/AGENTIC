package com.jarves.mh.model.download

import com.jarves.mh.model.ChecksumVerifier
import com.jarves.mh.model.DownloadEvent
import com.jarves.mh.model.DownloadState
import com.jarves.mh.model.DownloadStateMachine
import com.jarves.mh.model.DownloadStatus
import com.jarves.mh.model.ManifestEntry
import com.jarves.mh.model.ModelPathResolver
import com.jarves.mh.model.VerificationOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** User-facing progress for one download. */
data class DownloadProgress(
    val state: DownloadState,
    /** 0f..1f of [totalBytes]. Sits at its last value during VERIFYING and MOVING. */
    val fraction: Float,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val message: String,
) {
    val isTerminal: Boolean get() = state.isTerminal
}

/**
 * Fetches a pinned [ModelSpec] into the models directory, verifying before install.
 *
 * ## Why the checksum is the point
 *
 * The bytes arrive over the network from a third party, and the PRD's premise is that
 * a model runs locally. Serving a tampered checkpoint to a local runtime is worse than
 * serving one to a cloud API, because nothing downstream ever sees it. So the digest is
 * checked against a constant compiled into the app - not against a manifest fetched at
 * download time, which would be self-certifying - and the file is moved into place only
 * after it matches.
 *
 * ## Resume
 *
 * Mobile connections drop. The staging file survives a failure and a cancellation, and
 * the next attempt sends a `Range` header. Without it, a 639 MB download that fails at
 * 90% starts from zero, which on mobile data is both slow and expensive.
 *
 * ## Integrity gap, stated plainly
 *
 * The digest is pinned in the binary, which is stronger than a fetched manifest but
 * weaker than the PRD's Ed25519 release key: anyone who can replace the APK can also
 * replace the constant. Closing that means signing a manifest and verifying it against
 * a key held outside the binary. Not implemented.
 */
class ModelDownloadManager(
    private val resolver: ModelPathResolver,
    private val machine: DownloadStateMachine = DownloadStateMachine(),
    private val verifier: ChecksumVerifier = ChecksumVerifier(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) {
    /**
     * Partial download. Kept across failures on purpose - this file is the reason a
     * resume is possible, and it must not live beside the verified weights.
     */
    private fun stagingFile(spec: ModelSpec): File =
        resolver.downloadStagingDir.resolve("${spec.directoryName}.gguf.part")

    /** The installed, verified weights. */
    private fun targetFile(spec: ModelSpec): File =
        resolver.modelDir(spec.directoryName).resolve(spec.url.substringAfterLast('/'))

    /** True when [spec] is installed at the expected size. */
    fun isInstalled(spec: ModelSpec): Boolean {
        val target = targetFile(spec)
        return target.exists() && target.length() == spec.sizeBytes
    }

    /** Delete any partial download, e.g. when the user cancels and restarts clean. */
    fun discardPartial(spec: ModelSpec): Boolean = stagingFile(spec).delete()

    /**
     * Download, verify and install [spec].
     *
     * Emits progress and completes normally on [DownloadState.READY]. Terminal failures
     * are emitted and then thrown, so a caller can `collect` for updates and `catch`
     * for the error without inspecting every emitted state.
     */
    fun download(spec: ModelSpec): Flow<DownloadProgress> = flow {
        val staging = stagingFile(spec)
        val target = targetFile(spec)
        var status = DownloadStatus()

        suspend fun reduce(event: DownloadEvent, downloaded: Long, message: String) {
            status = machine.reduce(status, event)
            emit(
                DownloadProgress(
                    state = status.state,
                    fraction = (downloaded.toFloat() / spec.sizeBytes).coerceIn(0f, 1f),
                    downloadedBytes = downloaded,
                    totalBytes = spec.sizeBytes,
                    message = message,
                )
            )
        }

        reduce(DownloadEvent.Start, 0, "Preparing…")

        // --- free space -------------------------------------------------------
        val headroom = resolver.storageHeadroom(spec.sizeBytes)
        if (resolver.freeSpace(resolver.modelsDir) < headroom) {
            reduce(
                DownloadEvent.InsufficientStorage,
                0,
                "Needs ${mb(headroom)} free — free some space and retry.",
            )
            return@flow
        }

        // --- fetch ------------------------------------------------------------
        staging.parentFile?.mkdirs()
        target.parentFile?.mkdirs()

        var resumeFrom = if (staging.exists()) staging.length() else 0L
        if (resumeFrom > spec.sizeBytes) {
            // Longer than the real artifact: a partial from a different build.
            staging.delete()
            resumeFrom = 0
        }

        val request = Request.Builder().url(spec.url).apply {
            if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-")
        }.build()

        reduce(
            DownloadEvent.MetadataResolved(1),
            resumeFrom,
            if (resumeFrom > 0) "Resuming at ${mb(resumeFrom)}…" else "Downloading…",
        )

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            reduce(DownloadEvent.ChunkFailed(0, "network: ${e.message}"), resumeFrom, "Network error — tap to retry")
            throw IOException("could not reach the model host", e)
        }

        response.use { r ->
            if (!r.isSuccessful && r.code != 206) {
                reduce(DownloadEvent.ChunkFailed(0, "http ${r.code}"), resumeFrom, "Download failed (HTTP ${r.code})")
                throw IOException("model host returned HTTP ${r.code}")
            }

            // 206 means the server honoured our Range. Anything else is a fresh body.
            val resuming = r.code == 206
            val body = r.body ?: throw IOException("empty response body")
            var written = if (resuming) resumeFrom else 0L
            var lastEmitted = written

            body.byteStream().use { input ->
                // A non-206 response restarts the body from zero, so any existing
                // partial must go. outputStream() on an existing file appends and
                // cannot be truncated in place, hence the delete rather than a seek.
                if (!resuming) staging.delete()
                staging.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        // Lets a cancelled download abandon the blocking read promptly.
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        // Throttle: at 64 KB per read a 639 MB transfer would otherwise
                        // recompose the progress bar thousands of times.
                        if (written - lastEmitted >= EMIT_EVERY_BYTES) {
                            lastEmitted = written
                            emit(
                                DownloadProgress(
                                    state = DownloadState.DOWNLOADING,
                                    fraction = (written.toFloat() / spec.sizeBytes).coerceIn(0f, 1f),
                                    downloadedBytes = written,
                                    totalBytes = spec.sizeBytes,
                                    message = "${mb(written)} / ${mb(spec.sizeBytes)}",
                                )
                            )
                        }
                    }
                }
            }

            if (written != spec.sizeBytes) {
                reduce(
                    DownloadEvent.ChunkFailed(0, "truncated"),
                    written,
                    "Stopped at ${mb(written)} of ${mb(spec.sizeBytes)} — tap to resume",
                )
                throw IOException("download truncated at $written of ${spec.sizeBytes}")
            }

            // --- verify --------------------------------------------------------
            emit(
                DownloadProgress(
                    state = DownloadState.VERIFYING,
                    fraction = 1f,
                    downloadedBytes = written,
                    totalBytes = spec.sizeBytes,
                    message = "Verifying checksum…",
                )
            )
            currentCoroutineContext().ensureActive()

            val entry = ManifestEntry(staging.name, spec.sizeBytes, sha256 = spec.sha256)
            when (val outcome = verifier.verify(staging, entry)) {
                is VerificationOutcome.Verified -> Unit
                is VerificationOutcome.Mismatched -> {
                    // Kept for diagnosis rather than deleted, as the PRD asks.
                    resolver.quarantineDir.mkdirs()
                    runCatching { staging.copyTo(resolver.quarantineDir.resolve(staging.name), overwrite = true) }
                    staging.delete()
                    reduce(
                        DownloadEvent.ChecksumMismatch(outcome.expected, outcome.actual),
                        written,
                        "Checksum mismatch — file quarantined",
                    )
                    throw IOException("checksum mismatch for ${spec.displayName}")
                }
                is VerificationOutcome.Unreadable -> {
                    reduce(DownloadEvent.Unreadable(outcome.reason), written, "Could not read the download")
                    throw IOException("unreadable download: ${outcome.reason}")
                }
                is VerificationOutcome.NoUsableDigest ->
                    throw IllegalStateException("model spec has no usable digest")
            }

            // --- install --------------------------------------------------------
            emit(
                DownloadProgress(
                    state = DownloadState.MOVING,
                    fraction = 1f,
                    downloadedBytes = written,
                    totalBytes = spec.sizeBytes,
                    message = "Installing…",
                )
            )
            if (target.exists()) target.delete()
            // renameTo fails across filesystems, which is reachable once SD-card
            // offloading exists; fall back to a copy rather than losing the download.
            if (!staging.renameTo(target)) {
                staging.copyTo(target, overwrite = true)
                staging.delete()
            }

            reduce(DownloadEvent.Moved, spec.sizeBytes, "${spec.displayName} ready")
        }
    }.flowOn(Dispatchers.IO)

    private fun mb(bytes: Long): String = "${bytes / 1_000_000} MB"

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val EMIT_EVERY_BYTES = 2L * 1024 * 1024
    }
}

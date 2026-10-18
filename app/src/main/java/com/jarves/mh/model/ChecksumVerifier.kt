package com.jarves.mh.model

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * Streaming digest computation for downloaded model files.
 *
 * ## Why streaming
 *
 * A 35B Q4_K_M weight file is around 20 GB. `File.readBytes()` on that will exhaust
 * the heap long before it finishes, and a single-pass digest is required because the
 * assembled file is the only copy. Reads are buffered at 1 MB per the PRD: large
 * enough that syscall overhead disappears, small enough to stay off the large-object
 * heap.
 */
object FileDigests {

    /** Buffered read size. See the class note on why this is not larger. */
    private const val BUFFER_BYTES = 1024 * 1024

    /** Digest algorithms this build will accept. */
    enum class Algorithm(val jcaName: String) {
        SHA_256("SHA-256"),
        MD5("MD5"),
        ;

        /** True when this digest is strong enough to be the sole basis of trust. */
        val isStrong: Boolean get() = this == SHA_256
    }

    /** Compute [algorithm] over [file] without loading it into memory. */
    @Throws(IOException::class)
    fun of(file: File, algorithm: Algorithm): String =
        file.inputStream().use { digest(it, algorithm) }

    /** Compute [algorithm] over a stream. Does not close it. */
    @Throws(IOException::class)
    fun digest(stream: InputStream, algorithm: Algorithm): String {
        val digest = newDigest(algorithm)
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    private fun newDigest(algorithm: Algorithm): MessageDigest = try {
        MessageDigest.getInstance(algorithm.jcaName)
    } catch (e: NoSuchAlgorithmException) {
        // SHA-256 is mandatory on every Android release we target, so this is a
        // platform fault rather than something a caller can handle. MD5 is likewise
        // present everywhere, but it is only ever a fallback.
        throw IllegalStateException("${algorithm.jcaName} unavailable on this platform", e)
    }

    private fun ByteArray.toHex(): String {
        val out = CharArray(size * 2)
        forEachIndexed { i, byte ->
            val v = byte.toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/**
 * One entry from a model's signed checksum manifest.
 *
 * [sha256] is nullable because the PRD allows MD5 for legacy ModelScope mirrors that
 * publish no SHA-256. That fallback is a downgrade in integrity, which is why it is
 * represented as absent rather than as an empty string: "no strong digest exists" and
 * "an empty strong digest was published" are different situations and must not
 * collapse.
 */
data class ManifestEntry(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String? = null,
    val md5: String? = null,
) {
    /** True when the entry carries a digest strong enough to trust on its own. */
    val hasStrongDigest: Boolean get() = !sha256.isNullOrBlank()
}

/** The result of checking a downloaded file against its manifest entry. */
sealed interface VerificationOutcome {
    /** The digest matched. [downgraded] is true when only MD5 was available to check. */
    data class Verified(val algorithm: FileDigests.Algorithm, val downgraded: Boolean) : VerificationOutcome

    /**
     * The file was read successfully but its digest did not match.
     *
     * This is the only outcome that justifies quarantining the file. Treating a
     * missing entry as a mismatch would quarantine every model on a mirror that
     * publishes digests under a different key.
     */
    data class Mismatched(val expected: String, val actual: String, val algorithm: FileDigests.Algorithm) : VerificationOutcome

    /**
     * The entry carried no digest this build can use.
     *
     * Surfaced separately from [Mismatched] on purpose: it means the *manifest* is
     * incomplete, not that the *file* is bad, and the two need different handling.
     */
    data class NoUsableDigest(val fileName: String) : VerificationOutcome

    /** The file could not be read - truncated, or the volume disappeared mid-verify. */
    data class Unreadable(val reason: String) : VerificationOutcome
}

/**
 * Verifies downloaded model files against a signed manifest.
 *
 * Pure JVM and free of Android so the whole policy is testable without a device.
 * Quarantine, retry and state transitions belong to the download manager; this type
 * only decides whether a file is good, and reports *how* it was checked so the caller
 * can record a downgrade.
 */
class ChecksumVerifier {

    /**
     * Check [file] against [entry].
     *
     * Prefers SHA-256. MD5 is used only when the entry publishes no SHA-256, and the
     * outcome is flagged [VerificationOutcome.Verified.downgraded] so the caller can
     * log it. That flag is not cosmetic: an attacker able to strip the SHA-256 field
     * from a manifest would otherwise quietly downgrade the check for every user.
     *
     * Does not throw: a read failure is reported as
     * [VerificationOutcome.Unreadable] so callers handle it as a value, not an
     * exception path.
     */
    fun verify(file: File, entry: ManifestEntry): VerificationOutcome {
        if (!entry.hasStrongDigest) {
            val legacy = entry.md5?.takeIf { it.isNotBlank() } ?: return VerificationOutcome.NoUsableDigest(entry.fileName)
            return digestAndCompare(file, legacy, FileDigests.Algorithm.MD5, downgraded = true)
        }

        return digestAndCompare(file, entry.sha256!!, FileDigests.Algorithm.SHA_256, downgraded = false)
    }

    private fun digestAndCompare(
        file: File,
        expected: String,
        algorithm: FileDigests.Algorithm,
        downgraded: Boolean,
    ): VerificationOutcome = try {
        val actual = FileDigests.of(file, algorithm)
        if (actual.equals(expected.trim(), ignoreCase = true)) {
            VerificationOutcome.Verified(algorithm, downgraded)
        } else {
            VerificationOutcome.Mismatched(expected, actual, algorithm)
        }
    } catch (e: IOException) {
        // A truncated or vanished file is not a corruption signal in the manifest's
        // sense - it is a transfer failure, and the download manager should retry it
        // rather than quarantine it as bad.
        VerificationOutcome.Unreadable(e.message ?: e::class.java.simpleName)
    }
}

package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Digest computation and manifest verification policy.
 *
 * The expected digests are the published vectors for `"abc"` and `""` rather than
 * values computed by this code, so a mistake in [FileDigests] cannot make the tests
 * agree with it.
 */
class ChecksumVerifierTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val verifier = ChecksumVerifier()

    /** SHA-256 of `"abc"`. */
    private val abcSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    /** MD5 of `"abc"`. */
    private val abcMd5 = "900150983cd24fb0d6963f7d28e17f72"

    /** SHA-256 of the empty string. */
    private val emptySha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private fun file(contents: String): File =
        temp.newFile().apply { writeText(contents) }

    // --- digest computation -------------------------------------------------

    @Test
    fun `sha256 matches the published vector`() {
        assertEquals(abcSha256, FileDigests.of(file("abc"), FileDigests.Algorithm.SHA_256))
    }

    @Test
    fun `md5 matches the published vector`() {
        assertEquals(abcMd5, FileDigests.of(file("abc"), FileDigests.Algorithm.MD5))
    }

    @Test
    fun `an empty file digests correctly`() {
        // A zero-length file is a real case: a model that only ships a projector or
        // config has legitimately empty components.
        assertEquals(emptySha256, FileDigests.of(file(""), FileDigests.Algorithm.SHA_256))
    }

    @Test
    fun `content larger than the read buffer is digested whole`() {
        // 1 MB buffer with 2.5 MB of data, so the read loop has to iterate several
        // times and handle a short final read.
        val big = "x".repeat(2_500_000)
        val digest = FileDigests.of(file(big), FileDigests.Algorithm.SHA_256)

        assertEquals(64, digest.length)
        assertEquals(
            FileDigests.of(file(big), FileDigests.Algorithm.SHA_256),
            digest,
        )
    }

    @Test
    fun `digest of identical content is stable across calls`() {
        val one = file("abc")
        val two = file("abc")
        assertEquals(
            FileDigests.of(one, FileDigests.Algorithm.SHA_256),
            FileDigests.of(two, FileDigests.Algorithm.SHA_256),
        )
    }

    // --- verification -------------------------------------------------------

    @Test
    fun `a matching sha256 verifies`() {
        val outcome = verifier.verify(file("abc"), ManifestEntry("m.gguf", 3, sha256 = abcSha256))

        assertEquals(
            VerificationOutcome.Verified(FileDigests.Algorithm.SHA_256, downgraded = false),
            outcome,
        )
    }

    @Test
    fun `a mismatched sha256 is reported as a mismatch not as an error`() {
        val wrong = "0".repeat(64)
        val outcome = verifier.verify(file("abc"), ManifestEntry("m.gguf", 3, sha256 = wrong))

        assertTrue(outcome is VerificationOutcome.Mismatched)
        assertEquals(wrong, (outcome as VerificationOutcome.Mismatched).expected)
        assertEquals(abcSha256, outcome.actual)
    }

    @Test
    fun `digest comparison ignores case and surrounding whitespace`() {
        // Manifests are hand-edited and copied between mirrors often enough that a
        // case difference should not fail an otherwise good 20 GB download.
        val outcome = verifier.verify(file("abc"), ManifestEntry("m.gguf", 3, sha256 = "  ${abcSha256.uppercase()} "))
        assertTrue(outcome is VerificationOutcome.Verified)
    }

    @Test
    fun `sha256 is preferred when both digests are published`() {
        val outcome = verifier.verify(
            file("abc"),
            ManifestEntry("m.gguf", 3, sha256 = abcSha256, md5 = "deadbeef"),
        )

        assertEquals(FileDigests.Algorithm.SHA_256, (outcome as VerificationOutcome.Verified).algorithm)
        assertFalse("a strong digest present means no downgrade", outcome.downgraded)
    }

    @Test
    fun `md5 is used only when no sha256 is published, and is flagged`() {
        // The PRD allows this for legacy ModelScope entries. The flag is not
        // cosmetic: anyone who can strip the sha256 field from a manifest would
        // otherwise silently downgrade every user's integrity check.
        val outcome = verifier.verify(file("abc"), ManifestEntry("m.gguf", 3, md5 = abcMd5))

        assertEquals(FileDigests.Algorithm.MD5, (outcome as VerificationOutcome.Verified).algorithm)
        assertTrue("the downgrade must be visible to the caller", outcome.downgraded)
    }

    @Test
    fun `a blank sha256 falls through to md5 rather than verifying against nothing`() {
        val outcome = verifier.verify(file("abc"), ManifestEntry("m.gguf", 3, sha256 = "   ", md5 = abcMd5))

        assertEquals(FileDigests.Algorithm.MD5, (outcome as VerificationOutcome.Verified).algorithm)
    }

    @Test
    fun `an entry with no usable digest is distinct from a mismatch`() {
        // These need different handling: one says the manifest is incomplete, the
        // other says the file is bad. Collapsing them would quarantine every model on
        // a mirror that publishes digests under an unrecognised key.
        val outcome = verifier.verify(file("abc"), ManifestEntry("m.gguf", 3))

        assertEquals(VerificationOutcome.NoUsableDigest("m.gguf"), outcome)
    }

    @Test
    fun `an unreadable file is not reported as corruption`() {
        // A truncated transfer is a retryable transfer failure, not a bad file.
        val missing = File(temp.root, "never-downloaded.gguf")
        val outcome = verifier.verify(missing, ManifestEntry("m.gguf", 3, sha256 = abcSha256))

        assertTrue("expected Unreadable, got $outcome", outcome is VerificationOutcome.Unreadable)
    }

    @Test
    fun `hasStrongDigest reflects what the entry can actually be checked against`() {
        assertTrue(ManifestEntry("m", 1, sha256 = abcSha256).hasStrongDigest)
        assertFalse(ManifestEntry("m", 1).hasStrongDigest)
        assertFalse(ManifestEntry("m", 1, sha256 = "  ").hasStrongDigest)
        assertFalse(ManifestEntry("m", 1, md5 = abcMd5).hasStrongDigest)
    }

    @Test
    fun `only sha256 counts as a strong algorithm`() {
        assertTrue(FileDigests.Algorithm.SHA_256.isStrong)
        assertFalse(FileDigests.Algorithm.MD5.isStrong)
    }
}

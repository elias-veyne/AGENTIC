package com.jarves.mh.model

import java.io.File

/**
 * Resolves every on-disk location the local core uses.
 *
 * ## Why this exists
 *
 * The PRD is explicit that "code never hardcodes paths", and the table it specifies is
 * not a single directory: weights live in external app storage when it is available
 * and fall back to internal storage when it is not, while staging and manifests sit
 * under cache and files respectively. Assembling that from string templates at each
 * call site is how a download ends up verified in one directory and loaded from
 * another.
 *
 * ## Why the roots are injected
 *
 * Resolving the real paths needs `Context`, which would make this untestable on the
 * JVM. [StorageRoots] is the seam: production supplies the Android implementation and
 * tests supply temporary directories. Every decision below - which root wins, whether
 * an SD card is offered, whether there is room - is therefore exercised in CI without
 * a device.
 */
class ModelPathResolver(
    private val roots: StorageRoots,
    /**
     * Free-space probe, in bytes at the given directory.
     *
     * Injected rather than called inline because [destinationFor] cannot otherwise be
     * tested. Two temp directories live on the same physical volume, so their real
     * free space is always nearly identical - a test can never make the primary volume
     * full while the secondary has room, which is the only case the method exists to
     * handle. Production uses [File.usableSpace].
     */
    private val freeSpaceOf: (File) -> Long = { it.usableSpace },
) {

    /** Primary and fallback locations for model weights. */
    val modelsDir: File get() = roots.externalFilesDir?.resolve("models") ?: roots.internalFilesDir.resolve("models")

    /** Root of all model-private files, used for manifests and sibling asset classes. */
    val filesDir: File get() = roots.externalFilesDir ?: roots.internalFilesDir

    /** Partial chunks awaiting verification. Wiped on a successful install. */
    val downloadStagingDir: File get() = roots.cacheDir.resolve("downloads")

    /** Files that failed checksum verification, kept for diagnosis rather than deleted. */
    val quarantineDir: File get() = roots.cacheDir.resolve("quarantine")

    /** Signed checksums, one JSON file per model. */
    fun manifestFile(modelId: String): File = filesDir.resolve("manifests/$modelId.json")

    /**
     * Directory holding one installed model: weights, tokenizer and configs together.
     *
     * Tokenizers and `config.json` deliberately sit beside the weights so a model is
     * one directory that can be moved to an SD card, or deleted, as a unit.
     */
    fun modelDir(modelId: String): File = modelsDir.resolve(modelId)

    /** The GGUF weight file. Named per the PRD's `<model_id>_<quant>.gguf` convention. */
    fun weightFile(modelId: String, quant: String): File = modelDir(modelId).resolve("${modelId}_$quant.gguf")

    /** Multimodal projector, loaded by llama.cpp for vision. */
    fun mmProjFile(modelId: String): File = modelDir(modelId).resolve("mm_proj.gguf")

    /** Draft head directory for speculative decoding. Phase 2; resolved now so paths exist. */
    fun draftDir(modelId: String): File = modelDir(modelId).resolve("draft")

    fun sttDir(): File = filesDir.resolve("asr")

    fun wakeWordDir(): File = filesDir.resolve("wake")

    fun ttsDir(): File = filesDir.resolve("tts")

    /**
     * Adopted or removable storage candidates, excluding the primary volume.
     *
     * Maps `Context.getExternalFilesDirs()`, whose index 0 is the primary emulated
     * volume - already covered by [modelsDir] - and whose later entries are adopted
     * SD cards that survive the primary volume being removed.
     */
    fun secondaryVolumes(): List<File> = roots.secondaryExternalFilesDirs

    /**
     * A volume that could hold [requiredBytes] for [modelId], best first.
     *
     * The primary volume is preferred even when a secondary would also fit, so models
     * land in one predictable place. A secondary is offered only when the primary
     * genuinely cannot take the file, which is what makes "Move to SD card" a
     * suggestion rather than the default.
     */
    fun destinationFor(modelId: String, requiredBytes: Long): File? {
        if (freeSpace(modelsDir) >= requiredBytes) return modelsDir
        return secondaryVolumes().firstOrNull { freeSpace(it) >= requiredBytes }
    }

    /**
     * Free bytes at [dir], walking up to the nearest existing ancestor.
     *
     * `StatFs` throws on a path that does not exist, and the models directory
     * frequently does not exist yet on a fresh install - precisely when the free-space
     * check is most needed. Walking up finds the volume root instead of failing.
     *
     * @return 0 when no ancestor can be measured, so callers fail closed.
     */
    fun freeSpace(dir: File): Long {
        var probe: File? = dir
        while (probe != null) {
            if (probe.exists()) {
                return runCatching { freeSpaceOf(probe) }.getOrDefault(0L)
            }
            probe = probe.parentFile
        }
        return 0L
    }

    /**
     * Whether [dir] has room for a download of [fileBytes].
     *
     * The PRD asks for 1.25x the file size: verification is streamed so 1.15x would
     * technically do, and the extra 0.1 covers filesystem block overhead. Callers
     * should not re-apply a margin on top.
     */
    fun hasRoomFor(dir: File, fileBytes: Long): Boolean =
        freeSpace(dir) >= storageHeadroom(fileBytes)

    /**
     * The bound a download must fit under: 1.25x [fileBytes].
     *
     * Exposed so the check and the number reported to the user cannot drift apart.
     */
    fun storageHeadroom(fileBytes: Long): Long =
        if (fileBytes <= 0) 0 else fileBytes + fileBytes / 4

    companion object {
        /**
         * Marker that keeps the media scanner out of the models tree.
         *
         * Without it, multi-gigabyte weights show up in the gallery and in other apps'
         * media pickers, and the user cannot tell what they are.
         */
        const val NO_MEDIA = ".nomedia"
    }
}

/**
 * The filesystem roots [ModelPathResolver] resolves against.
 *
 * Production implements this over `Context`; tests implement it over temporary
 * directories. [secondaryExternalFilesDirs] mirrors the tail of
 * `Context.getExternalFilesDirs()` - index 0 is excluded because it is the primary
 * volume.
 */
interface StorageRoots {
    /** `Context.getExternalFilesDirs(null)[0]`, or null when external storage is unavailable. */
    val externalFilesDir: File?

    /** `Context.filesDir`. Always present. */
    val internalFilesDir: File

    /** `Context.cacheDir`. Always present. */
    val cacheDir: File

    /** `Context.getExternalFilesDirs(null).drop(1)`. */
    val secondaryExternalFilesDirs: List<File>
}

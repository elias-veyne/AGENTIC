package com.jarves.mh.model.download

/**
 * A downloadable model, pinned.
 *
 * Every field here is part of the app's trust boundary, so all of it is a constant
 * rather than something fetched at runtime. A manifest that describes its own
 * download is not a manifest.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val url: String,
    /** Expected bytes, used for the progress bar and for detecting a truncated file. */
    val sizeBytes: Long,
    /** Lowercase hex SHA-256 of the file at [url]. */
    val sha256: String,
    /** Subdirectory under the models root; one model is one directory. */
    val directoryName: String = id,
) {
    init {
        require(sha256.length == 64 && sha256.all { it in "0123456789abcdef" }) {
            "sha256 must be 64 lowercase hex characters, got '$sha256'"
        }
        require(sizeBytes > 0) { "sizeBytes must be positive" }
    }
}

/**
 * The models this build will fetch.
 *
 * ## Why these particular files
 *
 * The MiniCPM-V-4.7-1B *checkpoint* on HuggingFace is 3.1 GB of safetensors, and
 * that is not what ships: llama.cpp loads GGUF, not safetensors. The equivalent
 * quantisation is far smaller, and the PRD's entry tier names IQ4_XS explicitly.
 *
 *   safetensors checkpoint   3.1 GB   not loadable by llama.cpp
 *   GGUF F16                2.0 GB   loadable, wasteful for an entry device
 *   GGUF Q8_0               1.1 GB   loadable
 *   GGUF IQ4_XS             639 MB   loadable - PRD entry-tier default, chosen
 *
 * Vision needs a second file, `mmproj-model-f16.gguf` (1.1 GB), which the bridge
 * does not load yet. It is therefore deliberately absent from this catalogue rather
 * than downloaded and then ignored.
 */
object ModelCatalog {

    const val ENTRY_MODEL_ID = "minicpm-v-4_7"

    private const val HF = "https://huggingface.co/openbmb/MiniCPM-V-4.7-1B-gguf/resolve/main"

    /**
     * The entry-tier model. Downloaded automatically on first launch.
     *
     * [sha256] is the Git LFS object id, read from the `X-Linked-ETag` response header
     * rather than measured by downloading 639 MB to hash it locally. Note that the
     * CDN's own `ETag` is a Xet content hash and is **not** the file digest - using it
     * would verify nothing. `X-Linked-ETag` is the sha256 of the file, and
     * `X-Linked-Size` (638856128) independently confirms the size.
     */
    val entryTier = ModelSpec(
        id = ENTRY_MODEL_ID,
        displayName = "MiniCPM-V 4.7 1B",
        url = "$HF/MiniCPM-V-4_7-1B-IQ4_XS.gguf",
        sizeBytes = 638_856_128L,
        sha256 = "7b97426ebceb33dbe264a606a96570939e82309309d74c378ddc566fe8647546",
    )

    fun byId(id: String): ModelSpec? = if (id == entryTier.id) entryTier else null
}

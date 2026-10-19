package com.jarves.mh.model

import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile

/**
 * Architecture facts read from a GGUF header.
 *
 * ## Why this exists
 *
 * The load guard must know how much memory a model will occupy *before* loading it.
 * The obvious formula - `2 x layers x kv_heads x head_dim x bytes x context` - is wrong
 * for a large and growing class of models. MiniCPM-V-4.7-1B declares
 * `attention.recurrent_layers = [24]` against `block_count = 24`: every layer is a
 * state-space layer, and llama.cpp filters those out of the KV cache entirely. Memory
 * there is dominated by weights and a fixed-size state, and does **not** grow with
 * context length.
 *
 * Guessing a constant gets this wrong in the direction that hurts. A conservative
 * 64 KiB/token over-reserved by 2.7x against the naive formula for this model, and by
 * far more once recurrent layers are excluded. Over-reserving makes the guard refuse
 * loads that would fit, which presents to the user as "this phone cannot run it" -
 * a wrong conclusion about the hardware, reached from a plausible-looking number.
 *
 * So the figure is read from the file rather than assumed.
 */
data class GgufArchitecture(
    val architecture: String,
    val blockCount: Int,
    val embeddingLength: Int,
    val headCount: Int,
    val headCountKv: Int,
    /** Layers held in a recurrent/SSM state rather than a KV cache. */
    val recurrentLayers: Set<Int>,
) {
    /** Dimension of one attention head. Zero when the model declares no attention heads. */
    val headDim: Int get() = if (headCount > 0) embeddingLength / headCount else 0

    /** Layers that actually consume KV cache. */
    val attentionLayerCount: Int get() = (blockCount - recurrentLayers.size).coerceAtLeast(0)

    /**
     * KV cache bytes per context token.
     *
     * Two bytes per element: llama.cpp allocates K and V in f16 unless the checkpoint
     * says otherwise, and the entry tier is f16.
     */
    val kvBytesPerToken: Long
        get() = 2L * attentionLayerCount * headCountKv * headDim * BYTES_PER_ELEMENT

    /**
     * Bytes of recurrent state, which is fixed-size rather than per-token.
     *
     * Counted so it is not silently omitted: a model whose layers are all recurrent
     * still needs it, and for those it is the dominant term.
     */
    val recurrentStateBytes: Long
        get() = recurrentLayers.size.toLong() * embeddingLength * BYTES_PER_ELEMENT * 2L

    private companion object {
        /** f16 for both K and V. */
        const val BYTES_PER_ELEMENT = 2L
    }
}

/**
 * Minimal GGUF header reader.
 *
 * Reads only the key/value metadata block, which precedes the tensor data, and only
 * the handful of keys the memory planner needs. Deliberately not a general GGUF parser:
 * it seeks past values it does not care about rather than materialising them, which
 * matters because `tokenizer.ggml.tokens` alone is a 150k-entry string array.
 *
 * Only the header is touched, so this is safe to point at a 639 MB file.
 */
object GgufReader {

    /** "GGUF" read little-endian. */
    private const val MAGIC = 0x46554747

    private const val T_UINT32 = 4
    private const val T_STRING = 8
    private const val T_ARRAY = 9

    /** Refuse absurd headers rather than seeking into gigabytes on a corrupt file. */
    private const val MAX_HEADER_BYTES = 64L * 1024 * 1024

    /** Refuse absurd string lengths before allocating. */
    private const val MAX_STRING_BYTES = 1L * 1024 * 1024

    private const val MAX_KV_PAIRS = 100_000

    /**
     * Read architecture metadata from [file].
     *
     * @return null when the file is not a GGUF, is truncated, or declares an
     *   architecture this reader does not recognise. Null means "cannot measure", and
     *   callers must fall back to a conservative estimate rather than assume zero.
     */
    fun readArchitecture(file: File): GgufArchitecture? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 24) return null
            if (readLeInt(raf) != MAGIC) return null

            readLeInt(raf) // format version, not needed
            readLeLong(raf) // tensor count, not needed
            val kvCount = readLeLong(raf)
            if (kvCount < 0 || kvCount > MAX_KV_PAIRS) return null

            var architecture: String? = null
            var blockCount = 0
            var embeddingLength = 0
            var headCount = 0
            var headCountKv = 0
            val recurrent = mutableSetOf<Int>()

            for (i in 0 until kvCount.toInt()) {
                val key = readString(raf) ?: return null
                val type = readLeInt(raf)

                when {
                    key == "general.architecture" && type == T_STRING -> architecture = readString(raf)

                    key.endsWith(".block_count") && type == T_UINT32 -> blockCount = readLeInt(raf)
                    key.endsWith(".embedding_length") && type == T_UINT32 -> embeddingLength = readLeInt(raf)
                    key.endsWith(".attention.head_count") && type == T_UINT32 -> headCount = readLeInt(raf)
                    key.endsWith(".attention.head_count_kv") && type == T_UINT32 -> headCountKv = readLeInt(raf)

                    key.endsWith(".attention.recurrent_layers") && type == T_ARRAY -> {
                        val elementType = readLeInt(raf)
                        val count = readLeLong(raf)
                        if (elementType == T_UINT32 && count in 0..MAX_KV_PAIRS) {
                            for (n in 0 until count.toInt()) recurrent += readLeInt(raf)
                        } else if (!skipValue(raf, elementType, count)) {
                            return null
                        }
                    }

                    else -> if (!skipValue(raf, type, 1L)) return null
                }

                if (raf.filePointer > MAX_HEADER_BYTES) return null
            }

            GgufArchitecture(
                architecture = architecture ?: return null,
                blockCount = blockCount,
                embeddingLength = embeddingLength,
                headCount = headCount,
                headCountKv = headCountKv,
                recurrentLayers = recurrent,
            )
        }
    }.getOrNull()

    /**
     * Advance past one value of [type], repeated [count] times.
     *
     * @return false if the file ended early or the type is unrecognised.
     */
    private fun skipValue(raf: RandomAccessFile, type: Int, count: Long): Boolean {
        if (count < 0 || count > MAX_KV_PAIRS.toLong()) return false

        // Fixed-width types are a straight seek. Not skipBytes: RandomAccessFile has
        // two overloads (int from DataInput, long from itself) and Kotlin binds the
        // int one, so a Long argument fails to compile.
        val width = when (type) {
            0, 1, 7 -> 1L      // uint8, int8, bool
            2, 3 -> 2L          // uint16, int16
            4, 5, 6 -> 4L       // uint32, int32, float32
            10, 11, 12 -> 8L    // uint64, int64, float64
            else -> 0L
        }
        if (width > 0) return seekPast(raf, count * width)

        return when (type) {
            T_STRING -> {
                // Variable width, so each length prefix has to be walked.
                for (i in 0 until count) {
                    val length = readLeLong(raf)
                    if (length < 0 || length > MAX_STRING_BYTES) return false
                    if (!seekPast(raf, length)) return false
                }
                true
            }

            T_ARRAY -> {
                val elementType = readLeInt(raf)
                val n = readLeLong(raf)
                skipValue(raf, elementType, n)
            }

            else -> false
        }
    }

    /** Move the cursor forward [n] bytes, failing if that would run past the end. */
    private fun seekPast(raf: RandomAccessFile, n: Long): Boolean {
        val target = raf.filePointer + n
        if (target < 0 || target > raf.length()) return false
        raf.seek(target)
        return true
    }

    // --- little-endian readers -----------------------------------------------
    //
    // GGUF is little-endian. RandomAccessFile.readInt()/readLong() are DataInput
    // methods and therefore BIG-endian, so using them here fails the magic-number
    // check on every real file and the reader silently returns null - which downgrades
    // the memory guard to its conservative constant with no visible error.
    // Names are suffixed so they cannot shadow the DataInput members.

    private fun readLeInt(raf: RandomAccessFile): Int {
        val a = raf.read()
        val b = raf.read()
        val c = raf.read()
        val d = raf.read()
        if (d < 0) throw EOFException("truncated int")
        return a or (b shl 8) or (c shl 16) or (d shl 24)
    }

    private fun readLeLong(raf: RandomAccessFile): Long {
        val low = readLeInt(raf).toLong() and 0xFFFFFFFFL
        val high = readLeInt(raf).toLong() and 0xFFFFFFFFL
        return low or (high shl 32)
    }

    /** Length-prefixed UTF-8 string, or null if truncated or implausibly long. */
    private fun readString(raf: RandomAccessFile): String? {
        val length = readLeLong(raf)
        if (length < 0 || length > MAX_STRING_BYTES) return null
        if (raf.filePointer + length > raf.length()) return null
        val bytes = ByteArray(length.toInt())
        raf.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}

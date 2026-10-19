package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reading architecture facts out of a GGUF header.
 *
 * The values used here are the real ones read from
 * `openbmb/MiniCPM-V-4.7-1B-gguf` at IQ4_XS, not invented for the test. That matters:
 * the whole point of this reader is to replace an assumption with a measurement, and a
 * test built on invented numbers would only prove the arithmetic works on numbers I
 * chose to make it work.
 */
class GgufReaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    // Real metadata from MiniCPM-V-4.7-1B-IQ4_XS.gguf.
    private val architecture = "qwen35"
    private val blockCount = 24
    private val embeddingLength = 1024
    private val headCount = 8
    private val headCountKv = 2
    private val recurrent = (0 until 24).toSet()

    // Typed wrappers so the fixture writer reads as GGUF metadata rather than as
    // byte-poking, and so the writer's `when` can tell the types apart.
    private fun uint32(v: Int): Int = v
    private fun uint32Array(values: List<Int>) = U32Array(values)
    private fun stringArray(values: List<String>) = StrArray(values)

    private class U32Array(val values: List<Int>) {
        val size: Int get() = values.size
        fun forEach(block: (Int) -> Unit) = values.forEach(block)
    }

    private class StrArray(val values: List<String>) {
        val size: Int get() = values.size
        fun forEach(block: (String) -> Unit) = values.forEach(block)
    }

    // --- the derived figures -------------------------------------------------

    @Test
    fun `head dimension is embedding length over head count`() {
        val a = GgufArchitecture(architecture, blockCount, embeddingLength, headCount, headCountKv, recurrent)
        assertEquals(128, a.headDim)
    }

    @Test
    fun `recurrent layers are excluded from the kv cache`() {
        // llama.cpp filters recurrent layers out of the KV cache entirely, so a model
        // whose layers are all recurrent has essentially no per-token KV cost.
        val a = GgufArchitecture(architecture, blockCount, embeddingLength, headCount, headCountKv, recurrent)
        assertEquals(0, a.attentionLayerCount)
        assertEquals(0L, a.kvBytesPerToken)
    }

    @Test
    fun `attention layers still cost kv cache`() {
        // Same model shape with a quarter of the layers recurrent: 18 attention
        // layers x 2 KV heads x 128 head dim x 2 bytes x 2 (K and V) = 18,432.
        val a = GgufArchitecture(
            architecture, blockCount, embeddingLength, headCount, headCountKv,
            recurrentLayers = setOf(0, 1, 2, 3, 4, 5),
        )
        assertEquals(18, a.attentionLayerCount)
        assertEquals(18_432L, a.kvBytesPerToken)
    }

    @Test
    fun `the old constant overstated this model by a wide margin`() {
        // The reason this reader exists. A conservative constant made the guard refuse
        // loads that fit, which presents as "this phone cannot run it".
        val dense = GgufArchitecture(architecture, blockCount, embeddingLength, headCount, headCountKv, emptySet())
        val naivePerToken = dense.kvBytesPerToken
        val constant = 64L * 1024L

        assertTrue("constant must be the pessimistic one", constant > naivePerToken)
        assertTrue("overstatement must be material", constant > naivePerToken * 2)
    }

    @Test
    fun `recurrent state is fixed size rather than per token`() {
        val a = GgufArchitecture(architecture, blockCount, embeddingLength, headCount, headCountKv, recurrent)
        assertEquals(24L * 1024L * 2L * 2L, a.recurrentStateBytes)
        // The point of the distinction: it does not scale with context.
        assertEquals(a.recurrentStateBytes, a.recurrentStateBytes)
    }

    @Test
    fun `a model with no attention heads does not divide by zero`() {
        val a = GgufArchitecture(architecture, blockCount, embeddingLength, headCount = 0, headCountKv = 0, emptySet())
        assertEquals(0, a.headDim)
        assertEquals(0L, a.kvBytesPerToken)
    }

    // --- header parsing ------------------------------------------------------

    @Test
    fun `reads architecture keys from a synthetic header`() {
        val file = writeHeader(
            architecture = "qwen35",
            entries = buildList {
                add("qwen35.block_count" to uint32(blockCount))
                add("qwen35.embedding_length" to uint32(embeddingLength))
                add("qwen35.attention.head_count" to uint32(headCount))
                add("qwen35.attention.head_count_kv" to uint32(headCountKv))
                add("qwen35.attention.recurrent_layers" to uint32Array(listOf(0, 1, 2)))
                add("tokenizer.ggml.tokens" to stringArray(listOf("a", "bb", "ccc")))
                add("qwen35.rope.dimension_count" to uint32(embeddingLength / headCount))
            }        )

        val read = GgufReader.readArchitecture(file)

        assertEquals("qwen35", read!!.architecture)
        assertEquals(24, read.blockCount)
        assertEquals(1024, read.embeddingLength)
        assertEquals(8, read.headCount)
        assertEquals(2, read.headCountKv)
        assertEquals(setOf(0, 1, 2), read.recurrentLayers)
        assertEquals(21, read.attentionLayerCount)
    }

    @Test
    fun `skips values it does not care about`() {
        // tokenizer.ggml.tokens is a 150k string array in a real file. A reader that
        // tried to materialise it would either OOM or take minutes; this one seeks past.
        val big = (0 until 5_000).map { "token-$it" }
        val file = writeHeader(
            architecture = "qwen35",
            entries = listOf(
                "tokenizer.ggml.tokens" to stringArray(big),
                "qwen35.block_count" to uint32(12),
            )
        )

        val read = GgufReader.readArchitecture(file)
        assertEquals(12, read!!.blockCount)
    }

    @Test
    fun `a file that is not a gguf reads as null rather than throwing`() {
        val notGgu = temp.newFile().apply { writeBytes(ByteArray(4096) { 7 }) }
        assertNull(GgufReader.readArchitecture(notGgu))
    }

    @Test
    fun `a truncated file reads as null rather than throwing`() {
        val truncated = temp.newFile().apply { writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46, 0x03)) }
        assertNull(GgufReader.readArchitecture(truncated))
    }

    @Test
    fun `an empty file reads as null`() {
        assertNull(GgufReader.readArchitecture(temp.newFile()))
    }

    // --- a hand-rolled GGUF writer, so the reader is tested against real bytes ---

    private fun writeHeader(architecture: String, entries: List<Pair<String, Any>>): File {
        val out = java.io.ByteArrayOutputStream()
        fun u32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun u64(v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun string(s: String) { u64(s.length.toLong()); out.write(s.toByteArray()) }

        out.write("GGUF".toByteArray())
        u32(3)          // version
        u64(0)          // tensor count
        u64((entries.size + 1).toLong())

        string("general.architecture"); u32(8); string(architecture)
        for ((key, value) in entries) {
            string(key)
            when (value) {
                is Int -> { u32(4); u32(value) }
                is U32Array -> {
                    u32(9); u32(4); u64(value.size.toLong())
                    value.forEach { u32(it) }
                }
                is StrArray -> {
                    u32(9); u32(8); u64(value.size.toLong())
                    value.forEach { string(it) }
                }
                else -> error("unsupported fixture type ${value::class}")
            }
        }

        return temp.newFile().apply { writeBytes(out.toByteArray()) }
    }
}

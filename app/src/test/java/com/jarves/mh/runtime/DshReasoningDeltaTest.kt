package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the duplicated "Think" text reported from the app: every
 * phrase repeated, and two copies of a sentence interleaved at different offsets.
 *
 * The bridge re-sent the ENTIRE accumulated reasoning block on every delta while
 * the UI appended each event, so the first delta's text appeared once per later
 * delta. These tests pin the contract the UI now relies on: a reasoning delta
 * carries only what is NEW.
 */
class DshReasoningDeltaTest {

    private fun line(json: String) = json.trim()

    private fun delta(turn: Int, step: Int, index: Int, text: String) = line(
        """{"method":"session.event","params":{"sessionId":"s","event":{"type":"assistant/chunk",
        "data":{"turn":$turn,"step":$step,"chunk":{"index":$index,"type":"reasoning-delta",
        "text":${q(text)}}}}}}""",
    )

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun blockEndReasoning(turn: Int, step: Int, index: Int, text: String) = line(
        """{"method":"session.event","params":{"sessionId":"s","event":{"type":"assistant/chunk",
        "data":{"turn":$turn,"step":$step,"chunk":{"index":$index,"type":"block-end",
        "block":{"type":"reasoning","text":${q(text)}}}}}}""",
    )

    @Test
    fun `each reasoning delta carries only new text`() {
        val parser = DshSdkProtocolParser("s")
        val first = parser.parseLine(delta(1, 0, 0, "user wants me to "))
        val second = parser.parseLine(delta(1, 0, 0, "find the bug"))

        val a = first as DshSdkProtocolEvent.Reasoning
        val b = second as DshSdkProtocolEvent.Reasoning

        // The first delta opens the block and carries its own text.
        assertEquals("user wants me to ", a.text)
        assertTrue(a.startsNewBlock)

        // The second must NOT repeat the first delta's text, or the UI appends it twice.
        assertEquals("find the bug", b.text)
        assertEquals(false, b.startsNewBlock)
    }

    @Test
    fun `consecutive deltas concatenate to the block without repetition`() {
        val parser = DshSdkProtocolParser("s")
        val parts = listOf("Let me ", "look at ", "the logs.").map {
            (parser.parseLine(delta(1, 0, 0, it)) as DshSdkProtocolEvent.Reasoning).text
        }
        assertEquals("Let me look at the logs.", parts.joinToString(""))
    }

    @Test
    fun `many deltas do not grow quadratically`() {
        val parser = DshSdkProtocolParser("s")
        val word = "alpha "
        var total = 0
        repeat(50) {
            val e = parser.parseLine(delta(1, 0, 0, word)) as DshSdkProtocolEvent.Reasoning
            total += e.text.length
        }
        // Previously every delta re-sent the whole buffer: 50 * sum(1..50) characters.
        assertEquals(50 * word.length, total)
    }

    @Test
    fun `block-end emits only the suffix deltas did not deliver`() {
        val parser = DshSdkProtocolParser("s")
        parser.parseLine(delta(1, 0, 0, "hello "))
        val end = parser.parseLine(blockEndReasoning(1, 0, 0, "hello world")) as DshSdkProtocolEvent.Reasoning

        // "hello " was already streamed; only "world" is new.
        assertEquals("world", end.text)
        assertTrue(end.isFinal)
    }

    @Test
    fun `a replayed delta after block-end is not re-emitted`() {
        val parser = DshSdkProtocolParser("s")
        parser.parseLine(delta(1, 0, 0, "done"))
        parser.parseLine(blockEndReasoning(1, 0, 0, "done"))
        // Same blockId reused by a retry: the buffer was cleared, so this opens a new
        // block rather than silently duplicating "done" into the old one.
        val replay = parser.parseLine(delta(1, 0, 0, "done")) as DshSdkProtocolEvent.Reasoning
        assertEquals("done", replay.text)
    }
}

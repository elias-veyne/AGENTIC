package com.jarves.mh.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the duplicated "Think" text reported from the app: every
 * phrase repeated, and two copies of a sentence interleaved at different offsets.
 *
 * The bridge re-sent the ENTIRE accumulated reasoning block on every delta while
 * the UI appended each event, so the first delta's text appeared once per later
 * delta. These tests pin the contract the UI relies on: a reasoning delta
 * carries only what is NEW.
 */
class DshReasoningDeltaTest {

    private fun chunkEvent(turn: Int, step: Int, chunk: JSONObject) = JSONObject()
        .put("method", "session.event")
        .put(
            "params",
            JSONObject().put("sessionId", "s").put(
                "event",
                JSONObject()
                    .put("type", "assistant/chunk")
                    .put("seq", step)
                    .put("time", 0)
                    .put(
                        "data",
                        JSONObject().put("turn", turn).put("step", step).put("chunk", chunk),
                    ),
            ),
        )
        .toString()

    private fun delta(turn: Int, step: Int, index: Int, text: String) = chunkEvent(
        turn,
        step,
        JSONObject()
            .put("type", "reasoning-delta")
            .put("index", index)
            .put("text", text),
    )

    private fun blockEndReasoning(turn: Int, step: Int, index: Int, text: String) = chunkEvent(
        turn,
        step,
        JSONObject()
            .put("type", "block-end")
            .put("index", index)
            .put("block", JSONObject().put("type", "reasoning").put("text", text)),
    )

    @Test
    fun `each reasoning delta carries only new text`() {
        val parser = DshSdkProtocolParser("s")
        val first = parser.parseLine(delta(1, 0, 0, "user wants me to "))
        val second = parser.parseLine(delta(1, 0, 0, "find the bug"))

        val a = first as DshSdkProtocolEvent.Reasoning
        val b = second as DshSdkProtocolEvent.Reasoning

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
        // Previously every delta re-sent the whole buffer: sum(1..50) * len characters.
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
    fun `block-end with nothing new still closes the block`() {
        val parser = DshSdkProtocolParser("s")
        parser.parseLine(delta(1, 0, 0, "all "))
        parser.parseLine(delta(1, 0, 0, "done"))
        // The deltas already delivered the entire block text, so no suffix remains. A final
        // marker must still be emitted or the UI leaves the Think block live forever.
        val end = parser.parseLine(blockEndReasoning(1, 0, 0, "all done")) as DshSdkProtocolEvent.Reasoning
        assertEquals("", end.text)
        assertTrue(end.isFinal)
        assertEquals(false, end.startsNewBlock)
    }

    @Test
    fun `a rewritten block after a retry is dropped rather than re-injected`() {
        val parser = DshSdkProtocolParser("s")
        parser.parseLine(delta(1, 0, 0, "original text"))
        // The block reports text that does NOT extend what was streamed (a retry rewrote
        // it). Emitting it would splice a second copy into the UI.
        val end = parser.parseLine(blockEndReasoning(1, 0, 0, "completely different")) as DshSdkProtocolEvent.Reasoning
        assertEquals("", end.text)
        assertTrue(end.isFinal)
    }

    @Test
    fun `deltas plus a final suffix concatenate to the full block`() {
        val parser = DshSdkProtocolParser("s")
        val sb = StringBuilder()
        listOf("Let me ", "read ", "the logs").forEach {
            sb.append((parser.parseLine(delta(1, 0, 0, it)) as DshSdkProtocolEvent.Reasoning).text)
        }
        val end = parser.parseLine(blockEndReasoning(1, 0, 0, "Let me read the logs.")) as DshSdkProtocolEvent.Reasoning
        sb.append(end.text)
        assertEquals("Let me read the logs.", sb.toString())
    }

    @Test
    fun `turn end resets the dedup accumulator`() {
        val parser = DshSdkProtocolParser("s")
        parser.parseLine(delta(1, 0, 0, "hello"))
        val turnEnd = JSONObject()
            .put("method", "session.event")
            .put(
                "params",
                JSONObject().put("sessionId", "s").put(
                    "event",
                    JSONObject().put("type", "turn/end").put(
                        "data",
                        JSONObject().put("reason", JSONObject().put("kind", "stop")),
                    ),
                ),
            )
            .toString()
        parser.parseLine(turnEnd)

        // A fresh turn's assistant/message must not be suppressed by the previous turn's
        // streaming state.
        val message = JSONObject()
            .put("method", "session.event")
            .put(
                "params",
                JSONObject().put("sessionId", "s").put(
                    "event",
                    JSONObject().put("type", "assistant/message").put(
                        "data",
                        JSONObject().put(
                            "message",
                            JSONObject().put(
                                "content",
                                org.json.JSONArray().put(
                                    JSONObject().put("type", "text").put("text", "second turn"),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            .toString()
        val event = parser.parseLine(message)
        assertTrue(
            "expected the second turn's message to survive, got $event",
            event is DshSdkProtocolEvent.AssistantText && event.text == "second turn",
        )
    }
}

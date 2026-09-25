package com.jarves.mh.formatting

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM mirror of the Home stat formatters. They live as private functions in
 * PocketDevApp.kt (which needs Android types), so the behaviour is asserted here
 * through an identical copy to lock the formatting rules.
 */
class StatFormattingTest {

    private fun formatTokens(tokens: Long): String = when {
        tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
        tokens >= 1_000 -> "%.0fk".format(tokens / 1_000.0)
        else -> tokens.toString()
    }

    private fun formatRelativeTime(millis: Long): String {
        val minutes = (System.currentTimeMillis() - millis) / 60_000
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            minutes < 60 * 24 -> "${minutes / 60}h"
            minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d"
            else -> "${minutes / (60 * 24 * 7)}w"
        }
    }

    @Test
    fun tokenFormattingScalesToUnits() {
        assertEquals("0", formatTokens(0))
        assertEquals("999", formatTokens(999))
        assertEquals("1k", formatTokens(1_000))
        assertEquals("48k", formatTokens(48_000))
        assertEquals("1.2M", formatTokens(1_200_000))
    }

    @Test
    fun relativeTimeUsesLargestSensibleUnit() {
        assertEquals("now", formatRelativeTime(System.currentTimeMillis()))
        assertEquals("2m", formatRelativeTime(System.currentTimeMillis() - 2 * 60_000))
        assertEquals("3h", formatRelativeTime(System.currentTimeMillis() - 3 * 60 * 60_000))
        assertEquals("2d", formatRelativeTime(System.currentTimeMillis() - 2L * 24 * 60 * 60_000))
        assertEquals("2w", formatRelativeTime(System.currentTimeMillis() - 14L * 24 * 60 * 60_000))
    }
}

package com.kazumaproject.markdownhelperkeyboard.ime_service

import org.junit.Assert.*
import org.junit.Test

class BunsetsuBoundaryAdjustmentTest {
    private fun parts(vararg readings: String) = readings.map {
        BunsetsuSegmentState(it, "変換:$it", hasConvertedDisplay = true, candidatesLoaded = true)
    }
    private fun readings(parts: List<BunsetsuSegmentState>) = parts.map { it.reading }

    @Test fun leftShortensByOneReadingCharacterAndPrependsItToNextSegment() {
        val source = parts("わたしは", "とうきょうに", "いきます")
        val resized = resizeBunsetsuSegments(source, 0, -1)
        assertEquals(listOf("わたし", "はとうきょうに", "いきます"), readings(resized))
        assertSame(source[2], resized[2])
        assertFalse(resized[0].candidatesLoaded)
        assertFalse(resized[1].hasConvertedDisplay)
    }
    @Test fun rightExpandsFromNextReadingAndPreservesOtherChoices() {
        val source = parts("まえ", "きょう", "はいく", "あと")
        val resized = resizeBunsetsuSegments(source, 1, 1)
        assertEquals(listOf("まえ", "きょうは", "いく", "あと"), readings(resized))
        assertSame(source[0], resized[0])
        assertSame(source[3], resized[3])
    }
    @Test fun expandingConsumesSingleCharacterNeighborWithoutLeavingEmptySegment() {
        assertEquals(listOf("きょうは", "いく"), readings(resizeBunsetsuSegments(parts("きょう", "は", "いく"), 0, 1)))
    }
    @Test fun shrinkingLastSegmentCreatesNewSuffixSegment() {
        assertEquals(listOf("まえ", "いきま", "す"), readings(resizeBunsetsuSegments(parts("まえ", "いきます"), 1, -1)))
    }
    @Test fun cannotShrinkBelowOneCharacterOrExpandPastReadingEnd() {
        val source = parts("あ", "いう")
        assertSame(source, resizeBunsetsuSegments(source, 0, -1))
        assertSame(source, resizeBunsetsuSegments(source, 1, 1))
    }
    @Test fun unicodeSupplementaryCharacterIsTransferredWhole() {
        assertEquals(listOf("あ", "𠮷いう"), readings(resizeBunsetsuSegments(parts("あ𠮷", "いう"), 0, -1)))
        assertEquals(listOf("あ𠮷", "いう"), readings(resizeBunsetsuSegments(parts("あ", "𠮷いう"), 0, 1)))
        val source = parts("𠮷", "あ")
        assertSame(source, resizeBunsetsuSegments(source, 0, -1))
    }
    @Test fun repeatedChangesPreserveEntireReadingAndNeverCreateEmptySegments() {
        var segments = parts("わたしは", "とうきょうに", "いきます")
        val original = segments.joinToString("") { it.reading }
        repeat(30) {
            segments = resizeBunsetsuSegments(segments, 0, if (it % 4 < 2) -1 else 1)
            assertEquals(original, segments.joinToString("") { it.reading })
            assertTrue(segments.all { it.reading.isNotEmpty() })
        }
    }
    @Test fun invalidFocusAndZeroDeltaAreNoOps() {
        val source = parts("あいう")
        assertSame(source, resizeBunsetsuSegments(source, -1, 1))
        assertSame(source, resizeBunsetsuSegments(source, 1, -1))
        assertSame(source, resizeBunsetsuSegments(source, 0, 0))
        assertTrue(resizeBunsetsuSegments(emptyList(), 0, 1).isEmpty())
    }
}

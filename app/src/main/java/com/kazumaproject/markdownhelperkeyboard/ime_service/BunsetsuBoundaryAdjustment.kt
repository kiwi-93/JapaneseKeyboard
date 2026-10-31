package com.kazumaproject.markdownhelperkeyboard.ime_service

/** Move the focused segment's right boundary by one reading character, without losing its suffix. */
internal fun resizeBunsetsuSegments(
    segments: List<BunsetsuSegmentState>,
    focusedIndex: Int,
    delta: Int,
): List<BunsetsuSegmentState> {
    val focused = segments.getOrNull(focusedIndex) ?: return segments
    if (delta == 0 || focused.reading.isEmpty()) return segments
    val next = segments.getOrNull(focusedIndex + 1)
    val focusedReading: String
    val nextReading: String
    if (delta < 0) {
        val boundary = focused.reading.offsetByCodePoints(focused.reading.length, -1)
        if (boundary == 0) return segments
        focusedReading = focused.reading.substring(0, boundary)
        nextReading = focused.reading.substring(boundary) + next?.reading.orEmpty()
    } else {
        val reading = next?.reading?.takeIf { it.isNotEmpty() } ?: return segments
        val boundary = reading.offsetByCodePoints(0, 1)
        focusedReading = focused.reading + reading.substring(0, boundary)
        nextReading = reading.substring(boundary)
    }
    // Only the affected pair needs new candidates; choices in all other segments survive.
    return buildList {
        addAll(segments.take(focusedIndex))
        add(BunsetsuSegmentState(focusedReading, focusedReading))
        if (nextReading.isNotEmpty()) add(BunsetsuSegmentState(nextReading, nextReading))
        addAll(segments.drop(focusedIndex + if (next == null) 1 else 2))
    }
}

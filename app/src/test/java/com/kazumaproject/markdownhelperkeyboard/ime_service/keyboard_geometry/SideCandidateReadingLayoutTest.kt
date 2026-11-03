package com.kazumaproject.markdownhelperkeyboard.ime_service.keyboard_geometry

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.kazumaproject.markdownhelperkeyboard.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SideCandidateReadingLayoutTest {
    private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MarkdownKeyboard)
    private fun show(view: SideCandidateReadingView, text: String, cursor: Int = text.length, width: Int = 360,
        height: Int? = 100, size: Int = 32) {
        view.setReading(text, textSizeSp = size, cursorOffset = cursor)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height ?: 0, if (height == null) View.MeasureSpec.UNSPECIFIED else View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    private fun assertCentered(view: SideCandidateReadingView) {
        assertEquals(Gravity.CENTER_HORIZONTAL, view.readingTextView.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
        assertEquals(0, view.scrollX)
        val text = view.readingTextView
        val line = text.layout
        val textCenter = text.left + text.compoundPaddingLeft + (line.getLineLeft(0) + line.getLineRight(0)) / 2f
        assertEquals(view.width / 2f, textCenter, 2f)
    }
    private fun assertCursorVisible(view: SideCandidateReadingView) {
        val text = view.readingTextView
        val x = text.left + text.compoundPaddingLeft + text.layout.getPrimaryHorizontal(view.cursorOffset) - view.scrollX
        assertTrue("cursor x=$x scroll=${view.scrollX}", x >= view.paddingLeft - 1f)
        assertTrue("cursor x=$x width=${view.width}", x <= view.width - view.paddingRight + 1f)
    }

    @Test fun shortReadingsAreCenteredAtTheSelectedSizeAndDeletionRestoresThem() {
        val view = SideCandidateReadingView(context)
        show(view, "こんにちは")
        val normal = view.readingTextView.textSize
        assertEquals(32 * context.resources.displayMetrics.scaledDensity, normal, .1f)
        assertCentered(view)
        show(view, "こんにちは".repeat(50))
        assertEquals(12 * context.resources.displayMetrics.scaledDensity, view.readingTextView.textSize, .1f)
        assertTrue(view.scrollX > 0)
        assertCursorVisible(view)
        show(view, "こんにちは")
        assertEquals(normal, view.readingTextView.textSize, .1f)
        assertCentered(view)
    }

    @Test fun mediumReadingsShrinkInStepsAndRemainCentered() {
        val view = SideCandidateReadingView(context)
        show(view, "あ")
        val normal = view.readingTextView.textSize
        val glyphWidth = view.readingTextView.paint.measureText("あ")
        val count = kotlin.math.ceil(360f / glyphWidth * 1.3f).toInt()
        show(view, "あ".repeat(count))
        val smaller = view.readingTextView.textSize
        assertTrue("normal=$normal smaller=$smaller", smaller < normal)
        assertTrue(smaller > 12 * context.resources.displayMetrics.scaledDensity)
        assertCentered(view)
        show(view, "あ".repeat(count))
        assertEquals(smaller, view.readingTextView.textSize, .01f)
        assertCentered(view)
    }

    @Test fun intermediateCursorPositionsAreFollowedWithoutJumpingToTheEnd() {
        val view = SideCandidateReadingView(context)
        val text = "きょうはよいてんきです".repeat(50)
        show(view, text)
        val endScroll = view.scrollX
        assertTrue(endScroll > 0)
        assertCursorVisible(view)
        show(view, text, cursor = 12)
        assertEquals(12, view.cursorOffset)
        assertTrue(view.scrollX < endScroll)
        assertCursorVisible(view)
        val nearStart = view.scrollX
        show(view, text + "あ", cursor = 13)
        assertTrue(view.scrollX < endScroll)
        assertCursorVisible(view)
        assertEquals(nearStart, view.scrollX)
        show(view, text, cursor = 0)
        assertEquals(0, view.scrollX)
        assertCursorVisible(view)
        show(view, text)
        assertCursorVisible(view)
        view.scrollTo(0, 0)
        show(view, text)
        assertEquals("An unchanged candidate refresh preserves manual reading scroll", 0, view.scrollX)
        show(view, text, cursor = text.length / 2, width = 240)
        assertCursorVisible(view)
    }

    @Test fun adaptiveShrinkingDoesNotChangeTheStripHeightOrSplitSurrogatePairs() {
        val view = SideCandidateReadingView(context)
        show(view, "あ", height = null)
        val normalHeight = view.height
        show(view, "あ".repeat(200), height = null)
        assertEquals(normalHeight, view.height)
        show(view, "あ", height = null)
        assertEquals(normalHeight, view.height)
        show(view, "あ".repeat(200), height = 20)
        assertTrue(view.readingTextView.paint.fontMetrics.bottom - view.readingTextView.paint.fontMetrics.top <= 16.1f)
        show(view, "😀あ", cursor = 1)
        assertEquals(0, view.cursorOffset)
        assertCentered(view)
        assertCursorVisible(view)
    }
}

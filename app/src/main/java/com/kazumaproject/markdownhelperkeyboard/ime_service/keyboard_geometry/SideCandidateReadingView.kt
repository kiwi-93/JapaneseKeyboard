package com.kazumaproject.markdownhelperkeyboard.ime_service.keyboard_geometry

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.kazumaproject.core.ui.font.KeyboardFontApplicator

/** Read-only text in the strip vacated by side candidates. Long readings remain scrollable. */
class SideCandidateReadingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    HorizontalScrollView(context, attrs) {
    private var scrollToEndRequested = false
    internal val readingTextView = TextView(context).apply {
        setSingleLine(true)
        includeFontPadding = true
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        isFocusable = false
        setTextIsSelectable(false)
    }

    init {
        isFillViewport = true
        isSmoothScrollingEnabled = false
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        isClickable = true
        val density = resources.displayMetrics.density
        setPadding((8 * density).toInt(), (2 * density).toInt(), (8 * density).toInt(), (2 * density).toInt())
        addView(readingTextView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        visibility = GONE
    }

    fun setReading(reading: String, color: Int? = null) {
        val changed = readingTextView.text.toString() != reading
        if (changed) {
            readingTextView.text = reading
            scrollToEndRequested = reading.isNotEmpty()
        }
        if (color != null) readingTextView.setTextColor(color)
        KeyboardFontApplicator.apply(readingTextView, KeyboardFontApplicator.processSnapshot)
        fitTextHeight()
        visibility = if (reading.isEmpty()) GONE else VISIBLE
        if (reading.isEmpty()) {
            scrollToEndRequested = false
            scrollTo(0, 0)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val oldEnd = (readingTextView.right + paddingRight - width).coerceAtLeast(0)
        val keepAtEnd = scrollToEndRequested || scrollX >= oldEnd
        super.onLayout(changed, left, top, right, bottom)
        if (visibility == VISIBLE && keepAtEnd) {
            scrollTo((readingTextView.right + paddingRight - width).coerceAtLeast(0), 0)
        }
        scrollToEndRequested = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitTextHeight()
    }

    private fun fitTextHeight() {
        val requested = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics)
        val available = height - paddingTop - paddingBottom
        val metrics = readingTextView.paint.fontMetrics
        val lineRatio = (metrics.bottom - metrics.top) / readingTextView.textSize.coerceAtLeast(1f)
        val target = if (available > 0 && lineRatio > 0f) minOf(requested, available / lineRatio) else requested
        readingTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, target)
    }
}

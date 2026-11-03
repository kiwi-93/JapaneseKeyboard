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
    private var requestedTextSizeSp = SideCandidateReadingSizing.DEFAULT_SP
    internal val readingTextView = TextView(context).apply {
        setSingleLine(true)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, SideCandidateReadingSizing.DEFAULT_SP.toFloat())
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

    fun setReading(reading: String, color: Int? = null, textSizeSp: Int = SideCandidateReadingSizing.DEFAULT_SP) {
        val size = textSizeSp.coerceIn(SideCandidateReadingSizing.MIN_SP, SideCandidateReadingSizing.MAX_SP)
        if (requestedTextSizeSp != size) { requestedTextSizeSp = size; requestLayout() }
        val changed = readingTextView.text.toString() != reading
        if (changed) {
            readingTextView.text = reading
            scrollToEndRequested = reading.isNotEmpty()
        }
        if (color != null) readingTextView.setTextColor(color)
        KeyboardFontApplicator.apply(readingTextView, KeyboardFontApplicator.processSnapshot)
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Fit against the incoming constraint, so increasing the setting can also grow a
        // wrap-content strip rather than being capped by its previous, smaller height.
        val requested = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, requestedTextSizeSp.toFloat(), resources.displayMetrics)
        val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
            else MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
        val metrics = readingTextView.paint.fontMetrics
        val lineRatio = (metrics.bottom - metrics.top) / readingTextView.textSize.coerceAtLeast(1f)
        val target = if (available > 0 && lineRatio > 0f) minOf(requested, available / lineRatio) else requested
        readingTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, target)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

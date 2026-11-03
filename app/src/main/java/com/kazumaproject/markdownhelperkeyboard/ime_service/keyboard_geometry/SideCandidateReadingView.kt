package com.kazumaproject.markdownhelperkeyboard.ime_service.keyboard_geometry

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.kazumaproject.core.ui.font.KeyboardFontApplicator
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Read-only preedit: centered while it fits, then bounded shrinking and cursor-following scroll. */
class SideCandidateReadingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    HorizontalScrollView(context, attrs) {
    private var followCursorRequested = false
    private var overflowing = false
    private var requestedTextSizeSp = SideCandidateReadingSizing.DEFAULT_SP
    private val measurePaint = TextPaint()
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    internal var cursorOffset = 0
        private set

    internal val readingTextView = object : TextView(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Short text needs no end marker, but an intermediate cursor must remain visible.
            if (!overflowing && cursorOffset == text.length) return
            val lineLayout = layout ?: return
            val offset = cursorOffset.coerceIn(0, lineLayout.text.length)
            val line = lineLayout.getLineForOffset(offset)
            val x = compoundPaddingLeft + lineLayout.getPrimaryHorizontal(offset) - scrollX
            caretPaint.color = currentTextColor
            caretPaint.strokeWidth = resources.displayMetrics.density.coerceAtLeast(1f)
            canvas.drawLine(x, totalPaddingTop + lineLayout.getLineTop(line).toFloat(),
                x, totalPaddingTop + lineLayout.getLineBottom(line).toFloat(), caretPaint)
        }
    }.apply {
        setSingleLine(true)
        setHorizontallyScrolling(false)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, SideCandidateReadingSizing.DEFAULT_SP.toFloat())
        includeFontPadding = true
        gravity = Gravity.CENTER
        isFocusable = false
        setTextIsSelectable(false)
        val caretInset = resources.displayMetrics.density.ceilToInt()
        setPadding(caretInset, 0, caretInset, 0)
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

    fun setReading(reading: String, color: Int? = null,
        textSizeSp: Int = SideCandidateReadingSizing.DEFAULT_SP, cursorOffset: Int = reading.length) {
        val size = textSizeSp.coerceIn(SideCandidateReadingSizing.MIN_SP, SideCandidateReadingSizing.MAX_SP)
        val sizeChanged = requestedTextSizeSp != size
        requestedTextSizeSp = size
        var cursor = cursorOffset.coerceIn(0, reading.length)
        if (cursor in 1 until reading.length && reading[cursor].isLowSurrogate() && reading[cursor - 1].isHighSurrogate()) cursor--
        val changed = readingTextView.text.toString() != reading
        if (changed || this.cursorOffset != cursor || sizeChanged) {
            followCursorRequested = reading.isNotEmpty()
            requestLayout()
        }
        this.cursorOffset = cursor
        if (changed) readingTextView.text = reading
        if (color != null) readingTextView.setTextColor(color)
        KeyboardFontApplicator.apply(readingTextView, KeyboardFontApplicator.processSnapshot)
        readingTextView.invalidate()
        visibility = if (reading.isEmpty()) GONE else VISIBLE
        if (reading.isEmpty()) {
            followCursorRequested = false
            scrollTo(0, 0)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) followCursorRequested = true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val oldEnd = (readingTextView.right + paddingRight - width).coerceAtLeast(0)
        val keepEndVisible = cursorOffset == readingTextView.text.length && scrollX >= oldEnd
        super.onLayout(changed, left, top, right, bottom)
        if (!overflowing) scrollTo(0, 0)
        else if (visibility == VISIBLE && (followCursorRequested || keepEndVisible)) scrollToCursor()
        followCursorRequested = false
    }

    private fun scrollToCursor() {
        val lineLayout = readingTextView.layout ?: return
        val x = readingTextView.left + readingTextView.compoundPaddingLeft +
            lineLayout.getPrimaryHorizontal(cursorOffset.coerceIn(0, lineLayout.text.length))
        val margin = minOf(12f * resources.displayMetrics.density, (width - paddingLeft - paddingRight) / 4f)
        val start = scrollX + paddingLeft + margin
        val end = scrollX + width - paddingRight - margin
        val target = when {
            cursorOffset == readingTextView.text.length -> x - width + paddingRight + margin
            cursorOffset == 0 -> 0f
            x < start || x > end -> x - width / 2f
            else -> scrollX.toFloat()
        }
        scrollTo(target.roundToInt().coerceAtLeast(0), 0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measurePaint.set(readingTextView.paint)
        val requested = sp(requestedTextSizeSp)
        measurePaint.textSize = requested
        val metrics = measurePaint.fontMetrics
        val lineHeight = (metrics.bottom - metrics.top).coerceAtLeast(1f)
        val availableHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
            else (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(0)
        val normalSize = if (availableHeight > 0) minOf(requested, requested * availableHeight / lineHeight) else requested
        val widthBounded = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        val availableWidth = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight -
            readingTextView.compoundPaddingLeft - readingTextView.compoundPaddingRight).coerceAtLeast(1)
        val text = readingTextView.transformationMethod?.getTransformation(readingTextView.text, readingTextView)
            ?: readingTextView.text
        fun textWidth(size: Float): Float {
            measurePaint.textSize = size
            return Layout.getDesiredWidth(text, measurePaint)
        }
        val levels = (SideCandidateReadingSizing.MIN_SP..requestedTextSizeSp step 2)
            .map { minOf(sp(it), normalSize) }.plus(normalSize).distinct().sorted()
        var selected = normalSize
        // Keep a little breathing room and change in fixed steps, rather than resizing
        // fractionally on every character. At the minimum, scrolling takes over.
        if (widthBounded && textWidth(normalSize) > availableWidth * .95f) {
            var low = 0
            var high = levels.lastIndex
            while (low < high) {
                val middle = (low + high + 1) / 2
                if (textWidth(levels[middle]) <= availableWidth * .95f) low = middle else high = middle - 1
            }
            selected = levels[low]
        }
        overflowing = widthBounded && textWidth(selected) > availableWidth
        readingTextView.gravity = Gravity.CENTER_VERTICAL or if (overflowing) Gravity.START else Gravity.CENTER_HORIZONTAL
        readingTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, selected)
        // Reserve the normal font's height: shrinking a long reading must not move the
        // keyboard or collapse the strip when the expand control is hidden.
        val desiredHeight = (lineHeight * normalSize / requested).ceilToInt() + paddingTop + paddingBottom
        val stableHeight = resolveSize(desiredHeight.coerceAtLeast(suggestedMinimumHeight), heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(stableHeight, MeasureSpec.EXACTLY))
    }

    private fun sp(size: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size.toFloat(), resources.displayMetrics)
    private fun Float.ceilToInt() = ceil(toDouble()).toInt()
}

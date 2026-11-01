package com.kazumaproject.custom_keyboard.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyLabelPositionTest {
    private fun button() = AutoSizeButton(ContextThemeWrapper(
        ApplicationProvider.getApplicationContext(), androidx.appcompat.R.style.Theme_AppCompat
    )).apply {
        text = "a"
        isAllCaps = false
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
        setBackgroundColor(Color.TRANSPARENT)
        setTextColor(Color.RED)
        setDefaultTextSize(24f)
        setFlickGuideLabels(AutoSizeButton.FlickGuideLabels(tap = "a", up = "c", down = "2"), Color.BLUE)
        measure(View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY))
        layout(0, 0, 160, 160)
    }

    private fun render(button: AutoSizeButton): Bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).also {
        button.draw(Canvas(it))
    }

    private fun centroid(bitmap: Bitmap, centerOnly: Boolean = false): Double {
        val rows = (0 until 160).flatMap { y -> (0 until 160).mapNotNull { x ->
            val color = bitmap.getPixel(x, y)
            if (Color.alpha(color) > 100 && (if (centerOnly) y in 50..110 else Color.red(color) > Color.blue(color))) y else null
        } }
        assertTrue("Expected visible label pixels", rows.isNotEmpty())
        return rows.average()
    }

    @Test fun offsetMovesLabelWithoutMovingDirectionalGuidesAndZeroRestoresIt() {
        val button = button()
        val original = render(button)
        for (offset in listOf(-4f, 4f)) {
            button.setLabelVerticalOffsetDp(offset)
            val shifted = render(button)
            assertEquals((offset * button.resources.displayMetrics.density).toDouble(),
                centroid(shifted) - centroid(original), 1.0)
            for (y in listOf(0 until 40, 120 until 160).flatMap { it.toList() }) for (x in 0 until 160) {
                assertEquals("Guide moved at $x,$y", original.getPixel(x,y), shifted.getPixel(x,y))
            }
        }
        button.setLabelVerticalOffsetDp(0f)
        assertTrue(original.sameAs(render(button)))
    }

    @Test fun separatelyDrawnCenterLabelAlsoMoves() {
        val button = button().apply { setTextColor(Color.TRANSPARENT) }
        val original = render(button)
        button.setLabelVerticalOffsetDp(-4f)
        assertEquals((-4f * button.resources.displayMetrics.density).toDouble(),
            centroid(render(button), true) - centroid(original, true), 1.0)
    }
}

package com.kazumaproject.markdownhelperkeyboard.ime_service.keyboard_geometry

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.kazumaproject.core.domain.state.TenKeyQWERTYMode
import com.kazumaproject.markdownhelperkeyboard.R
import com.kazumaproject.markdownhelperkeyboard.converter.candidate.Candidate
import com.kazumaproject.markdownhelperkeyboard.databinding.FloatingKeyboardLayoutBinding
import com.kazumaproject.markdownhelperkeyboard.databinding.MainLayoutBinding
import com.kazumaproject.markdownhelperkeyboard.ime_service.IMEService
import com.kazumaproject.markdownhelperkeyboard.ime_service.candidate.CandidateStripContent
import com.kazumaproject.markdownhelperkeyboard.setting_activity.AppPreference
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class SideCandidateReadingTest {
    private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MarkdownKeyboard)
        .also { AppPreference.init(it) }
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)
    private fun layout(view: View, width: Int = 240, height: Int = 56) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    @Test fun readingIsLargeScrollableAndClearedWhenHidden() {
        val view = SideCandidateReadingView(context)
        assertEquals(View.GONE, view.visibility)
        val reading = "きょうはよいてんきです".repeat(120)
        view.setReading(reading, Color.CYAN)
        layout(view)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(reading, view.readingTextView.text.toString())
        assertEquals(View.VISIBLE, view.visibility)
        assertEquals(Color.CYAN, view.readingTextView.currentTextColor)
        assertTrue(view.readingTextView.textSize > 20f * context.resources.displayMetrics.scaledDensity)
        assertTrue("textWidth=${view.readingTextView.width} viewport=${view.width}", view.readingTextView.width > view.width)
        assertTrue(view.scrollX > 0)
        view.scrollTo(0, 0)
        view.setReading(reading)
        view.requestLayout()
        layout(view)
        assertEquals("Candidate refreshes keep a manually scrolled reading in place", 0, view.scrollX)
        view.setReading("")
        assertEquals(View.GONE, view.visibility)
        assertEquals("", view.readingTextView.text.toString())
        assertEquals(0, view.scrollX)
        assertFalse(view.readingTextView.isFocusable)
    }

    @Test fun shortStripsKeepTheReadingWithinTheAvailableHeight() {
        val view = SideCandidateReadingView(context)
        view.setReading("とうきょう")
        layout(view, height = 20)
        val metrics = view.readingTextView.paint.fontMetrics
        assertTrue(metrics.bottom - metrics.top <= view.height - view.paddingTop - view.paddingBottom + .1f)
        assertEquals("とうきょう", view.readingTextView.text.toString())
    }

    @Test fun sizeCanGrowAndShrinkForTheSameReadingAndFitsShortStrips() {
        val view = SideCandidateReadingView(context)
        for (size in listOf(12, 48, 18, 36)) {
            view.setReading("きょうは", textSizeSp = size)
            layout(view, height = 160)
            assertEquals(size * context.resources.displayMetrics.scaledDensity, view.readingTextView.textSize, .1f)
            assertEquals("きょうは", view.readingTextView.text.toString())
        }
        view.setReading("きょうは", textSizeSp = 48)
        layout(view, height = 20)
        val metrics = view.readingTextView.paint.fontMetrics
        assertTrue(metrics.bottom - metrics.top <= 16.1f)
        layout(view, height = 160)
        assertEquals(48 * context.resources.displayMetrics.scaledDensity, view.readingTextView.textSize, .1f)
    }

    @Test fun sizeDefaultsAndResetsStayScopedToTheSelectedCandidateSettings() {
        prefs.edit().clear().commit()
        val store = KeyboardGeometryPreferences(prefs, "sized")
        assertEquals(24, store.read(GeometryKeyboard.SUMIRE, false).sideCandidateReadingSizeSp)
        for ((requested, expected) in listOf(0 to 12, 32 to 32, 100 to 48)) {
            store.saveCandidateDisplay(GeometryKeyboard.SUMIRE, false, KeyboardGeometryPreference(
                showReadingAboveSideCandidates = true, sideCandidateReadingSizeSp = requested))
            assertEquals(expected, store.read(GeometryKeyboard.SUMIRE, false).sideCandidateReadingSizeSp)
        }
        assertEquals(24, store.read(GeometryKeyboard.SUMIRE, true).sideCandidateReadingSizeSp)
        assertEquals(24, KeyboardGeometryPreferences(prefs, "other").read(GeometryKeyboard.SUMIRE, false).sideCandidateReadingSizeSp)
        store.resetCandidateDisplay(GeometryKeyboard.SUMIRE, false)
        assertEquals(24, store.read(GeometryKeyboard.SUMIRE, false).sideCandidateReadingSizeSp)
        assertFalse(store.read(GeometryKeyboard.SUMIRE, false).showReadingAboveSideCandidates)
    }

    @Test fun movingCandidatesBackRestoresTheListAndClearsOnlyTheReading() {
        val root = FrameLayout(context)
        val strip = FrameLayout(context)
        val reading = SideCandidateReadingView(context).apply { id = R.id.side_candidate_reading }
        val recycler = RecyclerView(context)
        val shortcuts = View(context)
        val params = FrameLayout.LayoutParams(200, 56)
        strip.addView(recycler, params)
        strip.addView(reading)
        root.addView(strip)
        root.addView(shortcuts)
        val host = GeometryCandidateHost(root, recycler)
        host.show(Rect(0, 56, 100, 240))
        reading.setReading("きょう")
        assertTrue(host.attached)
        assertNotSame(strip, recycler.parent)
        assertTrue(host.detach())
        assertEquals(View.GONE, reading.visibility)
        assertEquals("", reading.readingTextView.text.toString())
        assertSame(strip, recycler.parent)
        assertSame(params, recycler.layoutParams)
        assertSame(root, shortcuts.parent)
        assertEquals(View.VISIBLE, shortcuts.visibility)
    }

    @Test fun actualStripsKeepTheReadingVisibleAndClearOfTheExpandButton() {
        val main = MainLayoutBinding.inflate(LayoutInflater.from(context))
        val floating = FloatingKeyboardLayoutBinding.inflate(LayoutInflater.from(context))
        for ((strip, recycler, expand) in listOf(
            Triple(main.suggestionViewParent, main.suggestionRecyclerView, main.suggestionVisibility),
            Triple(floating.suggestionViewParent, floating.suggestionRecyclerView, floating.suggestionVisibility))) {
            val root = FrameLayout(context)
            val host = GeometryCandidateHost(root, recycler)
            host.show(Rect(0, 60, 100, 300))
            val reading = strip.findViewById<SideCandidateReadingView>(R.id.side_candidate_reading)
            for (buttonVisibility in listOf(View.VISIBLE, View.GONE)) {
                expand.visibility = buttonVisibility
                reading.setReading("きょうはよいてんきです")
                repeat(4) {
                    strip.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    strip.layout(0, 0, strip.measuredWidth, strip.measuredHeight)
                }
                assertTrue(reading.readingTextView.textSize > 20f * context.resources.displayMetrics.scaledDensity)
                assertTrue("reading height=${reading.height} strip=${strip.height}", reading.height > 0)
                assertTrue(reading.bottom <= strip.height)
                if (buttonVisibility == View.VISIBLE) assertTrue(reading.right <= expand.left)
            }
            host.detach()
            assertEquals(View.GONE, reading.visibility)
        }
    }

    @Test fun serviceShowsRawReadingOnTheActiveSurfaceAndKeepsCandidateRubyIndependent() {
        prefs.edit().clear().commit()
        val main = MainLayoutBinding.inflate(LayoutInflater.from(context))
        val floating = FloatingKeyboardLayoutBinding.inflate(LayoutInflater.from(context))
        main.candidatesRowView.visibility = View.GONE
        floating.candidatesRowView.visibility = View.GONE
        val service = IMEService()
        ReflectionHelpers.callInstanceMethod<Unit>(service, "attachBaseContext", ReflectionHelpers.ClassParameter.from(Context::class.java, context))
        ReflectionHelpers.setField(service, "isInputViewActive", true)
        ReflectionHelpers.setField(service, "mainLayoutBinding", main)
        ReflectionHelpers.setField(service, "floatingKeyboardBinding", floating)
        ReflectionHelpers.getField<MutableStateFlow<TenKeyQWERTYMode>>(service, "_tenKeyQWERTYMode").value = TenKeyQWERTYMode.Sumire
        val host = mock(GeometryCandidateHost::class.java)
        `when`(host.attached).thenReturn(true)
        ReflectionHelpers.setField(service, "dockedGeometryCandidateHost", host)
        ReflectionHelpers.setField(service, "floatingGeometryCandidateHost", host)
        val input = ReflectionHelpers.getField<MutableStateFlow<String>>(service, "_inputString")
        val tail = ReflectionHelpers.getField<AtomicReference<String>>(service, "stringInTail")
        input.value = "きょう"; tail.set("は")
        val content = CandidateStripContent.Candidates(listOf(Candidate("今日は", 0, 4u, 0)))
        ReflectionHelpers.setField(service, "currentCandidateStripContent", content)
        val store = KeyboardGeometryPreferences(prefs)
        fun save(placement: GeometryCandidatePlacement, enabled: Boolean = true) {
            store.saveCandidateDisplay(GeometryKeyboard.SUMIRE, false,
                KeyboardGeometryPreference(candidates = placement, showReadingAboveSideCandidates = enabled, sideCandidateReadingSizeSp = 32))
        }
        fun refresh() = ReflectionHelpers.callInstanceMethod<Unit>(service, "refreshSideCandidateReading")
        for (ruby in listOf(false, true)) for (live in listOf(false, true)) {
            AppPreference.live_conversion_candidate_yomi_preference = ruby
            ReflectionHelpers.setField(service, "isLiveConversionEnable", live)
            for (isFloating in listOf(false, true)) for (side in listOf(GeometryCandidatePlacement.LEFT, GeometryCandidatePlacement.RIGHT)) {
                ReflectionHelpers.setField(service, "isKeyboardFloatingMode", isFloating)
                save(side); refresh()
                val active = if (isFloating) floating.sideCandidateReading else main.sideCandidateReading
                val inactive = if (isFloating) main.sideCandidateReading else floating.sideCandidateReading
                layout(active, height = 160)
                assertEquals(32 * context.resources.displayMetrics.scaledDensity, active.readingTextView.textSize, .1f)
                assertEquals("きょうは", active.readingTextView.text.toString())
                assertEquals(View.VISIBLE, active.visibility)
                assertEquals(View.GONE, inactive.visibility)
                assertSame(content, ReflectionHelpers.getField<CandidateStripContent>(service, "currentCandidateStripContent"))
            }
        }
        ReflectionHelpers.setField(service, "isKeyboardFloatingMode", false)
        save(GeometryCandidatePlacement.LEFT)
        input.value = "きょ"; tail.set(""); refresh()
        assertEquals("きょ", main.sideCandidateReading.readingTextView.text.toString())
        input.value = ""; refresh()
        assertEquals(View.GONE, main.sideCandidateReading.visibility)
        input.value = "きょう"
        for (disabled in listOf(GeometryCandidatePlacement.TOP, GeometryCandidatePlacement.LEFT)) {
            save(disabled, enabled = false); refresh()
            assertEquals(View.GONE, main.sideCandidateReading.visibility)
        }
        save(GeometryCandidatePlacement.TOP); refresh()
        assertEquals(View.GONE, main.sideCandidateReading.visibility)
        save(GeometryCandidatePlacement.LEFT)
        `when`(host.attached).thenReturn(false); refresh()
        assertEquals(View.GONE, main.sideCandidateReading.visibility)
        `when`(host.attached).thenReturn(true)
        ReflectionHelpers.setField(service, "currentCandidateStripContent", CandidateStripContent.Empty); refresh()
        assertEquals(View.GONE, main.sideCandidateReading.visibility)
        ReflectionHelpers.setField(service, "currentCandidateStripContent", content)
        ReflectionHelpers.setField(service, "suppressSuggestions", true); refresh()
        assertEquals(View.GONE, main.sideCandidateReading.visibility)
        ReflectionHelpers.setField(service, "suppressSuggestions", false)
        main.candidatesRowView.visibility = View.VISIBLE; refresh()
        assertEquals(View.GONE, main.sideCandidateReading.visibility)
        main.candidatesRowView.visibility = View.GONE; refresh()
        assertEquals(View.VISIBLE, main.sideCandidateReading.visibility)
        ReflectionHelpers.setField(service, "isInputViewActive", false); refresh()
        assertEquals(View.GONE, main.sideCandidateReading.visibility)
        ReflectionHelpers.callInstanceMethod<Unit>(service, "clearSideCandidateReading")
        assertEquals(View.GONE, main.sideCandidateReading.visibility)
        assertEquals("", main.sideCandidateReading.readingTextView.text.toString())
    }
}

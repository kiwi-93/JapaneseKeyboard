package com.kazumaproject.markdownhelperkeyboard.ime_service.keyboard_geometry

import android.content.Context
import android.view.ContextThemeWrapper
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.RadioGroup
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.kazumaproject.core.ui.geometry.*
import com.kazumaproject.markdownhelperkeyboard.R
import com.kazumaproject.markdownhelperkeyboard.setting_activity.ui.keyboard_geometry.UnifiedKeyboardSettingsFragment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CustomKeyboardCurveTest {
    private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MarkdownKeyboard)
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    @Test fun shapePersistsPerLayoutAndOrientationWithoutChangingSizeOrCandidates() {
        prefs.edit().clear().commit()
        val a = KeyboardGeometryPreferences(prefs, "a")
        val shape = KeyboardGeometry(curveDegrees = 20, hand = KeyboardCurveHand.LEFT,
            curveDirection = KeyboardCurveDirection.UP, autoHeight = false)
        a.saveCustomShape(false, shape)
        assertEquals(shape, KeyboardGeometryPreferences(prefs, "a").read(GeometryKeyboard.SUMIRE, false).geometry)
        assertEquals(KeyboardGeometry(), a.read(GeometryKeyboard.SUMIRE, true).geometry)
        assertEquals(KeyboardGeometry(), KeyboardGeometryPreferences(prefs, "b").read(GeometryKeyboard.SUMIRE, false).geometry)
        assertEquals(KeyboardGeometry(), KeyboardGeometryPreferences(prefs).read(GeometryKeyboard.SUMIRE, false).geometry)
        a.saveCandidateDisplay(GeometryKeyboard.SUMIRE, false, KeyboardGeometryPreference())
        assertEquals(shape, a.read(GeometryKeyboard.SUMIRE, false).geometry)
        a.saveCustomShape(false, shape.copy(curveDegrees = 100))
        assertEquals(25, a.read(GeometryKeyboard.SUMIRE, false).geometry.curveDegrees)
        a.saveCustomShape(false, KeyboardGeometry())
        assertEquals(KeyboardGeometry(), a.read(GeometryKeyboard.SUMIRE, false).geometry)
    }

    @Test fun candidateControlsPersistPerLayoutAndOrientationAndKeepTheCurve() {
        prefs.edit().clear().commit()
        val store = KeyboardGeometryPreferences(prefs, "edited")
        val shape = KeyboardGeometry(curveDegrees = 20)
        store.saveCustomShape(false, shape)
        fun editor(): Pair<UnifiedKeyboardSettingsFragment, LinearLayout> {
            val fragment = spy(UnifiedKeyboardSettingsFragment())
            doReturn(context).`when`(fragment).requireContext()
            val body = LinearLayout(context)
            ReflectionHelpers.setField(fragment, "body", body)
            ReflectionHelpers.setField(fragment, "selectedCustomId", "edited")
            ReflectionHelpers.setField(fragment, "geometry", store.read(GeometryKeyboard.SUMIRE, false))
            ReflectionHelpers.callInstanceMethod<Unit>(fragment, "buildCustomCandidateBody")
            return fragment to body
        }
        val (fragment, body) = editor()
        val placement = body.findViewById<RadioGroup>(R.id.keyboard_geometry_candidate_placement)
        val width = body.findViewById<SeekBar>(R.id.keyboard_geometry_candidate_width)
        assertEquals(android.view.View.GONE, (width.parent as android.view.View).visibility)
        for ((choice, expected) in listOf(3 to GeometryCandidatePlacement.LEFT, 4 to GeometryCandidatePlacement.RIGHT)) {
            placement.check(choice)
            assertEquals(expected, store.read(GeometryKeyboard.SUMIRE, false).candidates)
            assertEquals(android.view.View.VISIBLE, (width.parent as android.view.View).visibility)
        }
        ReflectionHelpers.getField<SeekBar.OnSeekBarChangeListener>(width, "mOnSeekBarChangeListener")
            .onProgressChanged(width, 25, true)
        assertEquals(40, store.read(GeometryKeyboard.SUMIRE, false).sideCandidateWidthPercent)
        assertEquals(4, editor().second.findViewById<RadioGroup>(R.id.keyboard_geometry_candidate_placement).checkedRadioButtonId)
        assertEquals(shape, store.read(GeometryKeyboard.SUMIRE, false).geometry)
        assertEquals(GeometryCandidatePlacement.TOP, store.read(GeometryKeyboard.SUMIRE, true).candidates)
        assertEquals(GeometryCandidatePlacement.TOP, KeyboardGeometryPreferences(prefs, "other").read(GeometryKeyboard.SUMIRE, false).candidates)
        placement.check(1)
        assertEquals(GeometryCandidatePlacement.TOP, store.read(GeometryKeyboard.SUMIRE, false).candidates)
        assertEquals(android.view.View.GONE, (width.parent as android.view.View).visibility)
        assertEquals(GeometryCandidatePlacement.TOP, ReflectionHelpers.getField<KeyboardGeometryPreference>(fragment, "geometry").candidates)
    }

    @Test fun customPreviewReservesCandidateSpaceOnEitherSideWhileKeepingCurvature() {
        com.kazumaproject.markdownhelperkeyboard.setting_activity.AppPreference.init(context)
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_MarkdownKeyboard)
        val previewContext = activity
        val layout = com.kazumaproject.custom_keyboard.data.KeyboardLayout(
            keys = listOf(com.kazumaproject.custom_keyboard.data.KeyData("delete", 0, 0, false, isSpecialKey = true, drawableResId = com.kazumaproject.core.R.drawable.backspace_24px),
                com.kazumaproject.custom_keyboard.data.KeyData("right", 0, 1, false, isSpecialKey = true, drawableResId = com.kazumaproject.core.R.drawable.baseline_arrow_right_alt_24)),
            flickKeyMaps = emptyMap(), columnCount = 2, rowCount = 1)
        fun preview(placement: GeometryCandidatePlacement): com.kazumaproject.markdownhelperkeyboard.setting_activity.ui.keyboard_geometry.GeometryPreviewSnapshot {
            val view = com.kazumaproject.markdownhelperkeyboard.setting_activity.ui.keyboard_geometry.GeometryPreview(
                previewContext, com.kazumaproject.markdownhelperkeyboard.setting_activity.ui.keyboard_geometry.PreviewKeyboard.CUSTOM)
            view.update(KeyboardGeometryPreference(geometry = KeyboardGeometry(curveDegrees = 20), candidates = placement,
                sideCandidateWidthPercent = 35), false, 100, false,
                com.kazumaproject.markdownhelperkeyboard.setting_activity.keyboard_size.IndependentKeyboardSize(), layout)
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(480, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
            return view.snapshotForTesting()
        }
        val top = preview(GeometryCandidatePlacement.TOP)
        val sideWidth = SideCandidateSizing.width(top.screenWidth.toInt(), context.resources.displayMetrics.density, 35).toFloat()
        assertTrue(sideWidth > 0)
        for (placement in listOf(GeometryCandidatePlacement.LEFT, GeometryCandidatePlacement.RIGHT)) {
            val side = preview(placement)
            assertEquals(top.bodyWidth - sideWidth, side.bodyWidth, 1f)
            assertEquals(top.bodyLeft + if (placement == GeometryCandidatePlacement.LEFT) sideWidth else 0f, side.bodyLeft, 1f)
            assertTrue(side.keys.any { kotlin.math.abs(it.angle) > 0f })
        }
        activity.finish()
    }

    @Test fun imeSelectsTheCurrentCustomLayoutsShapeInsteadOfSumire() {
        prefs.edit().clear().commit()
        KeyboardGeometryPreferences(prefs, "active").saveCustomShape(false, KeyboardGeometry(curveDegrees = 18))
        KeyboardGeometryPreferences(prefs, "active").saveCandidateDisplay(GeometryKeyboard.SUMIRE, false,
            KeyboardGeometryPreference(candidates = GeometryCandidatePlacement.LEFT, sideCandidateWidthPercent = 35))
        val service = com.kazumaproject.markdownhelperkeyboard.ime_service.IMEService()
        ReflectionHelpers.callInstanceMethod<Unit>(service, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, context))
        val layout = com.kazumaproject.markdownhelperkeyboard.custom_keyboard.data.CustomKeyboardLayout(
            name = "custom", columnCount = 2, rowCount = 2, stableId = "active")
        ReflectionHelpers.setField(service, "customLayouts", listOf(layout))
        ReflectionHelpers.setField(service, "currentCustomKeyboardStableId", "active")
        val mode = ReflectionHelpers.ClassParameter.from(com.kazumaproject.core.domain.state.TenKeyQWERTYMode::class.java,
            com.kazumaproject.core.domain.state.TenKeyQWERTYMode.Custom)
        assertEquals(GeometryKeyboard.SUMIRE,
            ReflectionHelpers.callInstanceMethod<GeometryKeyboard>(service, "geometryKeyboardForMode", mode))
        val store = ReflectionHelpers.callInstanceMethod<KeyboardGeometryPreferences>(service, "geometryPreferencesForMode", mode)
        assertEquals(18, store.read(GeometryKeyboard.SUMIRE, false).geometry.curveDegrees)
        assertEquals(GeometryCandidatePlacement.LEFT, store.read(GeometryKeyboard.SUMIRE, false).candidates)
        assertEquals(35, store.read(GeometryKeyboard.SUMIRE, false).sideCandidateWidthPercent)
        assertEquals(0, KeyboardGeometryPreferences(prefs).read(GeometryKeyboard.SUMIRE, false).geometry.curveDegrees)
    }

    @Test fun originalShapeControlsSaveCustomLayoutAndUpdatePreviewState() {
        prefs.edit().clear().commit()
        val fragment = spy(UnifiedKeyboardSettingsFragment())
        doReturn(context).`when`(fragment).requireContext()
        val body = LinearLayout(context)
        ReflectionHelpers.setField(fragment, "body", body)
        ReflectionHelpers.setField(fragment, "selectedCustomId", "edited")
        ReflectionHelpers.callInstanceMethod<Unit>(fragment, "buildCustomShapeBody")
        val slider = (0 until body.childCount).map { body.getChildAt(it) }.filterIsInstance<SeekBar>().single()
        ReflectionHelpers.getField<SeekBar.OnSeekBarChangeListener>(slider, "mOnSeekBarChangeListener")
            .onProgressChanged(slider, 20, true)
        val groups = (0 until body.childCount).map { body.getChildAt(it) }.filterIsInstance<RadioGroup>()
        groups.forEach { it.check(2) }
        val shape = KeyboardGeometryPreferences(prefs, "edited").read(GeometryKeyboard.SUMIRE, false).geometry
        assertEquals(20, shape.curveDegrees)
        assertEquals(KeyboardCurveHand.LEFT, shape.hand)
        assertEquals(KeyboardCurveDirection.UP, shape.curveDirection)
        assertFalse(shape.autoHeight)
        assertEquals(shape, ReflectionHelpers.getField<KeyboardGeometryPreference>(fragment, "geometry").geometry)
    }
}

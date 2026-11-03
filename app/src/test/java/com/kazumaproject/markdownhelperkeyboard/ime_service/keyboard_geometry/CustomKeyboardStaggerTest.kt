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
class CustomKeyboardStaggerTest {
    private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MarkdownKeyboard)
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    @Test fun shapePersistsPerLayoutAndOrientationWithoutChangingSizeOrCandidates() {
        prefs.edit().clear().commit()
        val a = KeyboardGeometryPreferences(prefs, "a")
        val shape = KeyboardGeometry(columnStaggerDp = -20)
        a.saveCustomShape(false, shape)
        assertEquals(shape, KeyboardGeometryPreferences(prefs, "a").read(GeometryKeyboard.SUMIRE, false).geometry)
        assertEquals(KeyboardGeometry(), a.read(GeometryKeyboard.SUMIRE, true).geometry)
        assertEquals(KeyboardGeometry(), KeyboardGeometryPreferences(prefs, "b").read(GeometryKeyboard.SUMIRE, false).geometry)
        assertEquals(KeyboardGeometry(), KeyboardGeometryPreferences(prefs).read(GeometryKeyboard.SUMIRE, false).geometry)
        a.saveCandidateDisplay(GeometryKeyboard.SUMIRE, false, KeyboardGeometryPreference())
        assertEquals(shape, a.read(GeometryKeyboard.SUMIRE, false).geometry)
        a.saveCustomShape(false, shape.copy(columnStaggerDp = 100))
        assertEquals(32, a.read(GeometryKeyboard.SUMIRE, false).geometry.columnStaggerDp)
        a.saveCustomShape(false, KeyboardGeometry())
        assertEquals(KeyboardGeometry(), a.read(GeometryKeyboard.SUMIRE, false).geometry)
    }

    @Test fun legacyCurveSettingsAreIgnoredAndStaggerDefaultsToAlignedGrid() {
        prefs.edit().clear().putInt("keyboard_geometry_custom_old_portrait_curve", 25)
            .putInt("keyboard_geometry_custom_old_portrait_flick_follow", 50)
            .putString("keyboard_geometry_custom_old_portrait_hand", "LEFT")
            .putString("keyboard_geometry_custom_old_portrait_direction", "UP")
            .putBoolean("keyboard_geometry_custom_old_portrait_auto_height", false).commit()
        assertEquals(KeyboardGeometry(), KeyboardGeometryPreferences(prefs, "old").read(GeometryKeyboard.SUMIRE, false).geometry)
    }

    @Test fun candidateControlsPersistPerLayoutAndOrientationAndKeepTheStagger() {
        prefs.edit().clear().commit()
        val store = KeyboardGeometryPreferences(prefs, "edited")
        val shape = KeyboardGeometry(columnStaggerDp = 20)
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
        val reading = body.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.keyboard_geometry_reading_header)
        assertFalse(reading.isChecked)
        reading.isChecked = true
        assertTrue(store.read(GeometryKeyboard.SUMIRE, false).showReadingAboveSideCandidates)
        assertTrue(editor().second.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.keyboard_geometry_reading_header).isChecked)
        assertFalse(store.read(GeometryKeyboard.SUMIRE, true).showReadingAboveSideCandidates)
        assertFalse(KeyboardGeometryPreferences(prefs, "other").read(GeometryKeyboard.SUMIRE, false).showReadingAboveSideCandidates)
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
        assertTrue(store.read(GeometryKeyboard.SUMIRE, false).showReadingAboveSideCandidates)
        assertEquals(4, editor().second.findViewById<RadioGroup>(R.id.keyboard_geometry_candidate_placement).checkedRadioButtonId)
        assertEquals(shape, store.read(GeometryKeyboard.SUMIRE, false).geometry)
        assertEquals(GeometryCandidatePlacement.TOP, store.read(GeometryKeyboard.SUMIRE, true).candidates)
        assertEquals(GeometryCandidatePlacement.TOP, KeyboardGeometryPreferences(prefs, "other").read(GeometryKeyboard.SUMIRE, false).candidates)
        placement.check(1)
        assertEquals(GeometryCandidatePlacement.TOP, store.read(GeometryKeyboard.SUMIRE, false).candidates)
        assertEquals(android.view.View.GONE, (width.parent as android.view.View).visibility)
        assertEquals(GeometryCandidatePlacement.TOP, ReflectionHelpers.getField<KeyboardGeometryPreference>(fragment, "geometry").candidates)
    }

    @Test fun customPreviewReservesCandidateSpaceOnEitherSideWhileKeepingStagger() {
        com.kazumaproject.markdownhelperkeyboard.setting_activity.AppPreference.init(context)
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_MarkdownKeyboard)
        val previewContext = activity
        val layout = com.kazumaproject.custom_keyboard.data.KeyboardLayout(
            keys = listOf(com.kazumaproject.custom_keyboard.data.KeyData("delete", 0, 0, false, isSpecialKey = true, drawableResId = com.kazumaproject.core.R.drawable.backspace_24px),
                com.kazumaproject.custom_keyboard.data.KeyData("right", 0, 1, false, isSpecialKey = true, drawableResId = com.kazumaproject.core.R.drawable.baseline_arrow_right_alt_24)),
            flickKeyMaps = emptyMap(), columnCount = 2, rowCount = 1)
        fun preview(placement: GeometryCandidatePlacement, step: Int = 20, alignEnd: Boolean = false, width: Int = 100): com.kazumaproject.markdownhelperkeyboard.setting_activity.ui.keyboard_geometry.GeometryPreviewSnapshot {
            val view = com.kazumaproject.markdownhelperkeyboard.setting_activity.ui.keyboard_geometry.GeometryPreview(
                previewContext, com.kazumaproject.markdownhelperkeyboard.setting_activity.ui.keyboard_geometry.PreviewKeyboard.CUSTOM)
            view.update(KeyboardGeometryPreference(geometry = KeyboardGeometry(columnStaggerDp = step), candidates = placement,
                sideCandidateWidthPercent = 35), false, width, alignEnd,
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
            assertTrue(side.keys.all { it.angle == 0f })
            assertTrue(side.keys[0].y > side.keys[1].y)
        }
        for (step in listOf(-20, 0, 20)) {
            val left = preview(GeometryCandidatePlacement.TOP, step, false, 70)
            val right = preview(GeometryCandidatePlacement.TOP, step, true, 70)
            val expected = step * context.resources.displayMetrics.density
            assertEquals(expected, left.keys[0].y - left.keys[1].y, .1f)
            assertTrue(left.keys.all { it.angle == 0f })
            assertEquals(left.bodyWidth, right.bodyWidth, .1f)
            assertTrue(right.bodyLeft > left.bodyLeft)
            assertEquals(left.keys.map { it.y }, right.keys.map { it.y })
        }
        activity.finish()
    }

    @Test fun imeSelectsTheCurrentCustomLayoutsStaggerInsteadOfSumire() {
        prefs.edit().clear().commit()
        KeyboardGeometryPreferences(prefs, "active").saveCustomShape(false, KeyboardGeometry(columnStaggerDp = 18))
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
        assertEquals(18, store.read(GeometryKeyboard.SUMIRE, false).geometry.columnStaggerDp)
        assertEquals(GeometryCandidatePlacement.LEFT, store.read(GeometryKeyboard.SUMIRE, false).candidates)
        assertEquals(35, store.read(GeometryKeyboard.SUMIRE, false).sideCandidateWidthPercent)
        assertEquals(0, KeyboardGeometryPreferences(prefs).read(GeometryKeyboard.SUMIRE, false).geometry.curveDegrees)
    }

    @Test fun signedStepControlSavesBothDirectionsAndZeroWithoutChangingCandidates() {
        prefs.edit().clear().commit()
        val store = KeyboardGeometryPreferences(prefs, "edited")
        store.saveCandidateDisplay(GeometryKeyboard.SUMIRE, false, KeyboardGeometryPreference(candidates = GeometryCandidatePlacement.RIGHT))
        val fragment = spy(UnifiedKeyboardSettingsFragment())
        doReturn(context).`when`(fragment).requireContext()
        val body = LinearLayout(context)
        ReflectionHelpers.setField(fragment, "body", body)
        ReflectionHelpers.setField(fragment, "selectedCustomId", "edited")
        ReflectionHelpers.setField(fragment, "geometry", store.read(GeometryKeyboard.SUMIRE, false))
        ReflectionHelpers.callInstanceMethod<Unit>(fragment, "buildCustomStaggerBody")
        val slider = body.findViewById<SeekBar>(R.id.keyboard_geometry_column_stagger)
        assertEquals(32, slider.progress)
        for (step in listOf(-32, -12, 0, 12, 32)) {
            ReflectionHelpers.getField<SeekBar.OnSeekBarChangeListener>(slider, "mOnSeekBarChangeListener")
                .onProgressChanged(slider, step + 32, true)
            assertEquals(step, store.read(GeometryKeyboard.SUMIRE, false).geometry.columnStaggerDp)
            assertEquals(GeometryCandidatePlacement.RIGHT, store.read(GeometryKeyboard.SUMIRE, false).candidates)
        }
        store.saveCustomShape(false, KeyboardGeometry(columnStaggerDp = -100))
        assertEquals(-32, store.read(GeometryKeyboard.SUMIRE, false).geometry.columnStaggerDp)
    }
}

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

    @Test fun imeSelectsTheCurrentCustomLayoutsShapeInsteadOfSumire() {
        prefs.edit().clear().commit()
        KeyboardGeometryPreferences(prefs, "active").saveCustomShape(false, KeyboardGeometry(curveDegrees = 18))
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

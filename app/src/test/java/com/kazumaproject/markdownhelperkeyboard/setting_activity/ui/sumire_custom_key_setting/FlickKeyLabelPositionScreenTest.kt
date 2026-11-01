package com.kazumaproject.markdownhelperkeyboard.setting_activity.ui.sumire_custom_key_setting

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.slider.Slider
import com.kazumaproject.custom_keyboard.view.FlickKeyboardView
import com.kazumaproject.markdownhelperkeyboard.R
import com.kazumaproject.markdownhelperkeyboard.setting_activity.AppPreference
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FlickKeyLabelPositionScreenTest {
    private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MarkdownKeyboard)
    private fun screen(): View = LayoutInflater.from(context).inflate(R.layout.fragment_flick_keyboard_size_settings, null).also {
        FlickKeyboardSizeSettingsFragment().bindLabelPositionControl(it, it.findViewById(R.id.previewFlickKeyboardView))
    }
    @After fun reset() { AppPreference.flick_key_label_vertical_offset_dp = 0 }
    @Test fun actualSizeScreenExposesControlPersistsAndRestoresPositionAndResetsPreview() {
        AppPreference.init(context)
        AppPreference.flick_key_label_vertical_offset_dp = -3
        val root = screen()
        val slider = root.findViewById<Slider>(R.id.sizingLabelPosition)
        assertEquals(View.VISIBLE, slider.visibility)
        assertEquals(-3f, slider.value, 0f)
        slider.value = -5f
        assertEquals(-5, AppPreference.flick_key_label_vertical_offset_dp)
        assertEquals(context.getString(R.string.flick_key_label_vertical_offset_value, -5), root.findViewById<TextView>(R.id.sizingLabelPositionValue).text.toString())
        val preview = root.findViewById<FlickKeyboardView>(R.id.previewFlickKeyboardView)
        assertEquals(-5f, ReflectionHelpers.getField<Float>(preview, "labelVerticalOffsetDp"), 0f)
        val recreated = screen()
        assertEquals(-5f, recreated.findViewById<Slider>(R.id.sizingLabelPosition).value, 0f)
        recreated.findViewById<View>(R.id.sizingLabelPositionReset).performClick()
        assertEquals(0, AppPreference.flick_key_label_vertical_offset_dp)
        assertEquals(0f, ReflectionHelpers.getField<Float>(recreated.findViewById<FlickKeyboardView>(R.id.previewFlickKeyboardView), "labelVerticalOffsetDp"), 0f)
    }
}

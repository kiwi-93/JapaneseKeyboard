package com.kazumaproject.markdownhelperkeyboard.custom_keyboard.ui

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.kazumaproject.custom_keyboard.data.KeyData
import com.kazumaproject.markdownhelperkeyboard.R
import com.kazumaproject.markdownhelperkeyboard.databinding.FragmentKeyEditorBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyLabelPositionEditorTest {
    @Test fun selectedKeyCanBeAdjustedWithoutChangingOtherKeysAndReset() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_MarkdownKeyboard)
        fun editor(key: KeyData): FragmentKeyEditorBinding {
            val binding = FragmentKeyEditorBinding.inflate(LayoutInflater.from(context))
            val fragment = KeyEditorFragment()
            ReflectionHelpers.setField(fragment, "_binding", binding)
            fragment.bindLabelPositionControl(key)
            return binding
        }
        val key = KeyData("a", 0, 0, false, labelVerticalOffsetDp = -3)
        val binding = editor(key)
        assertEquals(View.VISIBLE, binding.keyLabelPosition.visibility)
        assertEquals(-3f, binding.keyLabelPosition.value, 0f)
        binding.keyLabelPosition.value = -5f
        val saved = key.copy(labelVerticalOffsetDp = binding.keyLabelPosition.value.toInt())
        assertEquals(-5, saved.labelVerticalOffsetDp)
        assertEquals(-5f * context.resources.displayMetrics.density,
            ReflectionHelpers.getField<Float>(binding.keyLabelPositionPreview, "labelVerticalOffsetPx"), 0f)
        assertEquals(-5f, editor(saved).keyLabelPosition.value, 0f)
        assertEquals(0f, editor(KeyData("あ", 0, 1, false)).keyLabelPosition.value, 0f)
        binding.keyLabelPositionReset.performClick()
        assertEquals(0f, binding.keyLabelPosition.value, 0f)
    }
}

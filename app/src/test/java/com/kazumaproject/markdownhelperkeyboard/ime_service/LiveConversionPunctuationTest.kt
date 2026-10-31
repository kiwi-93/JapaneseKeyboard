package com.kazumaproject.markdownhelperkeyboard.ime_service

import android.content.Context
import android.text.Selection
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import androidx.test.core.app.ApplicationProvider
import com.kazumaproject.core.domain.state.TenKeyQWERTYMode
import com.kazumaproject.markdownhelperkeyboard.converter.candidate.Candidate
import com.kazumaproject.markdownhelperkeyboard.ime_service.flick_preview.ComposingTextArbiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveConversionPunctuationTest {
    private class Editor : BaseInputConnection(View(ApplicationProvider.getApplicationContext()), true) {
        private val content = SpannableStringBuilder()
        init { Selection.setSelection(content, 0) }
        override fun getEditable() = content
    }

    private val editor = Editor()
    private val arbiter = ComposingTextArbiter(
        writeComposingText = { text, cursor -> editor.setComposingText(text, cursor) },
        finishComposingText = { editor.finishComposingText() },
        copyText = { SpannableString(it) },
    )
    private val service = spy(IMEService()).also {
        doReturn(editor).`when`(it).getCurrentInputConnection()
        it.zenzRuntimeClient = mock(com.kazumaproject.markdownhelperkeyboard.zenz.runtime.ZenzRuntimeClient::class.java)
        it.appPreference = mock(com.kazumaproject.markdownhelperkeyboard.setting_activity.AppPreference::class.java)
        ReflectionHelpers.setField(it, "runtimeInputSharedPreferences",
            ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("live-punctuation-test", Context.MODE_PRIVATE))
        ReflectionHelpers.setField(it, "composingTextArbiter", arbiter)
        ReflectionHelpers.callInstanceMethod<Unit>(it, "attachBaseContext",
            ClassParameter.from(Context::class.java, ApplicationProvider.getApplicationContext<Context>()))
        ReflectionHelpers.getField<MutableStateFlow<TenKeyQWERTYMode>>(it, "_tenKeyQWERTYMode").value = TenKeyQWERTYMode.Custom
        ReflectionHelpers.setField(it, "isLiveConversionEnable", true)
        ReflectionHelpers.setField(it, "customComposingTextPreference", true)
        ReflectionHelpers.setField(it, "inputCompositionAfterBackgroundColor", 0x77556677)
    }
    private val input get() = ReflectionHelpers.getField<MutableStateFlow<String>>(service, "_inputString")
    private val tail get() = ReflectionHelpers.getField<AtomicReference<String>>(service, "stringInTail")
    private fun candidate(text: String = "今日", reading: String = "きょう") {
        input.value = reading
        ReflectionHelpers.callInstanceMethod<Unit>(service, "applyFirstSuggestion",
            ClassParameter.from(Candidate::class.java, Candidate(text, 1, reading.length.toUByte(), 0)))
    }
    private fun type(text: String): Boolean = ReflectionHelpers.callInstanceMethod(service, "dispatchDirectTextIfNeeded",
        ClassParameter.from(String::class.java, text))

    @After fun cleanup() {
        ReflectionHelpers.callInstanceMethod<Unit>(service, "resetCustomToggleState")
        listOf("scope", "ioScope").forEach { ReflectionHelpers.getField<CoroutineScope>(service, it).cancel() }
        ReflectionHelpers.getField<ExecutorCoroutineDispatcher>(service, "kanaKanjiConversionDispatcher").close()
    }

    @Test fun commaCommitsDisplayedConversionAndNextInputStartsNewComposition() {
        editor.commitText("前文", 1)
        candidate()
        assertTrue(type("、"))
        assertEquals("前文今日、", editor.editable.toString())
        assertEquals("", input.value)
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.editable))
        service.setComposingText("つぎ", 1)
        assertEquals("前文今日、つぎ", editor.editable.toString())
        assertEquals(5, BaseInputConnection.getComposingSpanStart(editor.editable))
    }

    @Test fun periodCommitsLatestUpdatedLiveCandidate() {
        candidate("京")
        candidate("今日")
        assertTrue(type("。"))
        assertEquals("今日。", editor.editable.toString())
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.editable))
    }

    private fun assertMarkCommitsConversionAndStartsFreshComposition(mark: String) {
        candidate()
        assertTrue(type(mark))
        assertEquals("今日$mark", editor.editable.toString())
        assertEquals("", input.value)
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.editable))
        service.setComposingText("つぎ", 1)
        assertEquals("今日${mark}つぎ", editor.editable.toString())
        assertEquals(2 + mark.length, BaseInputConnection.getComposingSpanStart(editor.editable))
    }

    @Test fun fullWidthExclamationCommitsConversion() {
        assertMarkCommitsConversionAndStartsFreshComposition("！")
    }

    @Test fun fullWidthQuestionCommitsConversion() {
        assertMarkCommitsConversionAndStartsFreshComposition("？")
    }

    @Test fun ellipsisCommitsConversion() {
        assertMarkCommitsConversionAndStartsFreshComposition("…")
    }

    @Test fun previewDoesNotCommitRawReadingOrDuplicatePunctuation() {
        candidate()
        arbiter.showPreview("きょう、", 1)
        assertTrue(type("、"))
        assertEquals("今日、", editor.editable.toString())
        assertFalse(arbiter.hasCanonicalText())
    }

    @Test fun preservesTailAndCaretWhenConversionLengthDiffersFromReading() {
        tail.set("です")
        candidate()
        assertTrue(type("。"))
        assertEquals("今日。です", editor.editable.toString())
        assertEquals(3, Selection.getSelectionStart(editor.editable))
        assertEquals("", tail.get())
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.editable))
    }

    @Test fun pendingNewReadingCannotCommitStaleCandidate() {
        candidate()
        input.value = "きょうは"
        assertFalse(type("、"))
        assertEquals("きょうは", input.value)
        assertEquals("今日", editor.editable.toString())
    }

    @Test fun rawFallbackCannotCommitPreviouslyDisplayedCandidate() {
        candidate()
        service.setComposingText("きょう", 1)
        assertFalse(type("。"))
        assertEquals("きょう", editor.editable.toString())
    }

    @Test fun disabledLiveConversionAndManualConversionKeepExistingPipeline() {
        candidate()
        ReflectionHelpers.setField(service, "isLiveConversionEnable", false)
        assertFalse(type("、"))
        ReflectionHelpers.setField(service, "isLiveConversionEnable", true)
        ReflectionHelpers.getField<AtomicBoolean>(service, "isHenkan").set(true)
        assertFalse(type("。"))
        assertEquals("今日", editor.editable.toString())
    }

    @Test fun unconfiguredMarksAndMultipleCharactersKeepExistingPipeline() {
        candidate()
        listOf(",", ".", "!", "?", "...", "‥", "、。", "！？", "……", "あ").forEach {
            assertFalse(type(it))
        }
        assertEquals("今日", editor.editable.toString())
    }

    @Test fun noCandidateAndFinishedSessionCannotReusePreviousConversion() {
        input.value = "きょう"
        assertFalse(type("、"))
        candidate()
        service.finishComposingText()
        service.setComposingText("今日", 1)
        assertFalse(type("。"))
    }

    @Test fun customDirectModeDoesNotAutoCommitComposition() {
        candidate()
        ReflectionHelpers.setField(service, "isCustomLayoutDirectMode", true)
        assertFalse(type("、"))
        assertEquals("きょう", input.value)
    }
}

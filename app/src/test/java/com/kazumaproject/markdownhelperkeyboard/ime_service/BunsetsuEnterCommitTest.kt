package com.kazumaproject.markdownhelperkeyboard.ime_service

import android.content.Context
import android.text.Selection
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.view.View
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.inputmethod.BaseInputConnection
import androidx.test.core.app.ApplicationProvider
import com.kazumaproject.markdownhelperkeyboard.converter.candidate.Candidate
import com.kazumaproject.markdownhelperkeyboard.databinding.FloatingKeyboardLayoutBinding
import com.kazumaproject.markdownhelperkeyboard.databinding.MainLayoutBinding
import com.kazumaproject.markdownhelperkeyboard.ime_service.flick_preview.ComposingTextArbiter
import com.kazumaproject.markdownhelperkeyboard.setting_activity.AppPreference
import com.kazumaproject.markdownhelperkeyboard.zenz.runtime.ZenzRuntimeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import com.kazumaproject.markdownhelperkeyboard.converter.engine.KanaKanjiEngine
import com.kazumaproject.markdownhelperkeyboard.converter.session.KanaKanjiConversionSession
import com.kazumaproject.markdownhelperkeyboard.converter.session.KanaKanjiQueryRequest
import com.kazumaproject.markdownhelperkeyboard.converter.session.KanaKanjiQueryResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
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
class BunsetsuEnterCommitTest {
    private class Editor : BaseInputConnection(View(ApplicationProvider.getApplicationContext()), true) {
        private val content = SpannableStringBuilder()
        var editorActions = 0
        init { Selection.setSelection(content, 0) }
        override fun getEditable() = content
        override fun performEditorAction(actionCode: Int): Boolean { editorActions++; return true }
    }
    private val editor = Editor()
    private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(),
        com.kazumaproject.markdownhelperkeyboard.R.style.Theme_MarkdownKeyboard).also { AppPreference.init(it) }
    private val binding = MainLayoutBinding.inflate(LayoutInflater.from(context))
    private val service = spy(IMEService().also {
        ReflectionHelpers.callInstanceMethod<Unit>(it, "attachBaseContext",
            ClassParameter.from(Context::class.java, context))
    }).also {
        doReturn(editor).`when`(it).getCurrentInputConnection()
        it.zenzRuntimeClient = mock(ZenzRuntimeClient::class.java)
        it.appPreference = mock(AppPreference::class.java)
        ReflectionHelpers.setField(it, "runtimeInputSharedPreferences",
            ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("bunsetsu-enter-test", Context.MODE_PRIVATE))
        ReflectionHelpers.setField(it, "mainLayoutBinding", binding)
        ReflectionHelpers.setField(it, "composingTextArbiter", ComposingTextArbiter(
            writeComposingText = { text, cursor -> editor.setComposingText(text, cursor) },
            finishComposingText = { editor.finishComposingText() },
            copyText = { text -> SpannableString(text) },
        ))
        ReflectionHelpers.setField(it, "bunsetsuSeparation", true)
        ReflectionHelpers.setField(it, "bunsetsuCursorMove", true)
        ReflectionHelpers.setField(it, "customComposingTextPreference", true)
        ReflectionHelpers.setField(it, "inputCompositionAfterBackgroundColor", 0x77556677)
    }
    private val input get() = ReflectionHelpers.getField<MutableStateFlow<String>>(service, "_inputString")
    private val tail get() = ReflectionHelpers.getField<AtomicReference<String>>(service, "stringInTail")
    private val parts = listOf(
        BunsetsuSegmentState("わたしは", "私は"),
        BunsetsuSegmentState("とうきょうに", "東京に"),
        BunsetsuSegmentState("いきます", "行きます"),
    ).map { it.copy(candidates = listOf(Candidate(it.displayText, 1, it.reading.length.toUByte(), 0)),
        hasConvertedDisplay = true, candidatesLoaded = true) }

    private fun session(segments: List<BunsetsuSegmentState> = parts, focused: Int = 0, suffix: String = "") {
        val reading = segments.joinToString("") { it.reading }
        val type = IMEService::class.java.declaredClasses.single { it.simpleName == "BunsetsuConversionSession" }
        val constructor = type.declaredConstructors.single { it.parameterCount == 9 }.apply { isAccessible = true }
        ReflectionHelpers.setField(service, "bunsetsuConversionSession",
            constructor.newInstance(reading + suffix, reading, segments, 1L, null, suffix, focused, emptyList<List<Int>>(), 0))
        input.value = reading
        tail.set(suffix)
        ReflectionHelpers.getField<AtomicBoolean>(service, "isHenkan").set(true)
        service.setComposingText(segments.joinToString("") { it.displayText } + suffix, 1)
    }
    private fun enter() = ReflectionHelpers.callInstanceMethod<Unit>(service, "handleNonEmptyInputEnterKey",
        ClassParameter.from(List::class.java, emptyList<Candidate>()),
        ClassParameter.from(MainLayoutBinding::class.java, binding),
        ClassParameter.from(String::class.java, input.value),
        ClassParameter.from(Boolean::class.javaPrimitiveType, false))

    private fun verticalSession() {
        session()
        val type = IMEService::class.java.declaredClasses.single { it.simpleName == "VerticalSegmentSession" }
        val constructor = type.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
        ReflectionHelpers.setField(service, "verticalSegmentSession",
            constructor.newInstance(input.value, parts, null, null, 0, true, false, 1L))
    }

    private fun assertVerticalSessionCommitted() {
        assertEquals("私は東京に行きます", editor.editable.toString())
        assertEquals("", input.value)
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.editable))
        assertNull(ReflectionHelpers.getField(service, "verticalSegmentSession"))
        assertNull(ReflectionHelpers.getField(service, "bunsetsuConversionSession"))
        assertEquals(0, editor.editorActions)
    }

    @Test fun verticalScreenEnterKeepsUpstreamCommitBehavior() {
        verticalSession()
        enter()
        assertVerticalSessionCommitted()
    }

    @Test fun verticalFloatingScreenEnterKeepsUpstreamCommitBehavior() {
        verticalSession()
        ReflectionHelpers.callInstanceMethod<Unit>(service, "handleNonEmptyInputEnterKeyFloating",
            ClassParameter.from(List::class.java, emptyList<Candidate>()),
            ClassParameter.from(FloatingKeyboardLayoutBinding::class.java, mock(FloatingKeyboardLayoutBinding::class.java)),
            ClassParameter.from(String::class.java, input.value))
        assertVerticalSessionCommitted()
    }

    @Test fun verticalPhysicalEnterKeepsUpstreamCommitBehavior() {
        verticalSession()
        assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(service, "handleJapaneseEnterFloating",
            ClassParameter.from(MainLayoutBinding::class.java, binding),
            ClassParameter.from(String::class.java, input.value),
            ClassParameter.from(List::class.java, emptyList<Any>())))
        assertVerticalSessionCommitted()
    }
    private suspend fun converter(): KanaKanjiConversionSession {
        service.userDictionaryRepository = org.mockito.kotlin.mock()
        service.userTemplateRepository = org.mockito.kotlin.mock()
        ReflectionHelpers.getField<CompletableDeferred<KanaKanjiEngine>>(service, "kanaKanjiEngineReady")
            .complete(org.mockito.kotlin.mock())
        return org.mockito.kotlin.mock<KanaKanjiConversionSession>().also { converter ->
            ReflectionHelpers.setField(service, "kanaKanjiConversionSession", converter)
            org.mockito.kotlin.whenever(converter.query(org.mockito.kotlin.any())).thenAnswer { call ->
                val reading = call.getArgument<KanaKanjiQueryRequest>(0).input
                KanaKanjiQueryResult(listOf(Candidate("[$reading]", 1, reading.length.toUByte(), 0)))
            }
        }
    }
    private fun activeSession(): Any = ReflectionHelpers.getField(service, "bunsetsuConversionSession")
    private suspend fun resize(delta: Int, current: () -> Boolean = { true }) {
        val session = activeSession()
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            IMEService::class.java.getDeclaredMethod("resizeFocusedBunsetsuSegmentNow",
                session.javaClass, Int::class.javaPrimitiveType, FloatingKeyboardLayoutBinding::class.java,
                Function0::class.java, Continuation::class.java,
            ).apply { isAccessible = true }.invoke(service, session, delta, null, current, continuation)
        }
    }
    private fun assertRemaining(prefix: String, remaining: String, suffix: String = "", displayed: String = remaining) {
        assertEquals(prefix + displayed + suffix, editor.editable.toString())
        assertEquals(remaining, input.value)
        assertEquals(suffix, tail.get())
        assertEquals(prefix.length, BaseInputConnection.getComposingSpanStart(editor.editable))
        assertEquals(editor.editable.length, BaseInputConnection.getComposingSpanEnd(editor.editable))
    }
    @After fun cleanup() {
        binding.keyboardView.cancelTenKeyScope()
        listOf("scope", "ioScope").forEach { ReflectionHelpers.getField<CoroutineScope>(service, it).cancel() }
        ReflectionHelpers.getField<ExecutorCoroutineDispatcher>(service, "kanaKanjiConversionDispatcher").close()
    }

    @Test fun screenEnterCommitsFirstSegmentAndKeepsRemainingConversion() {
        session()
        enter()
        assertRemaining("私は", "とうきょうにいきます", displayed = "東京に行きます")
    }
    @Test fun focusedMiddleSegmentMatchesCandidateClickCommitBoundary() {
        session(focused = 1)
        enter()
        assertRemaining("私は東京に", "いきます", displayed = "行きます")
    }
    @Test fun selectedDisplayResultIsPreservedInsteadOfFirstCandidate() {
        session(segments = listOf(parts[0].copy(displayText = "私わ")) + parts.drop(1))
        enter()
        assertRemaining("私わ", "とうきょうにいきます", displayed = "東京に行きます")
    }
    @Test fun remainingTailIsPreservedAndConvertedAfterLastSegment() {
        session(focused = 2, suffix = "そして")
        enter()
        assertRemaining("私は東京に行きます", "そして")
    }
    @Test fun lastSegmentFinishesCompositionWithoutSendingEditorAction() {
        session(segments = listOf(parts.last()))
        enter()
        assertEquals("行きます", editor.editable.toString())
        assertEquals("", input.value)
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.editable))
        assertEquals(0, editor.editorActions)
    }
    @Test fun floatingScreenEnterUsesSamePartialCommit() {
        session()
        ReflectionHelpers.callInstanceMethod<Unit>(service, "handleNonEmptyInputEnterKeyFloating",
            ClassParameter.from(List::class.java, emptyList<Candidate>()),
            ClassParameter.from(FloatingKeyboardLayoutBinding::class.java, mock(FloatingKeyboardLayoutBinding::class.java)),
            ClassParameter.from(String::class.java, input.value))
        assertRemaining("私は", "とうきょうにいきます", displayed = "東京に行きます")
    }
    @Test fun physicalEnterUsesSamePartialCommit() {
        session()
        assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(service, "handleJapaneseEnterFloating",
            ClassParameter.from(MainLayoutBinding::class.java, binding),
            ClassParameter.from(String::class.java, input.value),
            ClassParameter.from(List::class.java, emptyList<Any>())))
        assertRemaining("私は", "とうきょうにいきます", displayed = "東京に行きます")
    }
    @Test fun repeatedEnterConfirmsExactlyOneSegmentEachTimeWithoutWaitingForCandidates() {
        session()
        enter()
        assertRemaining("私は", "とうきょうにいきます", displayed = "東京に行きます")
        ReflectionHelpers.callInstanceMethod<Unit>(service, "processInputString",
            ClassParameter.from(String::class.java, input.value),
            ClassParameter.from(MainLayoutBinding::class.java, binding))
        assertRemaining("私は", "とうきょうにいきます", displayed = "東京に行きます")
        enter()
        assertRemaining("私は東京に", "いきます", displayed = "行きます")
        enter()
        assertEquals("私は東京に行きます", editor.editable.toString())
        assertEquals("", input.value)
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.editable))
        assertEquals(0, editor.editorActions)
    }
    @Test fun enterAfterCandidateClickConfirmsNextSegmentAndKeepsLastSegment() {
        session()
        val candidate = parts.first().candidates.single()
        assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(service, "handleBunsetsuCandidateClick",
            ClassParameter.from(Candidate::class.java, candidate),
            ClassParameter.from(Int::class.javaPrimitiveType, 0),
            ClassParameter.from(List::class.java, listOf(candidate))))
        assertRemaining("私は", "とうきょうにいきます", displayed = "東京に行きます")
        enter()
        assertRemaining("私は東京に", "いきます", displayed = "行きます")
    }
    @Test fun noBunsetsuSessionLeavesExistingEnterHandlingAvailable() {
        assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(service, "commitBunsetsuConversionOnEnter",
            ClassParameter.from(MainLayoutBinding::class.java, binding)))
    }
    @Test fun leftResizesReadingAndRequeriesPairBeforeEnterContinues() = runBlocking {
        converter()
        session()
        resize(-1)
        assertEquals("[わたし][はとうきょうに]行きます", editor.editable.toString())
        assertEquals("わたしはとうきょうにいきます", input.value)
        assertEquals(0, ReflectionHelpers.getField<Int>(activeSession(), "focusedIndex"))
        enter()
        assertRemaining("[わたし]", "はとうきょうにいきます", displayed = "[はとうきょうに]行きます")
    }
    @Test fun rightResizesReadingWithoutMovingFocusOrCommitting() = runBlocking {
        converter()
        session()
        resize(1)
        assertEquals("[わたしはと][うきょうに]行きます", editor.editable.toString())
        assertEquals(0, BaseInputConnection.getComposingSpanStart(editor.editable))
        assertEquals(0, ReflectionHelpers.getField<Int>(activeSession(), "focusedIndex"))
    }
    @Test fun resizeMiddleSegmentKeepsPrefixChoiceAndTail() = runBlocking {
        converter()
        session(focused = 1, suffix = "です")
        resize(-1)
        assertEquals("私は[とうきょう][にいきます]です", editor.editable.toString())
        assertEquals("です", ReflectionHelpers.getField<String>(activeSession(), "tailText"))
        assertEquals(1, ReflectionHelpers.getField<Int>(activeSession(), "focusedIndex"))
    }
    @Test fun resizingAtLimitsIsConsumedWithoutQueryOrEditorChange() = runBlocking {
        val conversion = converter()
        session(segments = listOf(BunsetsuSegmentState("あ", "亜")))
        resize(-1)
        resize(1)
        assertEquals("亜", editor.editable.toString())
        org.mockito.kotlin.verifyNoInteractions(conversion)
    }
    @Test fun canceledCursorWorkCannotPublishResizedCandidates() = runBlocking {
        converter()
        session()
        val before = activeSession()
        var checks = 0
        resize(-1) { ++checks == 1 }
        assertSame(before, activeSession())
        assertEquals("私は東京に行きます", editor.editable.toString())
    }
}

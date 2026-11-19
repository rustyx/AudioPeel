package com.ltx.audiopeel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEditsTest {
    private val on = EndEditOption(enabled = true)
    private val off = EndEditOption(enabled = false)

    private fun ready(plan: EndEditPlan): AudioEdits = (plan as EndEditPlan.Ready).edits

    @Test
    fun defaultsAreOffWithTwelveSeconds() {
        val settings = EndEditSettings()
        assertFalse(settings.cutEnd.enabled)
        assertFalse(settings.fadeOut.enabled)
        assertEquals(12, settings.cutEnd.seconds)
        assertEquals(12, settings.fadeOut.seconds)
    }

    @Test
    fun secondsOutsideRangeAreInvalidOnlyWhenEnabled() {
        listOf("", "0", "3601", "12a").forEach { text ->
            assertNull(text, EndEditOption(secondsText = text).seconds)
            assertTrue(text, EndEditOption(enabled = false, secondsText = text).isValid)
            assertFalse(text, EndEditOption(enabled = true, secondsText = text).isValid)
        }
        assertEquals(1, EndEditOption(secondsText = "1").seconds)
        assertEquals(3600, EndEditOption(secondsText = "3600").seconds)
    }

    @Test
    fun noEditsWhenBothOff() {
        assertEquals(AudioEdits(), ready(planEndEdits(EndEditSettings(), 0L)))
    }

    @Test
    fun cutOnlyShortensOutput() {
        val edits = ready(planEndEdits(EndEditSettings(cutEnd = on, fadeOut = off), 60_000L))
        assertEquals(48_000L, edits.outputDurationMs)
        assertNull(edits.fadeOut)
        assertFalse(edits.requiresTranscode)
    }

    @Test
    fun fadeOnlyCoversLastSeconds() {
        val edits = ready(planEndEdits(EndEditSettings(cutEnd = off, fadeOut = on), 60_500L))
        assertNull(edits.outputDurationMs)
        assertEquals(AudioEdits.FadeOut(startMs = 48_500L, durationMs = 12_000L), edits.fadeOut)
        assertTrue(edits.requiresTranscode)
    }

    @Test
    fun fadeIsAppliedAfterCut() {
        val edits = ready(planEndEdits(EndEditSettings(cutEnd = on, fadeOut = on), 60_000L))
        assertEquals(48_000L, edits.outputDurationMs)
        assertEquals(AudioEdits.FadeOut(startMs = 36_000L, durationMs = 12_000L), edits.fadeOut)
    }

    @Test
    fun fadeIsShortenedToRemainingAudio() {
        val edits = ready(planEndEdits(EndEditSettings(cutEnd = on, fadeOut = on), 20_000L))
        assertEquals(8_000L, edits.outputDurationMs)
        assertEquals(AudioEdits.FadeOut(startMs = 0L, durationMs = 8_000L), edits.fadeOut)
    }

    @Test
    fun failsWhenCutIsLongerThanAudio() {
        listOf(12_000L, 5_000L).forEach { duration ->
            assertEquals(
                EndEditPlan.Failed(EndEditError.CUT_TOO_LONG),
                planEndEdits(EndEditSettings(cutEnd = on), duration)
            )
        }
    }

    @Test
    fun failsWhenDurationUnknown() {
        assertEquals(
            EndEditPlan.Failed(EndEditError.UNKNOWN_DURATION),
            planEndEdits(EndEditSettings(fadeOut = on), 0L)
        )
    }

    @Test
    fun failsWhenEnabledSecondsInvalid() {
        val settings = EndEditSettings(fadeOut = EndEditOption(enabled = true, secondsText = ""))
        assertEquals(
            EndEditPlan.Failed(EndEditError.INVALID_SECONDS),
            planEndEdits(settings, 60_000L)
        )
    }

    @Test
    fun invalidSecondsOfDisabledOptionAreIgnored() {
        val settings = EndEditSettings(
            cutEnd = EndEditOption(enabled = false, secondsText = ""), fadeOut = on
        )
        assertTrue(planEndEdits(settings, 60_000L) is EndEditPlan.Ready)
    }

    @Test
    fun settingsAccessByKind() {
        val settings = EndEditSettings().with(EndEditKind.FADE_OUT, on)
        assertEquals(on, settings[EndEditKind.FADE_OUT])
        assertEquals(EndEditOption(), settings[EndEditKind.CUT_END])
    }
}

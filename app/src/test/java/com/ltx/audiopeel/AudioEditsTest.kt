package com.ltx.audiopeel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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

    @Test
    fun commandWithoutEditsIsUnchanged() {
        assertEquals(
            "-y -nostdin -i in -vn -threads 2 -c:a copy -f mp4 -movflags +faststart \"/o.m4a\"",
            buildFfmpegCommand("in", "/o.m4a", OutputFormat.M4A, "aac", 2)
        )
        assertEquals(
            "-y -nostdin -i in -vn -threads 2 -c:a libmp3lame -q:a 2 \"/o.mp3\"",
            buildFfmpegCommand("in", "/o.mp3", OutputFormat.MP3, "aac", 2)
        )
    }

    @Test
    fun cutOnlyKeepsStreamCopy() {
        val edits = AudioEdits(outputDurationMs = 48_000L)
        assertEquals(
            "-y -nostdin -i in -vn -threads 1 -t 48.000 -c:a copy -f mp4 -movflags +faststart \"/o.m4a\"",
            buildFfmpegCommand("in", "/o.m4a", OutputFormat.M4A, "aac", 1, edits)
        )
    }

    @Test
    fun fadeForcesTranscodeInEveryFormat() {
        val edits = AudioEdits(
            outputDurationMs = 48_250L, fadeOut = AudioEdits.FadeOut(36_250L, 12_000L)
        )
        val sourceCodecs = mapOf(
            OutputFormat.MP3 to "mp3",
            OutputFormat.M4A to "aac",
            OutputFormat.WAV to "pcm",
            OutputFormat.FLAC to "flac",
            OutputFormat.OGG to "opus",
        )
        sourceCodecs.forEach { (format, codec) ->
            val cmd = buildFfmpegCommand("in", "/o", format, codec, 1, edits)
            assertFalse(cmd, cmd.contains("copy"))
            assertTrue(
                cmd,
                cmd.contains(
                    " -af asetpts=PTS-STARTPTS,afade=t=out:st=36.250:d=12.000 -t 48.250 "
                )
            )
        }
    }

    @Test
    fun numbersIgnoreLocale() {
        val saved = Locale.getDefault()
        try {
            listOf(Locale.GERMANY, Locale.forLanguageTag("ar-EG")).forEach { locale ->
                Locale.setDefault(locale)
                val edits = AudioEdits(
                    outputDurationMs = 5_005L, fadeOut = AudioEdits.FadeOut(5L, 5_000L)
                )
                val cmd = buildFfmpegCommand("in", "/o.wav", OutputFormat.WAV, "", 4, edits)
                assertTrue(cmd, cmd.contains("afade=t=out:st=0.005:d=5.000 -t 5.005 "))
                assertTrue(cmd, cmd.contains("-threads 4 "))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }
}

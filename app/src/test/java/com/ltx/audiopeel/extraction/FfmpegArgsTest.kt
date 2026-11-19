package com.ltx.audiopeel.extraction

import com.ltx.audiopeel.AudioEdits
import com.ltx.audiopeel.OutputFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.Locale

class FfmpegArgsTest {
    private val base = listOf("-y", "-nostdin", "-i", "in", "-map", "0:a:0", "-vn")

    @Test
    fun argsWithoutEdits() {
        assertEquals(
            base + listOf("-threads", "2", "-c:a", "copy", "-f", "mp4", "-movflags", "+faststart", "/o.m4a"),
            buildSinglePassArgs("in", "/o.m4a", OutputFormat.M4A, "aac", 2)
        )
        assertEquals(
            base + listOf("-threads", "2", "-c:a", "libmp3lame", "-q:a", "2", "/o.mp3"),
            buildSinglePassArgs("in", "/o.mp3", OutputFormat.MP3, "aac", 2)
        )
    }

    @Test
    fun outputPathIsOneArgument() {
        val args = buildSinglePassArgs("in", "/a b/o \"x\".wav", OutputFormat.WAV, "", 1)
        assertEquals("/a b/o \"x\".wav", args.last())
    }

    @Test
    fun cutOnlyKeepsStreamCopy() {
        val edits = AudioEdits(outputDurationMs = 48_000L)
        assertEquals(
            base + listOf(
                "-threads", "1", "-t", "48.000",
                "-c:a", "copy", "-f", "mp4", "-movflags", "+faststart", "/o.m4a"
            ),
            buildSinglePassArgs("in", "/o.m4a", OutputFormat.M4A, "aac", 1, edits)
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
            val args = buildSinglePassArgs("in", "/o", format, codec, 1, edits)
            assertFalse(args.toString(), args.contains("copy"))
            val expected = listOf(
                "-af", "asetpts=PTS-STARTPTS,afade=t=out:st=36.250:d=12.000", "-t", "48.250"
            )
            assertTrue(args.toString(), Collections.indexOfSubList(args, expected) >= 0)
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
                val args = buildSinglePassArgs("in", "/o.wav", OutputFormat.WAV, "", 4, edits)
                assertEquals(
                    listOf(
                        "-threads", "4", "-af", "asetpts=PTS-STARTPTS,afade=t=out:st=0.005:d=5.000",
                        "-t", "5.005"
                    ),
                    args.subList(base.size, base.size + 6)
                )
                assertEquals("15.669333", samplesToSeconds(752_128L, 48_000))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }
}

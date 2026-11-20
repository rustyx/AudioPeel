package com.ltx.audiopeel.extraction

import com.ltx.audiopeel.AudioEdits
import com.ltx.audiopeel.EndEditOption
import com.ltx.audiopeel.EndEditPlan
import com.ltx.audiopeel.EndEditSettings
import com.ltx.audiopeel.OutputFormat
import com.ltx.audiopeel.planEndEdits
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Runs the extraction against real FFmpeg in Docker
 *
 * Skipped unless AUDIOPEEL_TEST_FFMPEG_IMAGE names an image with ffmpeg and
 * ffprobe, ideally the FFmpeg version FFmpegKit bundles, e.g.
 * `AUDIOPEEL_TEST_FFMPEG_IMAGE=jrottenberg/ffmpeg:6.0-alpine ./gradlew testDebugUnitTest`
 */
class FfmpegIntegrationTest {
    private val image: String? = System.getenv("AUDIOPEEL_TEST_FFMPEG_IMAGE")
    private lateinit var dir: File
    private lateinit var runner: DockerRunner
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        assumeTrue("AUDIOPEEL_TEST_FFMPEG_IMAGE not set", !image.isNullOrBlank())
        dir = Files.createTempDirectory("audiopeel-it").toFile()
        runner = DockerRunner(image!!, dir)
    }

    @After
    fun tearDown() {
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    @Test
    fun aacFadeIsSplicedSeamlessly() {
        assertSplicedSeamlessly(makeSource("aac", "-b:a", "128k"), cutSeconds = 5)
    }

    @Test
    fun fadeOnlyIsSplicedSeamlessly() {
        assertSplicedSeamlessly(makeSource("aac", "-b:a", "128k"), cutSeconds = null)
    }

    @Test
    fun fdkAacFadeIsSplicedSeamlessly() {
        assumeFdk()
        // Different encoder delay (2048 samples) and block switching than FFmpeg's encoder
        assertSplicedSeamlessly(makeSource("libfdk_aac", "-b:a", "128k"), cutSeconds = 5)
    }

    @Test
    fun delayedAudioIsSplicedSeamlessly() {
        // Audio starting after the video, as after trimming in an editor
        val source = makeSource("aac", "-b:a", "128k", audioDelay = "0.7")
        assertSplicedSeamlessly(source, cutSeconds = null)
    }

    private fun assertSplicedSeamlessly(source: File, cutSeconds: Int?) {
        val fadeSeconds = 8
        // Re-encoding everything, for comparison
        val singlePass = File(dir, "single.m4a")
        val edits = edits(cutSeconds, fadeSeconds)
        assertTrue(runner.ffmpeg(buildSinglePassArgs(source.path, singlePass.path, OutputFormat.M4A, "aac", 2, edits)).isSuccess)
        val result = extract(source, cutSeconds, fadeSeconds)
        assertEquals(ExtractionResult.Success(ExtractionResult.Method.SPLICED), result)
        val junction = logs.single { it.startsWith(AudioExtractor.JUNCTION_LOG) }
            .removePrefix(AudioExtractor.JUNCTION_LOG).substringBefore(' ').toInt()

        val out = decode(File(dir, "out.m4a"))
        val ref = decode(source)
        val end = (SOURCE_SECONDS - (cutSeconds ?: 0)) * RATE
        val fadeStart = end - fadeSeconds * RATE
        assertEquals("length", end.toDouble(), (out.size / CHANNELS).toDouble(), RATE * 0.03)
        assertTrue("junction $junction before fade $fadeStart", junction in fadeStart - 3 * RATE..fadeStart)
        // Copied audio decodes exactly like the source up to the frame overlapping the junction
        val copied = (junction - AAC_FRAME_SAMPLES) * CHANNELS
        val firstDifference = (0 until minOf(copied, out.size)).firstOrNull { out[it] != ref[it] }
        assertEquals("first differing sample", null, firstDifference)

        fun expected(i: Int) = ref[i] * when {
            i / CHANNELS < fadeStart -> 1f
            else -> (1f - (i / CHANNELS - fadeStart).toFloat() / (end - fadeStart)).coerceAtLeast(0f)
        }

        fun windowError(from: Int) = sqrt(
            (from * CHANNELS until (from + WINDOW) * CHANNELS)
                .sumOf { ((out[it] - expected(it)) * (out[it] - expected(it))).toDouble() } / (WINDOW * CHANNELS)
        )
        // No click: the frames around the junction are no worse than ordinary coding error after them
        val around = (junction - AAC_FRAME_SAMPLES until junction + 2 * AAC_FRAME_SAMPLES step WINDOW)
            .map { windowError(it) }
        val following = (junction + 2 * AAC_FRAME_SAMPLES until junction + RATE step WINDOW)
            .maxOf { windowError(it) }
        println("junction windows ${around.joinToString { "%.5f".format(it) }}, following max $following")
        assertTrue("junction ${around.max()} vs following $following", around.max() <= following)
        // The fade follows the expected curve as closely as when re-encoding everything
        val faded = (fadeStart * CHANNELS until (end - AAC_FRAME_SAMPLES) * CHANNELS)
        fun errorDb(audio: FloatArray) = 10 * log10(
            faded.sumOf { ((audio[it] - expected(it)) * (audio[it] - expected(it))).toDouble() } /
                faded.sumOf { (expected(it) * expected(it)).toDouble() }
        )
        val spliced = errorDb(out)
        val reEncoded = errorDb(decode(singlePass))
        println("fade error: spliced $spliced dB, re-encoded $reEncoded dB")
        assertTrue("fade error $spliced dB", spliced < -20)
        assertTrue("fade error $spliced dB vs $reEncoded dB", spliced < reEncoded + 2)
    }

    @Test
    fun heAacFallsBackToSinglePass() {
        assumeFdk()
        val source = makeSource("libfdk_aac", "-profile:a", "aac_he", "-b:a", "64k")
        val result = extract(source, cutSeconds = 5, fadeSeconds = 8)
        assertEquals(ExtractionResult.Success(ExtractionResult.Method.SINGLE_PASS), result)
        // Detected before copying the audio
        assertTrue(logs.toString(), logs.single().contains("unsupported source stream"))
    }

    private fun assumeFdk() {
        val encoders = runner.run("ffmpeg", listOf("-encoders")).output
        assumeTrue("libfdk_aac not available", "libfdk_aac" in encoders)
    }

    private fun edits(cutSeconds: Int?, fadeSeconds: Int): AudioEdits {
        val settings = EndEditSettings(
            cutEnd = EndEditOption(enabled = cutSeconds != null, secondsText = "${cutSeconds ?: 1}"),
            fadeOut = EndEditOption(enabled = true, secondsText = "$fadeSeconds")
        )
        return (planEndEdits(settings, SOURCE_SECONDS * 1000L) as EndEditPlan.Ready).edits
    }

    private fun extract(source: File, cutSeconds: Int?, fadeSeconds: Int): ExtractionResult {
        val edits = edits(cutSeconds, fadeSeconds)
        val request = ExtractionRequest(
            input = { source.path },
            outFile = File(dir, "out.m4a"),
            format = OutputFormat.M4A,
            sourceCodec = "aac",
            threads = 2,
            edits = edits,
            sourceDurationMs = SOURCE_SECONDS * 1000L
        )
        return AudioExtractor(runner, File(dir, "work")) { println(it); logs += it }.extract(request) {}
    }

    /** A video with tones and sharp transients, which make the encoder switch block sizes */
    private fun makeSource(vararg audioCodecArgs: String, audioDelay: String = "0"): File {
        val file = File(dir, "source.mp4")
        val audio = "aevalsrc='0.25*sin(2*PI*220*t)+0.7*exp(-300*mod(t,0.25))*sin(2*PI*2500*t)" +
            "|0.25*sin(2*PI*330*t)+0.7*exp(-300*mod(t+0.1,0.25))*sin(2*PI*1800*t)':s=$RATE"
        val args = listOf(
            "-y", "-f", "lavfi", "-t", "${SOURCE_SECONDS + 1}", "-i", "testsrc=size=160x120:rate=25",
            "-itsoffset", audioDelay, "-f", "lavfi", "-t", "$SOURCE_SECONDS", "-i", audio,
            "-c:v", "mpeg4", "-c:a"
        ) + audioCodecArgs + file.path
        assertTrue(runner.ffmpeg(args).isSuccess)
        return file
    }

    private fun decode(file: File): FloatArray {
        val raw = File(dir, "${file.nameWithoutExtension}.f32")
        assertTrue(runner.ffmpeg(listOf("-y", "-i", file.path, "-map", "0:a:0", "-f", "f32le", raw.path)).isSuccess)
        val buffer = ByteBuffer.wrap(raw.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    private class DockerRunner(private val image: String, private val dir: File) : MediaToolRunner {
        private val user = listOf("id -u", "id -g").joinToString(":") { command ->
            ProcessBuilder(command.split(" ")).start().inputStream.bufferedReader().readText().trim()
        }

        override val isCancelled = false

        override fun ffmpeg(args: List<String>, onProgress: (Long) -> Unit) = run("ffmpeg", args)

        override fun ffprobe(args: List<String>) = run("ffprobe", args)

        fun run(tool: String, args: List<String>): ToolResult {
            val command = listOf(
                "docker", "run", "--rm", "--user", user, "-v", "${dir.path}:${dir.path}",
                "--entrypoint", tool, image, "-hide_banner"
            ) + args
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val outcome = if (process.waitFor() == 0) ToolResult.Outcome.SUCCESS else ToolResult.Outcome.FAILED
            return ToolResult(outcome, output)
        }
    }

    private companion object {
        const val RATE = 48_000
        const val CHANNELS = 2
        const val WINDOW = 128
        const val SOURCE_SECONDS = 30
    }
}

package com.ltx.audiopeel.extraction

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.ltx.audiopeel.EndEditOption
import com.ltx.audiopeel.EndEditPlan
import com.ltx.audiopeel.EndEditSettings
import com.ltx.audiopeel.OutputFormat
import com.ltx.audiopeel.planEndEdits
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the splice with the app's FFmpegKit build and Android's AAC encoder
 *
 * The sources in the test assets hold 30 s of tones with sharp transients,
 * encoded by FFmpeg's AAC-LC encoder and by Fraunhofer's HE-AAC encoder.
 */
@RunWith(AndroidJUnit4::class)
class DeviceSpliceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "device-splice-test")
    private val runner = FfmpegKitRunner()
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        dir.deleteRecursively()
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun encoderProducesJoinableHeAac() {
        val pcm = File(dir, "calibration.pcm")
        val signal = calibrationSignal(RATE, CHANNELS)
        writePcm(signal, pcm)
        val result = MediaCodecAacEncoder().encodeHeAac(pcm, 0, AacEncoderConfig(RATE, CHANNELS, 48_000)) { false }
        assertTrue("$result", result is AacEncodeResult.Success)
        result as AacEncodeResult.Success
        val config = parseAudioSpecificConfig(assertNotNull(result.config).let { result.config!! })
        Log.i(TAG, "config ${result.config?.toHex()} $config, ${result.frames.size} frames")
        assertTrue("$config", config != null && isJoinableHeAacConfig(config, RATE, CHANNELS))
        // FFmpeg decodes it with SBR, and the delay can be measured
        val adts = File(dir, "calibration.aac")
        adts.outputStream().use { writeAdts(result.frames, RATE / 2, CHANNELS, it) }
        val decoded = decode(adts)
        assertTrue(decoded.size / CHANNELS >= (result.frames.size - 1) * AacProfile.HE.frameSamples)
        val delay = measureDelay(signal, decoded, CHANNELS, RATE, delaySearchRange(AacProfile.HE.frameSamples))
        Log.i(TAG, "delay $delay")
        assertNotNull(delay)
    }

    @Test
    fun heAacFadeIsSpliced() {
        val source = copyAsset("he-aac.m4a")
        assertSpliced(source, AacProfile.HE) { source.path }
    }

    @Test
    fun lcAacFadeIsSpliced() {
        val source = copyAsset("lc-aac.m4a")
        assertSpliced(source, AacProfile.LC) { source.path }
    }

    /** The app reads videos through content URIs, opened by FFmpegKit's SAF protocol */
    @Test
    fun contentUriInputIsSpliced() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val source = copyAsset("he-aac.m4a")
        val uri = insertDownload(source)
        try {
            assertSpliced(source, AacProfile.HE) { FFmpegKitConfig.getSafParameterForRead(context, uri) }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    /**
     * Extracts a real video given as instrumentation arguments, as the app
     * would with the default end edits; skipped without one. For example:
     * `adb shell am instrument -w -e class com.ltx.audiopeel.extraction.DeviceSpliceTest#externalVideo
     * -e video /path/readable/by/the/app.mp4 -e durationMs 2171948
     * com.ltx.audiopeel.test/androidx.test.runner.AndroidJUnitRunner`.
     * With `-e keepOutput true` the result is kept as external-video-out.m4a in the app's cache
     */
    @Test
    fun externalVideo() {
        val arguments = InstrumentationRegistry.getArguments()
        val video = arguments.getString("video")
        assumeTrue("no video argument", video != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val durationMs = arguments.getString("durationMs")!!.toLong()
        val uri = insertDownload(File(video!!))
        try {
            val settings = EndEditSettings(
                cutEnd = EndEditOption(enabled = true, secondsText = "12"),
                fadeOut = EndEditOption(enabled = true, secondsText = "12")
            )
            val started = System.nanoTime()
            val result = extract(durationMs, settings) { FFmpegKitConfig.getSafParameterForRead(context, uri) }
            Log.i(TAG, "$result in ${(System.nanoTime() - started) / 1_000_000} ms")
            if (arguments.getString("keepOutput") == "true") {
                File(dir, OUT).copyTo(File(context.cacheDir, "external-video-out.m4a"), overwrite = true)
            }
            assertEquals(logs.toString(), ExtractionResult.Success(ExtractionResult.Method.SPLICED), result)
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    private fun assertSpliced(source: File, profile: AacProfile, input: () -> String) {
        val settings = EndEditSettings(
            cutEnd = EndEditOption(enabled = true, secondsText = "5"),
            fadeOut = EndEditOption(enabled = true, secondsText = "8")
        )
        val result = extract(SOURCE_SECONDS * 1000L, settings, input)
        assertEquals(logs.toString(), ExtractionResult.Success(ExtractionResult.Method.SPLICED), result)

        val junction = logs.single { it.startsWith(AudioExtractor.JUNCTION_LOG) }
            .removePrefix(AudioExtractor.JUNCTION_LOG).substringBefore(' ').toInt()
        val decoded = decode(File(dir, OUT))
        val reference = decode(source)
        val end = (SOURCE_SECONDS - 5) * RATE
        assertEquals("length", end.toDouble(), (decoded.size / CHANNELS).toDouble(), RATE * 0.03)
        val copied = (junction - profile.frameSamples) * CHANNELS
        assertEquals("first differing sample", null, (0 until copied).firstOrNull { decoded[it] != reference[it] })
    }

    private fun extract(durationMs: Long, settings: EndEditSettings, input: () -> String): ExtractionResult {
        val edits = (planEndEdits(settings, durationMs) as EndEditPlan.Ready).edits
        val request = ExtractionRequest(
            input = input,
            outFile = File(dir, OUT),
            format = OutputFormat.M4A,
            sourceCodec = "aac",
            threads = 2,
            edits = edits,
            sourceDurationMs = durationMs
        )
        return AudioExtractor(runner, File(dir, "work"), MediaCodecAacEncoder()) {
            Log.i(TAG, it)
            logs += it
        }.extract(request) {}
    }

    private fun copyAsset(asset: String): File {
        val file = File(dir, asset)
        InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertDownload(file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "audiopeel-test-${System.nanoTime()}.${file.extension}")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { output -> file.inputStream().use { it.copyTo(output) } }
        return uri
    }

    private fun decode(file: File): ShortArray {
        val pcm = File(dir, "${file.name}.pcm")
        val result = runner.ffmpeg(listOf("-y", "-i", file.path, "-f", "s16le", "-c:a", "pcm_s16le", pcm.path)) {}
        assertEquals(result.output, ToolResult.Outcome.SUCCESS, result.outcome)
        return readPcm(pcm)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "DeviceSpliceTest"
        const val RATE = 44_100
        const val CHANNELS = 2
        const val SOURCE_SECONDS = 30
        const val OUT = "out.m4a"
    }
}

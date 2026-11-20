package com.ltx.audiopeel.extraction

import com.ltx.audiopeel.AudioEdits
import com.ltx.audiopeel.OutputFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AudioExtractorTest {
    private lateinit var root: File
    private lateinit var workDir: File
    private lateinit var outFile: File
    private val runner = FakeRunner()
    private var inputsOpened = 0
    private val logs = mutableListOf<String>()

    private val fadeAndCut = AudioEdits(
        outputDurationMs = 27_700L, fadeOut = AudioEdits.FadeOut(15_700L, 12_000L)
    )

    @Before
    fun setUp() {
        root = Files.createTempDirectory("extractor").toFile()
        workDir = File(root, "work")
        outFile = File(root, "out.m4a")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun request(
        format: OutputFormat = OutputFormat.M4A,
        codec: String = "aac",
        edits: AudioEdits = fadeAndCut,
    ) = ExtractionRequest(
        input = { "saf:${++inputsOpened}" },
        outFile = outFile,
        format = format,
        sourceCodec = codec,
        threads = 2,
        edits = edits,
        sourceDurationMs = 39_700L
    )

    private fun extract(request: ExtractionRequest = request(), progress: MutableList<Float> = mutableListOf()) =
        AudioExtractor(runner, workDir) { logs += it }.extract(request) { progress += it }

    @Test
    fun aacFadeToM4aIsSpliced() {
        val progress = mutableListOf<Float>()
        assertEquals(ExtractionResult.Success(ExtractionResult.Method.SPLICED), extract(progress = progress))
        assertEquals(
            listOf(
                Step.SOURCE_PROBE, Step.HEAD, Step.STREAM_PROBE, Step.PACKET_PROBE, Step.TAIL, Step.TAIL_PROBE,
                Step.JOIN, Step.DURATION_PROBE
            ),
            runner.steps
        )
        assertEquals(2, inputsOpened)
        assertTrue(outFile.exists())
        assertFalse("work files are removed", workDir.exists())
        assertTrue(runner.concatScript!!.contains("outpoint 15.669333"))
        assertEquals("${AudioExtractor.JUNCTION_LOG}752640 (fade at 753600)", logs.single())
        assertEquals(progress.sorted(), progress)
        assertEquals(1f, progress.last(), 0.001f)
    }

    @Test
    fun otherFormatsAndCodecsUseSinglePass() {
        listOf(
            request(format = OutputFormat.MP3),
            request(codec = "opus"),
            request(edits = AudioEdits(outputDurationMs = 27_700L)),
            request(edits = AudioEdits()),
        ).forEach { request ->
            runner.steps.clear()
            assertEquals(ExtractionResult.Success(ExtractionResult.Method.SINGLE_PASS), extract(request))
            assertEquals(listOf(Step.SINGLE_PASS), runner.steps)
        }
    }

    @Test
    fun unsupportedSourceFallsBackBeforeCopying() {
        // HE-AAC, with timestamps counting the core's samples
        runner.sourceProbeOutput = runner.sourceProbeOutput.replace("profile=LC", "profile=4")
            .replace("time_base=1/48000", "time_base=1/24000")
        assertEquals(ExtractionResult.Success(ExtractionResult.Method.SINGLE_PASS), extract())
        assertEquals(listOf(Step.SOURCE_PROBE, Step.SINGLE_PASS), runner.steps)
        assertEquals(2, inputsOpened)
        assertTrue(runner.singlePassArgs!!.contains("saf:2"))
        assertTrue(logs.single().contains("unsupported source stream"))
        assertFalse(workDir.exists())
    }

    @Test
    fun sourceTimestampsDontMatter() {
        // The head file gets its own timestamps
        runner.sourceProbeOutput = runner.sourceProbeOutput.replace("time_base=1/48000", "time_base=1/90000")
        assertEquals(ExtractionResult.Success(ExtractionResult.Method.SPLICED), extract())
    }

    @Test
    fun unprobeableSourceIsCheckedAfterCopying() {
        runner.failing = Step.SOURCE_PROBE
        assertEquals(ExtractionResult.Success(ExtractionResult.Method.SPLICED), extract())
        runner.steps.clear()
        runner.streamProbeOutput = runner.streamProbeOutput.replace("profile=LC", "profile=4")
        assertEquals(ExtractionResult.Success(ExtractionResult.Method.SINGLE_PASS), extract())
        assertEquals(listOf(Step.SOURCE_PROBE, Step.HEAD, Step.STREAM_PROBE, Step.SINGLE_PASS), runner.steps)
        assertTrue(logs.last().contains("unsupported stream"))
    }

    @Test
    fun noSteadyJunctionFallsBack() {
        runner.packetHeader = "2120 00"  // long-start: the source switches to short blocks everywhere
        assertEquals(ExtractionResult.Success(ExtractionResult.Method.SINGLE_PASS), extract())
        assertEquals(
            listOf(Step.SOURCE_PROBE, Step.HEAD, Step.STREAM_PROBE, Step.PACKET_PROBE, Step.SINGLE_PASS),
            runner.steps
        )
        assertTrue(logs.single().contains("no steady frame boundary"))
    }

    @Test
    fun tailStartingWithShortBlocksFallsBack() {
        runner.tailHeader = "2140 00"  // eight-short
        assertEquals(ExtractionResult.Success(ExtractionResult.Method.SINGLE_PASS), extract())
        assertEquals(Step.TAIL_PROBE, runner.steps[runner.steps.size - 2])
        assertTrue(logs.last().contains("tail starts with"))
    }

    @Test
    fun failedStepFallsBack() {
        listOf(
            Step.HEAD, Step.STREAM_PROBE, Step.PACKET_PROBE, Step.TAIL, Step.TAIL_PROBE, Step.JOIN,
            Step.DURATION_PROBE
        )
            .forEach { failing ->
                runner.steps.clear()
                runner.failing = failing
                assertEquals(
                    failing.name,
                    ExtractionResult.Success(ExtractionResult.Method.SINGLE_PASS),
                    extract()
                )
                assertEquals(failing.name, Step.SINGLE_PASS, runner.steps.last())
                assertFalse(workDir.exists())
            }
    }

    @Test
    fun unexpectedDurationFallsBack() {
        runner.durationOutput = "16294.677333\n"
        assertEquals(ExtractionResult.Success(ExtractionResult.Method.SINGLE_PASS), extract())
        assertTrue(logs.last().contains("expected 27700 ms"))
    }

    @Test
    fun cancelDuringSpliceStopsWithoutFallback() {
        listOf(Step.SOURCE_PROBE, Step.HEAD, Step.TAIL, Step.JOIN).forEach { cancelling ->
            runner.steps.clear()
            runner.cancelling = cancelling
            runner.isCancelled = false
            assertEquals(cancelling.name, ExtractionResult.Cancelled, extract())
            assertEquals(cancelling, runner.steps.last())
            assertFalse(outFile.exists())
            assertFalse(workDir.exists())
        }
    }

    @Test
    fun singlePassFailureIsReported() {
        runner.failing = Step.SINGLE_PASS
        val result = extract(request(format = OutputFormat.WAV))
        assertTrue(result is ExtractionResult.Failed)
        assertFalse(outFile.exists())
    }

    @Test
    fun singlePassProgressUsesOutputDuration() {
        val progress = mutableListOf<Float>()
        runner.progressMs = 13_850L
        extract(request(format = OutputFormat.WAV), progress)
        assertEquals(listOf(0.5f), progress)
    }

    private enum class Step { SOURCE_PROBE, HEAD, STREAM_PROBE, PACKET_PROBE, TAIL, TAIL_PROBE, JOIN, DURATION_PROBE, SINGLE_PASS }

    /**
     * Recognizes each step from its arguments and simulates it
     */
    private class FakeRunner : MediaToolRunner {
        val steps = mutableListOf<Step>()
        var failing: Step? = null
        var cancelling: Step? = null
        var progressMs: Long? = null
        var streamProbeOutput =
            "codec_name=aac\nprofile=LC\nsample_rate=48000\nchannels=2\ntime_base=1/48000\nstart_pts=0\n"
        var sourceProbeOutput = streamProbeOutput

        /** First bytes of the head packets: channel pair, long-only window, KBD shape */
        var packetHeader = "211a 08"

        /** First bytes of the tail's first packet */
        var tailHeader = "211a 08"
        var durationOutput = "27.712000\n"
        var concatScript: String? = null
        var singlePassArgs: List<String>? = null
        override var isCancelled = false

        override fun ffmpeg(args: List<String>, onProgress: (Long) -> Unit): ToolResult {
            val step = when {
                "concat" in args -> Step.JOIN.also { concatScript = File(args[args.indexOf("-i") + 1]).readText() }
                args.any { it.startsWith("noise=drop") } -> Step.TAIL
                args.any { it.endsWith("head.m4a") } -> Step.HEAD
                else -> Step.SINGLE_PASS.also { singlePassArgs = args }
            }
            steps += step
            if (step == cancelling) {
                isCancelled = true
                return ToolResult(ToolResult.Outcome.CANCELLED)
            }
            // The output exists even when FFmpeg fails half-way
            File(args.last()).writeText("audio")
            if (step == failing) return ToolResult(ToolResult.Outcome.FAILED, "boom")
            val totalMs = when (step) {
                Step.HEAD, Step.JOIN -> 27_700L
                Step.TAIL -> 12_020L
                else -> null
            }
            (progressMs ?: totalMs)?.let(onProgress)
            return ToolResult(ToolResult.Outcome.SUCCESS)
        }

        override fun ffprobe(args: List<String>): ToolResult {
            val (step, output) = when {
                args.contains("%+#1") -> Step.TAIL_PROBE to "pts=2016\ndata=\n00000000: $tailHeader\n"
                args.contains("packet=pts,data") -> Step.PACKET_PROBE to
                    generateSequence(650_240L) { it + 1024 }.take(110)
                        .joinToString("\n") { "pts=$it\ndata=\n00000000: $packetHeader 1234  !..\n" }
                args.contains("format=duration") -> Step.DURATION_PROBE to durationOutput
                args.last().startsWith("saf:") -> Step.SOURCE_PROBE to sourceProbeOutput
                else -> Step.STREAM_PROBE to streamProbeOutput
            }
            steps += step
            if (step == cancelling) {
                isCancelled = true
                return ToolResult(ToolResult.Outcome.CANCELLED)
            }
            if (step == failing) return ToolResult(ToolResult.Outcome.FAILED, "boom")
            return ToolResult(ToolResult.Outcome.SUCCESS, output)
        }
    }
}

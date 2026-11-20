package com.ltx.audiopeel.extraction

import com.ltx.audiopeel.AudioEdits
import com.ltx.audiopeel.OutputFormat
import java.io.File
import kotlin.math.abs

/**
 * What to extract
 *
 * @param input returns an FFmpeg input for the video; called for each FFmpeg
 *        or FFprobe run on it, since some inputs (SAF parameters) can only be
 *        opened once
 * @param outFile output file
 * @param format output format
 * @param sourceCodec codec of the source audio track, empty if unknown
 * @param threads FFmpeg thread count
 * @param edits edits to apply
 * @param sourceDurationMs length of the source audio, or 0 if unknown
 */
data class ExtractionRequest(
    val input: () -> String,
    val outFile: File,
    val format: OutputFormat,
    val sourceCodec: String,
    val threads: Int,
    val edits: AudioEdits,
    val sourceDurationMs: Long,
)

sealed interface ExtractionResult {
    /** @param method how the audio was produced */
    data class Success(val method: Method) : ExtractionResult
    data object Cancelled : ExtractionResult
    data class Failed(val details: String) : ExtractionResult

    enum class Method {
        /** Copied or transcoded in one FFmpeg run */
        SINGLE_PASS,

        /** Copied up to the fade, with only the fade re-encoded */
        SPLICED,
    }
}

internal sealed interface SpliceOutcome {
    data object Done : SpliceOutcome
    data object Cancelled : SpliceOutcome
    data class Unavailable(val reason: String) : SpliceOutcome
}

/** Maps an unsuccessful step to the splice outcome, or null if it succeeded */
internal fun ToolResult.toSpliceFailure(runner: MediaToolRunner, step: String): SpliceOutcome? = when {
    outcome == ToolResult.Outcome.CANCELLED || runner.isCancelled -> SpliceOutcome.Cancelled
    outcome == ToolResult.Outcome.FAILED ->
        SpliceOutcome.Unavailable("$step failed: ${output.takeLast(MAX_LOGGED_OUTPUT)}")
    else -> null
}

private const val MAX_LOGGED_OUTPUT = 2000

internal fun fraction(positionMs: Long, totalMs: Long): Float =
    if (totalMs > 0) (positionMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f

/**
 * Extracts audio, re-encoding only the faded end of AAC audio when possible
 *
 * @param runner runs FFmpeg and FFprobe
 * @param workDir directory for intermediate files; emptied after each run
 * @param heAacEncoder encodes HE-AAC tails; without one, HE-AAC is re-encoded entirely
 * @param log receives diagnostic messages
 */
class AudioExtractor(
    private val runner: MediaToolRunner,
    private val workDir: File,
    private val heAacEncoder: AacEncoder? = null,
    private val log: (String) -> Unit = {},
) {
    /**
     * Runs the extraction; the output file is deleted unless it succeeds
     *
     * @param request what to extract
     * @param onProgress receives the progress from 0 to 1
     */
    fun extract(request: ExtractionRequest, onProgress: (Float) -> Unit): ExtractionResult {
        val result = try {
            extractOrSplice(request, onProgress)
        } finally {
            workDir.deleteRecursively()
        }
        if (result !is ExtractionResult.Success) request.outFile.delete()
        return result
    }

    private fun extractOrSplice(
        request: ExtractionRequest,
        onProgress: (Float) -> Unit,
    ): ExtractionResult {
        val fadeOut = request.edits.fadeOut
        if (fadeOut != null && request.format == OutputFormat.M4A && request.sourceCodec == "aac") {
            when (val outcome = splice(request, fadeOut, onProgress)) {
                SpliceOutcome.Done -> return ExtractionResult.Success(ExtractionResult.Method.SPLICED)
                SpliceOutcome.Cancelled -> return ExtractionResult.Cancelled
                is SpliceOutcome.Unavailable ->
                    log("Can't re-encode only the fade (${outcome.reason}), re-encoding all audio")
            }
            request.outFile.delete()
            onProgress(0f)
        }
        return singlePass(request, onProgress)
    }

    private fun singlePass(
        request: ExtractionRequest,
        onProgress: (Float) -> Unit,
    ): ExtractionResult {
        val args = buildSinglePassArgs(
            input = request.input(),
            outPath = request.outFile.path,
            format = request.format,
            sourceCodec = request.sourceCodec,
            threads = request.threads,
            edits = request.edits
        )
        val totalMs = request.edits.outputDurationMs ?: request.sourceDurationMs
        val result = runner.ffmpeg(args) { onProgress(fraction(it, totalMs)) }
        return when (result.outcome) {
            ToolResult.Outcome.SUCCESS -> ExtractionResult.Success(ExtractionResult.Method.SINGLE_PASS)
            ToolResult.Outcome.CANCELLED -> ExtractionResult.Cancelled
            ToolResult.Outcome.FAILED -> ExtractionResult.Failed(result.output)
        }
    }

    /** Whether a tail for this stream can be encoded */
    private fun canEncode(stream: AudioStreamInfo) = when (stream.aacProfile) {
        AacProfile.LC -> true
        AacProfile.HE -> heAacEncoder?.canEncodeHeAac(stream.sampleRate, stream.codedChannels) == true
        null -> false
    }

    private fun splice(
        request: ExtractionRequest,
        fadeOut: AudioEdits.FadeOut,
        onProgress: (Float) -> Unit,
    ): SpliceOutcome {
        val endMs = fadeOut.startMs + fadeOut.durationMs
        workDir.deleteRecursively()
        if (!workDir.mkdirs()) return SpliceOutcome.Unavailable("can't create ${workDir.name}")
        val head = File(workDir, HEAD_FILE)
        val tail = File(workDir, TAIL_FILE)
        val script = File(workDir, CONCAT_SCRIPT)

        // 1. Check the codec first: copying the audio takes most of the time,
        // wasted if the source can't be spliced. If the source can't be
        // probed, the head file is checked below instead
        val sourceProbe = runner.ffprobe(buildStreamProbeArgs(request.input()))
        if (sourceProbe.toSpliceFailure(runner, "source probe") == SpliceOutcome.Cancelled) return SpliceOutcome.Cancelled
        val source = sourceProbe.takeIf { it.outcome == ToolResult.Outcome.SUCCESS }
            ?.let { parseStreamInfo(it.output) }
        if (source != null && !canEncode(source)) {
            return SpliceOutcome.Unavailable("unsupported source stream $source")
        }

        // 2. Copy the audio track
        runner.ffmpeg(buildHeadCopyArgs(request.input(), head.path, endMs)) {
            onProgress(HEAD_PROGRESS * fraction(it, endMs))
        }.toSpliceFailure(runner, "copy")?.let { return it }

        // 3. Probe the stream and the packets before the fade, to find a junction
        val info = runner.ffprobe(buildStreamProbeArgs(head.path))
            .also { result -> result.toSpliceFailure(runner, "stream probe")?.let { return it } }
            .let { parseStreamInfo(it.output) }
            ?: return SpliceOutcome.Unavailable("unreadable stream parameters")
        if (!info.isSpliceable || !canEncode(info)) {
            return SpliceOutcome.Unavailable("unsupported stream $info")
        }
        val packets = runner.ffprobe(buildPacketProbeArgs(head.path, info, fadeOut.startMs))
            .also { result -> result.toSpliceFailure(runner, "packet probe")?.let { return it } }
            .let { parsePackets(it.output) }
        val plan = planAacSplice(info, packets, fadeOut)
            ?: return SpliceOutcome.Unavailable("no steady frame boundary before the fade")
        log("$JUNCTION_LOG${plan.junctionSample - plan.startPts} (fade at ${plan.fadeStartSample - plan.startPts})")

        // 4. Re-encode the tail with the fade
        val tailProgress = { fraction: Float -> onProgress(HEAD_PROGRESS + TAIL_PROGRESS * fraction) }
        val heAacTail = heAacEncoder?.let { HeAacTail(runner, it, workDir, log) }
        when (plan.profile) {
            AacProfile.LC -> encodeLcTail(plan, head, tail, request.threads, tailProgress)
            AacProfile.HE -> {
                // The source usually states the encoder's nominal bit rate, the copy only its average
                val bitRate = listOfNotNull(source?.bitRate, info.bitRate).firstOrNull { it > 0 }
                    ?: return SpliceOutcome.Unavailable("unknown bit rate")
                (heAacTail ?: return SpliceOutcome.Unavailable("no HE-AAC encoder")).encode(
                    plan, head, tail, roundBitRate(bitRate), request.threads, tailProgress
                )
            }
        }?.let { return it }

        // 5. Join head and tail
        script.writeText(buildConcatScript(head.name, tail.name, plan))
        runner.ffmpeg(buildJoinArgs(script.path, request.outFile.path)) {
            onProgress(HEAD_PROGRESS + TAIL_PROGRESS + JOIN_PROGRESS * fraction(it, endMs))
        }.toSpliceFailure(runner, "join")?.let { return it }

        // 6. Check the result
        val durationMs = runner.ffprobe(buildDurationProbeArgs(request.outFile.path))
            .also { result -> result.toSpliceFailure(runner, "duration probe")?.let { return it } }
            .let { parseDurationMs(it.output) }
        if (durationMs == null || abs(durationMs - endMs) > SPLICE_DURATION_TOLERANCE_MS) {
            return SpliceOutcome.Unavailable("joined duration $durationMs ms, expected $endMs ms")
        }
        if (plan.profile == AacProfile.HE) heAacTail?.checkJoin(plan, head, request.outFile)?.let { return it }
        return SpliceOutcome.Done
    }

    /**
     * Encodes an AAC-LC tail with FFmpeg's encoder
     *
     * @return null if it succeeded, else why not
     */
    private fun encodeLcTail(
        plan: SplicePlan,
        head: File,
        tail: File,
        threads: Int,
        onProgress: (Float) -> Unit,
    ): SpliceOutcome? {
        runner.ffmpeg(buildTailEncodeArgs(head.path, tail.path, plan, threads)) {
            onProgress(fraction(it, plan.tailDurationMs))
        }.toSpliceFailure(runner, "tail encode")?.let { return it }

        // Check that the tail's first frame continues the head seamlessly
        val tailStart = runner.ffprobe(buildFirstPacketProbeArgs(tail.path))
            .also { result -> result.toSpliceFailure(runner, "tail probe")?.let { return it } }
            .let { parsePackets(it.output).firstOrNull() }
        if (!continuesLongWindow(tailStart?.window)) {
            return SpliceOutcome.Unavailable("tail starts with ${tailStart?.window}")
        }
        return null
    }

    companion object {
        /** Logged with the junction position in samples from the start of the audio */
        const val JUNCTION_LOG = "Re-encoding from sample "

        private const val HEAD_FILE = "head.m4a"
        private const val TAIL_FILE = "tail.m4a"
        private const val CONCAT_SCRIPT = "join.ffconcat"

        // Share of the progress bar per step: copying is bound by reading the
        // whole video, the tail is short, joining copies the audio once more
        private const val HEAD_PROGRESS = 0.7f
        private const val TAIL_PROGRESS = 0.1f
        private const val JOIN_PROGRESS = 0.2f

        /** Rounds an average bit rate to whole kb/s, as encoders are configured */
        private fun roundBitRate(bitRate: Long): Int = ((bitRate + 500) / 1000 * 1000).toInt()
    }
}

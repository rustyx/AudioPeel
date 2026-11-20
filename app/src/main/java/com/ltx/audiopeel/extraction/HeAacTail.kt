package com.ltx.audiopeel.extraction

import java.io.File

/**
 * Encodes an HE-AAC tail with an external encoder and checks the joined
 * result; see HeAacSplice.kt
 *
 * @param runner runs FFmpeg
 * @param encoder the HE-AAC encoder
 * @param workDir directory for intermediate files
 * @param log receives diagnostic messages
 */
internal class HeAacTail(
    private val runner: MediaToolRunner,
    private val encoder: AacEncoder,
    private val workDir: File,
    private val log: (String) -> Unit,
) {
    /**
     * Writes the tail, starting at the junction, to an M4A file
     *
     * @param onProgress receives the progress of this step from 0 to 1
     * @return null if it succeeded, else why not
     */
    fun encode(
        plan: SplicePlan,
        head: File,
        tail: File,
        bitRate: Int,
        threads: Int,
        onProgress: (Float) -> Unit,
    ): SpliceOutcome? = guarded("tail") { encodeUnguarded(plan, head, tail, bitRate, threads, onProgress) }

    /**
     * Compares the joined file with the head around the junction
     *
     * @return null if the join looks right, else why not
     */
    fun checkJoin(plan: SplicePlan, head: File, joined: File): SpliceOutcome? =
        guarded("join check") { checkJoinUnguarded(plan, head, joined) }

    /**
     * Runs a step that does its own file I/O and parsing, whose failures
     * (a full cache, an unexpected format) should fall back like any other
     */
    private inline fun guarded(step: String, block: () -> SpliceOutcome?): SpliceOutcome? = try {
        block()
    } catch (e: Exception) {
        SpliceOutcome.Unavailable("$step failed: $e")
    }

    private fun encodeUnguarded(
        plan: SplicePlan,
        head: File,
        tail: File,
        bitRate: Int,
        threads: Int,
        onProgress: (Float) -> Unit,
    ): SpliceOutcome? {
        val pcmMs = (plan.endSample - plan.pcmStartSample) * 1000 / plan.sampleRate
        if (pcmMs > MAX_EXTERNAL_TAIL_MS) return SpliceOutcome.Unavailable("fade too long to encode separately")
        val config = AacEncoderConfig(plan.sampleRate, plan.channels, bitRate)

        // 1. Measure the encoder's delay
        val signal = calibrationSignal(plan.sampleRate, plan.channels)
        val calibrationPcm = File(workDir, CALIBRATION_PCM).also { writePcm(signal, it) }
        val calibration = when (val result = encoder.encodeHeAac(calibrationPcm, 0, config) { runner.isCancelled }) {
            is AacEncodeResult.Success -> result
            AacEncodeResult.Cancelled -> return SpliceOutcome.Cancelled
            is AacEncodeResult.Failed -> return SpliceOutcome.Unavailable("encoder failed: ${result.reason}")
        }
        calibration.config?.let { bytes ->
            val info = parseAudioSpecificConfig(bytes)
            if (info == null || !isJoinableHeAacConfig(info, plan.sampleRate, plan.channels)) {
                return SpliceOutcome.Unavailable("encoder config $info doesn't match")
            }
        }
        val calibrationAdts = File(workDir, CALIBRATION_ADTS)
        writeAdtsFile(calibration.frames, plan, calibrationAdts)
        val decodedPcm = File(workDir, CALIBRATION_DECODED_PCM)
        runner.ffmpeg(buildAdtsDecodeArgs(calibrationAdts.path, decodedPcm.path, plan.channels)) {}
            .toSpliceFailure(runner, "calibration decode")?.let { return it }
        val decoded = readPcm(decodedPcm)
        // With SBR, each frame decodes to the full frame size at the output sample rate
        if (decoded.size / plan.channels < (calibration.frames.size - 1) * plan.frameSamples) {
            return SpliceOutcome.Unavailable("encoder output decodes without SBR")
        }
        val delay = measureDelay(
            signal, decoded, plan.channels, plan.sampleRate, delaySearchRange(plan.frameSamples)
        ) ?: return SpliceOutcome.Unavailable("encoder delay not measurable")

        // 2. Find which of its frames the encoder codes independently
        val parts = (1..minOf(MAX_INDEPENDENT_PERIOD, calibration.frames.size - INDEPENDENCE_TEST_FRAMES)).map { k ->
            val adts = File(workDir, "part$k.aac")
            writeAdtsFile(calibration.frames.subList(k, k + INDEPENDENCE_TEST_FRAMES), plan, adts)
            adts to File(workDir, "part$k.pcm")
        }
        runner.ffmpeg(buildAdtsBatchDecodeArgs(parts.map { (adts, pcm) -> adts.path to pcm.path }, plan.channels)) {}
            .toSpliceFailure(runner, "calibration part decode")?.let { return it }
        val period = findIndependentPeriod(decoded, parts.map { readPcm(it.second) }, plan.channels, plan.frameSamples)
            ?: return SpliceOutcome.Unavailable("encoder codes no frame independently")
        log("HE-AAC encoder delay $delay samples, independent frames every $period")
        val alignment = alignEncoder(plan, delay, period)
        if (plan.pcmStartSample - alignment.leadFrames * plan.frameSamples < plan.decodeStartSample) {
            return SpliceOutcome.Unavailable("too little audio before the junction")
        }

        // 3. Decode the tail to PCM with the fade
        val pcm = File(workDir, TAIL_PCM)
        runner.ffmpeg(buildPcmDecodeArgs(head.path, pcm.path, plan, alignment.leadFrames, threads)) {
            onProgress(DECODE_PROGRESS * fraction(it, pcmMs))
        }.toSpliceFailure(runner, "tail decode")?.let { return it }
        // Encoders may stop without flushing the audio still delayed in them, so
        // the end is padded with silence and the extra frames dropped
        pcm.appendBytes(ByteArray((delay.coerceAtLeast(0) + plan.frameSamples) * 2 * plan.channels))

        // 4. Encode it so that an independent frame starts at the junction, and drop the frames before it
        val encoded = when (val result = encoder.encodeHeAac(pcm, alignment.skipSamples, config) { runner.isCancelled }) {
            is AacEncodeResult.Success -> result
            AacEncodeResult.Cancelled -> return SpliceOutcome.Cancelled
            is AacEncodeResult.Failed -> return SpliceOutcome.Unavailable("encoder failed: ${result.reason}")
        }
        val tailFrames = ((plan.endSample - plan.junctionSample + plan.frameSamples - 1) / plan.frameSamples).toInt()
        val frames = encoded.frames.drop(alignment.dropFrames).take(tailFrames)
        if (frames.size < tailFrames) {
            return SpliceOutcome.Unavailable("encoder returned ${frames.size} of $tailFrames frames")
        }
        val firstWindow = frames.firstOrNull()?.let(::parseAacWindow)
        if (!continuesLongWindow(firstWindow)) return SpliceOutcome.Unavailable("tail starts with $firstWindow")
        onProgress(1f)

        // 5. Put the frames into an M4A for the join
        val adts = File(workDir, TAIL_ADTS)
        writeAdtsFile(frames, plan, adts)
        return runner.ffmpeg(buildAdtsRemuxArgs(adts.path, tail.path)) {}.toSpliceFailure(runner, "tail remux")
    }

    private fun checkJoinUnguarded(plan: SplicePlan, head: File, joined: File): SpliceOutcome? {
        val from = plan.junctionSample - CHECK_FRAMES_BEFORE * plan.frameSamples
        val to = plan.junctionSample + CHECK_FRAMES_AFTER * plan.frameSamples
        val sourcePcm = File(workDir, CHECK_SOURCE_PCM)
        val joinedPcm = File(workDir, CHECK_JOINED_PCM)
        runner.ffmpeg(buildWindowDecodeArgs(head.path, sourcePcm.path, plan, from, to)) {}
            .toSpliceFailure(runner, "check decode")?.let { return it }
        runner.ffmpeg(buildWindowDecodeArgs(joined.path, joinedPcm.path, plan, from, to)) {}
            .toSpliceFailure(runner, "check decode")?.let { return it }
        val fadeLength = (plan.endSample - plan.fadeStartSample).toDouble()
        val check = checkJunction(
            joined = readPcm(joinedPcm),
            source = readPcm(sourcePcm),
            channels = plan.channels,
            junctionIndex = (plan.junctionSample - from).toInt(),
            frameSamples = plan.frameSamples
        ) { index ->
            val sample = from + index
            if (sample < plan.fadeStartSample) 1.0 else (1 - (sample - plan.fadeStartSample) / fadeLength).coerceIn(0.0, 1.0)
        }
        log("HE-AAC join check: ${check.summary}")
        return check.problem?.let { SpliceOutcome.Unavailable("join check: $it") }
    }

    private fun writeAdtsFile(frames: List<ByteArray>, plan: SplicePlan, file: File) {
        file.outputStream().buffered().use { writeAdts(frames, plan.sampleRate / 2, plan.channels, it) }
    }

    private companion object {
        const val CALIBRATION_PCM = "calibration.pcm"
        const val CALIBRATION_ADTS = "calibration.aac"
        const val CALIBRATION_DECODED_PCM = "calibration-decoded.pcm"
        const val TAIL_PCM = "tail.pcm"
        const val TAIL_ADTS = "tail.aac"
        const val CHECK_SOURCE_PCM = "check-source.pcm"
        const val CHECK_JOINED_PCM = "check-joined.pcm"

        /** Share of this step's progress for decoding; the encoder reports none */
        const val DECODE_PROGRESS = 0.5f
    }
}

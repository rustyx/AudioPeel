package com.ltx.audiopeel.extraction

import com.ltx.audiopeel.AudioEdits

/*
 * Fading out AAC audio without re-encoding all of it
 *
 * The audio is copied up to an AAC frame boundary (the junction) before the
 * fade start, and only the rest is re-encoded with the fade. The steps are:
 *  1. copy the audio track into an audio-only M4A (the "head" file)
 *  2. probe its stream parameters and the packets before the fade
 *  3. decode from a while before the junction and re-encode the tail with
 *     the fade, dropping the encoder's priming frame and the preroll frames
 *     so the first kept frame starts exactly at the junction
 *  4. check that the tail's first frame continues the head's last frame
 *  5. join the head's packets before the junction with the tail's packets
 *     (concat demuxer, stream copy, original timestamps kept so the
 *     source's encoder delay is preserved)
 *  6. check the result's duration
 * Any step that fails or doesn't fit makes the caller fall back to
 * re-encoding all of the audio. HE-AAC works the same way with frames of
 * twice the samples, but its tail is encoded outside FFmpeg (see
 * HeAacSplice.kt).
 *
 * Why the junction must be chosen with care: an AAC frame's samples are the
 * overlap-add of two consecutive transformed blocks, and the aliasing of one
 * only cancels the other's if both were encoded with the same window there.
 * The preroll gives the new encoder the signal before the junction, so it
 * produces matching windows as long as both encoders use long blocks; at a
 * transient the source encoder may switch to short blocks where the new one
 * doesn't, which leaves an audible artifact. So the junction is placed where
 * the source has long blocks on both sides, and the new encoder's first frame
 * is checked to start with a long block as well.
 *
 * The preroll is much longer than the one frame the overlap needs: the
 * encoder's transient detection and rate control adapt to the signal, and
 * starting them cold codes the first transients noticeably worse than
 * re-encoding everything would. After a couple of seconds the re-encoded
 * frames are practically the same as those of a full re-encode.
 */

/** Samples per AAC-LC frame */
const val AAC_FRAME_SAMPLES = 1024

/**
 * AAC profiles that can be spliced
 *
 * @param frameSamples output samples per frame
 */
enum class AacProfile(val frameSamples: Int) {
    LC(AAC_FRAME_SAMPLES),

    /** HE-AAC v1: AAC-LC at half the sample rate plus spectral band replication */
    HE(2 * AAC_FRAME_SAMPLES),
}

/** Audio encoded before the junction and then dropped, to settle the encoder */
private const val SPLICE_PREROLL_MS = 2000L

/** Extra audio copied past the end, so the tail can be decoded up to the end */
private const val HEAD_COPY_MARGIN_MS = 2000L

/** How far before the fade start a junction is searched for */
private const val JUNCTION_SEARCH_MS = 3000L

/** How far after the fade start packets are probed */
private const val PACKET_PROBE_AFTER_MS = 100L

/** Allowed difference between the joined file's duration and the expected one */
const val SPLICE_DURATION_TOLERANCE_MS = 100L

/**
 * Parameters of an audio stream: in the head file, or in the source
 *
 * @param channels channels FFmpeg decodes, which for HE-AAC are two even
 *        if the stream is mono, in case it uses parametric stereo
 * @param startPts timestamp of the first decoded sample; the edits are
 *        relative to it, as in a single-pass extraction
 * @param bitRate stated or average bit rate, 0 if unknown
 * @param codecConfig AudioSpecificConfig as hex, empty if unknown
 */
data class AudioStreamInfo(
    val codec: String,
    val profile: String,
    val sampleRate: Int,
    val channels: Int,
    val timeBase: String,
    val startPts: Long,
    val bitRate: Long = 0,
    val codecConfig: String = "",
) {
    /** Channels actually coded, as the AudioSpecificConfig states */
    val codedChannels: Int
        get() = codecConfig.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
            .let(::parseAudioSpecificConfig)?.channels?.takeIf { it in 1..2 } ?: channels

    /**
     * The profile if the codec allows splicing, regardless of the
     * container's timestamps; checked on the source before copying anything
     */
    val aacProfile: AacProfile?
        get() = when {
            codec != "aac" || channels !in 1..2 || sampleRate <= 0 -> null
            profile == "LC" || profile == "1" -> AacProfile.LC
            profile == "HE-AAC" || profile == "4" -> AacProfile.HE
            else -> null
        }

    /** Whether the tail can be joined to this stream: timestamps must count samples */
    val isSpliceable: Boolean
        get() = aacProfile != null && timeBase == "1/$sampleRate"
}

/**
 * AAC window sequence of a frame (ISO/IEC 14496-3, ics_info)
 */
enum class WindowSequence(
    /** Whether the frame's first half overlaps the previous frame with a long window */
    val startsLong: Boolean,
    /** Whether the frame's second half overlaps the next frame with a long window */
    val endsLong: Boolean,
) {
    ONLY_LONG(true, true),
    LONG_START(true, false),
    EIGHT_SHORT(false, false),
    LONG_STOP(false, true),
}

/**
 * Window of an AAC frame
 *
 * @param sequence window sequence
 * @param kbd whether the Kaiser-Bessel-derived window shape is used (else sine)
 */
data class AacWindow(val sequence: WindowSequence, val kbd: Boolean)

/**
 * A packet of the head file
 *
 * @param pts timestamp in samples
 * @param window window of its first channel element, or null if unknown
 */
data class AacPacket(val pts: Long, val window: AacWindow?)

/**
 * Reads the window of an AAC-LC raw data block from its first bytes
 *
 * @return the window, or null if the frame doesn't start with a single
 *         channel element or a channel pair sharing one window
 */
fun parseAacWindow(header: ByteArray): AacWindow? {
    if (header.size < 3) return null
    val bits = header.take(3).fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }
    // id_syn_ele (3 bits), element_instance_tag (4 bits), then per element
    val icsInfo = when (bits ushr 21) {
        // SCE: global_gain (8 bits), then ics_info
        ID_SCE -> (bits ushr 5) and 0xF
        // CPE: common_window (1 bit), then ics_info if the window is shared
        ID_CPE -> if ((bits ushr 16) and 1 == 1) (bits ushr 12) and 0xF else return null
        else -> return null
    }
    // ics_info: ics_reserved_bit (1), window_sequence (2), window_shape (1)
    return AacWindow(
        sequence = WindowSequence.entries[(icsInfo ushr 1) and 3],
        kbd = icsInfo and 1 == 1
    )
}

private const val ID_SCE = 0
private const val ID_CPE = 1

/**
 * The window FFmpeg's and Fraunhofer's AAC encoders use for long blocks; the
 * frame before the junction must use the same, since the encoder assumes it did
 */
private fun isLongKbd(window: AacWindow?) = window != null && window.sequence.endsLong && window.kbd

/**
 * Whether the tail's first frame continues a head frame that ends with a
 * long window; checked after encoding, since the encoder chooses it
 */
fun continuesLongWindow(window: AacWindow?): Boolean = window != null && window.sequence.startsLong

/** Frames encoded before the junction and then dropped */
fun prerollFrames(sampleRate: Int, frameSamples: Int = AAC_FRAME_SAMPLES): Int =
    ((msToSamples(SPLICE_PREROLL_MS, sampleRate) + frameSamples - 1) / frameSamples).toInt()

/**
 * Resolved positions for splicing, in samples of the head file's timeline
 *
 * @param profile AAC profile
 * @param sampleRate sample rate
 * @param channels channel count
 * @param startPts timestamp of the first decoded sample
 * @param junctionSample timestamp of the first replaced packet
 * @param prerollFrames frames encoded before the junction and dropped
 * @param encodeStartSample first sample fed to the encoder (junction minus preroll)
 * @param seekSeconds whole seconds after the start to seek to before decoding,
 *        so that the decoded timeline starts exactly on a sample
 * @param fadeStartSample first faded sample
 * @param endSample end of the output (exclusive)
 */
data class SplicePlan(
    val profile: AacProfile,
    val sampleRate: Int,
    val channels: Int,
    val startPts: Long,
    val junctionSample: Long,
    val prerollFrames: Int,
    val encodeStartSample: Long,
    val seekSeconds: Long,
    val fadeStartSample: Long,
    val endSample: Long,
) {
    val frameSamples: Int get() = profile.frameSamples

    /**
     * Where FFmpeg's decoded timeline starts, in samples of the head's
     * timeline: it is rebased to the seek position
     */
    val decodeStartSample: Long get() = startPts + seekSeconds * sampleRate

    /** Filter that cuts the tail out of the decoded audio and fades it */
    val tailFilter: String get() = tailFilterFrom(encodeStartSample)

    /**
     * First sample of the PCM decoded for an external encoder: a frame early,
     * so the encoder's input can be shifted to match its delay
     */
    val pcmStartSample: Long get() = encodeStartSample - frameSamples

    /**
     * Filter that cuts the PCM for an external encoder out of the decoded audio and fades it
     *
     * @param leadFrames frames to start before [pcmStartSample]
     */
    fun pcmFilter(leadFrames: Int): String = tailFilterFrom(pcmStartSample - leadFrames * frameSamples)

    private fun tailFilterFrom(start: Long) =
        "atrim=start_sample=${start - decodeStartSample}" +
            ":end_sample=${endSample - decodeStartSample}" +
            ",asetpts=PTS-STARTPTS" +
            ",afade=t=out:start_sample=${fadeStartSample - start}" +
            ":nb_samples=${endSample - fadeStartSample}:curve=$FADE_CURVE"

    /** Length of the re-encoded part */
    val tailDurationMs: Long get() = (endSample - junctionSample) * 1000 / sampleRate
}

/**
 * Plans the splice, or returns null if the stream doesn't allow it
 *
 * @param info head stream parameters
 * @param packets head packets before the fade start, in any order
 * @param fadeOut fade to apply, relative to the start of the audio
 */
fun planAacSplice(
    info: AudioStreamInfo,
    packets: List<AacPacket>,
    fadeOut: AudioEdits.FadeOut,
): SplicePlan? {
    if (!info.isSpliceable) return null
    val profile = info.aacProfile ?: return null
    val frame = profile.frameSamples
    val rate = info.sampleRate
    val fadeStart = info.startPts + msToSamples(fadeOut.startMs, rate)
    val end = info.startPts + msToSamples(fadeOut.startMs + fadeOut.durationMs, rate)
    val preroll = prerollFrames(rate, frame) * frame.toLong()
    val windows = packets.associate { it.pts to it.window }
    // The packet before the junction must be a frame earlier; with HE-AAC it
    // isn't if the SBR runs at the core's sample rate, which is too rare to support
    val previous = packets.map { it.pts }.sorted().zipWithNext().associate { (a, b) -> b to a }
    // The latest frame boundary before the fade where the source is in a
    // steady state: long windows on both sides
    val junction = packets.map { it.pts }
        .filter { it <= fadeStart && it - preroll >= info.startPts }
        .sortedDescending()
        .firstOrNull { pts ->
            previous[pts] == pts - frame &&
                isLongKbd(windows[pts - frame]) &&
                windows[pts]?.sequence == WindowSequence.ONLY_LONG
        }
        ?: return null
    val encodeStart = junction - preroll
    // Seek to at least a second before the encode start; the decoder needs
    // a few frames to settle after a seek
    val seekSeconds = maxOf(0L, (encodeStart - info.startPts) / rate - 1)
    return SplicePlan(
        profile = profile,
        sampleRate = rate,
        channels = info.codedChannels,
        startPts = info.startPts,
        junctionSample = junction,
        prerollFrames = prerollFrames(rate, frame),
        encodeStartSample = encodeStart,
        seekSeconds = seekSeconds,
        fadeStartSample = fadeStart,
        endSample = end
    )
}

/**
 * Arguments that copy the first audio track into an audio-only M4A
 *
 * @param input FFmpeg input
 * @param headPath output path
 * @param endMs end of the wanted audio
 */
fun buildHeadCopyArgs(input: String, headPath: String, endMs: Long): List<String> = listOf(
    "-y", "-nostdin", "-i", input, "-map", "0:a:0", "-vn", "-c:a", "copy",
    "-t", formatSeconds(endMs + HEAD_COPY_MARGIN_MS), "-f", "mp4", headPath
)

/**
 * FFprobe arguments that print the audio stream parameters as key=value
 * lines, with the codec config as a hex dump
 */
fun buildStreamProbeArgs(path: String): List<String> = listOf(
    "-v", "error", "-select_streams", "a:0",
    "-show_entries", "stream=codec_name,profile,sample_rate,channels,time_base,start_pts,bit_rate,extradata",
    "-show_data", "-of", "default=noprint_wrappers=1", path
)

/**
 * FFprobe arguments that print the timestamps and data of the packets
 * before a position, relative to the start of the audio
 */
fun buildPacketProbeArgs(path: String, info: AudioStreamInfo, aroundMs: Long): List<String> {
    val startMs = info.startPts * 1000 / info.sampleRate
    val from = formatSeconds(maxOf(0L, startMs + aroundMs - JUNCTION_SEARCH_MS))
    val to = formatSeconds(startMs + aroundMs + PACKET_PROBE_AFTER_MS)
    return buildPacketDataProbeArgs(path, "$from%$to")
}

/** FFprobe arguments that print the timestamp and data of the first packet */
fun buildFirstPacketProbeArgs(path: String): List<String> = buildPacketDataProbeArgs(path, "%+#1")

private fun buildPacketDataProbeArgs(path: String, intervals: String) = listOf(
    "-v", "error", "-select_streams", "a:0", "-show_entries", "packet=pts,data", "-show_data",
    "-read_intervals", intervals, "-of", "default=noprint_wrappers=1", path
)

/**
 * Arguments that re-encode the tail with the fade
 *
 * The bitstream filter drops the encoder's priming frame and the preroll
 * frames, so the first packet written starts at the junction.
 */
fun buildTailEncodeArgs(
    headPath: String,
    tailPath: String,
    plan: SplicePlan,
    threads: Int,
): List<String> = buildList {
    addAll(listOf("-y", "-nostdin"))
    if (plan.seekSeconds > 0) addAll(listOf("-ss", "${plan.seekSeconds}"))
    addAll(listOf("-i", headPath, "-vn", "-threads", "$threads", "-af", plan.tailFilter))
    addAll(AAC_ENCODER_ARGS)
    addAll(listOf("-ar", "${plan.sampleRate}", "-ac", "${plan.channels}"))
    // The comma is escaped since it would otherwise separate bitstream filters
    addAll(listOf("-bsf:a", "noise=drop=lt(n\\,${plan.prerollFrames + 1})"))
    addAll(listOf("-f", "mp4", tailPath))
}

/**
 * Concat demuxer script joining the head before the junction with the tail
 *
 * The out point is half a frame before the junction, so rounding can't
 * decide whether the junction packet is kept. The concat demuxer shifts the
 * head to start at 0, and the explicit duration places the tail exactly
 * where the junction lands after that shift.
 *
 * @param headName head file name, relative to the script
 * @param tailName tail file name, relative to the script
 */
fun buildConcatScript(headName: String, tailName: String, plan: SplicePlan): String {
    val rate = plan.sampleRate
    return buildString {
        appendLine("ffconcat version 1.0")
        appendLine("file '$headName'")
        appendLine("outpoint ${samplesToSeconds(plan.junctionSample - plan.frameSamples / 2, rate)}")
        appendLine("duration ${samplesToSeconds(plan.junctionSample - plan.startPts, rate)}")
        appendLine("file '$tailName'")
    }
}

/**
 * Arguments that join head and tail by stream copy
 *
 * `-copyts` keeps the head's negative start timestamps, so the source's
 * encoder delay stays hidden behind an edit list as in a plain copy.
 */
fun buildJoinArgs(scriptPath: String, outPath: String): List<String> =
    listOf("-y", "-nostdin", "-copyts", "-f", "concat", "-safe", "0", "-i", scriptPath, "-c", "copy") +
        M4A_MUXER_ARGS + outPath

/** FFprobe arguments that print a file's duration in seconds */
fun buildDurationProbeArgs(path: String): List<String> = listOf(
    "-v", "error", "-show_entries", "format=duration",
    "-of", "default=noprint_wrappers=1:nokey=1", path
)

/**
 * Parses key=value lines printed by FFprobe, and the hex dump of the codec
 * config after them; other lines are ignored
 */
fun parseStreamInfo(output: String): AudioStreamInfo? {
    val lines = output.lineSequence().map { it.trim() }.toList()
    val values = lines
        .mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator > 0) line.substring(0, separator) to line.substring(separator + 1) else null
        }
        .toMap()
    val codecConfig = lines.dropWhile { it != "extradata=" }.drop(1)
        .takeWhile { HEX_DUMP_LINE.matches(it) }
        .joinToString("") { parseHexDumpLine(it) }
    return AudioStreamInfo(
        codec = values["codec_name"] ?: return null,
        profile = values["profile"].orEmpty(),
        sampleRate = values["sample_rate"]?.toIntOrNull() ?: return null,
        channels = values["channels"]?.toIntOrNull() ?: return null,
        timeBase = values["time_base"] ?: return null,
        startPts = values["start_pts"]?.toLongOrNull() ?: return null,
        // "N/A" when the container doesn't state it
        bitRate = values["bit_rate"]?.toLongOrNull() ?: 0,
        codecConfig = codecConfig
    )
}

/**
 * Parses packets printed with their data as a hex dump; other lines are ignored
 */
fun parsePackets(output: String): List<AacPacket> {
    val packets = mutableListOf<AacPacket>()
    var pts: Long? = null
    for (line in output.lineSequence().map { it.trim() }) {
        if (line.startsWith("pts=")) {
            pts = line.removePrefix("pts=").toLongOrNull()
            pts?.let { packets += AacPacket(it, null) }
        } else if (pts != null && line.startsWith(FIRST_DUMP_LINE)) {
            val bytes = parseHexDumpLine(line).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            packets[packets.lastIndex] = AacPacket(pts, parseAacWindow(bytes))
            pts = null
        }
    }
    return packets
}

private const val FIRST_DUMP_LINE = "00000000:"
private val HEX_DUMP_LINE = Regex("[0-9a-f]{8}: .*")

/**
 * Reads the bytes of a hex dump line, "00000000: 211a 080f c302 ...  ascii",
 * as hex; the last group may be one byte, and the ASCII column follows after
 * two spaces
 */
private fun parseHexDumpLine(line: String): String =
    line.substringAfter(':').trim().split(' ')
        .takeWhile { it.length in 2..4 && it.length % 2 == 0 && it.all { c -> c.isDigit() || c in 'a'..'f' } }
        .joinToString("")

/** Parses a duration in seconds printed by FFprobe */
fun parseDurationMs(output: String): Long? =
    output.lineSequence()
        .mapNotNull { it.trim().toDoubleOrNull() }
        .firstOrNull()
        ?.let { (it * 1000).toLong() }

internal fun msToSamples(ms: Long, sampleRate: Int): Long = (ms * sampleRate + 500) / 1000

/** Formats samples as seconds with microsecond precision, independent of the locale */
internal fun samplesToSeconds(samples: Long, sampleRate: Int): String {
    val micros = (samples * 1_000_000 + sampleRate / 2) / sampleRate
    return "${micros / 1_000_000}.${(micros % 1_000_000).toString().padStart(6, '0')}"
}

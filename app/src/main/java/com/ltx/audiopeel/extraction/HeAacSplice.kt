package com.ltx.audiopeel.extraction

import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Splicing HE-AAC
 *
 * The app's FFmpeg can't encode HE-AAC, so FFmpeg decodes the tail to PCM
 * and an AacEncoder (Android's own encoder on devices) encodes it. Its delay
 * isn't reported, but the junction must fall exactly on one of its frame
 * boundaries, so the delay is measured first: a calibration signal is
 * encoded, decoded by FFmpeg and located by cross-correlation. The PCM starts
 * a frame early, the encoder's input is shifted by the remainder of the delay
 * so that a frame boundary lands on the junction, and the frames before it are
 * dropped. The frames are written as ADTS and remuxed into M4A for the join.
 *
 * HE-AAC adds spectral band replication (SBR) to AAC-LC at half the sample
 * rate. Most SBR frames are coded as differences from the previous one, so a
 * tail starting with such a frame decodes against the source's last frame
 * and loses its high frequencies until the next independent frame. In raw
 * frames, Fraunhofer's encoder (which Android's is based on) makes every
 * tenth frame independent, counting from its start. The calibration output
 * shows which, and the PCM is lengthened by a few frames so that an
 * independent frame starts at the junction.
 *
 * The source's SBR settings may still differ from the new encoder's, which
 * can't be checked from the bitstream without parsing it entirely. So the
 * joined file is decoded around the junction and compared with the source
 * before it's accepted.
 */

/** Settings for the external encoder */
data class AacEncoderConfig(val sampleRate: Int, val channels: Int, val bitRate: Int)

sealed interface AacEncodeResult {
    /**
     * @param config AudioSpecificConfig the encoder reported, if any
     * @param frames raw AAC frames, in order
     */
    class Success(val config: ByteArray?, val frames: List<ByteArray>) : AacEncodeResult
    data object Cancelled : AacEncodeResult
    data class Failed(val reason: String) : AacEncodeResult
}

/**
 * Encodes PCM to HE-AAC v1
 */
interface AacEncoder {
    /** Whether HE-AAC with this sample rate and channel count can be encoded */
    fun canEncodeHeAac(sampleRate: Int, channels: Int): Boolean

    /**
     * @param pcm interleaved 16-bit little-endian PCM
     * @param skipSamples samples per channel to skip at the start
     * @param config encoder settings
     * @param isCancelled polled while encoding
     */
    fun encodeHeAac(
        pcm: File,
        skipSamples: Long,
        config: AacEncoderConfig,
        isCancelled: () -> Boolean,
    ): AacEncodeResult
}

/** Delays searched for, relative to the frame size: encoders delay by a few frames */
private const val MIN_DELAY_FRAMES = -1
private const val MAX_DELAY_FRAMES = 6

/** Samples correlated to measure the delay, after the encoder has settled */
private const val CORRELATION_SAMPLES = 4096

/** Normalized correlation needed to trust a measured delay */
private const val MIN_CALIBRATION_CORRELATION = 0.9

/** Longest PCM tail encoded externally; it's kept in a file while splicing */
const val MAX_EXTERNAL_TAIL_MS = 10 * 60 * 1000L

/**
 * Calibration signal: noise, which correlates with itself at one delay only.
 * Mostly low frequencies, which the AAC-LC core codes as a waveform, with
 * some high ones so that SBR has something to code
 *
 * @return interleaved samples, the same in each channel, 1.5 s long
 */
fun calibrationSignal(sampleRate: Int, channels: Int): ShortArray {
    val random = Random(CALIBRATION_SEED)
    val length = sampleRate * 3 / 2
    val white = DoubleArray(length + LOW_PASS.size) { random.nextGaussian() }
    val lowPassGain = LOW_PASS.sum()
    return ShortArray(length * channels) { index ->
        val sample = index / channels
        val low = LOW_PASS.indices.sumOf { LOW_PASS[it] * white[sample + it] } / lowPassGain
        val value = low + CALIBRATION_HIGH_LEVEL * white[sample]
        (value * CALIBRATION_LEVEL).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}

private const val CALIBRATION_SEED = 20261120L
private const val CALIBRATION_LEVEL = 6000.0

/** Level of the white noise added to the low-passed noise */
private const val CALIBRATION_HIGH_LEVEL = 0.1

/** Triangular low-pass filter, mostly below a sixth of the sample rate */
private val LOW_PASS = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 3.0, 2.0, 1.0)

private fun lowPass(x: DoubleArray) = DoubleArray(x.size) { i ->
    LOW_PASS.indices.sumOf { k -> LOW_PASS[k] * (x.getOrNull(i + k - LOW_PASS.size / 2) ?: 0.0) }
}

/** Delays searched for by [measureDelay] */
fun delaySearchRange(frameSamples: Int): IntRange =
    MIN_DELAY_FRAMES * frameSamples..MAX_DELAY_FRAMES * frameSamples

/**
 * Finds how many samples later the sent signal starts in the decoded one
 *
 * @param sent interleaved samples fed to the encoder
 * @param decoded interleaved samples decoded from the encoder's output
 * @param lags delays to search
 * @return the delay, or null if no delay matches clearly
 */
fun measureDelay(sent: ShortArray, decoded: ShortArray, channels: Int, sampleRate: Int, lags: IntRange): Int? {
    // Only the low frequencies are coded as a waveform
    val x = lowPass(mono(sent, channels))
    val y = lowPass(mono(decoded, channels))
    // Half a second in, after the encoder has settled
    val from = sampleRate / 2
    if (from + lags.first < 0 || from + CORRELATION_SAMPLES > x.size ||
        from + lags.last + CORRELATION_SAMPLES > y.size
    ) {
        return null
    }
    var bestLag = 0
    var best = Double.NEGATIVE_INFINITY
    for (lag in lags) {
        var dot = 0.0
        for (i in 0 until CORRELATION_SAMPLES) dot += x[from + i] * y[from + lag + i]
        if (dot > best) {
            best = dot
            bestLag = lag
        }
    }
    val correlation = best / sqrt(energy(x, from) * energy(y, from + bestLag))
    return bestLag.takeIf { correlation >= MIN_CALIBRATION_CORRELATION }
}

private fun mono(samples: ShortArray, channels: Int) = DoubleArray(samples.size / channels) { i ->
    (0 until channels).sumOf { samples[i * channels + it].toDouble() }
}

private fun energy(x: DoubleArray, from: Int) =
    (from until from + CORRELATION_SAMPLES).sumOf { x[it] * x[it] }.coerceAtLeast(Double.MIN_VALUE)

/**
 * How to feed the external encoder so that one of its frames starts at the junction
 *
 * @param leadFrames frames decoded before [SplicePlan.pcmStartSample]
 * @param skipSamples samples per channel to skip at the start of the PCM
 * @param dropFrames frames to drop from the encoder's output
 */
data class EncoderAlignment(val leadFrames: Int, val skipSamples: Long, val dropFrames: Int)

/**
 * Aligns the external encoder's frames with the junction
 *
 * The encoder's frame k holds its input from k * frameSamples - delay on. The
 * PCM starts a frame plus the preroll before the junction, so whatever the
 * delay, at least the preroll is encoded before the first kept frame. Lead
 * frames are added before it so that the first kept frame is independent.
 *
 * @param delay the encoder's measured delay
 * @param independentPeriod every how many frames the encoder codes one independently
 */
fun alignEncoder(plan: SplicePlan, delay: Int, independentPeriod: Int): EncoderAlignment {
    val frame = plan.frameSamples.toLong()
    val offset = plan.junctionSample - plan.pcmStartSample + delay
    val drop = Math.floorDiv(offset, frame)
    val lead = Math.floorMod(-drop, independentPeriod.toLong())
    return EncoderAlignment(
        leadFrames = lead.toInt(), skipSamples = offset - drop * frame, dropFrames = (drop + lead).toInt()
    )
}

/** Longest period of independent frames looked for */
const val MAX_INDEPENDENT_PERIOD = 16

/** Frames decoded on their own to tell whether the first is independent */
const val INDEPENDENCE_TEST_FRAMES = 3

/**
 * Finds every how many frames an encoder codes the SBR data independently
 *
 * @param fullDecode the encoder's output decoded from its first frame
 * @param partDecodes for k from 1, its output decoded from frame k on
 * @return the period, or null if no frame after the first is independent
 */
fun findIndependentPeriod(
    fullDecode: ShortArray,
    partDecodes: List<ShortArray>,
    channels: Int,
    frameSamples: Int,
): Int? {
    val full = mono(fullDecode, channels)
    partDecodes.forEachIndexed { index, part ->
        val k = index + 1
        val reference = full.takeIf { it.size >= (k + 1) * frameSamples }
            ?.let { bandLevels(it, k * frameSamples)[HIGH_BAND] } ?: return null
        val first = mono(part, channels).takeIf { it.size >= BLOCK_SAMPLES }
            ?.let { bandLevels(it, 0)[HIGH_BAND] } ?: return null
        // A dependent frame decodes without high frequencies on its own
        if (reference > MIN_SBR_LEVEL_DB && first > reference - INDEPENDENT_FRAME_MARGIN_DB) return k
    }
    return null
}

private const val HIGH_BAND = 1

/** High band level the calibration signal reaches, far above that of a frame without SBR */
private const val MIN_SBR_LEVEL_DB = 60.0
private const val INDEPENDENT_FRAME_MARGIN_DB = 30.0

/**
 * The parts of an AudioSpecificConfig that matter for joining
 *
 * @param objectType audio object type: 2 for AAC-LC, 5 for SBR, 29 for parametric stereo
 * @param sampleRate sample rate of the AAC-LC core
 * @param channels channel configuration
 * @param extensionSampleRate output sample rate when SBR is signalled explicitly
 */
data class AacConfigInfo(
    val objectType: Int,
    val sampleRate: Int,
    val channels: Int,
    val extensionSampleRate: Int?,
)

private val SAMPLE_RATES = intArrayOf(
    96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
)

private const val AOT_LC = 2
private const val AOT_SBR = 5
private const val AOT_PS = 29
private const val AOT_ESCAPE = 31
private const val EXPLICIT_RATE_INDEX = 15

/** Parses the start of an AudioSpecificConfig (ISO/IEC 14496-3, 1.6.2.1) */
fun parseAudioSpecificConfig(config: ByteArray): AacConfigInfo? {
    var position = 0
    fun read(bits: Int): Int? {
        if (position + bits > config.size * 8) return null
        var value = 0
        repeat(bits) {
            val bit = (config[position / 8].toInt() ushr (7 - position % 8)) and 1
            value = (value shl 1) or bit
            position++
        }
        return value
    }

    fun readObjectType(): Int? = read(5)?.let { if (it == AOT_ESCAPE) read(6)?.plus(32) else it }
    fun readRate(): Int? = read(4)?.let { if (it == EXPLICIT_RATE_INDEX) read(24) else SAMPLE_RATES.getOrNull(it) }

    val objectType = readObjectType() ?: return null
    val rate = readRate() ?: return null
    val channels = read(4) ?: return null
    var extensionRate: Int? = null
    if (objectType == AOT_SBR || objectType == AOT_PS) {
        extensionRate = readRate() ?: return null
        // The core must be AAC-LC
        if (readObjectType() != AOT_LC) return null
    }
    return AacConfigInfo(objectType, rate, channels, extensionRate)
}

/**
 * Whether an encoder's config describes HE-AAC v1 that can continue a stream
 * with this output sample rate and channel count: the SBR signalled
 * explicitly, or implicitly as AAC-LC at half the rate
 */
fun isJoinableHeAacConfig(config: AacConfigInfo, sampleRate: Int, channels: Int): Boolean =
    config.channels == channels &&
        config.sampleRate * 2 == sampleRate &&
        when (config.objectType) {
            AOT_SBR -> config.extensionSampleRate == sampleRate
            AOT_LC -> config.extensionSampleRate == null
            else -> false
        }

private const val ADTS_HEADER_SIZE = 7
private const val ADTS_MAX_FRAME_SIZE = 0x1FFF

/**
 * Writes raw HE-AAC frames as ADTS, which signals the SBR implicitly: the
 * header describes the AAC-LC core
 *
 * @param coreSampleRate sample rate of the AAC-LC core
 */
fun writeAdts(frames: List<ByteArray>, coreSampleRate: Int, channels: Int, out: OutputStream) {
    val rateIndex = SAMPLE_RATES.indexOf(coreSampleRate)
    require(rateIndex >= 0) { "no ADTS index for $coreSampleRate Hz" }
    require(channels in 1..7) { "no ADTS channel configuration for $channels channels" }
    val header = ByteArray(ADTS_HEADER_SIZE)
    for (frame in frames) {
        val length = frame.size + ADTS_HEADER_SIZE
        require(length <= ADTS_MAX_FRAME_SIZE) { "frame too large for ADTS: ${frame.size} bytes" }
        // Sync word, MPEG-4, no CRC
        header[0] = 0xFF.toByte()
        header[1] = 0xF1.toByte()
        // Profile (object type - 1), sample rate index, channel configuration
        header[2] = (((AOT_LC - 1) shl 6) or (rateIndex shl 2) or (channels shr 2)).toByte()
        header[3] = (((channels and 3) shl 6) or (length shr 11)).toByte()
        header[4] = ((length shr 3) and 0xFF).toByte()
        // Buffer fullness 0x7FF (variable bit rate), one raw data block
        header[5] = (((length and 7) shl 5) or 0x1F).toByte()
        header[6] = 0xFC.toByte()
        out.write(header)
        out.write(frame)
    }
}

/**
 * FFmpeg arguments that decode the tail from the head file to PCM for an
 * external encoder, with the fade
 *
 * @param leadFrames frames decoded before [SplicePlan.pcmStartSample]
 */
fun buildPcmDecodeArgs(
    headPath: String,
    pcmPath: String,
    plan: SplicePlan,
    leadFrames: Int,
    threads: Int,
): List<String> =
    seekArgs(plan) + listOf("-i", headPath, "-vn", "-threads", "$threads", "-af", plan.pcmFilter(leadFrames)) +
        rawPcmArgs(plan.sampleRate, plan.channels) + pcmPath

/**
 * FFmpeg arguments that decode several ADTS files to PCM in one run
 *
 * @param files pairs of ADTS input and PCM output
 */
fun buildAdtsBatchDecodeArgs(files: List<Pair<String, String>>, channels: Int): List<String> = buildList {
    addAll(listOf("-y", "-nostdin"))
    files.forEach { (adts, _) -> addAll(listOf("-f", "aac", "-i", adts)) }
    files.forEachIndexed { index, (_, pcm) ->
        addAll(listOf("-map", "$index:a", "-f", "s16le", "-c:a", "pcm_s16le", "-ac", "$channels", pcm))
    }
}

/** FFmpeg arguments that decode an ADTS file to PCM at its own sample rate */
fun buildAdtsDecodeArgs(adtsPath: String, pcmPath: String, channels: Int): List<String> =
    listOf("-y", "-nostdin", "-f", "aac", "-i", adtsPath, "-f", "s16le", "-c:a", "pcm_s16le", "-ac", "$channels", pcmPath)

/** FFmpeg arguments that put the ADTS frames into an M4A for joining */
fun buildAdtsRemuxArgs(adtsPath: String, tailPath: String): List<String> =
    listOf("-y", "-nostdin", "-f", "aac", "-i", adtsPath, "-c", "copy", "-f", "mp4", tailPath)

/**
 * FFmpeg arguments that decode a part of a file with the head's timeline to PCM
 *
 * @param fromSample first sample, in the head's timeline
 * @param toSample end (exclusive)
 */
fun buildWindowDecodeArgs(
    path: String,
    pcmPath: String,
    plan: SplicePlan,
    fromSample: Long,
    toSample: Long,
): List<String> = seekArgs(plan) + listOf(
    "-i", path, "-vn", "-af",
    "atrim=start_sample=${fromSample - plan.decodeStartSample}:end_sample=${toSample - plan.decodeStartSample}"
) + rawPcmArgs(plan.sampleRate, plan.channels) + pcmPath

private fun seekArgs(plan: SplicePlan) = buildList {
    addAll(listOf("-y", "-nostdin"))
    if (plan.seekSeconds > 0) addAll(listOf("-ss", "${plan.seekSeconds}"))
}

private fun rawPcmArgs(sampleRate: Int, channels: Int) =
    listOf("-f", "s16le", "-c:a", "pcm_s16le", "-ar", "$sampleRate", "-ac", "$channels")

/** Frames before the junction decoded to check the join */
const val CHECK_FRAMES_BEFORE = 4

/** Frames after the junction decoded to check the join */
const val CHECK_FRAMES_AFTER = 10

/** Frames after the junction whose spectrum is compared */
private const val COMPARED_FRAMES_AFTER = 8

/**
 * Most a band's level in a frame may differ from the source's near the
 * junction. Re-encoding alone changes it by a few dB, up to about 9 dB next
 * to transients; a frame decoded without its SBR loses 30 dB or more
 */
private const val MAX_BAND_DEVIATION_DB = 8.0

/** Frames quieter than this relative to the band's loudest one aren't compared */
private const val COMPARED_RANGE_DB = 40.0

/** Most the re-encoded audio may be shifted against the source */
private const val MAX_LAG_SAMPLES = 2
private const val LAG_SEARCH_SAMPLES = 256

/** Normalized correlation above which the lag check applies */
private const val LAG_CHECK_CORRELATION = 0.5

private const val BLOCK_SAMPLES = 512

/**
 * Result of [checkJunction]
 *
 * @param problem what's wrong, or null if the join looks right
 * @param comparedFrames per band, frames loud enough to compare
 * @param maxDeviationsDb per band, the largest level difference found
 */
data class JunctionCheck(
    val problem: String?,
    val comparedFrames: List<Int> = emptyList(),
    val maxDeviationsDb: List<Double> = emptyList(),
) {
    val summary: String
        get() = comparedFrames.indices.joinToString { band ->
            "band ${band + 1}: ${comparedFrames[band]} frames, max ${
                "%.1f".format(Locale.ROOT, maxDeviationsDb[band])
            } dB"
        }
}

/**
 * Compares the joined audio with the source around the junction
 *
 * Before the junction the joined audio must decode exactly like the source.
 * After it, the re-encoded audio must line up with the source and keep the
 * level of its low and high frequencies, so neither a misaligned tail nor
 * mismatched SBR parameters can pass. Levels are compared per frame, since a
 * frame is what the SBR data covers and shorter blocks next to transients vary
 * a lot even when re-encoding everything. Each band is compared where it's
 * within [COMPARED_RANGE_DB] of its own loudest frame, so a quiet high band is
 * still checked next to loud bass.
 *
 * @param joined interleaved samples decoded from the joined file
 * @param source interleaved samples decoded from the head file, from the same position
 * @param junctionIndex sample index of the junction in both
 * @param gain fade gain of the sample at an index
 */
fun checkJunction(
    joined: ShortArray,
    source: ShortArray,
    channels: Int,
    junctionIndex: Int,
    frameSamples: Int,
    gain: (Int) -> Double,
): JunctionCheck {
    val length = minOf(joined.size, source.size) / channels
    val comparedEnd = junctionIndex + COMPARED_FRAMES_AFTER * frameSamples
    if (junctionIndex < frameSamples || length < comparedEnd + CORRELATION_SAMPLES / 2) {
        return JunctionCheck("too little audio decoded around the junction")
    }
    // The frame before the junction overlaps the tail's first one
    val copiedEnd = (junctionIndex - frameSamples) * channels
    (0 until copiedEnd).firstOrNull { joined[it] != source[it] }?.let {
        return JunctionCheck("differs from the source ${junctionIndex - it / channels} samples before the junction")
    }
    val out = mono(joined, channels)
    val expected = mono(source, channels).also { x -> for (i in x.indices) x[i] *= gain(i) }

    // Lag: only measurable when the audio has enough waveform in common
    val lagFrom = junctionIndex + frameSamples
    val lagTo = minOf(lagFrom + CORRELATION_SAMPLES, length - LAG_SEARCH_SAMPLES)
    var bestLag = 0
    var best = Double.NEGATIVE_INFINITY
    for (lag in -LAG_SEARCH_SAMPLES..LAG_SEARCH_SAMPLES) {
        var dot = 0.0
        for (i in lagFrom until lagTo) dot += expected[i] * out[i + lag]
        if (dot > best) {
            best = dot
            bestLag = lag
        }
    }
    val expectedEnergy = (lagFrom until lagTo).sumOf { expected[it] * expected[it] }
    val outEnergy = (lagFrom until lagTo).sumOf { out[it + bestLag] * out[it + bestLag] }
    if (expectedEnergy > 0 && outEnergy > 0 && best / sqrt(expectedEnergy * outEnergy) > LAG_CHECK_CORRELATION &&
        abs(bestLag) > MAX_LAG_SAMPLES
    ) {
        return JunctionCheck("re-encoded audio shifted by $bestLag samples")
    }

    // Band levels per frame, from a frame before the junction on
    val frames = (junctionIndex - frameSamples until comparedEnd step frameSamples).map { from ->
        bandLevels(expected, from, frameSamples) to bandLevels(out, from, frameSamples)
    }
    val bands = frames.first().first.indices
    val compared = bands.map { band ->
        val loudest = frames.maxOf { (reference, _) -> reference[band] }
        frames.filter { (reference, actual) -> maxOf(reference[band], actual[band]) >= loudest - COMPARED_RANGE_DB }
            .map { (reference, actual) -> actual[band] - reference[band] }
    }
    val check = JunctionCheck(
        problem = null,
        comparedFrames = compared.map { it.size },
        maxDeviationsDb = compared.map { deviations -> deviations.maxByOrNull { abs(it) } ?: 0.0 }
    )
    val worst = bands.firstOrNull { abs(check.maxDeviationsDb[it]) > MAX_BAND_DEVIATION_DB }
        ?: return check
    val deviation = "%.1f".format(Locale.ROOT, check.maxDeviationsDb[worst])
    return check.copy(problem = "band ${worst + 1} level off by $deviation dB")
}

/**
 * Levels in dB of the low band (below a quarter of the Nyquist frequency,
 * mostly the AAC-LC core) and high band (0.3 to 0.75 of it, mostly SBR), over
 * whole blocks from a position on
 *
 * @param length samples covered, a multiple of the block size
 */
private fun bandLevels(x: DoubleArray, from: Int, length: Int = BLOCK_SAMPLES): DoubleArray {
    var low = 0.0
    var high = 0.0
    val bins = BLOCK_SAMPLES / 2
    for (start in from until from + length step BLOCK_SAMPLES) {
        val re = DoubleArray(BLOCK_SAMPLES) { x[start + it] * HANN[it] }
        val im = DoubleArray(BLOCK_SAMPLES)
        fft(re, im)
        for (k in 1 until bins) {
            val power = re[k] * re[k] + im[k] * im[k]
            when {
                k < bins / 4 -> low += power
                k >= bins * 3 / 10 && k < bins * 3 / 4 -> high += power
            }
        }
    }
    return doubleArrayOf(10 * log10(low + 1.0), 10 * log10(high + 1.0))
}

private val HANN = DoubleArray(BLOCK_SAMPLES) { 0.5 - 0.5 * cos(2 * PI * it / BLOCK_SAMPLES) }

/** In-place radix-2 FFT; the size must be a power of two */
private fun fft(re: DoubleArray, im: DoubleArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j or bit
        if (i < j) {
            re[i] = re[j].also { re[j] = re[i] }
            im[i] = im[j].also { im[j] = im[i] }
        }
    }
    var size = 2
    while (size <= n) {
        val angle = -2 * PI / size
        for (start in 0 until n step size) {
            for (k in 0 until size / 2) {
                val wr = cos(angle * k)
                val wi = sin(angle * k)
                val a = start + k
                val b = a + size / 2
                val tr = re[b] * wr - im[b] * wi
                val ti = re[b] * wi + im[b] * wr
                re[b] = re[a] - tr
                im[b] = im[a] - ti
                re[a] += tr
                im[a] += ti
            }
        }
        size *= 2
    }
}

/** Reads interleaved 16-bit little-endian PCM */
fun readPcm(file: File): ShortArray {
    val bytes = file.readBytes()
    return ShortArray(bytes.size / 2) { i ->
        ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
    }
}

/** Writes interleaved 16-bit little-endian PCM */
fun writePcm(samples: ShortArray, file: File) {
    val bytes = ByteArray(samples.size * 2)
    samples.forEachIndexed { i, sample ->
        bytes[2 * i] = sample.toByte()
        bytes[2 * i + 1] = (sample.toInt() shr 8).toByte()
    }
    file.writeBytes(bytes)
}

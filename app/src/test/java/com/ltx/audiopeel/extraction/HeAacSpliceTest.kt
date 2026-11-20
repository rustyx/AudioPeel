package com.ltx.audiopeel.extraction

import com.ltx.audiopeel.AudioEdits
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class HeAacSpliceTest {
    private val he44k = AudioStreamInfo("aac", "4", 44_100, 2, "1/44100", startPts = 0)
    private val frame = AacProfile.HE.frameSamples

    /** Packets as written for Fraunhofer's encoder: priming of 5058 samples */
    private fun packets(from: Long, to: Long) =
        generateSequence(-5058L) { it + frame }.dropWhile { it < from }.takeWhile { it <= to }
            .map { AacPacket(it, AacWindow(WindowSequence.ONLY_LONG, kbd = true)) }.toList()

    private val plan = planAacSplice(he44k, packets(500_000, 700_000), AudioEdits.FadeOut(15_000L, 10_000L))!!

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun plansWithHeAacFrames() {
        // Fade at 661500; the last frame boundary before it is 325 * 2048 - 5058
        assertEquals(AacProfile.HE, plan.profile)
        assertEquals(660_542L, plan.junctionSample)
        assertEquals(44, plan.prerollFrames)
        assertEquals(660_542L - 44 * 2048, plan.encodeStartSample)
        assertEquals(plan.encodeStartSample - 2048, plan.pcmStartSample)
        assertTrue(buildConcatScript("h", "t", plan).contains("outpoint ${samplesToSeconds(660_542L - 1024, 44_100)}\n"))
        // PCM from 2 lead frames before the PCM start, relative to the seek position
        val decodeStart = plan.seekSeconds * 44_100
        assertTrue(
            plan.pcmFilter(2),
            plan.pcmFilter(2).startsWith("atrim=start_sample=${plan.pcmStartSample - 2 * 2048 - decodeStart}:")
        )
    }

    @Test
    fun plansWithTheCodedChannels() {
        val mono = he44k.copy(codecConfig = "2b8a08")
        assertEquals(1, planAacSplice(mono, packets(500_000, 700_000), AudioEdits.FadeOut(15_000L, 10_000L))!!.channels)
    }

    @Test
    fun alignsAnIndependentFrameWithTheJunction() {
        val alignment = alignEncoder(plan, delay = 5057, independentPeriod = 10)
        // 92160 + 5057 = 47 frames + 961 samples; 3 lead frames make frame 50 the first kept
        assertEquals(EncoderAlignment(leadFrames = 3, skipSamples = 961, dropFrames = 50), alignment)
        for (delay in listOf(-2048, -1, 0, 1, 2047, 2048, 5057, 12_288)) {
            for (period in listOf(1, 7, 10, 16)) {
                val (lead, skip, drop) = alignEncoder(plan, delay, period)
                // Frame `drop` holds the input from drop * frame - delay on
                val firstKept = plan.pcmStartSample - lead * frame + skip + drop.toLong() * frame - delay
                assertEquals("delay $delay period $period", plan.junctionSample, firstKept)
                assertEquals(0, drop % period)
                assertTrue(skip in 0 until frame)
                assertTrue(lead in 0 until period)
                // At least the preroll is encoded before the junction
                assertTrue(plan.junctionSample - (plan.pcmStartSample - lead * frame + skip) >= 44 * 2048)
            }
        }
    }

    @Test
    fun parsesAudioSpecificConfig() {
        // Explicit SBR, as written by Fraunhofer's and Android's encoder
        val explicit = parseAudioSpecificConfig(bytes("2b920800"))!!
        assertEquals(AacConfigInfo(5, 22_050, 2, 44_100), explicit)
        assertTrue(isJoinableHeAacConfig(explicit, 44_100, 2))
        assertFalse(isJoinableHeAacConfig(explicit, 48_000, 2))
        assertFalse(isJoinableHeAacConfig(explicit, 44_100, 1))
        // Implicit: AAC-LC at half the rate
        val implicit = parseAudioSpecificConfig(bytes("1390"))!!
        assertEquals(AacConfigInfo(2, 22_050, 2, null), implicit)
        assertTrue(isJoinableHeAacConfig(implicit, 44_100, 2))
        // Plain AAC-LC at the full rate, parametric stereo
        assertFalse(isJoinableHeAacConfig(parseAudioSpecificConfig(bytes("1210"))!!, 44_100, 2))
        val ps = parseAudioSpecificConfig(bytes("eb8a08"))!!
        assertEquals(AacConfigInfo(29, 22_050, 1, 44_100), ps)
        assertFalse(isJoinableHeAacConfig(ps, 44_100, 1))
        assertNull(parseAudioSpecificConfig(bytes("2b")))
    }

    @Test
    fun writesAdts() {
        val out = ByteArrayOutputStream()
        writeAdts(listOf(bytes("010203"), bytes("0405")), 22_050, 2, out)
        assertArrayEquals(
            bytes("fff15c80015ffc010203" + "fff15c80013ffc0405"),
            out.toByteArray()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun adtsNeedsAStandardRate() {
        writeAdts(listOf(bytes("01")), 22_000, 2, ByteArrayOutputStream())
    }

    @Test
    fun calibrationSignalIsFixed() {
        val signal = calibrationSignal(44_100, 2)
        assertEquals(66_150 * 2, signal.size)
        assertArrayEquals(signal, calibrationSignal(44_100, 2))
        assertTrue((0 until signal.size step 2).all { signal[it] == signal[it + 1] })
    }

    @Test
    fun measuresDelay() {
        val sent = calibrationSignal(44_100, 2)
        val random = Random(1)
        val lags = delaySearchRange(frame)
        for (delay in listOf(-300, 0, 961, 5057, 12_000)) {
            // Shifted, with noise and the high frequencies lost
            val decoded = ShortArray(sent.size + 30_000) { i ->
                val source = i - 2 * delay
                val value = if (source in sent.indices) {
                    (sent.getOrElse(source - 2) { 0 } + 2 * sent[source] + sent.getOrElse(source + 2) { 0 }) / 4
                } else {
                    0
                }
                (value + random.nextGaussian() * 200).toInt().toShort()
            }
            assertEquals(delay, measureDelay(sent, decoded, 2, 44_100, lags))
        }
        // Unrelated audio
        val other = ShortArray(sent.size + 30_000) { (random.nextGaussian() * 3000).toInt().toShort() }
        assertNull(measureDelay(sent, other, 2, 44_100, lags))
        // Too short to search all delays
        assertNull(measureDelay(sent, sent.copyOf(40_000), 2, 44_100, lags))
    }

    @Test
    fun findsIndependentPeriod() {
        val random = Random(2)
        fun noise(samples: Int) = ShortArray(samples * 2) { (random.nextGaussian() * 3000).toInt().toShort() }

        /** Decodes from frame k: without high frequencies in the first frame unless independent */
        fun part(independent: Boolean) = noise(3 * frame).also { decoded ->
            if (!independent) {
                for (i in 0 until 2 * frame) decoded[i] = (1000 * sin(2 * PI * 440 * (i / 2) / 44_100)).toInt().toShort()
            }
        }
        val full = noise(20 * frame)
        assertEquals(5, findIndependentPeriod(full, (1..16).map { part(it % 5 == 0) }, 2, frame))
        assertEquals(1, findIndependentPeriod(full, (1..16).map { part(true) }, 2, frame))
        assertNull(findIndependentPeriod(full, (1..16).map { part(false) }, 2, frame))
        // No high frequencies at all: nothing to tell by
        assertNull(findIndependentPeriod(ShortArray(full.size), (1..16).map { part(true) }, 2, frame))
    }

    /** Low tones plus noise across the spectrum, like music with SBR */
    private fun music(samples: Int, seed: Long): ShortArray {
        val random = Random(seed)
        return ShortArray(samples * 2) { i ->
            val t = i / 2 / 44_100.0
            (4000 * sin(2 * PI * 220 * t) + 3000 * sin(2 * PI * 1300 * t) + random.nextGaussian() * 1500)
                .toInt().toShort()
        }
    }

    private val junction = 4 * frame
    private val samples = 14 * frame

    private fun check(joined: ShortArray, source: ShortArray, gain: (Int) -> Double = { 1.0 }) =
        checkJunction(joined, source, 2, junction, frame, gain).problem

    @Test
    fun acceptsMatchingJoin() {
        val source = music(samples, 3)
        assertNull(check(source.copyOf(), source))
        // Re-encoded audio differs a little, and the fade applies
        val random = Random(4)
        val gain = { i: Int -> if (i < junction + 1000) 1.0 else 1.0 - (i - junction - 1000) / 100_000.0 }
        val joined = ShortArray(source.size) { i ->
            if (i < junction * 2) source[i] else (source[i] * gain(i / 2) + random.nextGaussian() * 300).toInt().toShort()
        }
        assertNull(check(joined, source, gain))
    }

    @Test
    fun rejectsChangesBeforeTheJunction() {
        val source = music(samples, 3)
        val joined = source.copyOf().also { it[2 * (junction - frame) - 2]++ }
        assertTrue(check(joined, source)!!.contains("2049 samples before"))
        // The frame overlapping the junction may differ
        assertNull(check(source.copyOf().also { it[2 * (junction - frame)]++ }, source))
    }

    @Test
    fun rejectsShiftedTail() {
        val source = music(samples, 3)
        val joined = ShortArray(source.size) { i -> if (i < junction * 2) source[i] else source.getOrElse(i - 2 * 40) { 0 } }
        assertEquals("re-encoded audio shifted by 40 samples", check(joined, source))
    }

    @Test
    fun rejectsMissingHighFrequencies() {
        val source = music(samples, 3)
        // After the junction, a moving average removes most of the noise above a few kHz
        val joined = ShortArray(source.size) { i ->
            if (i < junction * 2) {
                source[i]
            } else {
                ((-8..8 step 2).sumOf { source.getOrElse(i + it) { 0 }.toInt() } / 9).toShort()
            }
        }
        assertTrue(check(joined, source)!!.startsWith("band 2 level off by -"))
    }

    @Test
    fun comparesQuietHighFrequenciesNextToLoudBass() {
        val random = Random(5)
        // Bass 60 dB above the high frequencies
        val source = ShortArray(samples * 2) { i ->
            (20_000 * sin(2 * PI * 60 * (i / 2) / 44_100.0) + random.nextGaussian() * 20).toInt().toShort()
        }
        val report = checkJunction(source.copyOf(), source, 2, junction, frame) { 1.0 }
        assertNull(report.problem)
        assertTrue(report.summary, report.comparedFrames.all { it > 0 })
        // Without them after the junction
        val joined = ShortArray(source.size) { i ->
            if (i < junction * 2) source[i] else (20_000 * sin(2 * PI * 60 * (i / 2) / 44_100.0)).toInt().toShort()
        }
        assertTrue(check(joined, source)!!.startsWith("band 2 level off by -"))
    }

    @Test
    fun rejectsTooLittleAudio() {
        val source = music(junction + frame, 3)
        assertEquals("too little audio decoded around the junction", check(source, source))
    }

    @Test
    fun decodeArgs() {
        val seek = listOf("-y", "-nostdin", "-ss", "${plan.seekSeconds}")
        assertEquals(
            seek + listOf(
                "-i", "/w/head.m4a", "-vn", "-threads", "2", "-af", plan.pcmFilter(3),
                "-f", "s16le", "-c:a", "pcm_s16le", "-ar", "44100", "-ac", "2", "/w/tail.pcm"
            ),
            buildPcmDecodeArgs("/w/head.m4a", "/w/tail.pcm", plan, 3, 2)
        )
        assertEquals(
            listOf(
                "-y", "-nostdin", "-f", "aac", "-i", "a.aac", "-f", "aac", "-i", "b.aac",
                "-map", "0:a", "-f", "s16le", "-c:a", "pcm_s16le", "-ac", "2", "a.pcm",
                "-map", "1:a", "-f", "s16le", "-c:a", "pcm_s16le", "-ac", "2", "b.pcm"
            ),
            buildAdtsBatchDecodeArgs(listOf("a.aac" to "a.pcm", "b.aac" to "b.pcm"), 2)
        )
        assertEquals(
            listOf("-y", "-nostdin", "-f", "aac", "-i", "t.aac", "-c", "copy", "-f", "mp4", "t.m4a"),
            buildAdtsRemuxArgs("t.aac", "t.m4a")
        )
        val window = buildWindowDecodeArgs("o.m4a", "o.pcm", plan, plan.junctionSample - 100, plan.junctionSample)
        val decodeStart = plan.seekSeconds * 44_100
        assertTrue(
            window.toString(),
            window.contains(
                "atrim=start_sample=${plan.junctionSample - 100 - decodeStart}:end_sample=${plan.junctionSample - decodeStart}"
            )
        )
    }
}

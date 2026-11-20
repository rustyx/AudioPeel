package com.ltx.audiopeel.extraction

import com.ltx.audiopeel.AudioEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AacSpliceTest {
    private val lc48k = AudioStreamInfo("aac", "LC", 48_000, 2, "1/48000", startPts = 0)
    private val longKbd = AacWindow(WindowSequence.ONLY_LONG, kbd = true)

    /** Steady packets as written by FFmpeg's AAC encoder: priming frame at -1024 */
    private fun packets(fromSample: Long, toSample: Long, first: Long = -1024L) =
        generateSequence(first) { it + 1024 }.dropWhile { it < fromSample }.takeWhile { it <= toSample }
            .map { AacPacket(it, longKbd) }.toList()

    private fun List<AacPacket>.withWindow(pts: Long, window: AacWindow?) =
        map { if (it.pts == pts) it.copy(window = window) else it }

    private val fade = AudioEdits.FadeOut(startMs = 15_700L, durationMs = 12_000L)

    @Test
    fun parsesStreamInfoAmongLogLines() {
        val output = """
            [mov,mp4 @ 0x1234] some warning
            codec_name=aac
            profile=LC
            sample_rate=44100
            channels=1
            time_base=1/44100
            start_pts=-1024
        """.trimIndent()
        assertEquals(AudioStreamInfo("aac", "LC", 44_100, 1, "1/44100", -1024), parseStreamInfo(output))
        // Mono HE-AAC: decoded as stereo, with the codec config after the values
        val heAac = """
            codec_name=aac
            profile=4
            sample_rate=44100
            channels=2
            time_base=1/44100
            start_pts=0
            bit_rate=N/A
            extradata=
            00000000: 2b8a 0800 0000 0000 0000 0000 0000 0000  +...............
            00000010: 0000 0000 0000 0000 00                   .........

        """.trimIndent()
        val info = parseStreamInfo(heAac)!!
        assertEquals("2b8a0800" + "00".repeat(21), info.codecConfig)
        assertEquals(0L, info.bitRate)
        assertEquals(2, info.channels)
        assertEquals(1, info.codedChannels)
        assertEquals(2, info.copy(codecConfig = "").codedChannels)
        assertNull(parseStreamInfo("codec_name=aac\nprofile=LC"))
        assertNull(parseStreamInfo(""))
    }

    @Test
    fun onlyAacLcWithSampleTimestampsIsSpliceable() {
        assertTrue(lc48k.isSpliceable)
        assertTrue(lc48k.copy(profile = "1", channels = 1).isSpliceable)
        assertTrue(lc48k.copy(profile = "4").isSpliceable)
        assertFalse(lc48k.copy(profile = "HE-AACv2").isSpliceable)
        assertFalse(lc48k.copy(codec = "mp3").isSpliceable)
        assertFalse(lc48k.copy(channels = 6).isSpliceable)
        assertFalse(lc48k.copy(timeBase = "1/90000").isSpliceable)
        // Before copying, only the codec is checked
        assertEquals(AacProfile.LC, lc48k.copy(timeBase = "1/90000").aacProfile)
        assertEquals(AacProfile.HE, lc48k.copy(profile = "4", timeBase = "1/24000").aacProfile)
        assertEquals(AacProfile.HE, lc48k.copy(profile = "HE-AAC").aacProfile)
        assertNull(lc48k.copy(profile = "28").aacProfile)
        assertNull(lc48k.copy(profile = "HE-AACv2").aacProfile)
    }

    @Test
    fun readsWindowFromFrameHeader() {
        fun window(vararg bytes: Int) = parseAacWindow(bytes.map { it.toByte() }.toByteArray())
        // Channel pair with a common window
        assertEquals(longKbd, window(0x21, 0x1a, 0x08))
        assertEquals(AacWindow(WindowSequence.LONG_STOP, true), window(0x21, 0x7a, 0x0e))
        assertEquals(AacWindow(WindowSequence.LONG_START, false), window(0x21, 0x20, 0x00))
        // Single channel element: tag 0, global_gain 0x80, then ics_info
        assertEquals(AacWindow(WindowSequence.EIGHT_SHORT, false), window(0x01, 0x00, 0x80))
        // Channel pair with separate windows, other elements, too short
        assertNull(window(0x20, 0xff, 0xff))
        assertNull(window(0xc0, 0x00, 0x00))
        assertNull(window(0x21, 0x1a))
    }

    @Test
    fun parsesPacketDump() {
        val output = """
            pts=811008
            data=
            00000000: 217a 0e02 81f8 0004 7da4 3e00 1d6d ad2c  !z......}.>..m.,
            00000010: 60e2 f100 1f13 ca32 8f8b 8691 0988 b221  `......2.......!
            [aac @ 0x1] some log line
            pts=812032
            data=
            00000000: 211a 0803 ed                             !....
            pts=813056
        """.trimIndent()
        assertEquals(
            listOf(
                AacPacket(811_008, AacWindow(WindowSequence.LONG_STOP, true)),
                AacPacket(812_032, longKbd),
                AacPacket(813_056, null),
            ),
            parsePackets(output)
        )
    }

    @Test
    fun parsesDuration() {
        assertEquals(27_712L, parseDurationMs("27.712000\n"))
        assertNull(parseDurationMs("N/A\n"))
    }

    @Test
    fun junctionIsLastFrameBoundaryBeforeFade() {
        val plan = planAacSplice(lc48k, packets(650_000, 760_000), fade)!!
        // Fade starts at sample 753600; the frame boundary before it is 735 * 1024
        assertEquals(752_640L, plan.junctionSample)
        // About 2 s of preroll
        assertEquals(94, plan.prerollFrames)
        assertEquals(656_384L, plan.encodeStartSample)
        assertEquals(12L, plan.seekSeconds)
        assertEquals(753_600L, plan.fadeStartSample)
        assertEquals(1_329_600L, plan.endSample)
        assertEquals(
            "atrim=start_sample=80384:end_sample=753600,asetpts=PTS-STARTPTS," +
                "afade=t=out:start_sample=97216:nb_samples=576000:curve=cub",
            plan.tailFilter
        )
    }

    @Test
    fun fadeStartOnFrameBoundaryIsTheJunction() {
        // 8 s at 48 kHz is exactly 375 frames
        val plan = planAacSplice(lc48k, packets(0, 400_000), AudioEdits.FadeOut(8_000L, 1_000L))!!
        assertEquals(384_000L, plan.junctionSample)
        assertEquals(4L, plan.seekSeconds)
    }

    @Test
    fun noSeekNearTheStart() {
        val plan = planAacSplice(lc48k, packets(-1024, 200_000), AudioEdits.FadeOut(3_000L, 1_000L))!!
        assertEquals(143_360L, plan.junctionSample)
        assertEquals(0L, plan.seekSeconds)
        assertTrue(plan.tailFilter.startsWith("atrim=start_sample=47104:end_sample=192000,"))
    }

    @Test
    fun junctionAvoidsBlockSwitching() {
        // A transient just before the fade: the source switches to short blocks
        val packets = packets(650_000, 760_000)
            .withWindow(751_616, AacWindow(WindowSequence.LONG_START, false))
            .withWindow(752_640, AacWindow(WindowSequence.EIGHT_SHORT, false))
            .withWindow(753_664, AacWindow(WindowSequence.LONG_STOP, true))
        assertEquals(750_592L, planAacSplice(lc48k, packets, fade)!!.junctionSample)
        // After a short block sequence, a long-stop frame may precede the junction
        val afterTransient = packets(650_000, 760_000)
            .withWindow(750_592, AacWindow(WindowSequence.EIGHT_SHORT, false))
            .withWindow(751_616, AacWindow(WindowSequence.LONG_STOP, true))
        assertEquals(752_640L, planAacSplice(lc48k, afterTransient, fade)!!.junctionSample)
    }

    @Test
    fun junctionNeedsThePreviousFrameRightBeforeIt() {
        // A packet missing away from the junction doesn't matter
        val gapFarAway = packets(650_000, 760_000).filter { it.pts != 700_416L }
        assertEquals(752_640L, planAacSplice(lc48k, gapFarAway, fade)!!.junctionSample)
        // Right before it, it moves the junction
        val gapBefore = packets(650_000, 760_000).filter { it.pts != 751_616L }
        assertEquals(750_592L, planAacSplice(lc48k, gapBefore, fade)!!.junctionSample)
    }

    @Test
    fun noJunctionWithoutMatchingWindows() {
        // Long blocks with the sine shape, which FFmpeg's encoder doesn't use
        val sine = packets(650_000, 760_000).map { it.copy(window = AacWindow(WindowSequence.ONLY_LONG, false)) }
        assertNull(planAacSplice(lc48k, sine, fade))
        // Unknown windows
        assertNull(planAacSplice(lc48k, packets(650_000, 760_000).map { it.copy(window = null) }, fade))
    }

    @Test
    fun positionsAreRelativeToAudioStart() {
        // Audio starting 0.678 s into the file, fade at 22 s into the audio
        val info = lc48k.copy(startPts = 32_544)
        val plan = planAacSplice(
            info, packets(1_000_000, 1_100_000, first = 32_544), AudioEdits.FadeOut(22_000L, 8_000L)
        )!!
        assertEquals(1_088_544L, plan.fadeStartSample)
        assertEquals(1_088_288L, plan.junctionSample)
        assertEquals(18L, plan.seekSeconds)
        assertTrue(plan.tailFilter, plan.tailFilter.startsWith("atrim=start_sample=95488:end_sample=576000,"))
        assertTrue(buildConcatScript("h", "t", plan).contains("duration 21.994667\n"))
        val args = buildPacketProbeArgs("h", info, 22_000L)
        assertEquals("19.678%22.778", args[args.indexOf("-read_intervals") + 1])
    }

    @Test
    fun noPlanWhenSplicingIsImpossible() {
        // Not enough audio before the fade for the preroll
        assertNull(planAacSplice(lc48k, packets(-1024, 5_000), AudioEdits.FadeOut(30L, 5_000L)))
        assertNull(planAacSplice(lc48k, packets(-1024, 100_000), AudioEdits.FadeOut(1_900L, 5_000L)))
        // No packets found
        assertNull(planAacSplice(lc48k, emptyList(), fade))
        // Unsupported stream
        assertNull(planAacSplice(lc48k.copy(profile = "28"), packets(650_000, 760_000), fade))
        // Packets not a frame apart: HE-AAC frames hold 2048 samples
        assertNull(planAacSplice(lc48k.copy(profile = "4"), packets(650_000, 760_000), fade))
    }

    @Test
    fun concatScriptPlacesTailAtJunction() {
        val plan = planAacSplice(lc48k, packets(650_000, 760_000), fade)!!
        assertEquals(
            """
                ffconcat version 1.0
                file 'head.m4a'
                outpoint 15.669333
                duration 15.680000
                file 'tail.m4a'

            """.trimIndent(),
            buildConcatScript("head.m4a", "tail.m4a", plan)
        )
    }

    @Test
    fun prerollCoversTwoSeconds() {
        assertEquals(94, prerollFrames(48_000))
        assertEquals(87, prerollFrames(44_100))
        assertEquals(16, prerollFrames(8_000))
    }

    @Test
    fun tailEncodeDropsPrimingAndPreroll() {
        val plan = planAacSplice(lc48k, packets(650_000, 760_000), fade)!!
        assertEquals(
            listOf(
                "-y", "-nostdin", "-ss", "12", "-i", "/w/head.m4a", "-vn", "-threads", "2",
                "-af", plan.tailFilter, "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2",
                "-bsf:a", "noise=drop=lt(n\\,95)", "-f", "mp4", "/w/tail.m4a"
            ),
            buildTailEncodeArgs("/w/head.m4a", "/w/tail.m4a", plan, 2)
        )
        assertFalse(buildTailEncodeArgs("h", "t", plan.copy(seekSeconds = 0), 1).contains("-ss"))
    }

    @Test
    fun otherSpliceArgs() {
        assertEquals(
            listOf(
                "-y", "-nostdin", "-i", "saf:1", "-map", "0:a:0", "-vn", "-c:a", "copy",
                "-t", "29.700", "-f", "mp4", "/w/head.m4a"
            ),
            buildHeadCopyArgs("saf:1", "/w/head.m4a", 27_700L)
        )
        assertEquals(
            listOf(
                "-v", "error", "-select_streams", "a:0", "-show_entries", "packet=pts,data", "-show_data",
                "-read_intervals", "12.700%15.800", "-of", "default=noprint_wrappers=1", "/w/head.m4a"
            ),
            buildPacketProbeArgs("/w/head.m4a", lc48k, 15_700L)
        )
        assertTrue(buildPacketProbeArgs("h", lc48k, 500L).contains("0.000%0.600"))
        assertTrue(buildFirstPacketProbeArgs("h").contains("%+#1"))
        assertEquals(
            listOf(
                "-y", "-nostdin", "-copyts", "-f", "concat", "-safe", "0", "-i", "/w/join.ffconcat",
                "-c", "copy", "-f", "mp4", "-movflags", "+faststart", "/o.m4a"
            ),
            buildJoinArgs("/w/join.ffconcat", "/o.m4a")
        )
    }
}

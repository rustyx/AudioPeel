package com.ltx.audiopeel.extraction

import com.ltx.audiopeel.AudioEdits
import com.ltx.audiopeel.OutputFormat

/** Encoder settings used whenever M4A audio is transcoded */
internal val AAC_ENCODER_ARGS = listOf("-c:a", "aac", "-b:a", "192k")

/** Container settings for M4A output */
internal val M4A_MUXER_ARGS = listOf("-f", "mp4", "-movflags", "+faststart")

/**
 * Shape of the fade-out, as FFmpeg's afade curve: the gain is the cube of
 * the fraction of the fade left. A linear fade sounds as if it stays loud
 * until the last moment, since loudness is heard logarithmically; the cubic
 * one falls at a more even rate in dB and is near silent at the end
 */
internal const val FADE_CURVE = "cub"

/** Gain of the fade-out with [remaining] of it left, from 1 to 0, matching [FADE_CURVE] */
internal fun fadeOutGain(remaining: Double): Double = remaining.coerceIn(0.0, 1.0).let { it * it * it }

/**
 * Builds the FFmpeg arguments that extract the audio in a single pass
 *
 * The audio stream is copied when the source codec matches the target
 * format and no edit needs decoded samples; otherwise it is transcoded.
 * The first audio track is used, the same one the codec was detected from.
 *
 * @param input FFmpeg input (e.g. an SAF parameter)
 * @param outPath output file path
 * @param format output format
 * @param sourceCodec codec of the source audio track, empty if unknown
 * @param threads FFmpeg thread count
 * @param edits edits to apply
 */
fun buildSinglePassArgs(
    input: String,
    outPath: String,
    format: OutputFormat,
    sourceCodec: String,
    threads: Int,
    edits: AudioEdits = AudioEdits(),
): List<String> {
    val canCopy = !edits.requiresTranscode
    val codecArgs = when (format) {
        OutputFormat.MP3 -> if (canCopy && sourceCodec == "mp3") {
            listOf("-c:a", "copy")
        } else {
            listOf("-c:a", "libmp3lame", "-q:a", "2")
        }
        OutputFormat.M4A -> if (canCopy && sourceCodec == "aac") {
            listOf("-c:a", "copy") + M4A_MUXER_ARGS
        } else {
            AAC_ENCODER_ARGS + M4A_MUXER_ARGS
        }
        OutputFormat.WAV -> listOf("-c:a", "pcm_s16le", "-ar", "44100")
        OutputFormat.FLAC -> if (canCopy && sourceCodec == "flac") {
            listOf("-c:a", "copy")
        } else {
            listOf("-c:a", "flac", "-compression_level", "5")
        }
        OutputFormat.OGG -> if (canCopy && (sourceCodec == "vorbis" || sourceCodec == "opus")) {
            listOf("-c:a", "copy", "-f", "ogg")
        } else {
            listOf("-c:a", "libvorbis", "-q:a", "5")
        }
    }
    return buildList {
        addAll(listOf("-y", "-nostdin", "-i", input, "-map", "0:a:0", "-vn", "-threads", "$threads"))
        edits.fadeOut?.let {
            // Rebase timestamps to 0 so the fade start matches the output timeline
            add("-af")
            add(
                "asetpts=PTS-STARTPTS,afade=t=out" +
                    ":st=${formatSeconds(it.startMs)}:d=${formatSeconds(it.durationMs)}:curve=$FADE_CURVE"
            )
        }
        edits.outputDurationMs?.let { addAll(listOf("-t", formatSeconds(it))) }
        addAll(codecArgs)
        add(outPath)
    }
}

/**
 * Formats milliseconds as seconds with a '.' separator, independent of the locale
 */
internal fun formatSeconds(ms: Long): String =
    "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"

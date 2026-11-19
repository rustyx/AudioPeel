package com.ltx.audiopeel

/**
 * Edits applied to the end of the extracted audio
 */
enum class EndEditKind { CUT_END, FADE_OUT }

/**
 * One end edit as configured by the user
 *
 * The length is kept as the raw text of the input field so that an
 * incomplete value (e.g. an empty field while typing) can be shown as is.
 *
 * @param enabled whether the edit is applied
 * @param secondsText length in seconds as typed by the user
 */
data class EndEditOption(
    val enabled: Boolean = false,
    val secondsText: String = DEFAULT_SECONDS.toString(),
) {
    /** The length in seconds, or null if the text isn't a number in range */
    val seconds: Int? get() = secondsText.toIntOrNull()?.takeIf { it in MIN_SECONDS..MAX_SECONDS }

    /** Whether the option can be applied as configured */
    val isValid: Boolean get() = !enabled || seconds != null

    companion object {
        const val DEFAULT_SECONDS = 12
        const val MIN_SECONDS = 1
        const val MAX_SECONDS = 3600
        const val MAX_DIGITS = 4
    }
}

/**
 * All end edits as configured by the user
 */
data class EndEditSettings(
    val cutEnd: EndEditOption = EndEditOption(),
    val fadeOut: EndEditOption = EndEditOption(),
) {
    operator fun get(kind: EndEditKind): EndEditOption = when (kind) {
        EndEditKind.CUT_END -> cutEnd
        EndEditKind.FADE_OUT -> fadeOut
    }

    fun with(kind: EndEditKind, option: EndEditOption): EndEditSettings = when (kind) {
        EndEditKind.CUT_END -> copy(cutEnd = option)
        EndEditKind.FADE_OUT -> copy(fadeOut = option)
    }

    val isValid: Boolean get() = cutEnd.isValid && fadeOut.isValid
}

/**
 * Edits resolved against a concrete audio duration
 *
 * @param outputDurationMs length to cut the output to, or null to keep it whole
 * @param fadeOut fade applied to the end of the output, or null for none
 */
data class AudioEdits(
    val outputDurationMs: Long? = null,
    val fadeOut: FadeOut? = null,
) {
    data class FadeOut(val startMs: Long, val durationMs: Long)

    /** A fade needs decoded samples, so the audio stream can't be copied */
    val requiresTranscode: Boolean get() = fadeOut != null
}

/**
 * Why the end edits can't be applied
 */
enum class EndEditError { INVALID_SECONDS, UNKNOWN_DURATION, CUT_TOO_LONG }

sealed interface EndEditPlan {
    data class Ready(val edits: AudioEdits) : EndEditPlan
    data class Failed(val error: EndEditError) : EndEditPlan
}

/**
 * Resolves the end edits for audio of the given length
 *
 * The cut is applied first; the fade then covers the end of what remains,
 * and is shortened if the remaining audio is shorter than the fade.
 *
 * @param settings end edits as configured by the user
 * @param sourceDurationMs length of the source audio, or 0 if unknown
 */
fun planEndEdits(settings: EndEditSettings, sourceDurationMs: Long): EndEditPlan {
    val cut = settings.cutEnd
    val fade = settings.fadeOut
    if (!cut.enabled && !fade.enabled) return EndEditPlan.Ready(AudioEdits())
    val cutSeconds = if (cut.enabled) cut.seconds else 0
    val fadeSeconds = if (fade.enabled) fade.seconds else 0
    if (cutSeconds == null || fadeSeconds == null) {
        return EndEditPlan.Failed(EndEditError.INVALID_SECONDS)
    }
    if (sourceDurationMs <= 0) return EndEditPlan.Failed(EndEditError.UNKNOWN_DURATION)
    val outputMs = sourceDurationMs - cutSeconds * 1000L
    if (outputMs <= 0) return EndEditPlan.Failed(EndEditError.CUT_TOO_LONG)
    val fadeOut = if (fade.enabled) {
        val fadeMs = minOf(fadeSeconds * 1000L, outputMs)
        AudioEdits.FadeOut(startMs = outputMs - fadeMs, durationMs = fadeMs)
    } else {
        null
    }
    return EndEditPlan.Ready(
        AudioEdits(
            outputDurationMs = if (cut.enabled) outputMs else null,
            fadeOut = fadeOut
        )
    )
}

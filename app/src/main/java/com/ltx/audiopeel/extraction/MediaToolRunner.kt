package com.ltx.audiopeel.extraction

/**
 * Runs FFmpeg and FFprobe
 */
interface MediaToolRunner {
    /**
     * Runs FFmpeg and waits for it to finish
     *
     * @param args command line arguments
     * @param onProgress receives the output position in milliseconds
     */
    fun ffmpeg(args: List<String>, onProgress: (Long) -> Unit = {}): ToolResult

    /**
     * Runs FFprobe and waits for it to finish
     *
     * @param args command line arguments
     */
    fun ffprobe(args: List<String>): ToolResult

    /** Whether cancellation was requested; no further tools are run once it is */
    val isCancelled: Boolean
}

/**
 * Outcome of a tool run
 *
 * @param outcome how the run ended
 * @param output what the tool printed (FFprobe), or its logs if it failed
 */
data class ToolResult(val outcome: Outcome, val output: String = "") {
    enum class Outcome { SUCCESS, CANCELLED, FAILED }

    val isSuccess: Boolean get() = outcome == Outcome.SUCCESS
}

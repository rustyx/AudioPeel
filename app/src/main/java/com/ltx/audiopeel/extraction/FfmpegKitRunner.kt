package com.ltx.audiopeel.extraction

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode

/**
 * Runs the tools with FFmpegKit; one instance per extraction
 */
class FfmpegKitRunner : MediaToolRunner {
    @Volatile
    private var cancelled = false

    @Volatile
    private var currentSessionId: Long? = null

    override val isCancelled: Boolean get() = cancelled

    override fun ffmpeg(args: List<String>, onProgress: (Long) -> Unit): ToolResult {
        if (cancelled) return ToolResult(ToolResult.Outcome.CANCELLED)
        val session = FFmpegSession.create(args.toTypedArray(), null, null) { statistics ->
            statistics.time.toLong().takeIf { it > 0 }?.let(onProgress)
        }
        currentSessionId = session.sessionId
        try {
            // Cancellation requested before the session ID was known
            if (cancelled) return ToolResult(ToolResult.Outcome.CANCELLED)
            FFmpegKitConfig.ffmpegExecute(session)
        } finally {
            currentSessionId = null
        }
        val returnCode = session.returnCode
        return when {
            cancelled || ReturnCode.isCancel(returnCode) -> ToolResult(ToolResult.Outcome.CANCELLED)
            ReturnCode.isSuccess(returnCode) -> ToolResult(ToolResult.Outcome.SUCCESS)
            else -> ToolResult(
                ToolResult.Outcome.FAILED,
                "return code $returnCode: ${session.allLogsAsString}"
            )
        }
    }

    override fun ffprobe(args: List<String>): ToolResult {
        if (cancelled) return ToolResult(ToolResult.Outcome.CANCELLED)
        val session = FFprobeKit.executeWithArguments(args.toTypedArray())
        val output = session.output.orEmpty()
        return when {
            cancelled -> ToolResult(ToolResult.Outcome.CANCELLED)
            ReturnCode.isSuccess(session.returnCode) -> ToolResult(ToolResult.Outcome.SUCCESS, output)
            else -> ToolResult(ToolResult.Outcome.FAILED, "return code ${session.returnCode}: $output")
        }
    }

    /**
     * Stops the running FFmpeg session, and any later ones from starting
     */
    fun cancel() {
        cancelled = true
        currentSessionId?.let { FFmpegKit.cancel(it) }
    }
}

package com.ltx.audiopeel

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.graphics.scale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Audio output format
 */
enum class OutputFormat(val extension: String) {
    MP3("mp3"), M4A("m4a"), WAV("wav"), FLAC("flac"), OGG("ogg")
}

/**
 * Audio extraction state
 */
sealed class ExtractionState {
    data object Idle : ExtractionState()
    data class Processing(val progress: Float) : ExtractionState()
    data object Cancelled : ExtractionState()
    data class Success(val outPath: String) : ExtractionState()
    data class Error(val message: String? = null, val messageResId: Int? = null) : ExtractionState()
}

/**
 * Video metadata
 */
data class VideoMetadata(
    val durationMs: Long = 0L,
    val fileSize: Long = 0L,
    val audioCodec: String? = null,
    val thumbnail: Bitmap? = null,
)

/**
 * Main view model
 */
class MainViewModel : ViewModel() {
    private val _state = MutableStateFlow<ExtractionState>(ExtractionState.Idle)
    val state: StateFlow<ExtractionState> = _state.asStateFlow()
    private val _selectedUri = MutableStateFlow<Uri?>(null)
    val selectedUri: StateFlow<Uri?> = _selectedUri.asStateFlow()
    private val _selectedFormat = MutableStateFlow(OutputFormat.M4A)
    val selectedFormat: StateFlow<OutputFormat> = _selectedFormat.asStateFlow()
    private val _videoMetadata = MutableStateFlow<VideoMetadata?>(null)
    val videoMetadata: StateFlow<VideoMetadata?> = _videoMetadata.asStateFlow()
    private var cachedSourceCodec: String? = null
    private var lastProgressUpdate = 0L

    @Volatile
    private var currentSessionId: Long? = null

    @Volatile
    private var cancelRequested = false

    /**
     * Probes the audio codec embedded in the video
     *
     * @return Pair(source codec name, suggested output format)
     */
    private fun detectAudio(context: Context, uri: Uri): Pair<String, OutputFormat>? {
        // Create the media extractor
        val extractor = MediaExtractor()
        try {
            // Set the data source
            extractor.setDataSource(context, uri, null)
            // Iterate over all tracks
            for (i in 0 until extractor.trackCount) {
                // Get the track format
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                // Check whether it is an audio track
                if (!mime.startsWith("audio/")) continue
                // Map the full MIME type to an internal codec name
                val codec = when (mime) {
                    "audio/mp4a-latm" -> "aac"
                    "audio/mpeg" -> "mp3"
                    "audio/flac" -> "flac"
                    "audio/vorbis" -> "vorbis"
                    "audio/opus" -> "opus"
                    "audio/raw" -> "pcm"
                    else -> mime.removePrefix("audio/")
                }
                // Suggest an output format based on the codec name
                val recommended = when (codec) {
                    "aac" -> OutputFormat.M4A
                    "mp3" -> OutputFormat.MP3
                    "flac" -> OutputFormat.FLAC
                    "vorbis", "opus" -> OutputFormat.OGG
                    else -> OutputFormat.M4A
                }
                return codec to recommended
            }
        } catch (e: Exception) {
            Log.w("AudioPeel", "Failed to detect audio codec: ${e.message}", e)
        } finally {
            runCatching { extractor.release() }.onFailure {
                Log.w(
                    "AudioPeel", "Failed to release MediaExtractor", it
                )
            }
        }
        return null
    }

    /**
     * Selects a video file
     *
     * @param context context
     * @param uri video URI
     */
    fun selectVideo(context: Context, uri: Uri?) {
        // Use the application context
        val appContext = context.applicationContext
        // Release the previous thumbnail bitmap
        _videoMetadata.value?.thumbnail?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        _videoMetadata.value = null
        // Update the selected video URI
        _selectedUri.value = uri
        // Reset the state to idle
        _state.value = ExtractionState.Idle
        // If the video URI is null
        if (uri == null) {
            // Clear the cached source codec
            cachedSourceCodec = null
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Get Pair(source codec name, suggested output format)
                val detected = detectAudio(appContext, uri)
                // Update the cached source codec
                cachedSourceCodec = detected?.first
                // If an audio codec was detected
                if (detected != null) {
                    // Update the selected output format
                    _selectedFormat.value = detected.second
                }
                // Extracts video metadata
                val metadata = extractVideoMetadata(appContext, uri, detected?.first)
                _videoMetadata.value = metadata
            }
        }
    }

    /**
     * Extracts video metadata
     *
     * @param context context
     * @param uri video URI
     * @param audioCodec audio codec
     * @return video metadata
     */
    private fun extractVideoMetadata(
        context: Context,
        uri: Uri,
        audioCodec: String?,
    ): VideoMetadata {
        // Get the file size
        val fileSize = resolveFileSize(context, uri)
        // Get duration and thumbnail
        var durationMs = 0L
        var thumbnail: Bitmap? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            thumbnail = extractThumbnail(retriever, durationMs)
        } catch (e: Exception) {
            Log.w("AudioPeel", "Failed to extract video metadata: ${e.message}", e)
        } finally {
            runCatching { retriever.release() }.onFailure {
                Log.w(
                    "AudioPeel", "Failed to release MediaMetadataRetriever", it
                )
            }
        }
        return VideoMetadata(
            durationMs = durationMs,
            fileSize = fileSize,
            audioCodec = audioCodec,
            thumbnail = thumbnail
        )
    }

    /**
     * Resolves the file size
     *
     * @param context context
     * @param uri video URI
     * @return file size
     */
    private fun resolveFileSize(context: Context, uri: Uri): Long {
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                pfd.statSize.takeIf { it > 0 } ?: 0L
            } ?: 0L
        }.onFailure {
            Log.d("AudioPeel", "Failed to get file size: ${it.message}")
        }.getOrDefault(0L)
    }

    /**
     * Extracts a video thumbnail
     *
     * @param retriever media metadata retriever
     * @param durationMs total video duration in milliseconds
     * @return thumbnail
     */
    private fun extractThumbnail(retriever: MediaMetadataRetriever, durationMs: Long): Bitmap? {
        // Pick the frame time
        val frameTimeUs = if (durationMs >= 1000L) 1_000_000L else 0L
        // Get the full-size frame
        val original =
            retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
        // Calculates the target thumbnail size
        val (targetW, targetH) = computeThumbnailSize(original.width, original.height)
        // Return the original if it is already small enough
        if (original.width <= targetW && original.height <= targetH) return original
        // Scale the thumbnail
        return original.scale(targetW, targetH).also {
            if (it !== original) original.recycle()
        }
    }

    /**
     * Calculates the target thumbnail size
     *
     * @param width original width
     * @param height original height
     * @return Pair(target width, target height)
     */
    private fun computeThumbnailSize(width: Int, height: Int): Pair<Int, Int> {
        val longSide = maxOf(width, height)
        if (longSide <= THUMBNAIL_MAX_SIZE) return width to height
        val scale = THUMBNAIL_MAX_SIZE.toFloat() / longSide
        val targetW = (width * scale).toInt().coerceAtLeast(1)
        val targetH = (height * scale).toInt().coerceAtLeast(1)
        return targetW to targetH
    }

    /**
     * Selects the output format
     *
     * @param format output format
     */
    fun selectFormat(format: OutputFormat) {
        _selectedFormat.value = format
    }

    /**
     * Extracts audio
     *
     * @param context context
     */
    fun extractAudio(context: Context) {
        // Use the application context
        val appContext = context.applicationContext
        val uri = _selectedUri.value ?: return
        if (_state.value is ExtractionState.Processing) return
        // Get the currently selected output format
        val format = _selectedFormat.value
        cancelRequested = false
        lastProgressUpdate = 0L
        currentSessionId = null
        _state.value = ExtractionState.Processing(0f)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    // Build the output path
                    val outDir = appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                        ?: appContext.cacheDir
                    // Clean up previously extracted audio files
                    outDir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
                    // Generate a unique file name
                    val timeStamp = SimpleDateFormat(
                        "yyyyMMdd_HHmmss", Locale.getDefault()
                    ).format(Date())
                    val fileName = "audio_${timeStamp}.${format.extension}"
                    val outFile = File(outDir, fileName)
                    val outPath = outFile.absolutePath
                    // Get media info using the native Android APIs
                    var totalDurationMs = 0L
                    // Get the duration
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(appContext, uri)
                        totalDurationMs = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION
                        )?.toLongOrNull() ?: 0L
                    } catch (e: Exception) {
                        Log.w("AudioPeel", "Failed to get duration: ${e.message}", e)
                    } finally {
                        runCatching { retriever.release() }.onFailure {
                            Log.w(
                                "AudioPeel", "Failed to release MediaMetadataRetriever", it
                            )
                        }
                    }
                    // If the cached source codec is null
                    if (cachedSourceCodec == null) {
                        // Probe the audio codec and update the cached source codec
                        cachedSourceCodec = detectAudio(appContext, uri)?.first
                    }
                    // Get the cached source codec
                    val sourceAudioCodec = cachedSourceCodec.orEmpty()
                    // Convert the content URI into an SAF input FFmpeg can read
                    val safInput = FFmpegKitConfig.getSafParameterForRead(appContext, uri)
                    // Limit the thread count to 1-4
                    val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
                    // Build the ffmpeg command
                    val base = "-y -nostdin -i $safInput -vn -threads $threads"
                    // Copy the audio stream if the source codec matches the target format, otherwise transcode
                    val cmd = when (format) {
                        OutputFormat.MP3 -> {
                            if (sourceAudioCodec == "mp3") {
                                "$base -c:a copy \"$outPath\""
                            } else {
                                "$base -c:a libmp3lame -q:a 2 \"$outPath\""
                            }
                        }
                        OutputFormat.M4A -> {
                            if (sourceAudioCodec == "aac") {
                                "$base -c:a copy -f mp4 -movflags +faststart \"$outPath\""
                            } else {
                                "$base -c:a aac -b:a 192k -f mp4 -movflags +faststart \"$outPath\""
                            }
                        }
                        OutputFormat.WAV -> "$base -c:a pcm_s16le -ar 44100 \"$outPath\""
                        OutputFormat.FLAC -> {
                            if (sourceAudioCodec == "flac") {
                                "$base -c:a copy \"$outPath\""
                            } else {
                                "$base -c:a flac -compression_level 5 \"$outPath\""
                            }
                        }
                        OutputFormat.OGG -> {
                            when (sourceAudioCodec) {
                                "vorbis", "opus" ->
                                    "$base -c:a copy -f ogg \"$outPath\""
                                else ->
                                    "$base -c:a libvorbis -q:a 5 \"$outPath\""
                            }
                        }
                    }
                    // Run the extraction asynchronously
                    Log.d("AudioPeel", "FFmpeg command: $cmd")
                    val session = FFmpegKit.executeAsync(cmd, { currentSession ->
                        // Completion callback
                        val returnCode = currentSession.returnCode
                        val cancelled = cancelRequested || ReturnCode.isCancel(returnCode)
                        currentSessionId = null
                        cancelRequested = false
                        if (cancelled) {
                            if (outFile.exists()) {
                                outFile.delete()
                            }
                            _state.value = ExtractionState.Cancelled
                        } else if (ReturnCode.isSuccess(returnCode)) {
                            _state.value = ExtractionState.Success(outPath)
                        } else {
                            val logs = currentSession.allLogsAsString
                            Log.e("AudioPeel", "FFmpeg Error $returnCode: $logs")
                            if (outFile.exists()) {
                                // Delete the incomplete output file
                                outFile.delete()
                            }
                            _state.value =
                                ExtractionState.Error(messageResId = R.string.extraction_failed)
                        }
                    }, { _ -> }, { statistics ->
                        // Progress callback
                        if (totalDurationMs > 0) {
                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate >= 200) {
                                lastProgressUpdate = now
                                val timeInMilliseconds = statistics.time
                                if (timeInMilliseconds > 0) {
                                    val progress =
                                        (timeInMilliseconds.toFloat() / totalDurationMs.toFloat()).coerceIn(
                                            0f, 1f
                                        )
                                    _state.value = ExtractionState.Processing(progress)
                                }
                            }
                        }
                    })
                    currentSessionId = session.sessionId
                } catch (e: Exception) {
                    currentSessionId = null
                    if (cancelRequested) {
                        cancelRequested = false
                        _state.value = ExtractionState.Cancelled
                    } else {
                        _state.value = ExtractionState.Error(
                            message = e.message,
                            messageResId = if (e.message == null) R.string.unknown_error else null
                        )
                    }
                }
            }
        }
    }

    /* Cancels the extraction */
    fun cancelExtraction() {
        if (_state.value !is ExtractionState.Processing) return
        cancelRequested = true
        val sessionId = currentSessionId
        if (sessionId != null) {
            FFmpegKit.cancel(sessionId)
        } else {
            FFmpegKit.cancel()
        }
    }

    /* Cancel extraction when the ViewModel is cleared */
    override fun onCleared() {
        super.onCleared()
        cancelExtraction()
        // Release the thumbnail bitmap
        _videoMetadata.value?.thumbnail?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        _videoMetadata.value = null
    }

    private companion object {
        const val THUMBNAIL_MAX_SIZE = 1280
    }
}

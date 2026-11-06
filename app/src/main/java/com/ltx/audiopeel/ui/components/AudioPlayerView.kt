package com.ltx.audiopeel.ui.components

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.ltx.audiopeel.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Audio player view
 *
 * @param filePath audio file path
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerView(filePath: String) {
    val context = LocalContext.current
    // Create an ExoPlayer instance for the file path
    val exoPlayer = remember(filePath) {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(Uri.fromFile(File(filePath)))
            setMediaItem(mediaItem)
            prepare()
        }
    }
    // Playback state and position
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var metadataDuration by remember(filePath) { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    LaunchedEffect(filePath) {
        metadataDuration = withContext(Dispatchers.IO) {
            getAudioDurationFromMetadata(filePath)
        }
        if (duration <= 0L && metadataDuration > 0L) {
            duration = metadataDuration
        }
    }
    // DisposableEffect manages the ExoPlayer lifecycle
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingChanged: Boolean) {
                isPlaying = isPlayingChanged
            }
            // Listen for playback position changes
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val playerDuration = normalizeDuration(exoPlayer.duration)
                    duration = if (playerDuration > 0L) playerDuration else metadataDuration
                } else if (playbackState == Player.STATE_ENDED) {
                    currentPosition = 0L
                    exoPlayer.seekTo(0L)
                    exoPlayer.pause()
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }
    // LaunchedEffect keeps the current position updated
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            if (!isSeeking) {
                currentPosition = exoPlayer.currentPosition
            }
            delay(100)
        }
    }
    // Playback controls and progress slider
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = {
                if (isPlaying) {
                    exoPlayer.pause()
                } else {
                    exoPlayer.play()
                }
            }) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            // Playback progress slider
            val interactionSource = remember { MutableInteractionSource() }
            Slider(
                value = if (duration > 0) {
                    (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }, onValueChange = { progress ->
                    isSeeking = true
                    if (duration > 0) {
                        currentPosition = (progress * duration).toLong()
                    }
                }, onValueChangeFinished = {
                    isSeeking = false
                    exoPlayer.seekTo(currentPosition)
                }, modifier = Modifier.weight(1f), interactionSource = interactionSource, thumb = {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary, shape = CircleShape
                            )
                    )
                }, track = { sliderState ->
                    SliderDefaults.Track(
                        colors = SliderDefaults.colors(
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
                        ),
                        sliderState = sliderState,
                        drawStopIndicator = null,
                        thumbTrackGapSize = 0.dp,
                        modifier = Modifier.height(3.dp)
                    )
                })
            // Show the current position and total duration
            Text(
                text = formatTime(currentPosition) + " / " + formatTime(duration),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/**
 * Formats a time as a string (minutes:seconds)
 *
 * @param ms time in milliseconds
 * @return formatted time string
 */
private fun formatTime(ms: Long): String {
    val safeMs = normalizeDuration(ms)
    if (safeMs <= 0L) return "00:00"
    val totalSeconds = safeMs / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/**
 * Normalizes a time in milliseconds (invalid values become 0L)
 *
 * @param ms time in milliseconds
 * @return normalized time in milliseconds
 */
private fun normalizeDuration(ms: Long): Long {
    if (ms == C.TIME_UNSET || ms <= 0L) return 0L
    return ms
}

/**
 * Gets the total duration of an audio file (milliseconds)
 *
 * @param filePath audio file path
 * @return total duration in milliseconds
 */
private fun getAudioDurationFromMetadata(filePath: String): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(filePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?.coerceAtLeast(0L) ?: 0L
    } catch (_: Exception) {
        0L
    } finally {
        runCatching { retriever.release() }
    }
}

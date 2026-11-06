package com.ltx.audiopeel.ui.components

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ltx.audiopeel.ExtractionState
import com.ltx.audiopeel.OutputFormat
import com.ltx.audiopeel.R
import com.ltx.audiopeel.VideoMetadata
import java.io.File

/**
 * Video selection card
 *
 * @param isSelected whether a video is selected
 * @param videoMetadata video metadata
 * @param onSelectClick select-video button callback
 * @param modifier modifier
 * @param onThumbnailClick thumbnail click callback
 */
@Composable
fun VideoSelectorCard(
    isSelected: Boolean,
    videoMetadata: VideoMetadata?,
    onSelectClick: () -> Unit,
    modifier: Modifier = Modifier,
    onThumbnailClick: (Bitmap) -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isSelected && videoMetadata?.thumbnail != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VideoThumbnail(
                        bitmap = videoMetadata.thumbnail,
                        onClick = { onThumbnailClick(videoMetadata.thumbnail) })
                    Spacer(modifier = Modifier.width(12.dp))
                    VideoInfoRow(
                        metadata = videoMetadata, modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.VideoFile,
                    contentDescription = stringResource(R.string.video_content_description),
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.select_video_prompt),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onSelectClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (!isSelected) stringResource(R.string.select_video) else stringResource(R.string.change_video))
            }
        }
    }
}

/**
 * Video thumbnail
 *
 * @param bitmap video thumbnail
 * @param onClick click callback
 */
@Composable
private fun VideoThumbnail(
    bitmap: Bitmap,
    onClick: () -> Unit,
) {
    val ratio = if (bitmap.height > 0) {
        bitmap.width.toFloat() / bitmap.height.toFloat()
    } else {
        16f / 9f
    }
    val contentDescription = stringResource(R.string.video_thumbnail)
    val interactionSource = remember { MutableInteractionSource() }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        modifier = Modifier
            .height(100.dp)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = interactionSource, indication = null, onClick = onClick
            ),
        contentScale = ContentScale.Fit
    )
}

/**
 * Fullscreen thumbnail preview overlay
 *
 * @param bitmap video thumbnail
 * @param onDismiss dismiss callback
 */
@Composable
fun FullscreenThumbnailOverlay(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
) {
    // Intercept the system back button
    BackHandler(enabled = true, onBack = onDismiss)
    // Hide the system bars
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller: WindowInsetsControllerCompat? = window?.let {
            WindowCompat.getInsetsController(it, view)
        }
        controller?.apply {
            // Let the user swipe to reveal the system bars temporarily
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = interactionSource, indication = null, onClick = onDismiss
            ), contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.video_thumbnail),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}

/**
 * Video info row
 *
 * @param metadata video metadata
 * @param modifier modifier
 */
@Composable
private fun VideoInfoRow(
    metadata: VideoMetadata,
    modifier: Modifier = Modifier,
) {
    val infoParts = buildList {
        if (metadata.durationMs > 0) {
            add(stringResource(R.string.duration_label, formatDuration(metadata.durationMs)))
        }
        if (!metadata.audioCodec.isNullOrEmpty()) {
            add(stringResource(R.string.audio_codec_label, metadata.audioCodec.uppercase()))
        }
        if (metadata.fileSize > 0) {
            add(stringResource(R.string.file_size_label, formatFileSize(metadata.fileSize)))
        }
    }
    if (infoParts.isNotEmpty()) {
        Column(modifier = modifier) {
            infoParts.forEach { part ->
                Text(
                    text = part,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Formats a duration
 *
 * @param ms milliseconds
 */
private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
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
 * Formats a file size
 *
 * @param bytes size in bytes
 */
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.2f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.2f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}

/**
 * Format selection and extraction controls
 *
 * @param visible whether the section is visible
 * @param selectedFormat currently selected format
 * @param extractionState extraction state
 * @param onFormatSelect format selection callback
 * @param onExtractClick extract callback
 * @param onCancelClick cancel callback
 * @param modifier modifier
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatSelectorSection(
    visible: Boolean,
    selectedFormat: OutputFormat,
    extractionState: ExtractionState,
    onFormatSelect: (OutputFormat) -> Unit,
    onExtractClick: () -> Unit,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.output_format),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            val formats = OutputFormat.entries
            Row(
                modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                formats.forEach { format ->
                    FilterChip(
                        selected = selectedFormat == format,
                        onClick = { onFormatSelect(format) },
                        label = { Text(format.name) })
                }
            }
            // Extract audio button
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onExtractClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = extractionState !is ExtractionState.Processing
            ) {
                if (extractionState is ExtractionState.Processing) {
                    val progressFloat = extractionState.progress
                    val progressPercent = (progressFloat * 100).toInt()
                    CircularProgressIndicator(
                        progress = { progressFloat },
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(stringResource(R.string.extracting_progress, progressPercent))
                } else {
                    Text(
                        stringResource(R.string.start_extraction),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            // Cancel extraction button
            AnimatedVisibility(
                visible = extractionState is ExtractionState.Processing,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                OutlinedButton(
                    onClick = onCancelClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.cancel_extraction)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.cancel_extraction))
                }
            }
        }
    }
}

/**
 * Extraction result section
 *
 * @param extractionState extraction state
 * @param onShareClick share callback
 * @param onSaveClick save callback
 * @param onOpenFolderClick open-folder callback
 * @param modifier modifier
 */
@Composable
fun ExtractionResultSection(
    extractionState: ExtractionState,
    onShareClick: (File, String) -> Unit,
    onSaveClick: (File, String) -> Unit,
    onOpenFolderClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = extractionState is ExtractionState.Success || extractionState is ExtractionState.Error,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        when (extractionState) {
            is ExtractionState.Success -> {
                ExtractionSuccessCard(
                    outPath = extractionState.outPath,
                    onShareClick = onShareClick,
                    onSaveClick = onSaveClick,
                    onOpenFolderClick = onOpenFolderClick
                )
            }

            is ExtractionState.Error -> {
                val errorMessage = when {
                    extractionState.messageResId != null -> stringResource(extractionState.messageResId)
                    else -> extractionState.message ?: ""
                }
                ExtractionFailedCard(message = errorMessage)
            }

            else -> {}
        }
    }
}

/**
 * Extraction success card
 *
 * @param outPath output path
 * @param onShareClick share callback
 * @param onSaveClick save callback
 * @param onOpenFolderClick open-folder callback
 * @param modifier modifier
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractionSuccessCard(
    outPath: String,
    onShareClick: (File, String) -> Unit,
    onSaveClick: (File, String) -> Unit,
    onOpenFolderClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var customFileName by remember(outPath) {
        mutableStateOf(File(outPath).nameWithoutExtension)
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = stringResource(R.string.success),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(R.string.extraction_success), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            val isNameValid = customFileName.trim().isNotEmpty()
            // Custom file name input
            OutlinedTextField(
                value = customFileName,
                onValueChange = { customFileName = it },
                label = { Text(stringResource(R.string.filename_no_extension)) },
                isError = !isNameValid,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
            if (!isNameValid) {
                Text(
                    text = stringResource(R.string.filename_cannot_be_empty),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, top = 2.dp),
                    textAlign = TextAlign.Start
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            // Audio player
            AudioPlayerView(outPath)
            Spacer(modifier = Modifier.height(12.dp))
            // Share and save buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        onShareClick(File(outPath), customFileName.trim())
                    }, enabled = isNameValid, modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.share))
                }
                OutlinedButton(
                    onClick = {
                        onSaveClick(File(outPath), customFileName.trim())
                    }, enabled = isNameValid, modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.SaveAlt,
                        contentDescription = stringResource(R.string.save_file)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.save_file))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(
                onClick = onOpenFolderClick, contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = stringResource(R.string.open_folder),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.open_audio_folder))
            }
        }
    }
}

/**
 * Extraction failure card
 *
 * @param message error message
 * @param modifier modifier
 */
@Composable
fun ExtractionFailedCard(
    message: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = stringResource(R.string.error),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.extraction_failed),
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
            Text(
                message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error
            )
        }
    }
}

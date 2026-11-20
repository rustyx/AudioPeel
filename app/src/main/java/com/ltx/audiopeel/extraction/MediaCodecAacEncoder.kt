package com.ltx.audiopeel.extraction

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import java.io.File
import java.io.InputStream

/**
 * Encodes HE-AAC with Android's software AAC encoder
 *
 * Only Google's own encoder is used, which is based on Fraunhofer's and
 * present on every device; vendor encoders vary too much.
 */
class MediaCodecAacEncoder : AacEncoder {
    override fun canEncodeHeAac(sampleRate: Int, channels: Int): Boolean =
        findEncoder(sampleRate, channels) != null

    override fun encodeHeAac(
        pcm: File,
        skipSamples: Long,
        config: AacEncoderConfig,
        isCancelled: () -> Boolean,
    ): AacEncodeResult {
        val name = findEncoder(config.sampleRate, config.channels)
            ?: return AacEncodeResult.Failed("no HE-AAC encoder for $config")
        val codec = try {
            MediaCodec.createByCodecName(name)
        } catch (e: Exception) {
            return AacEncodeResult.Failed("$name: $e")
        }
        return try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, config.sampleRate, config.channels
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectHE)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_CHUNK_BYTES)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            pcm.inputStream().buffered().use { input ->
                val bytesPerSample = 2L * config.channels
                if (!input.skipFully(skipSamples * bytesPerSample)) {
                    return AacEncodeResult.Failed("PCM shorter than $skipSamples samples")
                }
                encode(codec, input, config, isCancelled)
            }
        } catch (e: Exception) {
            // MediaCodec reports errors as IllegalStateException and CodecException
            AacEncodeResult.Failed("$name: $e")
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun encode(
        codec: MediaCodec,
        input: InputStream,
        config: AacEncoderConfig,
        isCancelled: () -> Boolean,
    ): AacEncodeResult {
        val bytesPerSample = 2 * config.channels
        val chunk = ByteArray(INPUT_CHUNK_BYTES / bytesPerSample * bytesPerSample)
        val frames = mutableListOf<ByteArray>()
        var audioConfig: ByteArray? = null
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var samplesQueued = 0L
        while (true) {
            if (isCancelled()) return AacEncodeResult.Cancelled
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index) ?: return AacEncodeResult.Failed("no input buffer")
                    val wanted = minOf(buffer.capacity(), chunk.size) / bytesPerSample * bytesPerSample
                    val read = input.readUpTo(chunk, wanted)
                    val timeUs = samplesQueued * 1_000_000 / config.sampleRate
                    if (read == 0) {
                        codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        buffer.clear()
                        buffer.put(chunk, 0, read)
                        codec.queueInputBuffer(index, 0, read, timeUs, 0)
                        samplesQueued += read / bytesPerSample
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (index >= 0) {
                val buffer = codec.getOutputBuffer(index) ?: return AacEncodeResult.Failed("no output buffer")
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(bytes)
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    audioConfig = bytes
                } else if (bytes.isNotEmpty()) {
                    frames += bytes
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    return AacEncodeResult.Success(audioConfig, frames)
                }
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && audioConfig == null) {
                audioConfig = codec.outputFormat.getByteBuffer(CSD_0)?.let { csd ->
                    ByteArray(csd.remaining()).also { csd.get(it) }
                }
            }
        }
    }

    private fun findEncoder(sampleRate: Int, channels: Int): String? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder &&
                GOOGLE_ENCODERS.any { info.name.startsWith(it) } &&
                info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true) } &&
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC).let { capabilities ->
                    capabilities.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AACObjectHE } &&
                        capabilities.audioCapabilities?.let { audio ->
                            audio.isSampleRateSupported(sampleRate) && audio.maxInputChannelCount >= channels
                        } ?: false
                }
        }?.name

    private companion object {
        val GOOGLE_ENCODERS = listOf("c2.android.aac.encoder", "OMX.google.aac.encoder")
        const val INPUT_CHUNK_BYTES = 16 * 1024
        const val TIMEOUT_US = 10_000L
        const val CSD_0 = "csd-0"
    }
}

/** Skips exactly [count] bytes; false if the stream ends first */
private fun InputStream.skipFully(count: Long): Boolean {
    var remaining = count
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else if (read() < 0) {
            return false
        } else {
            remaining--
        }
    }
    return true
}

/** Reads until [length] bytes or the end of the stream; returns the bytes read */
private fun InputStream.readUpTo(buffer: ByteArray, length: Int): Int {
    var total = 0
    while (total < length) {
        val read = read(buffer, total, length - total)
        if (read < 0) break
        total += read
    }
    return total
}

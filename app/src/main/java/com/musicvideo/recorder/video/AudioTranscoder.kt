package com.musicvideo.recorder.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/**
 * Decodes a user-supplied audio file (mp3, m4a, wav, etc.) and re-encodes it to AAC,
 * looping the source if it's shorter than the target duration, or trimming it if longer.
 * Encoded AAC frames are buffered in memory (a few MB for a typical song) and muxed
 * into the final video by [VideoStitcher].
 */
object AudioTranscoder {

    data class EncodedSample(val data: ByteArray, val presentationTimeUs: Long, val flags: Int)

    private const val TIMEOUT_US = 10_000L
    // Safety valve: if nothing makes progress for this many consecutive polls (~30s at the
    // 10ms/loop timeout below), something is stuck — fail loudly instead of hanging forever.
    private const val MAX_STALLED_ITERATIONS = 3000

    fun buildAacTrack(context: Context, uri: Uri, targetDurationUs: Long): Pair<MediaFormat, List<EncodedSample>> {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: throw IllegalStateException("Could not open the selected audio file (it may have been moved or deleted)")
        } catch (e: Exception) {
            extractor.release()
            throw IllegalStateException("Could not read the selected audio file: ${e.javaClass.simpleName}${e.message?.let { " - $it" } ?: ""}", e)
        }

        var audioTrackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                inputFormat = format
                break
            }
        }
        if (audioTrackIndex < 0 || inputFormat == null) {
            extractor.release()
            throw IllegalStateException("The selected file doesn't contain a readable audio track")
        }
        extractor.selectTrack(audioTrackIndex)

        val inMime = inputFormat.getString(MediaFormat.KEY_MIME)
            ?: run { extractor.release(); throw IllegalStateException("Audio track is missing a MIME type") }
        val sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

        // Bytes per microsecond of 16-bit PCM at this sample rate/channel count, used to convert
        // a byte offset within a decoded chunk back into a presentation-time offset.
        val bytesPerSecond = (sampleRate.toLong() * channelCount.toLong() * 2L).coerceAtLeast(1L)
        fun bytesToUs(byteOffset: Int): Long = (byteOffset.toLong() * 1_000_000L) / bytesPerSecond

        val decoder = try {
            MediaCodec.createDecoderByType(inMime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }
        } catch (e: Exception) {
            extractor.release()
            throw IllegalStateException("Failed to start audio decoder for $inMime: ${e.javaClass.simpleName}${e.message?.let { " - $it" } ?: ""}", e)
        }

        val encoderFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
        }
        val encoder = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (e: Exception) {
            decoder.stop(); decoder.release()
            extractor.release()
            throw IllegalStateException(
                "Failed to start AAC encoder (sampleRate=$sampleRate, channels=$channelCount): " +
                    "${e.javaClass.simpleName}${e.message?.let { " - $it" } ?: ""}", e
            )
        }

        val samples = mutableListOf<EncodedSample>()
        var outputFormat: MediaFormat? = null
        val decInfo = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()

        var extractorDone = false        // no more compressed data to feed the decoder
        var decoderOutputDone = false    // decoder has emitted its last output chunk
        var encoderDone = false          // encoder has emitted its EOS output buffer
        var loopOffsetUs = 0L
        var lastDecodedPtsUs = 0L
        var stalledIterations = 0

        // Decoded PCM waiting to be pushed into the encoder. Since one decoder output chunk
        // can be bigger than a single encoder input buffer, we drain it across as many
        // encoder input buffers as needed instead of assuming it fits in one (that mismatch
        // is what causes BufferOverflowException).
        var pendingPcm: ByteArray? = null
        var pendingOffset = 0
        var pendingBasePtsUs = 0L
        var pendingEosToEncoder = false
        var eosSentToEncoder = false

        try {
            while (!encoderDone) {
                var madeProgress = false

                // 1) Feed compressed audio into the decoder, looping the source if needed.
                if (!extractorDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        madeProgress = true
                        val inBuf = decoder.getInputBuffer(inIndex)
                            ?: throw IllegalStateException("Decoder returned a null input buffer")
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            if (lastDecodedPtsUs + loopOffsetUs < targetDurationUs) {
                                loopOffsetUs = lastDecodedPtsUs + loopOffsetUs
                                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                                val loopedSize = extractor.readSampleData(inBuf, 0)
                                if (loopedSize < 0) {
                                    decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    extractorDone = true
                                } else {
                                    decoder.queueInputBuffer(inIndex, 0, loopedSize, extractor.sampleTime + loopOffsetUs, 0)
                                    extractor.advance()
                                }
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                extractorDone = true
                            }
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime + loopOffsetUs, 0)
                            extractor.advance()
                        }
                    }
                }

                // 2) Pull a decoded PCM chunk out of the decoder (only once the previous chunk
                //    has been fully handed off to the encoder).
                if (!decoderOutputDone && pendingPcm == null) {
                    val outIndex = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                    if (outIndex >= 0) {
                        madeProgress = true
                        val eosFromDecoder = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val reachedTarget = decInfo.presentationTimeUs >= targetDurationUs

                        if (decInfo.size > 0 && !reachedTarget) {
                            val outBuf = decoder.getOutputBuffer(outIndex)
                                ?: throw IllegalStateException("Decoder returned a null output buffer")
                            val data = ByteArray(decInfo.size)
                            outBuf.position(decInfo.offset)
                            outBuf.limit(decInfo.offset + decInfo.size)
                            outBuf.get(data)
                            pendingPcm = data
                            pendingOffset = 0
                            pendingBasePtsUs = decInfo.presentationTimeUs
                            lastDecodedPtsUs = decInfo.presentationTimeUs
                        }
                        decoder.releaseOutputBuffer(outIndex, false)

                        if (eosFromDecoder || reachedTarget) {
                            decoderOutputDone = true
                            pendingEosToEncoder = true
                        }
                    }
                }

                // 3) Drain the pending PCM chunk into the encoder, one encoder-sized bite at a time.
                val currentPending = pendingPcm
                if (currentPending != null) {
                    val encInIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (encInIndex >= 0) {
                        madeProgress = true
                        val encInBuf = encoder.getInputBuffer(encInIndex)
                            ?: throw IllegalStateException("Encoder returned a null input buffer")
                        encInBuf.clear()
                        val remaining = currentPending.size - pendingOffset
                        val chunkSize = minOf(remaining, encInBuf.remaining())
                        encInBuf.put(currentPending, pendingOffset, chunkSize)
                        val chunkPtsUs = pendingBasePtsUs + bytesToUs(pendingOffset)
                        encoder.queueInputBuffer(encInIndex, 0, chunkSize, chunkPtsUs, 0)
                        pendingOffset += chunkSize
                        if (pendingOffset >= currentPending.size) {
                            pendingPcm = null
                            pendingOffset = 0
                        }
                    }
                } else if (pendingEosToEncoder && !eosSentToEncoder) {
                    // Everything decoded has been handed to the encoder — now signal end-of-stream.
                    val encInIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (encInIndex >= 0) {
                        madeProgress = true
                        encoder.queueInputBuffer(encInIndex, 0, 0, lastDecodedPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosSentToEncoder = true
                    }
                }

                // 4) Drain encoded AAC samples.
                val encOutIndex = encoder.dequeueOutputBuffer(encInfo, TIMEOUT_US)
                if (encOutIndex >= 0) {
                    madeProgress = true
                    if (encInfo.size > 0) {
                        val encOutBuf = encoder.getOutputBuffer(encOutIndex)
                            ?: throw IllegalStateException("Encoder returned a null output buffer")
                        val data = ByteArray(encInfo.size)
                        encOutBuf.position(encInfo.offset)
                        encOutBuf.limit(encInfo.offset + encInfo.size)
                        encOutBuf.get(data)
                        samples.add(EncodedSample(data, encInfo.presentationTimeUs, encInfo.flags))
                    }
                    encoder.releaseOutputBuffer(encOutIndex, false)
                    if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderDone = true
                    }
                } else if (encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    madeProgress = true
                    outputFormat = encoder.outputFormat
                }

                if (madeProgress) {
                    stalledIterations = 0
                } else {
                    stalledIterations++
                    if (stalledIterations > MAX_STALLED_ITERATIONS) {
                        throw IllegalStateException(
                            "Audio transcoding stalled with no codec progress " +
                                "(decoderOutputDone=$decoderOutputDone, eosSentToEncoder=$eosSentToEncoder, " +
                                "encoderDone=$encoderDone, samples=${samples.size})"
                        )
                    }
                }
            }
        } finally {
            try { decoder.stop() } catch (_: Exception) {}
            try { decoder.release() } catch (_: Exception) {}
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            extractor.release()
        }

        return Pair(outputFormat ?: encoderFormat, samples)
    }
}

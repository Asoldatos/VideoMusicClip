package com.musicvideo.recorder.video

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Thrown by any stitching stage. Always carries a human-readable [stage] and the original
 * [cause] (even when the underlying platform exception has no message of its own), so the
 * UI never has to fall back to a bare "unknown error".
 */
class StitchException(val stage: String, cause: Throwable) :
    Exception("$stage: ${cause.javaClass.simpleName}${cause.message?.let { " - $it" } ?: " (no message)"}", cause)

/**
 * Stitches together the individually-recorded video clips (each recorded video-only via
 * CameraX VideoCapture, since the audio track comes from the chosen music file instead of
 * the microphone) into one continuous MP4, then muxes in the audio track re-encoded to AAC
 * (looped or trimmed to match the video's total length).
 *
 * Pure Android SDK (MediaExtractor / MediaCodec / MediaMuxer) — no third-party native
 * libraries (no FFmpeg) required, so this compiles cleanly under a stock GitHub Actions runner.
 */
object VideoStitcher {

    private const val TAG = "VideoStitcher"

    // 4K keyframes from CameraX's default high-bitrate UHD profile can spike well past 8MB;
    // size generously so readSampleData() never silently truncates a frame.
    private const val SAMPLE_BUFFER_BYTES = 32 * 1024 * 1024

    fun stitch(context: Context, clipFiles: List<File>, audioUri: Uri, outputDisplayName: String): Uri {
        require(clipFiles.isNotEmpty()) { "No clips were recorded" }

        val workDir = File(context.cacheDir, "stitch_work").apply { mkdirs() }
        val concatFile = File(workDir, "concat_${System.currentTimeMillis()}.mp4")
        val finalFile = File(workDir, outputDisplayName)

        try {
            runStage("Combining clips") { concatenateVideoClips(clipFiles, concatFile) }

            val videoDurationUs = runStage("Reading combined video length") { readDurationUs(concatFile) }
            if (videoDurationUs <= 0L) {
                throw IllegalStateException(
                    "Combined video reported a duration of 0 — one of the recorded clips may be corrupt or empty"
                )
            }

            val (audioFormat, audioSamples) = runStage("Decoding/encoding audio track") {
                AudioTranscoder.buildAacTrack(context, audioUri, videoDurationUs)
            }
            if (audioSamples.isEmpty()) {
                throw IllegalStateException("Audio track produced no encoded samples")
            }

            runStage("Muxing final video") { muxVideoAndAudio(concatFile, audioFormat, audioSamples, finalFile) }

            return runStage("Saving to Movies") { saveToMediaStore(context, finalFile, outputDisplayName) }
        } finally {
            concatFile.delete()
        }
    }

    private inline fun <T> runStage(stage: String, block: () -> T): T {
        return try {
            block()
        } catch (e: StitchException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Stage failed: $stage", e)
            throw StitchException(stage, e)
        }
    }

    // ---------- Step 1: concatenate video-only clips ----------

    private fun concatenateVideoClips(clips: List<File>, output: File) {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrackOut = -1
        var muxerStarted = false
        var ptsOffsetUs = 0L
        val buffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
        val info = MediaCodec.BufferInfo()
        var writtenAnySamples = false

        try {
            for (clip in clips) {
                if (!clip.exists() || clip.length() == 0L) {
                    Log.w(TAG, "Skipping missing/empty clip: ${clip.absolutePath}")
                    continue
                }

                val extractor = MediaExtractor()
                extractor.setDataSource(clip.absolutePath)

                var trackIn = -1
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                        trackIn = i
                        if (!muxerStarted) {
                            videoTrackOut = muxer.addTrack(format)
                            muxer.start()
                            muxerStarted = true
                        }
                        break
                    }
                }
                if (trackIn < 0) {
                    Log.w(TAG, "No video track in clip: ${clip.absolutePath}")
                    extractor.release()
                    continue
                }
                extractor.selectTrack(trackIn)

                var maxPtsThisClip = 0L
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = extractor.sampleTime + ptsOffsetUs
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(videoTrackOut, buffer, info)
                    writtenAnySamples = true
                    maxPtsThisClip = maxOf(maxPtsThisClip, extractor.sampleTime)
                    extractor.advance()
                }
                // Advance the timeline by this clip's duration (+ ~1 frame at 30fps) so the
                // next clip starts right after it instead of overlapping.
                ptsOffsetUs += maxPtsThisClip + 33_333L
                extractor.release()
            }
        } finally {
            if (muxerStarted) {
                muxer.stop()
            }
            muxer.release()
        }

        if (!muxerStarted || !writtenAnySamples) {
            throw IllegalStateException("None of the recorded clips contained readable video data")
        }
    }

    // ---------- Step 2: read total duration ----------

    private fun readDurationUs(file: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var durationUs = 0L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
                }
            }
            return durationUs
        } finally {
            extractor.release()
        }
    }

    // ---------- Step 3: mux final video + AAC audio ----------

    private fun muxVideoAndAudio(
        videoFile: File,
        audioFormat: MediaFormat,
        audioSamples: List<AudioTranscoder.EncodedSample>,
        output: File
    ) {
        val videoExtractor = MediaExtractor()
        videoExtractor.setDataSource(videoFile.absolutePath)
        var videoTrackIn = -1
        var videoFormat: MediaFormat? = null
        for (i in 0 until videoExtractor.trackCount) {
            val format = videoExtractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                videoTrackIn = i
                videoFormat = format
                break
            }
        }
        if (videoTrackIn < 0 || videoFormat == null) {
            videoExtractor.release()
            throw IllegalStateException("Concatenated video has no video track")
        }
        videoExtractor.selectTrack(videoTrackIn)

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val videoTrackOut = muxer.addTrack(videoFormat)
            val audioTrackOut = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = videoExtractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = videoExtractor.sampleTime
                info.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(videoTrackOut, buffer, info)
                videoExtractor.advance()
            }
            videoExtractor.release()

            for (sample in audioSamples) {
                val audioBuffer = ByteBuffer.wrap(sample.data)
                info.offset = 0
                info.size = sample.data.size
                info.presentationTimeUs = sample.presentationTimeUs
                // Strip EOS flag; MediaMuxer only wants sync/key-frame flags on written samples.
                info.flags = sample.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                muxer.writeSampleData(audioTrackOut, audioBuffer, info)
            }

            muxer.stop()
        } finally {
            muxer.release()
        }
    }

    // ---------- Step 4: publish to the user's Movies collection ----------

    private fun saveToMediaStore(context: Context, file: File, displayName: String): Uri {
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MusicVideoRecorder")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri = resolver.insert(collection, values)
                ?: throw IllegalStateException("Failed to create MediaStore entry")

            try {
                resolver.openOutputStream(itemUri)?.use { out ->
                    FileInputStream(file).use { input -> input.copyTo(out) }
                } ?: throw IllegalStateException("Failed to open output stream for MediaStore entry")

                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(itemUri, null, null)
                throw e
            }
            file.delete()
            return itemUri
        } else {
            @Suppress("DEPRECATION")
            val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "MusicVideoRecorder")
            moviesDir.mkdirs()
            val destFile = File(moviesDir, displayName)
            FileOutputStream(destFile).use { out ->
                FileInputStream(file).use { input -> input.copyTo(out) }
            }
            file.delete()
            return Uri.fromFile(destFile)
        }
    }
}

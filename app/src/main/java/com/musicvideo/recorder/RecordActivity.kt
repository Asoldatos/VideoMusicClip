package com.musicvideo.recorder

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.musicvideo.recorder.databinding.ActivityRecordBinding
import com.musicvideo.recorder.util.Prefs
import com.musicvideo.recorder.video.VideoStitcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RecordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordBinding
    private lateinit var cameraExecutor: ExecutorService

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var mediaPlayer: MediaPlayer? = null
    private var audioUri: Uri? = null

    private val clipFiles = mutableListOf<File>()
    private var isRecording = false
    private var isProcessing = false

    private val uiScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemBars()

        cameraExecutor = Executors.newSingleThreadExecutor()
        audioUri = Prefs.audioUri(this)?.let { Uri.parse(it) }

        setupMediaPlayer()
        startCamera()
        updateClipsLabel()

        binding.recordButton.setOnClickListener {
            if (isProcessing) return@setOnClickListener
            if (isRecording) stopClip() else startClip()
        }

        binding.endSessionButton.setOnClickListener {
            if (!isRecording && clipFiles.isNotEmpty() && !isProcessing) {
                confirmAndFinish()
            }
        }
    }

    // ---------- UI ----------

    private fun hideSystemBars() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    private fun updateClipsLabel() {
        binding.clipsCountText.text = getString(R.string.clips_recorded, clipFiles.size)
        binding.endSessionButton.isEnabled = clipFiles.isNotEmpty() && !isRecording && !isProcessing
    }

    // ---------- Audio ----------

    private fun setupMediaPlayer() {
        val uri = audioUri ?: return
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@RecordActivity, uri)
                isLooping = false
                prepare()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare audio", e)
            Toast.makeText(this, "Could not load audio file", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Camera ----------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val width = Prefs.width(this)
            val quality = when {
                width >= 3840 -> Quality.UHD
                width >= 1920 -> Quality.FHD
                else -> Quality.HD
            }
            val qualitySelector = QualitySelector.from(
                quality,
                FallbackStrategy.lowerQualityOrHigherThan(quality)
            )

            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .setExecutor(cameraExecutor)
                .build()
            val capture = VideoCapture.withOutput(recorder)

            val selector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(this, selector, preview, capture)
                applyStabilizationAndFrameRate(camera)
                videoCapture = capture
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                Toast.makeText(this, "Failed to start camera", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun applyStabilizationAndFrameRate(camera: androidx.camera.core.Camera) {
        try {
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                )
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(30, 30)
                )
                .build()
            Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(options)
        } catch (e: Exception) {
            Log.w(TAG, "Stabilization/FPS interop not supported on this device", e)
        }
    }

    // ---------- Clip recording ----------

    @SuppressLint("MissingPermission")
    private fun startClip() {
        val capture = videoCapture ?: return
        val clipsDir = File(getExternalFilesDir(null), "clips").apply { mkdirs() }
        val name = "clip_${SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(java.util.Date())}.mp4"
        val clipFile = File(clipsDir, name)
        val outputOptions = FileOutputOptions.Builder(clipFile).build()

        activeRecording = capture.output.prepareRecording(this, outputOptions)
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        binding.recordButton.setImageResource(R.drawable.ic_stop)
                        resumeAudio()
                        updateClipsLabel()
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!event.hasError()) {
                            clipFiles.add(clipFile)
                        } else {
                            Log.e(TAG, "Clip recording error: ${event.error}")
                            Toast.makeText(this, "Clip failed to save", Toast.LENGTH_SHORT).show()
                        }
                        isRecording = false
                        binding.recordButton.setImageResource(R.drawable.ic_record)
                        pauseAudio()
                        updateClipsLabel()
                    }
                    else -> Unit
                }
            }
    }

    private fun stopClip() {
        activeRecording?.stop()
        activeRecording = null
    }

    private fun resumeAudio() {
        try {
            mediaPlayer?.let { if (!it.isPlaying) it.start() }
        } catch (e: Exception) {
            Log.w(TAG, "Audio resume failed", e)
        }
    }

    private fun pauseAudio() {
        try {
            mediaPlayer?.let { if (it.isPlaying) it.pause() }
        } catch (e: Exception) {
            Log.w(TAG, "Audio pause failed", e)
        }
    }

    // ---------- Finish & stitch ----------

    private fun confirmAndFinish() {
        AlertDialog.Builder(this)
            .setTitle("Finish video?")
            .setMessage("This will stitch your ${clipFiles.size} clip(s) together with the audio track.")
            .setPositiveButton("Export") { _, _ -> runStitching() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runStitching() {
        val uri = audioUri
        if (uri == null) {
            Toast.makeText(this, "No audio file selected", Toast.LENGTH_LONG).show()
            return
        }
        isProcessing = true
        binding.recordButton.isEnabled = false
        binding.endSessionButton.isEnabled = false
        binding.processingSpinner.visibility = View.VISIBLE
        binding.processingLabel.visibility = View.VISIBLE

        uiScope.launch {
            try {
                val outputUri = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    VideoStitcher.stitch(
                        context = this@RecordActivity,
                        clipFiles = clipFiles.toList(),
                        audioUri = uri,
                        outputDisplayName = "music_video_${System.currentTimeMillis()}.mp4"
                    )
                }
                onStitchSuccess(outputUri)
            } catch (e: Exception) {
                Log.e(TAG, "Stitching failed", e)
                onStitchFailure(e)
            }
        }
    }

    private fun onStitchSuccess(outputUri: Uri) {
        isProcessing = false
        binding.processingSpinner.visibility = View.GONE
        binding.processingLabel.visibility = View.GONE
        AlertDialog.Builder(this)
            .setTitle("Video exported!")
            .setMessage("Your music video was saved to your device's Movies folder.")
            .setPositiveButton("Done") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun onStitchFailure(e: Exception) {
        isProcessing = false
        binding.processingSpinner.visibility = View.GONE
        binding.processingLabel.visibility = View.GONE
        binding.recordButton.isEnabled = true
        updateClipsLabel()

        val details = buildDiagnosticText(e)
        Log.e(TAG, "Export failed:\n$details")

        AlertDialog.Builder(this)
            .setTitle("Export failed")
            .setMessage(summarizeError(e) + "\n\nTap \"Copy details\" to copy the full error so it can be shared.")
            .setPositiveButton("Copy details") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Export error", details))
                Toast.makeText(this, "Error details copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Short, human-readable summary shown directly in the dialog body. */
    private fun summarizeError(e: Throwable): String {
        val msg = e.message
        return if (!msg.isNullOrBlank()) msg else "${e.javaClass.simpleName} with no additional detail."
    }

    /** Full exception chain + stack trace, for the "Copy details" button. */
    private fun buildDiagnosticText(e: Throwable): String {
        val sw = java.io.StringWriter()
        e.printStackTrace(java.io.PrintWriter(sw))
        return sw.toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "RecordActivity"
    }
}

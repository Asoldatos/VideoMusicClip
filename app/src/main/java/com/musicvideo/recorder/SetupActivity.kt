package com.musicvideo.recorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.musicvideo.recorder.databinding.ActivitySetupBinding
import com.musicvideo.recorder.util.Prefs

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private var selectedAudioUri: Uri? = null

    data class ResolutionOption(val label: String, val width: Int, val height: Int)

    private val resolutions = listOf(
        ResolutionOption("4K UHD  3840x2160  30fps", 3840, 2160),
        ResolutionOption("Full HD  1920x1080  30fps", 1920, 1080),
        ResolutionOption("HD  1280x720  30fps", 1280, 720),
    )

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    private val pickAudioLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // Some providers don't support persistable permissions; ignore.
                }
                selectedAudioUri = uri
                binding.audioFileLabel.text = queryDisplayName(uri)
                updateStartEnabled()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.resolutionSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, resolutions.map { it.label }
        )

        requestNeededPermissions()

        binding.pickAudioButton.setOnClickListener {
            pickAudioLauncher.launch(arrayOf("audio/*"))
        }

        binding.startButton.setOnClickListener {
            val chosen = resolutions[binding.resolutionSpinner.selectedItemPosition]
            val audioUri = selectedAudioUri ?: return@setOnClickListener
            Prefs.save(this, chosen.width, chosen.height, chosen.label, audioUri.toString())
            startActivity(Intent(this, RecordActivity::class.java))
        }
    }

    private fun updateStartEnabled() {
        binding.startButton.isEnabled = selectedAudioUri != null
    }

    private fun queryDisplayName(uri: Uri): String {
        var name = "Selected audio file"
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && it.moveToFirst()) {
                name = it.getString(idx)
            }
        }
        return name
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.READ_MEDIA_AUDIO)
            needed.add(Manifest.permission.READ_MEDIA_VIDEO)
        }
        val toRequest = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(toRequest.toTypedArray())
        }
    }
}

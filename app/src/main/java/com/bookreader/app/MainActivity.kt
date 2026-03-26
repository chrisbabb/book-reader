package com.bookreader.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bookreader.app.UserPreferences
import com.bookreader.app.databinding.ActivityMainBinding
import com.bookreader.app.ui.MainViewModel
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        when {
            cameraGranted && audioGranted -> initializeApp()
            !cameraGranted -> showPermissionError("Camera permission is required to scan book pages.")
            !audioGranted -> showPermissionError("Microphone permission is required for voice commands.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
        checkAndRequestPermissions()
        setupObservers()
    }

    private fun checkAndRequestPermissions() {
        val required = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val allGranted = required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) initializeApp() else permissionLauncher.launch(required)
    }

    private fun initializeApp() {
        viewModel.initialize(this, binding.cameraPreview)
    }

    private fun setupObservers() {
        viewModel.statusText.observe(this) { text ->
            binding.statusText.text = text
        }

        viewModel.pageNumber.observe(this) { page ->
            binding.pageNumberText.text = if (page > 0) "Page $page" else "Page --"
        }

        viewModel.isReading.observe(this) { reading ->
            binding.pauseResumeButton.isEnabled = reading
            binding.stopButton.isEnabled = reading
        }

        viewModel.canCapture.observe(this) { canCapture ->
            binding.captureButton.isEnabled = canCapture
        }

        viewModel.isPaused.observe(this) { paused ->
            binding.pauseResumeButton.text = if (paused) getString(R.string.resume) else getString(R.string.pause)
        }

        viewModel.isListening.observe(this) { listening ->
            binding.listeningIndicator.visibility = if (listening) View.VISIBLE else View.GONE
            binding.micButton.backgroundTintList = androidx.core.content.res.ResourcesCompat.getColorStateList(
                resources,
                if (listening) R.color.accent else R.color.primary,
                theme
            )
        }

        viewModel.aiStatus.observe(this) { status ->
            binding.aiStatusText.text = status
            binding.voiceButton.visibility =
                if (viewModel.apiKeyManager.hasOpenAIKey) View.VISIBLE else View.GONE
        }
    }

    private fun showVoicePicker() {
        val voices = UserPreferences.VOICES
        val labels = voices.map { it.second }.toTypedArray()
        val currentVoice = viewModel.userPreferences.ttsVoice
        val checkedIndex = voices.indexOfFirst { it.first == currentVoice }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Select Reading Voice")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val selected = voices[which].first
                viewModel.userPreferences.ttsVoice = selected
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupClickListeners() {
        binding.captureButton.setOnClickListener {
            viewModel.captureAndReadPage()
        }

        binding.pauseResumeButton.setOnClickListener {
            viewModel.togglePauseResume()
        }

        binding.stopButton.setOnClickListener {
            viewModel.stopReading()
        }

        binding.voiceButton.setOnClickListener {
            showVoicePicker()
        }

        binding.micButton.setOnClickListener {
            viewModel.toggleVoiceSession()
        }
    }

    private fun showPermissionError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        binding.statusText.text = message
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.cleanup()
    }
}

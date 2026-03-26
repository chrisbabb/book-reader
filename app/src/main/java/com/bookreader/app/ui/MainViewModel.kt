package com.bookreader.app.ui

import android.app.Application
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.LifecycleOwner
import com.bookreader.app.camera.CameraManager
import com.bookreader.app.ocr.OCRProcessor
import com.bookreader.app.state.ReadingState
import com.bookreader.app.state.ReadingStateManager
import com.bookreader.app.state.ReadingStatus
import com.bookreader.app.tts.TextToSpeechManager
import com.bookreader.app.voice.VoiceCommand
import com.bookreader.app.voice.VoiceCommandManager
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // --- LiveData for UI ---
    private val _statusText = MutableLiveData("Ready. Tap Capture Page or say 'capture' to scan a page.")
    val statusText: LiveData<String> = _statusText

    private val _pageNumber = MutableLiveData(0)
    val pageNumber: LiveData<Int> = _pageNumber

    private val _isReading = MutableLiveData(false)
    val isReading: LiveData<Boolean> = _isReading

    private val _isPaused = MutableLiveData(false)
    val isPaused: LiveData<Boolean> = _isPaused

    private val _isListening = MutableLiveData(false)
    val isListening: LiveData<Boolean> = _isListening

    private val _canCapture = MutableLiveData(true)
    val canCapture: LiveData<Boolean> = _canCapture

    // --- Core components ---
    private val cameraManager = CameraManager(application)
    private val ocrProcessor = OCRProcessor()
    private val stateManager = ReadingStateManager(application)

    private var ttsManager: TextToSpeechManager? = null
    private var voiceManager: VoiceCommandManager? = null

    private var currentState = ReadingState(pageNumber = stateManager.pageNumber)
    private var isInitialized = false

    fun initialize(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (isInitialized) return
        isInitialized = true

        _pageNumber.value = stateManager.pageNumber

        // Initialize TTS
        ttsManager = TextToSpeechManager(
            context = getApplication(),
            onSentenceStarted = { index, _ ->
                stateManager.lastSentenceIndex = index
                currentState = currentState.copy(currentSentenceIndex = index)
                voiceManager?.setTtsSpeaking(true)
            },
            onSentenceDone = { _ ->
                // sentence index is managed by TTS internally
            },
            onPageDone = {
                handlePageDone()
            },
            onReady = {
                // Start camera after TTS is ready
                viewModelScope.launch {
                    try {
                        cameraManager.startCamera(lifecycleOwner, previewView)
                        setupVoiceCommands()
                        ttsManager?.announce(
                            "Book Reader is ready. Point the camera at a book page and tap Capture Page, or say capture."
                        )
                        _statusText.postValue("Ready. Point camera at a page and tap Capture Page.")
                    } catch (e: Exception) {
                        _statusText.postValue("Camera error: ${e.message}")
                        Log.e(TAG, "Camera init error", e)
                    }
                }
            }
        )

        _pageNumber.value = stateManager.pageNumber
    }

    private fun setupVoiceCommands() {
        voiceManager = VoiceCommandManager(
            context = getApplication(),
            onCommandRecognized = { command -> handleVoiceCommand(command) },
            onListeningStateChanged = { listening -> _isListening.postValue(listening) }
        )
        voiceManager?.start()
    }

    fun captureAndReadPage() {
        if (_isReading.value == true) return
        viewModelScope.launch {
            try {
                updateStatus(ReadingStatus.CAPTURING, "Capturing page…")
                _canCapture.postValue(false)

                val bitmap = cameraManager.capturePhoto()

                updateStatus(ReadingStatus.PROCESSING, "Processing image, please wait…")

                val text = ocrProcessor.extractText(bitmap)

                if (text.isBlank()) {
                    updateStatus(ReadingStatus.IDLE, "No text detected. Please reposition the camera and try again.")
                    ttsManager?.announce("No text was detected. Please reposition the camera and try again.")
                    _canCapture.postValue(true)
                    return@launch
                }

                val pageNum = stateManager.pageNumber
                _pageNumber.postValue(pageNum)

                currentState = currentState.copy(
                    pageNumber = pageNum,
                    fullText = text,
                    status = ReadingStatus.READING,
                    currentSentenceIndex = 0
                )

                updateStatus(ReadingStatus.READING, "Reading page $pageNum…")
                _isReading.postValue(true)
                _isPaused.postValue(false)

                ttsManager?.loadText(text)

                // Announce page number, then start reading
                ttsManager?.announce("Page $pageNum.") {
                    ttsManager?.startReading(0)
                }

                Log.d(TAG, "Started reading page $pageNum, ${text.length} chars")

            } catch (e: Exception) {
                Log.e(TAG, "Capture/read error", e)
                updateStatus(ReadingStatus.IDLE, "Error: ${e.message}")
                ttsManager?.announce("There was an error processing the image. Please try again.")
                _canCapture.postValue(true)
                _isReading.postValue(false)
            }
        }
    }

    fun togglePauseResume() {
        val reading = _isReading.value == true
        val paused = _isPaused.value == true
        if (reading && !paused) {
            pauseReading()
        } else if (reading && paused) {
            resumeReading()
        }
    }

    private fun pauseReading() {
        ttsManager?.pause()
        _isPaused.postValue(true)
        updateStatus(ReadingStatus.PAUSED, "Paused. Say 'resume' or tap Resume to continue.")
    }

    private fun resumeReading() {
        ttsManager?.resume()
        _isPaused.postValue(false)
        updateStatus(ReadingStatus.READING, "Reading page ${currentState.pageNumber}…")
    }

    fun stopReading() {
        ttsManager?.stop()
        _isReading.postValue(false)
        _isPaused.postValue(false)
        _canCapture.postValue(true)
        updateStatus(ReadingStatus.IDLE, "Stopped. Tap Capture Page to read a new page.")
    }

    private fun handlePageDone() {
        val pageNum = currentState.pageNumber
        stateManager.pagesInCurrentSpread++
        val pagesInSpread = stateManager.pagesInCurrentSpread

        _isReading.postValue(false)
        _isPaused.postValue(false)
        _canCapture.postValue(true)

        // Increment for next page
        stateManager.incrementPage()
        _pageNumber.postValue(stateManager.pageNumber)

        if (pagesInSpread >= 2) {
            // Both pages of the open spread have been read
            stateManager.resetSpread()
            updateStatus(ReadingStatus.WAITING_FOR_CONTINUE,
                "Both pages read. Please turn the page, then say 'capture' or tap Capture Page.")
            ttsManager?.announce(
                "Both pages of this spread are done. Please turn the page. " +
                "When you're ready, say capture or tap the Capture Page button."
            )
        } else {
            // One page done, the other side is still visible
            updateStatus(ReadingStatus.WAITING_FOR_CONTINUE,
                "Page $pageNum done. Say 'continue' or tap Capture Page for the next page.")
            ttsManager?.announce(
                "Page $pageNum is done. When you're ready for the next page, say continue or tap Capture Page."
            )
        }
    }

    fun handleVoiceCommand(command: VoiceCommand) {
        Log.d(TAG, "Voice command: $command")
        when (command) {
            is VoiceCommand.Capture -> {
                if (_canCapture.value == true) captureAndReadPage()
            }
            is VoiceCommand.Pause -> {
                if (_isReading.value == true && _isPaused.value == false) pauseReading()
            }
            is VoiceCommand.Resume -> {
                if (_isReading.value == true && _isPaused.value == true) resumeReading()
            }
            is VoiceCommand.Stop -> stopReading()
            is VoiceCommand.Continue -> {
                if (_canCapture.value == true) captureAndReadPage()
            }
            is VoiceCommand.PreviousSentence -> {
                if (_isReading.value == true || _isPaused.value == true) {
                    _isPaused.postValue(false)
                    _isReading.postValue(true)
                    ttsManager?.readPreviousSentence()
                }
            }
            is VoiceCommand.LastNWords -> {
                ttsManager?.readLastNWords(command.count)
            }
            is VoiceCommand.ReadParagraph -> {
                ttsManager?.readCurrentParagraph()
            }
            is VoiceCommand.ReadPage -> {
                _isReading.postValue(true)
                _isPaused.postValue(false)
                ttsManager?.readCurrentPage()
                updateStatus(ReadingStatus.READING, "Re-reading page ${currentState.pageNumber}…")
            }
        }
    }

    private fun updateStatus(status: ReadingStatus, message: String) {
        currentState = currentState.copy(status = status)
        _statusText.postValue(message)
    }

    fun cleanup() {
        voiceManager?.stop()
        ttsManager?.release()
        ocrProcessor.release()
        cameraManager.shutdown()
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}

package com.bookreader.app.ui

import android.app.Application
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.bookreader.app.UserPreferences
import com.bookreader.app.ai.ApiKeyManager
import com.bookreader.app.ai.ClaudeTextProcessor
import com.bookreader.app.ai.NeuralTtsService
import com.bookreader.app.camera.CameraManager
import com.bookreader.app.camera.PageDetectionState
import com.bookreader.app.ocr.OCRProcessor
import com.bookreader.app.state.ReadingState
import com.bookreader.app.state.ReadingStateManager
import com.bookreader.app.state.ReadingStatus
import com.bookreader.app.voice.VoiceCommand
import com.bookreader.app.voice.VoiceCommandManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    private val _aiStatus = MutableLiveData("")

    private val _pageDetectionState = MutableLiveData(PageDetectionState.SEARCHING)
    val pageDetectionState: LiveData<PageDetectionState> = _pageDetectionState

    private val _pageDetectionCorners = MutableLiveData<FloatArray?>(null)
    val pageDetectionCorners: LiveData<FloatArray?> = _pageDetectionCorners

    val aiStatus: LiveData<String> = _aiStatus

    // --- Core components ---
    private val cameraManager = CameraManager(application)
    private val ocrProcessor = OCRProcessor()
    private val stateManager = ReadingStateManager(application)

    // AI components
    val apiKeyManager = ApiKeyManager(application)
    val userPreferences = UserPreferences(application)
    private val claudeProcessor = ClaudeTextProcessor(apiKeyManager)

    // NeuralTtsService wraps both OpenAI TTS and Android TTS fallback
    private var ttsService: NeuralTtsService? = null
    private var voiceManager: VoiceCommandManager? = null

    private var currentState = ReadingState(pageNumber = stateManager.pageNumber)
    private var isInitialized = false

    // Page detection / auto-capture
    private var autoCaptureJob: Job? = null
    private var lastGuidanceMs = 0L
    private val GUIDANCE_INTERVAL_MS = 5_000L

    fun initialize(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (isInitialized) return
        isInitialized = true

        _pageNumber.value = stateManager.pageNumber

        ttsService = NeuralTtsService(
            context = getApplication(),
            apiKeyManager = apiKeyManager,
            voiceProvider = { userPreferences.ttsVoice },
            onSentenceStarted = { index, _ ->
                stateManager.lastSentenceIndex = index
                currentState = currentState.copy(currentSentenceIndex = index)
                voiceManager?.setTtsSpeaking(true)
            },
            onSentenceDone = { _ ->
                voiceManager?.setTtsSpeaking(false)
            },
            onPageDone = {
                handlePageDone()
            },
            onNeuralTtsFallback = {
                Log.w(TAG, "OpenAI TTS unavailable — falling back to device voice")
                _aiStatus.postValue("OpenAI TTS unavailable — using device voice")
            },
            onReady = {
                viewModelScope.launch {
                    try {
                        cameraManager.startCamera(lifecycleOwner, previewView) { state, rect ->
                            onPageDetectionUpdate(state, rect)
                        }
                        setupVoiceCommands()

                        val voiceIndicator = buildString {
                            if (apiKeyManager.hasClaudeKey) append("Claude OCR ")
                            if (apiKeyManager.hasOpenAIKey) append("+ OpenAI TTS")
                            if (isEmpty()) append("On-device mode")
                        }
                        _aiStatus.postValue(voiceIndicator)

                        ttsService?.announce(
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

    /** Call after returning from SettingsActivity to refresh AI status label. */
    fun refreshAiStatus() {
        val label = buildString {
            if (apiKeyManager.hasClaudeKey) append("Claude OCR ")
            if (apiKeyManager.hasOpenAIKey) append("+ OpenAI TTS")
            if (isEmpty()) append("On-device mode")
        }
        _aiStatus.postValue(label)
    }

    private fun setupVoiceCommands() {
        voiceManager = VoiceCommandManager(
            context = getApplication(),
            onCommandRecognized = { command -> handleVoiceCommand(command) },
            onListeningModeChanged = { mode ->
                _isListening.postValue(mode == VoiceCommandManager.ListeningMode.LISTENING)
            }
        )
        // Mic starts OFF — only activates when user taps the mic button
    }

    /** Called by the mic button. Starts one listening session; tapping again cancels it. */
    fun toggleVoiceSession() {
        voiceManager?.startSession()
    }

    private fun onPageDetectionUpdate(state: PageDetectionState, corners: FloatArray?) {
        _pageDetectionState.postValue(state)
        _pageDetectionCorners.postValue(corners)
        // Don't give guidance while the app is busy reading or capturing
        if (_isReading.value == true || _canCapture.value == false) return

        when (state) {
            PageDetectionState.ALIGNED -> {
                if (autoCaptureJob == null || !autoCaptureJob!!.isActive) {
                    autoCaptureJob = viewModelScope.launch {
                        ttsService?.announce("Page aligned. Capturing in 2 seconds.")
                        delay(2_000)
                        if (_pageDetectionState.value == PageDetectionState.ALIGNED &&
                            _canCapture.value == true &&
                            _isReading.value == false
                        ) {
                            captureAndReadPage()
                        }
                    }
                }
            }
            else -> {
                autoCaptureJob?.cancel()
                autoCaptureJob = null
                val now = System.currentTimeMillis()
                if (now - lastGuidanceMs > GUIDANCE_INTERVAL_MS) {
                    lastGuidanceMs = now
                    val msg = if (state == PageDetectionState.PARTIAL)
                        "Move closer or adjust angle to show the full page."
                    else
                        "Point the camera at a book page."
                    ttsService?.announce(msg)
                }
            }
        }
    }

    fun captureAndReadPage() {
        if (_isReading.value == true) return
        autoCaptureJob?.cancel()
        autoCaptureJob = null
        viewModelScope.launch {
            try {
                cameraManager.setAnalysisEnabled(false)
                updateStatus(ReadingStatus.CAPTURING, "Capturing page…")
                _canCapture.postValue(false)

                val bitmap = cameraManager.capturePhoto()

                // Step 1: on-device ML Kit OCR
                updateStatus(ReadingStatus.PROCESSING, "Reading text from image…")
                val rawText = ocrProcessor.extractText(bitmap)

                if (rawText.isBlank()) {
                    updateStatus(ReadingStatus.IDLE, "No text detected. Please reposition the camera and try again.")
                    ttsService?.announce("No text was detected. Please reposition the camera and try again.")
                    _canCapture.postValue(true)
                    return@launch
                }

                // Step 2: Claude cleanup (if key available)
                val (cleanText, detectedPage) = if (apiKeyManager.hasClaudeKey) {
                    updateStatus(ReadingStatus.PROCESSING, "AI is cleaning up the text…")
                    val result = claudeProcessor.process(rawText)
                    Pair(result.cleanText, result.detectedPageNumber)
                } else {
                    Pair(rawText, null)
                }

                // Use Claude's detected page number if available, else use persisted counter
                val pageNum = detectedPage ?: stateManager.pageNumber
                if (detectedPage != null && detectedPage != stateManager.pageNumber) {
                    // Sync counter to what Claude detected
                    stateManager.pageNumber = detectedPage
                }
                _pageNumber.postValue(pageNum)

                currentState = currentState.copy(
                    pageNumber = pageNum,
                    fullText = cleanText,
                    status = ReadingStatus.READING,
                    currentSentenceIndex = 0
                )

                updateStatus(ReadingStatus.READING, "Reading page $pageNum…")
                _isReading.postValue(true)
                _isPaused.postValue(false)

                ttsService?.loadText(cleanText)

                // Announce page number, then start reading
                ttsService?.announce("Page $pageNum.") {
                    ttsService?.startReading(0)
                }

                Log.d(TAG, "Page $pageNum: ${cleanText.length} chars, AI=${apiKeyManager.hasClaudeKey}")

            } catch (e: Exception) {
                Log.e(TAG, "Capture/read error", e)
                updateStatus(ReadingStatus.IDLE, "Error: ${e.message}")
                ttsService?.announce("There was an error processing the image. Please try again.")
                _canCapture.postValue(true)
                _isReading.postValue(false)
                cameraManager.setAnalysisEnabled(true)
            }
        }
    }

    fun togglePauseResume() {
        val reading = _isReading.value == true
        val paused = _isPaused.value == true
        if (reading && !paused) pauseReading()
        else if (reading && paused) resumeReading()
    }

    private fun pauseReading() {
        ttsService?.pause()
        _isPaused.postValue(true)
        updateStatus(ReadingStatus.PAUSED, "Paused. Say 'resume' or tap Resume to continue.")
    }

    private fun resumeReading() {
        ttsService?.resume()
        _isPaused.postValue(false)
        updateStatus(ReadingStatus.READING, "Reading page ${currentState.pageNumber}…")
    }

    fun stopReading() {
        ttsService?.stop()
        _isReading.postValue(false)
        _isPaused.postValue(false)
        _canCapture.postValue(true)
        updateStatus(ReadingStatus.IDLE, "Stopped. Tap Capture Page to read a new page.")
        cameraManager.setAnalysisEnabled(true)
    }

    private fun handlePageDone() {
        val pageNum = currentState.pageNumber
        stateManager.pagesInCurrentSpread++
        val pagesInSpread = stateManager.pagesInCurrentSpread

        _isReading.postValue(false)
        _isPaused.postValue(false)
        _canCapture.postValue(true)
        cameraManager.setAnalysisEnabled(true)

        stateManager.incrementPage()
        _pageNumber.postValue(stateManager.pageNumber)

        if (pagesInSpread >= 2) {
            stateManager.resetSpread()
            updateStatus(ReadingStatus.WAITING_FOR_CONTINUE,
                "Both pages read. Please turn the page, then say 'capture' or tap Capture Page.")
            ttsService?.announce(
                "Both pages of this spread are done. Please turn the page. " +
                "When you're ready, say capture or tap the Capture Page button."
            )
        } else {
            updateStatus(ReadingStatus.WAITING_FOR_CONTINUE,
                "Page $pageNum done. Say 'continue' or tap Capture Page for the next page.")
            ttsService?.announce(
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
                    ttsService?.readPreviousSentence()
                }
            }
            is VoiceCommand.LastNWords -> {
                ttsService?.readLastNWords(command.count)
            }
            is VoiceCommand.ReadParagraph -> {
                ttsService?.readCurrentParagraph()
            }
            is VoiceCommand.ReadPage -> {
                _isReading.postValue(true)
                _isPaused.postValue(false)
                ttsService?.readCurrentPage()
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
        ttsService?.release()
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

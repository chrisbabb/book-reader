package com.bookreader.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Single-shot voice command listener.
 *
 * The microphone is NEVER active in the background. Listening only starts when
 * [startSession] is called explicitly (e.g. from a mic button tap). After one
 * command is recognized — or after a timeout — the session ends automatically
 * and the mic turns off.
 */
class VoiceCommandManager(
    private val context: Context,
    private val onCommandRecognized: (VoiceCommand) -> Unit,
    private val onListeningModeChanged: (mode: ListeningMode) -> Unit = {}
) {
    enum class ListeningMode { IDLE, LISTENING }

    private var speechRecognizer: SpeechRecognizer? = null
    private var sessionActive = false

    private val handler = Handler(Looper.getMainLooper())

    // ── Public API ────────────────────────────────────────────────────────────

    /** Start a single listening session. Mic turns on, waits for one command, then stops. */
    fun startSession() {
        if (sessionActive) {
            // Already listening — cancel current session
            endSession()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available on this device")
            return
        }
        sessionActive = true
        setMode(ListeningMode.LISTENING)
        createAndListen()
    }

    /** Cancel any active session and turn the mic off. */
    fun stop() {
        endSession()
    }

    /** No-op kept for call-site compatibility; mic is never on between sessions. */
    fun setTtsSpeaking(speaking: Boolean) {
        if (speaking && sessionActive) endSession()
    }

    val isListening: Boolean get() = sessionActive

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun endSession() {
        sessionActive = false
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        setMode(ListeningMode.IDLE)
    }

    private fun createAndListen() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                Log.d(TAG, "Recognition error: $error")
                endSession()
            }

            override fun onResults(results: Bundle?) {
                val matches = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?: emptyList()
                Log.d(TAG, "Results: $matches")

                val spoken = matches.firstOrNull().orEmpty()
                val command = VoiceCommand.parse(spoken)
                if (command != null) {
                    Log.d(TAG, "Command: $command from '$spoken'")
                    onCommandRecognized(command)
                } else {
                    Log.d(TAG, "No command matched for '$spoken'")
                }
                endSession()
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening: ${e.message}")
            endSession()
        }
    }

    private fun setMode(mode: ListeningMode) {
        onListeningModeChanged(mode)
    }

    companion object {
        private const val TAG = "VoiceCommandManager"
    }
}

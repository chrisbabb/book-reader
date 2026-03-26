package com.bookreader.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class VoiceCommandManager(
    private val context: Context,
    private val onCommandRecognized: (VoiceCommand) -> Unit,
    private val onListeningStateChanged: (isListening: Boolean) -> Unit = {}
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var shouldContinueListening = true
    private var isTtsSpeaking = false // suppress commands while TTS speaks

    fun start() {
        shouldContinueListening = true
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available on this device")
            return
        }
        createRecognizer()
        beginListening()
    }

    fun stop() {
        shouldContinueListening = false
        isListening = false
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        onListeningStateChanged(false)
    }

    /** Call when TTS starts speaking to reduce false positives. */
    fun setTtsSpeaking(speaking: Boolean) {
        isTtsSpeaking = speaking
        if (speaking) {
            speechRecognizer?.stopListening()
            isListening = false
        } else if (shouldContinueListening) {
            // Brief delay so TTS audio doesn't immediately re-trigger recognition
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (shouldContinueListening && !isListening) beginListening()
            }, 800)
        }
    }

    private fun createRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                onListeningStateChanged(true)
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                onListeningStateChanged(false)
                Log.d(TAG, "Speech recognition error: $error")
                // Restart unless it's a fatal error
                if (shouldContinueListening && error != SpeechRecognizer.ERROR_CLIENT) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (shouldContinueListening && !isTtsSpeaking) {
                            beginListening()
                        }
                    }, 1000)
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                onListeningStateChanged(false)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val spoken = matches[0]
                    Log.d(TAG, "Recognized: $spoken")
                    val command = VoiceCommand.parse(spoken)
                    if (command != null) {
                        onCommandRecognized(command)
                    }
                }
                // Always restart listening
                if (shouldContinueListening && !isTtsSpeaking) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (shouldContinueListening) beginListening()
                    }, 300)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // Could use partial results for faster response but skip for now
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun beginListening() {
        if (isListening || speechRecognizer == null) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            // Keep listening even in noisy environments
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 200L)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "VoiceCommandManager"
    }
}

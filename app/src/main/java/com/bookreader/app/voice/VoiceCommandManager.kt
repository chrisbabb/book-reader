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
 * Two-phase voice listener:
 *  Phase 1 — AWAITING_WAKE_WORD: always-on, waits for "Hey Reader"
 *  Phase 2 — AWAITING_COMMAND:   listens for one command, then returns to Phase 1
 *
 * Suppresses the microphone while TTS is speaking to avoid feedback.
 */
class VoiceCommandManager(
    private val context: Context,
    private val onCommandRecognized: (VoiceCommand) -> Unit,
    private val onListeningModeChanged: (mode: ListeningMode) -> Unit = {}
) {
    enum class ListeningMode { IDLE, AWAITING_WAKE_WORD, AWAITING_COMMAND }

    private var speechRecognizer: SpeechRecognizer? = null
    private var currentPhase = ListeningMode.IDLE
    private var shouldRun = false
    private var isTtsSpeaking = false

    private val handler = Handler(Looper.getMainLooper())

    // ── Public API ────────────────────────────────────────────────────────────

    fun start() {
        shouldRun = true
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available on this device")
            return
        }
        createRecognizer()
        enterWakeWordPhase()
    }

    fun stop() {
        shouldRun = false
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        setMode(ListeningMode.IDLE)
    }

    /** Call when TTS starts/stops to suppress false command triggers. */
    fun setTtsSpeaking(speaking: Boolean) {
        isTtsSpeaking = speaking
        if (speaking) {
            speechRecognizer?.stopListening()
            setMode(ListeningMode.IDLE)
        } else if (shouldRun) {
            handler.postDelayed({
                if (shouldRun && !isTtsSpeaking) enterWakeWordPhase()
            }, 800)
        }
    }

    // ── Phase transitions ─────────────────────────────────────────────────────

    private fun enterWakeWordPhase() {
        if (!shouldRun || isTtsSpeaking) return
        setMode(ListeningMode.AWAITING_WAKE_WORD)
        beginListening(wakeWordMode = true)
    }

    private fun enterCommandPhase() {
        if (!shouldRun) return
        setMode(ListeningMode.AWAITING_COMMAND)
        beginListening(wakeWordMode = false)
    }

    // ── SpeechRecognizer ──────────────────────────────────────────────────────

    private fun createRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                Log.d(TAG, "Recognition error $error in phase $currentPhase")
                if (!shouldRun || error == SpeechRecognizer.ERROR_CLIENT) return
                val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 2000L else 1200L
                handler.postDelayed({
                    if (!shouldRun || isTtsSpeaking) return@postDelayed
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_SERVER) {
                        createRecognizer()
                    }
                    enterWakeWordPhase()
                }, delay)
            }

            override fun onResults(results: Bundle?) {
                val matches = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?: return
                Log.d(TAG, "Results (phase=$currentPhase): $matches")

                when (currentPhase) {
                    ListeningMode.AWAITING_WAKE_WORD -> {
                        if (matches.any { containsWakeWord(it) }) {
                            Log.d(TAG, "Wake word detected — entering command phase")
                            handler.postDelayed({ enterCommandPhase() }, 150)
                        } else {
                            handler.postDelayed({
                                if (shouldRun && !isTtsSpeaking) enterWakeWordPhase()
                            }, 300)
                        }
                    }
                    ListeningMode.AWAITING_COMMAND -> {
                        val spoken = matches.firstOrNull().orEmpty()
                        val command = VoiceCommand.parse(spoken)
                        if (command != null) {
                            Log.d(TAG, "Command '$command' from '$spoken'")
                            onCommandRecognized(command)
                        } else {
                            Log.d(TAG, "No command matched for '$spoken'")
                        }
                        // Always return to wake-word phase after one attempt
                        handler.postDelayed({
                            if (shouldRun && !isTtsSpeaking) enterWakeWordPhase()
                        }, 400)
                    }
                    else -> {}
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun beginListening(wakeWordMode: Boolean) {
        if (speechRecognizer == null) createRecognizer()
        val silenceMs = if (wakeWordMode) 2500L else 1500L
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, if (wakeWordMode) 5 else 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs - 500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening: ${e.message}")
        }
    }

    private fun setMode(mode: ListeningMode) {
        currentPhase = mode
        onListeningModeChanged(mode)
    }

    // ── Wake word matching ────────────────────────────────────────────────────

    private fun containsWakeWord(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.contains("hey reader") ||
            lower.contains("hey, reader") ||
            lower.contains("hey read her") ||
            lower.contains("a reader") ||       // common misrecognition
            lower.contains("hey reed")
    }

    companion object {
        private const val TAG = "VoiceCommandManager"
    }
}

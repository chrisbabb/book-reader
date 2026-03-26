package com.bookreader.app.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class TextToSpeechManager(
    context: Context,
    private val onSentenceStarted: (sentenceIndex: Int, sentence: String) -> Unit = { _, _ -> },
    private val onSentenceDone: (sentenceIndex: Int) -> Unit = {},
    private val onPageDone: () -> Unit = {},
    private val onReady: () -> Unit = {}
) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private var sentences: List<String> = emptyList()
    private var paragraphs: List<String> = emptyList()
    private var currentSentenceIndex = 0
    private var isPaused = false
    private var isStopped = false

    // Track all words spoken so far for "last N words" command
    private val spokenWords = mutableListOf<String>()

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setSpeechRate(0.9f)
                tts?.setPitch(1.0f)
                isInitialized = true
                setupProgressListener()
                onReady()
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
            }
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val idx = utteranceId?.removePrefix(SENTENCE_PREFIX)?.toIntOrNull() ?: return
                if (idx >= 0) {
                    onSentenceStarted(idx, sentences.getOrElse(idx) { "" })
                }
            }

            override fun onDone(utteranceId: String?) {
                val idx = utteranceId?.removePrefix(SENTENCE_PREFIX)?.toIntOrNull() ?: return
                if (idx >= 0 && idx < sentences.size) {
                    onSentenceDone(idx)
                    if (!isPaused && !isStopped) {
                        val next = idx + 1
                        if (next < sentences.size) {
                            currentSentenceIndex = next
                            speakSentence(next)
                        } else {
                            onPageDone()
                        }
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error for utterance: $utteranceId")
            }
        })
    }

    fun loadText(text: String) {
        sentences = splitIntoSentences(text)
        paragraphs = text.split(Regex("\n\n+")).filter { it.isNotBlank() }
        currentSentenceIndex = 0
        spokenWords.clear()
    }

    fun startReading(fromSentenceIndex: Int = 0) {
        if (!isInitialized) return
        isPaused = false
        isStopped = false
        currentSentenceIndex = fromSentenceIndex.coerceIn(0, maxOf(0, sentences.size - 1))
        speakSentence(currentSentenceIndex)
    }

    fun pause() {
        isPaused = true
        tts?.stop()
    }

    fun resume() {
        if (!isPaused) return
        isPaused = false
        isStopped = false
        speakSentence(currentSentenceIndex)
    }

    fun stop() {
        isStopped = true
        isPaused = false
        tts?.stop()
        currentSentenceIndex = 0
    }

    fun readPreviousSentence() {
        val target = maxOf(0, currentSentenceIndex - 1)
        currentSentenceIndex = target
        isPaused = false
        isStopped = false
        speakSentence(target)
    }

    fun readLastNWords(n: Int) {
        val allSpoken = sentences.take(currentSentenceIndex + 1).joinToString(" ")
        val words = allSpoken.split(Regex("\\s+")).filter { it.isNotBlank() }
        val lastN = words.takeLast(n).joinToString(" ")
        speakInterrupt(lastN)
    }

    fun readCurrentParagraph() {
        // Find which paragraph the current sentence belongs to
        var charCount = 0
        val sentencesUpToCurrent = sentences.take(currentSentenceIndex + 1).joinToString(" ")
        val paragraphIndex = paragraphs.indexOfFirst { para ->
            val normalized = para.replace(Regex("\\s+"), " ").trim()
            sentencesUpToCurrent.contains(normalized.take(30))
        }.takeIf { it >= 0 } ?: 0

        val paraText = paragraphs.getOrElse(paragraphIndex) { sentences.getOrElse(currentSentenceIndex) { "" } }
        speakInterrupt(paraText)
    }

    fun readCurrentPage() {
        currentSentenceIndex = 0
        isPaused = false
        isStopped = false
        speakSentence(0)
    }

    /** Speak an announcement without affecting reading position. */
    fun announce(text: String, onDone: (() -> Unit)? = null) {
        if (!isInitialized) return
        tts?.stop()
        val params = Bundle()
        val id = "announce_${System.currentTimeMillis()}"
        if (onDone != null) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id) {
                        setupProgressListener() // restore normal listener
                        onDone()
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { setupProgressListener() }
            })
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
    }

    fun getCurrentSentenceIndex() = currentSentenceIndex
    fun getSentenceCount() = sentences.size
    fun isPaused() = isPaused
    fun isStopped() = isStopped

    private fun speakSentence(index: Int) {
        if (!isInitialized || index >= sentences.size) return
        val sentence = sentences[index]
        spokenWords.addAll(sentence.split(Regex("\\s+")).filter { it.isNotBlank() })
        val params = Bundle()
        tts?.speak(sentence, TextToSpeech.QUEUE_FLUSH, params, "$SENTENCE_PREFIX$index")
    }

    private fun speakInterrupt(text: String) {
        if (!isInitialized) return
        val wasPaused = isPaused
        isPaused = true // temporarily pause advancement
        val params = Bundle()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "interrupt_${System.currentTimeMillis()}")
        // After interrupt, remain paused so user can choose to resume
        isPaused = wasPaused
    }

    private fun splitIntoSentences(text: String): List<String> {
        // Split on sentence-ending punctuation followed by whitespace or end of string
        val raw = text.replace(Regex("\\n+"), " ")
        val parts = raw.split(Regex("(?<=[.!?])\\s+"))
        return parts
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 1 }
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val TAG = "TextToSpeechManager"
        private const val SENTENCE_PREFIX = "sentence_"
    }
}

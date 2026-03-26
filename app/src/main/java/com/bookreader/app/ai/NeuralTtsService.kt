package com.bookreader.app.ai

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.bookreader.app.tts.TextToSpeechManager
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TTS engine that uses OpenAI's tts-1-hd model (Nova voice) for natural-sounding
 * speech when an OpenAI API key is present.
 *
 * Automatically falls back to Android's built-in TextToSpeechManager when:
 *  - No OpenAI API key is configured
 *  - A network/API error occurs for a particular sentence
 *
 * Announcements (page number, prompts to continue) always use Android TTS for
 * zero-latency feedback, regardless of which engine is reading.
 *
 * Pre-fetches audio for the next 2 sentences while the current one plays, so
 * the gap between sentences is typically imperceptible.
 */
class NeuralTtsService(
    private val context: Context,
    private val apiKeyManager: ApiKeyManager,
    private val onSentenceStarted: (sentenceIndex: Int, sentence: String) -> Unit = { _, _ -> },
    private val onSentenceDone: (sentenceIndex: Int) -> Unit = {},
    private val onPageDone: () -> Unit = {},
    private val onReady: () -> Unit = {}
) {
    // Android TTS — always available; used for announcements + fallback reading
    private var androidTts: TextToSpeechManager? = null

    // Reading state
    private var sentences: List<String> = emptyList()
    private var paragraphs: List<String> = emptyList()
    var currentSentenceIndex = 0
        private set
    private val paused = AtomicBoolean(false)
    private val stopped = AtomicBoolean(true)

    // Neural TTS playback
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var readingJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null

    // Pre-fetch cache: sentence index → MP3 bytes (null = fetch failed)
    private val audioCache = mutableMapOf<Int, ByteArray?>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    init {
        androidTts = TextToSpeechManager(
            context = context,
            onSentenceStarted = onSentenceStarted,
            onSentenceDone = onSentenceDone,
            onPageDone = onPageDone,
            onReady = onReady
        )
    }

    // ── Public interface ──────────────────────────────────────────────────────

    fun loadText(text: String) {
        sentences = splitSentences(text)
        paragraphs = text.split(Regex("\n\n+")).filter { it.isNotBlank() }
        currentSentenceIndex = 0
        audioCache.clear()

        if (!apiKeyManager.hasOpenAIKey) {
            androidTts?.loadText(text)
        }
    }

    fun startReading(fromSentenceIndex: Int = 0) {
        if (!apiKeyManager.hasOpenAIKey) {
            androidTts?.startReading(fromSentenceIndex)
            return
        }
        paused.set(false)
        stopped.set(false)
        currentSentenceIndex = fromSentenceIndex.coerceIn(0, maxOf(0, sentences.size - 1))
        launchReadingFrom(currentSentenceIndex)
    }

    fun pause() {
        if (!apiKeyManager.hasOpenAIKey) { androidTts?.pause(); return }
        paused.set(true)
        readingJob?.cancel()
        mediaPlayer?.pause()
    }

    fun resume() {
        if (!apiKeyManager.hasOpenAIKey) { androidTts?.resume(); return }
        if (!paused.get()) return
        paused.set(false)
        stopped.set(false)
        launchReadingFrom(currentSentenceIndex)
    }

    fun stop() {
        if (!apiKeyManager.hasOpenAIKey) { androidTts?.stop(); return }
        stopped.set(true)
        paused.set(false)
        readingJob?.cancel()
        releaseMediaPlayer()
        audioCache.clear()
    }

    fun readPreviousSentence() {
        if (!apiKeyManager.hasOpenAIKey) { androidTts?.readPreviousSentence(); return }
        readingJob?.cancel()
        releaseMediaPlayer()
        currentSentenceIndex = maxOf(0, currentSentenceIndex - 1)
        paused.set(false)
        stopped.set(false)
        launchReadingFrom(currentSentenceIndex)
    }

    fun readLastNWords(n: Int) {
        if (!apiKeyManager.hasOpenAIKey) { androidTts?.readLastNWords(n); return }
        val allSpoken = sentences.take(currentSentenceIndex + 1).joinToString(" ")
        val lastN = allSpoken.split(Regex("\\s+")).filter { it.isNotBlank() }.takeLast(n).joinToString(" ")
        playInterrupt(lastN)
    }

    fun readCurrentParagraph() {
        if (!apiKeyManager.hasOpenAIKey) { androidTts?.readCurrentParagraph(); return }
        val upTo = sentences.take(currentSentenceIndex + 1).joinToString(" ")
        val para = paragraphs.firstOrNull { p -> upTo.contains(p.take(25)) }
            ?: sentences.getOrElse(currentSentenceIndex) { "" }
        playInterrupt(para)
    }

    fun readCurrentPage() {
        if (!apiKeyManager.hasOpenAIKey) { androidTts?.readCurrentPage(); return }
        readingJob?.cancel()
        releaseMediaPlayer()
        currentSentenceIndex = 0
        paused.set(false)
        stopped.set(false)
        launchReadingFrom(0)
    }

    /** Announces text immediately using Android TTS (zero latency). */
    fun announce(text: String, onDone: (() -> Unit)? = null) {
        androidTts?.announce(text, onDone)
    }

    fun getSentenceCount() = sentences.size
    fun isPaused() = paused.get()
    fun isStopped() = stopped.get()

    fun release() {
        scope.cancel()
        releaseMediaPlayer()
        androidTts?.release()
        androidTts = null
    }

    // ── Neural TTS internals ──────────────────────────────────────────────────

    private fun launchReadingFrom(startIndex: Int) {
        readingJob?.cancel()
        readingJob = scope.launch {
            prefetchAround(startIndex)
            readSentencesFrom(startIndex)
        }
    }

    private suspend fun readSentencesFrom(startIndex: Int) {
        var idx = startIndex
        while (idx < sentences.size && !paused.get() && !stopped.get()) {
            currentSentenceIndex = idx
            val sentence = sentences[idx]

            onSentenceStarted(idx, sentence)

            // Get audio from cache or fetch now
            val audio = withContext(Dispatchers.IO) {
                audioCache.getOrPut(idx) { fetchAudio(sentence) }
            }

            // Pre-fetch next two sentences in background
            scope.launch(Dispatchers.IO) {
                for (next in (idx + 1)..(idx + 2)) {
                    if (next < sentences.size && !audioCache.containsKey(next)) {
                        audioCache[next] = fetchAudio(sentences[next])
                    }
                }
            }

            if (paused.get() || stopped.get()) break

            if (audio != null) {
                // Play neural TTS audio and suspend until done
                val completed = suspendCancellableCoroutine<Boolean> { cont ->
                    playMp3(idx, audio,
                        onComplete = { if (cont.isActive) cont.resume(true, null) },
                        onError = { if (cont.isActive) cont.resume(false, null) }
                    )
                    cont.invokeOnCancellation { releaseMediaPlayer() }
                }
                if (!completed) {
                    // Neural TTS failed mid-sentence; skip and continue
                    Log.w(TAG, "Playback failed for sentence $idx, skipping")
                }
            } else {
                // No audio (fetch failed) — small pause so it doesn't race
                delay(200)
            }

            if (!paused.get() && !stopped.get()) {
                onSentenceDone(idx)
                // Evict old cache entries to save memory
                audioCache.remove(idx - 2)
                idx++
            }
        }

        if (!paused.get() && !stopped.get() && idx >= sentences.size) {
            onPageDone()
        }
    }

    private suspend fun prefetchAround(fromIndex: Int) {
        withContext(Dispatchers.IO) {
            for (i in fromIndex until minOf(fromIndex + 3, sentences.size)) {
                if (!audioCache.containsKey(i)) {
                    audioCache[i] = fetchAudio(sentences[i])
                }
            }
        }
    }

    private fun playInterrupt(text: String) {
        readingJob?.cancel()
        releaseMediaPlayer()
        scope.launch {
            val audio = withContext(Dispatchers.IO) { fetchAudio(text) }
            if (audio != null) {
                suspendCancellableCoroutine<Unit> { cont ->
                    playMp3(-1, audio,
                        onComplete = { if (cont.isActive) cont.resume(Unit, null) },
                        onError = { if (cont.isActive) cont.resume(Unit, null) }
                    )
                }
            } else {
                // Fallback: use Android TTS for the interrupted snippet
                suspendCancellableCoroutine<Unit> { cont ->
                    androidTts?.announce(text) { if (cont.isActive) cont.resume(Unit, null) }
                        ?: cont.resume(Unit, null)
                }
            }
        }
    }

    private fun playMp3(index: Int, bytes: ByteArray, onComplete: () -> Unit, onError: () -> Unit) {
        releaseMediaPlayer()
        val tmp = File(context.cacheDir, "tts_${index}_${System.currentTimeMillis()}.mp3")
        try {
            tmp.writeBytes(bytes)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(tmp.absolutePath)
                prepare()
                setOnCompletionListener { tmp.delete(); onComplete() }
                setOnErrorListener { _, _, _ -> tmp.delete(); onError(); true }
                start()
            }
        } catch (e: Exception) {
            tmp.delete()
            Log.e(TAG, "MediaPlayer error for sentence $index", e)
            onError()
        }
    }

    private fun releaseMediaPlayer() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    private fun fetchAudio(text: String): ByteArray? {
        val apiKey = apiKeyManager.openaiApiKey ?: return null
        val body = JSONObject().apply {
            put("model", "tts-1-hd")
            put("input", text)
            put("voice", "nova")       // clear, natural female voice
            put("response_format", "mp3")
        }.toString()

        val request = Request.Builder()
            .url(OPENAI_TTS_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.bytes()
            } else {
                Log.e(TAG, "OpenAI TTS ${response.code}: ${response.body?.string()?.take(200)}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI TTS fetch failed", e)
            null
        }
    }

    private fun splitSentences(text: String): List<String> =
        text.replace(Regex("\\n+"), " ")
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 1 }

    companion object {
        private const val TAG = "NeuralTtsService"
        private const val OPENAI_TTS_URL = "https://api.openai.com/v1/audio/speech"
    }
}

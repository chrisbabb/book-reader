package com.bookreader.app.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Uses Claude claude-haiku-4-5 to intelligently clean up raw OCR text before reading.
 *
 * Benefits over raw OCR:
 *  - Fixes garbled characters and misread words
 *  - Removes page numbers, running headers, and footers
 *  - Fixes hyphenated line-breaks (e.g. "knowl-\nedge" → "knowledge")
 *  - Detects the page number from the content
 *  - Makes the text flow naturally for TTS
 *
 * Degrades gracefully: if no API key or network failure, returns the raw OCR text unchanged.
 */
class ClaudeTextProcessor(private val apiKeyManager: ApiKeyManager) {

    data class ProcessedPage(
        val detectedPageNumber: Int?,
        val cleanText: String,
        val processedByAI: Boolean = false
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun process(rawText: String): ProcessedPage = withContext(Dispatchers.IO) {
        val apiKey = apiKeyManager.anthropicApiKey
        if (apiKey == null) {
            Log.d(TAG, "No Claude key — using raw OCR text")
            return@withContext ProcessedPage(null, rawText)
        }

        val requestJson = JSONObject().apply {
            put("model", "claude-haiku-4-5")
            put("max_tokens", 4096)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", buildPrompt(rawText))
                })
            })
        }.toString()

        val request = Request.Builder()
            .url(ANTHROPIC_MESSAGES_URL)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .build()

        return@withContext try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                Log.e(TAG, "Claude API ${response.code}: $body")
                return@withContext ProcessedPage(null, rawText)
            }

            val text = JSONObject(body)
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")

            parseResponse(text) ?: ProcessedPage(null, rawText)
        } catch (e: Exception) {
            Log.e(TAG, "Claude API call failed", e)
            ProcessedPage(null, rawText)
        }
    }

    private fun buildPrompt(rawText: String) = """
You are helping a visually impaired person listen to a book via text-to-speech.

Clean up this raw OCR text extracted from a printed book page so it reads naturally aloud.

Fix these issues:
1. OCR errors — misread letters, broken words, garbled characters
2. Remove page numbers, chapter headers, running headers/footers that break reading flow
3. Fix hyphenated line breaks (e.g. "knowl-\nedge" → "knowledge", "char-\nacter" → "character")
4. Preserve paragraph structure — separate paragraphs with a blank line
5. Identify the page number if it appears in the text (common positions: very first or very last line)

Respond with ONLY a JSON object — no markdown, no preamble:
{"pageNumber": <integer or null>, "cleanText": "<cleaned text ready for text-to-speech>"}

Raw OCR text:
$rawText
    """.trimIndent()

    private fun parseResponse(responseText: String): ProcessedPage? {
        return try {
            val jsonStr = responseText.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val obj = JSONObject(jsonStr)
            ProcessedPage(
                detectedPageNumber = if (obj.isNull("pageNumber")) null else obj.optInt("pageNumber"),
                cleanText = obj.getString("cleanText"),
                processedByAI = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Claude response: ${responseText.take(200)}", e)
            null
        }
    }

    companion object {
        private const val TAG = "ClaudeTextProcessor"
        private const val ANTHROPIC_MESSAGES_URL = "https://api.anthropic.com/v1/messages"
    }
}

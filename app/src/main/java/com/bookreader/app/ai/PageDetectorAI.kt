package com.bookreader.app.ai

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.bookreader.app.camera.PageDetectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Uses Claude Vision to find the physical book page in a camera frame.
 *
 * Claude identifies the actual paper page (including white margins, at any angle)
 * and returns the 4 corner coordinates as fractions of the image dimensions.
 * Falls back gracefully if the API key is missing or the call fails.
 */
class PageDetectorAI(private val apiKeyManager: ApiKeyManager) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Detects the page in [bitmap] and returns its detection state and 4 corners.
     *
     * @return Pair of (PageDetectionState, FloatArray?) where the array contains
     *   8 floats [x0,y0, x1,y1, x2,y2, x3,y3] as 0..1 fractions of [bitmap] dimensions,
     *   ordered clockwise from top-left. Returns (SEARCHING, null) if no page found.
     */
    suspend fun detectPage(bitmap: Bitmap): Pair<PageDetectionState, FloatArray?> =
        withContext(Dispatchers.IO) {
            val apiKey = apiKeyManager.anthropicApiKey
                ?: return@withContext Pair(PageDetectionState.SEARCHING, null)

            try {
                val scaled = scaleBitmap(bitmap, MAX_SEND_DIM)
                val base64 = bitmapToBase64(scaled)

                val requestJson = JSONObject().apply {
                    put("model", MODEL)
                    put("max_tokens", 256)
                    put("messages", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", org.json.JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "image")
                                    put("source", JSONObject().apply {
                                        put("type", "base64")
                                        put("media_type", "image/jpeg")
                                        put("data", base64)
                                    })
                                })
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", PROMPT)
                                })
                            })
                        })
                    })
                }.toString()

                val request = Request.Builder()
                    .url(API_URL)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .post(requestJson.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string()

                if (!response.isSuccessful || body == null) {
                    Log.w(TAG, "API error ${response.code}")
                    return@withContext Pair(PageDetectionState.SEARCHING, null)
                }

                parseResponse(body)
            } catch (e: Exception) {
                Log.w(TAG, "Detection failed: ${e.message}")
                Pair(PageDetectionState.SEARCHING, null)
            }
        }

    private fun parseResponse(body: String): Pair<PageDetectionState, FloatArray?> {
        return try {
            val text = JSONObject(body)
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
                .trim()

            // Extract JSON — Claude may wrap it in a markdown code block
            val jsonStr = Regex("""\{[^{}]*\}""", RegexOption.DOT_MATCHES_ALL)
                .find(text)?.value
                ?: return Pair(PageDetectionState.SEARCHING, null)

            val json = JSONObject(jsonStr)
            if (json.isNull("tl")) return Pair(PageDetectionState.SEARCHING, null)

            fun coord(key: String): Pair<Float, Float> {
                val arr = json.getJSONArray(key)
                return arr.getDouble(0).toFloat() to arr.getDouble(1).toFloat()
            }

            val (tlx, tly) = coord("tl")
            val (trx, try_) = coord("tr")
            val (brx, bry) = coord("br")
            val (blx, bly) = coord("bl")

            val corners = floatArrayOf(tlx, tly, trx, try_, brx, bry, blx, bly)
            val xs = floatArrayOf(tlx, trx, brx, blx)
            val ys = floatArrayOf(tly, try_, bry, bly)
            val approxArea = (xs.max() - xs.min()) * (ys.max() - ys.min())

            if (approxArea < 0.04f) return Pair(PageDetectionState.SEARCHING, null)

            val margin = 0.05f
            val fullyVisible = xs.all { it in margin..(1f - margin) } &&
                               ys.all { it in margin..(1f - margin) }

            val state = if (fullyVisible && approxArea >= 0.25f) PageDetectionState.ALIGNED
                        else PageDetectionState.PARTIAL

            Pair(state, corners)
        } catch (e: Exception) {
            Log.w(TAG, "Parse error: ${e.message}")
            Pair(PageDetectionState.SEARCHING, null)
        }
    }

    private fun scaleBitmap(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width; val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val scale = maxDim.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "PageDetectorAI"
        private const val MODEL = "claude-haiku-4-5-20251001"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MAX_SEND_DIM = 768

        private val PROMPT = """
You are analyzing a camera image to find a book page or printed document.

Locate the physical paper page in the image — the full page including its white margins, not just the text area. The page may be at any angle.

Respond with ONLY a JSON object, no other text.

If a page is clearly visible:
{"tl":[x,y],"tr":[x,y],"br":[x,y],"bl":[x,y]}

If no page is visible:
{"tl":null}

Coordinates are fractions of image size: x is 0.0 (left edge) to 1.0 (right edge), y is 0.0 (top) to 1.0 (bottom).
tl = top-left, tr = top-right, br = bottom-right, bl = bottom-left, listed clockwise.
        """.trimIndent()
    }
}

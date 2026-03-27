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
        .readTimeout(20, TimeUnit.SECONDS)
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
                Log.d(TAG, "Sending ${scaled.width}x${scaled.height} image to Claude Vision")

                val requestJson = JSONObject().apply {
                    put("model", MODEL)
                    put("max_tokens", 300)
                    put("system", SYSTEM_PROMPT)
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
                                    put("text", USER_PROMPT)
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
                    Log.w(TAG, "API error ${response.code}: $body")
                    return@withContext Pair(PageDetectionState.SEARCHING, null)
                }

                val result = parseResponse(body)
                Log.d(TAG, "Detection result: ${result.first}, corners=${result.second != null}")
                result
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

            Log.d(TAG, "Claude response: $text")

            // Extract the JSON object — handles markdown code fences and extra text
            val jsonStr = extractJson(text)
                ?: return Pair(PageDetectionState.SEARCHING, null)

            val json = JSONObject(jsonStr)

            // No page detected
            if (json.isNull("tl")) {
                Log.d(TAG, "Claude: no page found")
                return Pair(PageDetectionState.SEARCHING, null)
            }

            fun coord(key: String): Pair<Float, Float>? {
                return try {
                    val arr = json.getJSONArray(key)
                    arr.getDouble(0).toFloat() to arr.getDouble(1).toFloat()
                } catch (e: Exception) { null }
            }

            val tl = coord("tl") ?: return Pair(PageDetectionState.SEARCHING, null)
            val tr = coord("tr") ?: return Pair(PageDetectionState.SEARCHING, null)
            val br = coord("br") ?: return Pair(PageDetectionState.SEARCHING, null)
            val bl = coord("bl") ?: return Pair(PageDetectionState.SEARCHING, null)

            val (tlx, tly) = tl
            val (trx, try_) = tr
            val (brx, bry) = br
            val (blx, bly) = bl

            // Clamp to valid range
            val corners = floatArrayOf(
                tlx.coerceIn(0f, 1f), tly.coerceIn(0f, 1f),
                trx.coerceIn(0f, 1f), try_.coerceIn(0f, 1f),
                brx.coerceIn(0f, 1f), bry.coerceIn(0f, 1f),
                blx.coerceIn(0f, 1f), bly.coerceIn(0f, 1f)
            )

            val xs = floatArrayOf(tlx, trx, brx, blx)
            val ys = floatArrayOf(tly, try_, bry, bly)
            val approxArea = (xs.max() - xs.min()) * (ys.max() - ys.min())

            Log.d(TAG, "Page corners: TL($tlx,$tly) TR($trx,$try_) BR($brx,$bry) BL($blx,$bly) area=$approxArea")

            // Reject tiny detections (less than 4% of frame area)
            if (approxArea < 0.04f) {
                Log.d(TAG, "Page too small (area=$approxArea), ignoring")
                return Pair(PageDetectionState.SEARCHING, null)
            }

            // ALIGNED = all corners well inside frame AND page covers enough area
            val edgeMargin = 0.04f
            val fullyVisible = xs.all { it in edgeMargin..(1f - edgeMargin) } &&
                               ys.all { it in edgeMargin..(1f - edgeMargin) }
            val state = if (fullyVisible && approxArea >= 0.20f) PageDetectionState.ALIGNED
                        else PageDetectionState.PARTIAL

            Pair(state, corners)
        } catch (e: Exception) {
            Log.w(TAG, "Parse error: ${e.message} body=$body")
            Pair(PageDetectionState.SEARCHING, null)
        }
    }

    /**
     * Extracts the first JSON object from a string that may include markdown fences,
     * reasoning text, or other surrounding content.
     */
    private fun extractJson(text: String): String? {
        // Try to find { ... } that contains "tl"
        var depth = 0
        var start = -1
        for (i in text.indices) {
            when (text[i]) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        val candidate = text.substring(start, i + 1)
                        if (candidate.contains("\"tl\"")) return candidate
                        start = -1
                    }
                }
            }
        }
        return null
    }

    private fun scaleBitmap(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width; val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val scale = maxDim.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "PageDetectorAI"
        private const val MODEL = "claude-haiku-4-5-20251001"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MAX_SEND_DIM = 1024

        private const val SYSTEM_PROMPT = """You are a precise computer vision assistant. Your only job is to locate the corners of a physical book page or printed document in camera images and return them as JSON coordinates. You always respond with valid JSON only — no explanations, no markdown."""

        private const val USER_PROMPT = """Find the physical book page (or printed document page) in this image.

A book page is a rectangular piece of paper — usually white, cream, or light yellow — with printed text on it. The page has clear straight edges and may be at any angle.

Rules:
- Return the 4 corners of the FULL physical page including its white margins, not just the text area
- If two pages are visible (open book), pick the single page that is most completely visible (larger visible area, fewer edges cut off)
- The page corners should form a proper quadrilateral even if the page is tilted or slightly curved
- If a page edge is at the image boundary, the corner coordinate should be at or very near 0.0 or 1.0

Respond with ONLY a JSON object:

If a page is visible:
{"tl":[x,y],"tr":[x,y],"br":[x,y],"bl":[x,y]}

If no page is visible:
{"tl":null}

x=0.0 is the left edge of the image, x=1.0 is the right edge.
y=0.0 is the top of the image, y=1.0 is the bottom.
tl=top-left corner, tr=top-right corner, br=bottom-right corner, bl=bottom-left corner."""
    }
}

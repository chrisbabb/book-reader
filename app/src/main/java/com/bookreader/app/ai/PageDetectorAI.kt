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

            Log.d(TAG, "Claude raw response: $text")

            val jsonStr = extractJson(text)
                ?: return Pair(PageDetectionState.SEARCHING, null)

            val json = JSONObject(jsonStr)

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

            val pts = listOfNotNull(
                coord("tl"), coord("tr"), coord("br"), coord("bl")
            )
            if (pts.size != 4) return Pair(PageDetectionState.SEARCHING, null)

            // Detect if Claude returned pixel coordinates instead of 0..1 fractions.
            // Any value > 1.5 is almost certainly a pixel value, not a fraction.
            val maxVal = pts.flatMap { listOf(it.first, it.second) }.max()
            if (maxVal > 1.5f) {
                Log.w(TAG, "Claude returned pixel coordinates (max=$maxVal) — rejecting")
                return Pair(PageDetectionState.SEARCHING, null)
            }

            // Geometrically sort the 4 corners into clockwise TL/TR/BR/BL order.
            // This fixes self-intersecting "bow-tie" shapes when Claude labels corners wrong.
            val sorted = sortCornersCW(pts)
            val (tlx, tly) = sorted[0]
            val (trx, try_) = sorted[1]
            val (brx, bry) = sorted[2]
            val (blx, bly) = sorted[3]

            Log.d(TAG, "Sorted corners: TL($tlx,$tly) TR($trx,$try_) BR($brx,$bry) BL($blx,$bly)")

            val xs = floatArrayOf(tlx, trx, brx, blx)
            val ys = floatArrayOf(tly, try_, bry, bly)
            val approxArea = (xs.max() - xs.min()) * (ys.max() - ys.min())

            if (approxArea < 0.04f) {
                Log.d(TAG, "Page too small (area=$approxArea), ignoring")
                return Pair(PageDetectionState.SEARCHING, null)
            }

            val corners = floatArrayOf(
                tlx.coerceIn(0f, 1f), tly.coerceIn(0f, 1f),
                trx.coerceIn(0f, 1f), try_.coerceIn(0f, 1f),
                brx.coerceIn(0f, 1f), bry.coerceIn(0f, 1f),
                blx.coerceIn(0f, 1f), bly.coerceIn(0f, 1f)
            )

            val edgeMargin = 0.04f
            val fullyVisible = xs.all { it in edgeMargin..(1f - edgeMargin) } &&
                               ys.all { it in edgeMargin..(1f - edgeMargin) }
            val state = if (fullyVisible && approxArea >= 0.20f) PageDetectionState.ALIGNED
                        else PageDetectionState.PARTIAL

            Log.d(TAG, "State=$state area=$approxArea fullyVisible=$fullyVisible")
            Pair(state, corners)
        } catch (e: Exception) {
            Log.w(TAG, "Parse error: ${e.message}")
            Pair(PageDetectionState.SEARCHING, null)
        }
    }

    /**
     * Sorts 4 (x,y) points into clockwise order: TL, TR, BR, BL.
     * Works by splitting into top-2/bottom-2 by y value, then ordering by x within each pair.
     */
    private fun sortCornersCW(pts: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val byY = pts.sortedBy { it.second }
        val top    = byY.take(2).sortedBy { it.first }   // lower y → top; left then right
        val bottom = byY.drop(2).sortedBy { it.first }   // higher y → bottom; left then right
        return listOf(top[0], top[1], bottom[1], bottom[0])  // TL, TR, BR, BL
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

        private const val USER_PROMPT = """Find the physical book page in this image and return its 4 corner positions.

A book page is a rectangular piece of paper (white, cream, or light yellow) with printed text. It has clear straight edges and may be held at any angle.

CRITICAL: All coordinates MUST be decimal fractions between 0.0 and 1.0. Do NOT return pixel numbers.
- 0.0 = left/top edge of the image
- 1.0 = right/bottom edge of the image
- 0.5 = exact center of the image

Rules:
- Return corners of the ENTIRE physical page including white margins — not just the text
- If two pages are visible (open book), choose the page that shows more of itself
- Place corners at the actual paper edge, not the text edge
- If a page corner is at or beyond the image edge, use 0.0 or 1.0

Respond with ONLY a JSON object, nothing else:

Page found: {"tl":[x,y],"tr":[x,y],"br":[x,y],"bl":[x,y]}
No page:    {"tl":null}

tl=top-left, tr=top-right, br=bottom-right, bl=bottom-left corner of the page."""
    }
}

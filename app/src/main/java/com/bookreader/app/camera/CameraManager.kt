package com.bookreader.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.bookreader.app.ai.PageDetectorAI
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Manages the camera preview, photo capture, and real-time page detection.
 *
 * Page detection strategy:
 *  - If [pageDetectorAI] is provided: send each analysis frame to Claude Vision, which
 *    identifies the physical page corners at any angle. Accurate but network-dependent.
 *  - Otherwise: on-device ML Kit text recognition is used to infer page bounds from
 *    the union of all text block corners (minimum-area bounding rectangle).
 */
class CameraManager(
    private val context: Context,
    private val detectionScope: CoroutineScope? = null,
    private val pageDetectorAI: PageDetectorAI? = null
) {

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // Shared callback for detection results
    private var pageDetectionCallback: ((PageDetectionState, FloatArray?) -> Unit)? = null
    private var previewViewRef: WeakReference<PreviewView>? = null

    // Throttle / concurrency guards
    private var isAnalyzing = false
    private var isAiDetecting = false
    private var analysisEnabled = true
    private var lastAnalysisMs = 0L

    // ML Kit fallback
    private val mlKitRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // ── Camera setup ──────────────────────────────────────────────────────────

    suspend fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onPageDetected: ((PageDetectionState, FloatArray?) -> Unit)? = null
    ) {
        pageDetectionCallback = onPageDetected
        previewViewRef = WeakReference(previewView)

        val provider = getCameraProvider()
        cameraProvider = provider

        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        // RGBA_8888 gives us a directly usable Bitmap without YUV conversion;
        // works for both the AI path and InputImage.fromBitmap() for ML Kit fallback.
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { image ->
                    throttledAnalyze(image)
                }
            }

        try {
            provider.unbindAll()
            val camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
                imageAnalysis
            )
            camera.cameraControl.cancelFocusAndMetering()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera use cases: ${e.message}")
            throw e
        }
    }

    fun setAnalysisEnabled(enabled: Boolean) {
        analysisEnabled = enabled
        if (enabled) {
            lastAnalysisMs = 0L
            pageDetectionCallback?.invoke(PageDetectionState.SEARCHING, null)
        }
    }

    // ── Photo capture ─────────────────────────────────────────────────────────

    suspend fun capturePhoto(): Bitmap = suspendCancellableCoroutine { continuation ->
        val capture = imageCapture ?: run {
            continuation.resumeWithException(IllegalStateException("Camera not initialized"))
            return@suspendCancellableCoroutine
        }
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bitmap = jpegProxyToBitmap(image)
                        image.close()
                        continuation.resumeWith(Result.success(bitmap))
                    } catch (e: Exception) {
                        image.close()
                        continuation.resumeWithException(e)
                    }
                }
                override fun onError(exception: ImageCaptureException) {
                    continuation.resumeWithException(exception)
                }
            }
        )
    }

    fun shutdown() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        if (::mlKitRecognizer.isInitialized) mlKitRecognizer.close()
    }

    // ── Analysis dispatch ─────────────────────────────────────────────────────

    private fun throttledAnalyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        val interval = if (pageDetectorAI != null) AI_INTERVAL_MS else LOCAL_INTERVAL_MS
        if (!analysisEnabled || isAnalyzing || isAiDetecting ||
            now - lastAnalysisMs < interval) {
            image.close()
            return
        }
        lastAnalysisMs = now
        isAnalyzing = true

        val bitmap = rgbaProxyToBitmap(image)
        image.close()
        isAnalyzing = false

        if (pageDetectorAI != null && detectionScope != null) {
            analyzeWithAI(bitmap)
        } else {
            analyzeWithMlKit(bitmap)
        }
    }

    // ── AI detection ──────────────────────────────────────────────────────────

    private fun analyzeWithAI(bitmap: Bitmap) {
        isAiDetecting = true
        detectionScope!!.launch {
            try {
                val (state, normCorners) = pageDetectorAI!!.detectPage(bitmap)
                val viewCorners = normCorners?.let {
                    imagePointsToViewPoints(it, bitmap.width, bitmap.height)
                }
                pageDetectionCallback?.invoke(state, viewCorners)
            } finally {
                isAiDetecting = false
            }
        }
    }

    // ── ML Kit fallback detection ─────────────────────────────────────────────

    private fun analyzeWithMlKit(bitmap: Bitmap) {
        mlKitRecognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val (state, normCorners) = evaluateTextBlocks(
                    result.textBlocks, bitmap.width, bitmap.height
                )
                val viewCorners = normCorners?.let {
                    imagePointsToViewPoints(it, bitmap.width, bitmap.height)
                }
                pageDetectionCallback?.invoke(state, viewCorners)
            }
            .addOnFailureListener {
                pageDetectionCallback?.invoke(PageDetectionState.SEARCHING, null)
            }
    }

    private fun evaluateTextBlocks(
        blocks: List<com.google.mlkit.vision.text.Text.TextBlock>,
        imageWidth: Int,
        imageHeight: Int
    ): Pair<PageDetectionState, FloatArray?> {
        if (blocks.isEmpty()) return Pair(PageDetectionState.SEARCHING, null)

        val allPoints = mutableListOf<PointF>()
        for (block in blocks) {
            val corners = block.cornerPoints
            if (corners != null && corners.size == 4) {
                for (pt in corners)
                    allPoints.add(PointF(pt.x.toFloat() / imageWidth, pt.y.toFloat() / imageHeight))
            } else {
                val b = block.boundingBox ?: continue
                allPoints.add(PointF(b.left.toFloat() / imageWidth,  b.top.toFloat()    / imageHeight))
                allPoints.add(PointF(b.right.toFloat() / imageWidth, b.top.toFloat()    / imageHeight))
                allPoints.add(PointF(b.right.toFloat() / imageWidth, b.bottom.toFloat() / imageHeight))
                allPoints.add(PointF(b.left.toFloat() / imageWidth,  b.bottom.toFloat() / imageHeight))
            }
        }
        if (allPoints.size < 3) return Pair(PageDetectionState.SEARCHING, null)

        val corners = minimumBoundingRectangle(allPoints)
            ?: return Pair(PageDetectionState.SEARCHING, null)

        val xs = floatArrayOf(corners[0], corners[2], corners[4], corners[6])
        val ys = floatArrayOf(corners[1], corners[3], corners[5], corners[7])
        val approxArea = (xs.max() - xs.min()) * (ys.max() - ys.min())
        if (approxArea < 0.04f) return Pair(PageDetectionState.SEARCHING, null)

        val margin = 0.05f
        val fullyVisible = xs.all { it in margin..(1f - margin) } &&
                           ys.all { it in margin..(1f - margin) }
        val state = if (fullyVisible && approxArea >= 0.25f) PageDetectionState.ALIGNED
                    else PageDetectionState.PARTIAL
        return Pair(state, corners)
    }

    // ── Coordinate transformation ─────────────────────────────────────────────

    private fun imagePointsToViewPoints(corners: FloatArray, imgW: Int, imgH: Int): FloatArray? {
        val previewView = previewViewRef?.get() ?: return null
        val viewW = previewView.width.toFloat()
        val viewH = previewView.height.toFloat()
        if (viewW == 0f || viewH == 0f) return null

        val scale = max(viewW / imgW, viewH / imgH)
        val scaledW = imgW * scale
        val scaledH = imgH * scale
        val offsetX = (viewW - scaledW) / 2f
        val offsetY = (viewH - scaledH) / 2f

        val result = FloatArray(corners.size)
        for (i in corners.indices step 2) {
            result[i]     = offsetX + corners[i]     * scaledW
            result[i + 1] = offsetY + corners[i + 1] * scaledH
        }
        return result
    }

    // ── Geometry (ML Kit fallback) ────────────────────────────────────────────

    private fun minimumBoundingRectangle(points: List<PointF>): FloatArray? {
        val hull = convexHull(points)
        if (hull.size < 2) return null

        var minArea = Float.MAX_VALUE
        var bestCorners: FloatArray? = null

        for (i in hull.indices) {
            val p1 = hull[i]; val p2 = hull[(i + 1) % hull.size]
            val angle = atan2((p2.y - p1.y).toDouble(), (p2.x - p1.x).toDouble()).toFloat()
            val cosA = cos(angle.toDouble()).toFloat()
            val sinA = sin(angle.toDouble()).toFloat()

            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (p in hull) {
                val rx =  p.x * cosA + p.y * sinA
                val ry = -p.x * sinA + p.y * cosA
                if (rx < minX) minX = rx; if (rx > maxX) maxX = rx
                if (ry < minY) minY = ry; if (ry > maxY) maxY = ry
            }
            val area = (maxX - minX) * (maxY - minY)
            if (area < minArea) {
                minArea = area
                fun unrot(rx: Float, ry: Float) = PointF(rx * cosA - ry * sinA, rx * sinA + ry * cosA)
                val tl = unrot(minX, minY); val tr = unrot(maxX, minY)
                val br = unrot(maxX, maxY); val bl = unrot(minX, maxY)
                bestCorners = floatArrayOf(tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y)
            }
        }
        return bestCorners
    }

    private fun convexHull(pts: List<PointF>): List<PointF> {
        if (pts.size <= 1) return pts
        val sorted = pts.sortedWith(compareBy({ it.x }, { it.y }))
        val lower = mutableListOf<PointF>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f)
                lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = mutableListOf<PointF>()
        for (p in sorted.reversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f)
                upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        lower.removeAt(lower.size - 1); upper.removeAt(upper.size - 1)
        return lower + upper
    }

    private fun cross(o: PointF, a: PointF, b: PointF): Float =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    // ── Bitmap helpers ────────────────────────────────────────────────────────

    /** Convert an RGBA_8888 ImageProxy (from ImageAnalysis) to a Bitmap. */
    private fun rgbaProxyToBitmap(proxy: ImageProxy): Bitmap {
        val plane = proxy.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val width = proxy.width
        val height = proxy.height

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (rowStride == width * 4) {
            bitmap.copyPixelsFromBuffer(buffer)
        } else {
            // Row padding present — copy row by row
            val rowBytes = ByteArray(rowStride)
            val rgbaBytes = ByteArray(width * height * 4)
            var dst = 0
            for (row in 0 until height) {
                buffer.get(rowBytes, 0, rowStride)
                System.arraycopy(rowBytes, 0, rgbaBytes, dst, width * 4)
                dst += width * 4
            }
            bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgbaBytes))
        }

        val rotation = proxy.imageInfo.rotationDegrees
        return if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
        } else bitmap
    }

    /** Convert a JPEG ImageProxy (from ImageCapture) to a Bitmap. */
    private fun jpegProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val rotation = image.imageInfo.rotationDegrees
        return if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else bitmap
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            ProcessCameraProvider.getInstance(context).also { future ->
                future.addListener(
                    { continuation.resume(future.get()) },
                    ContextCompat.getMainExecutor(context)
                )
            }
        }

    companion object {
        private const val TAG = "CameraManager"
        private const val AI_INTERVAL_MS    = 500L   // AI calls at ~2fps max
        private const val LOCAL_INTERVAL_MS = 200L   // ML Kit at ~5fps
    }
}

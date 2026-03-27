package com.bookreader.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class CameraManager(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // Page detection
    private var pageDetectionCallback: ((PageDetectionState, FloatArray?) -> Unit)? = null
    private var previewViewRef: WeakReference<PreviewView>? = null
    private var isAnalyzing = false
    private var analysisEnabled = true
    private var lastAnalysisMs = 0L
    private val analysisRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // ── Camera setup ─────────────────────────────────────────────────────────

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

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
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

    /** Pause page detection while reading so frames aren't wasted. */
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
                        val bitmap = imageProxyToBitmap(image)
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
        analysisRecognizer.close()
    }

    // ── Page detection internals ──────────────────────────────────────────────

    private fun throttledAnalyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (!analysisEnabled || isAnalyzing || now - lastAnalysisMs < ANALYSIS_INTERVAL_MS) {
            image.close()
            return
        }
        lastAnalysisMs = now
        isAnalyzing = true
        analyzeFrame(image)
    }

    @ExperimentalGetImage
    private fun analyzeFrame(image: ImageProxy) {
        val mediaImage = image.image
        if (mediaImage == null) {
            image.close()
            isAnalyzing = false
            return
        }

        val rotation = image.imageInfo.rotationDegrees
        val imgW = if (rotation == 90 || rotation == 270) image.height else image.width
        val imgH = if (rotation == 90 || rotation == 270) image.width else image.height

        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)
        analysisRecognizer.process(inputImage)
            .addOnSuccessListener { result ->
                val (state, normalizedCorners) = evaluateDetection(result.textBlocks, imgW, imgH)
                val viewCorners = normalizedCorners?.let { imagePointsToViewPoints(it, imgW, imgH) }
                pageDetectionCallback?.invoke(state, viewCorners)
            }
            .addOnFailureListener {
                pageDetectionCallback?.invoke(PageDetectionState.SEARCHING, null)
            }
            .addOnCompleteListener {
                image.close()
                isAnalyzing = false
            }
    }

    /**
     * Collects all corner points from every text block, computes the minimum-area
     * bounding rectangle (possibly rotated), and returns the detection state plus
     * the 4 corners as a FloatArray [x0,y0, x1,y1, x2,y2, x3,y3] in normalized (0..1) space.
     */
    private fun evaluateDetection(
        blocks: List<com.google.mlkit.vision.text.Text.TextBlock>,
        imageWidth: Int,
        imageHeight: Int
    ): Pair<PageDetectionState, FloatArray?> {
        if (blocks.isEmpty()) return Pair(PageDetectionState.SEARCHING, null)

        // Collect all corner points from every block (normalized 0..1)
        val allPoints = mutableListOf<PointF>()
        for (block in blocks) {
            val corners = block.cornerPoints
            if (corners != null && corners.size == 4) {
                for (pt in corners) {
                    allPoints.add(PointF(pt.x.toFloat() / imageWidth, pt.y.toFloat() / imageHeight))
                }
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

        // Axis-aligned span to filter noise
        val xs = floatArrayOf(corners[0], corners[2], corners[4], corners[6])
        val ys = floatArrayOf(corners[1], corners[3], corners[5], corners[7])
        val spanX = xs.max() - xs.min()
        val spanY = ys.max() - ys.min()
        val approxArea = spanX * spanY
        if (approxArea < 0.04f) return Pair(PageDetectionState.SEARCHING, null)

        // Fully visible = all corners within 5% margin
        val margin = 0.05f
        val fullyVisible = xs.all { it in margin..(1f - margin) } &&
                           ys.all { it in margin..(1f - margin) }

        val state = if (fullyVisible && approxArea >= 0.25f) PageDetectionState.ALIGNED
                    else PageDetectionState.PARTIAL

        return Pair(state, corners)
    }

    /**
     * Converts normalized corner points (0..1 in image space) to pixel coordinates
     * in the PreviewView, respecting FILL_CENTER scaling.
     */
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

    // ── Geometry helpers ──────────────────────────────────────────────────────

    /**
     * Minimum-area bounding rectangle via convex hull + rotating calipers.
     * Returns 8 floats: x0,y0, x1,y1, x2,y2, x3,y3 (normalized coords).
     */
    private fun minimumBoundingRectangle(points: List<PointF>): FloatArray? {
        val hull = convexHull(points)
        if (hull.size < 2) return null

        var minArea = Float.MAX_VALUE
        var bestCorners: FloatArray? = null

        for (i in hull.indices) {
            val p1 = hull[i]
            val p2 = hull[(i + 1) % hull.size]

            val angle = atan2((p2.y - p1.y).toDouble(), (p2.x - p1.x).toDouble()).toFloat()
            val cosA = cos(angle.toDouble()).toFloat()
            val sinA = sin(angle.toDouble()).toFloat()

            // Rotate all hull points to align this edge with the x-axis
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
                // Un-rotate the 4 axis-aligned corners back to image space
                fun unrot(rx: Float, ry: Float) = PointF(
                    rx * cosA - ry * sinA,
                    rx * sinA + ry * cosA
                )
                val tl = unrot(minX, minY)
                val tr = unrot(maxX, minY)
                val br = unrot(maxX, maxY)
                val bl = unrot(minX, maxY)
                bestCorners = floatArrayOf(
                    tl.x, tl.y,
                    tr.x, tr.y,
                    br.x, br.y,
                    bl.x, bl.y
                )
            }
        }
        return bestCorners
    }

    /** Andrew's monotone chain convex hull. */
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
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    private fun cross(o: PointF, a: PointF, b: PointF): Float =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    // ── Bitmap helpers ────────────────────────────────────────────────────────

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
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
        private const val ANALYSIS_INTERVAL_MS = 200L   // ~5 fps for fluid tracking
    }
}

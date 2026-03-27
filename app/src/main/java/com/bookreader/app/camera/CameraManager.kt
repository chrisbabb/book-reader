package com.bookreader.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
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

class CameraManager(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // Page detection
    private var pageDetectionCallback: ((PageDetectionState, RectF?) -> Unit)? = null
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
        onPageDetected: ((PageDetectionState, RectF?) -> Unit)? = null
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

    /** Pause page detection (e.g. while reading so frames aren't wasted). */
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
                val (state, imageRect) = evaluateDetection(result.textBlocks, imgW, imgH)
                val viewRect = imageRect?.let { imageRectToViewRect(it, imgW, imgH) }
                pageDetectionCallback?.invoke(state, viewRect)
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
     * Returns the detection state and the bounding rect of all text blocks in image coordinates.
     */
    private fun evaluateDetection(
        blocks: List<com.google.mlkit.vision.text.Text.TextBlock>,
        imageWidth: Int,
        imageHeight: Int
    ): Pair<PageDetectionState, RectF?> {
        if (blocks.isEmpty()) return Pair(PageDetectionState.SEARCHING, null)

        val normalizedBounds = blocks.mapNotNull { block ->
            val b = block.boundingBox ?: return@mapNotNull null
            RectF(
                b.left.toFloat() / imageWidth,
                b.top.toFloat() / imageHeight,
                b.right.toFloat() / imageWidth,
                b.bottom.toFloat() / imageHeight
            )
        }
        if (normalizedBounds.isEmpty()) return Pair(PageDetectionState.SEARCHING, null)

        val pageLeft   = normalizedBounds.minOf { it.left }
        val pageTop    = normalizedBounds.minOf { it.top }
        val pageRight  = normalizedBounds.maxOf { it.right }
        val pageBottom = normalizedBounds.maxOf { it.bottom }

        val pageArea = (pageRight - pageLeft) * (pageBottom - pageTop)

        if (pageArea < 0.04f) return Pair(PageDetectionState.SEARCHING, null)

        // Normalized rect in image space (0..1 on each axis)
        val normalizedRect = RectF(pageLeft, pageTop, pageRight, pageBottom)

        // Determine if fully within frame with small margin (7% on each side)
        val margin = 0.07f
        val fullyVisible = pageLeft >= margin && pageTop >= margin &&
                           pageRight <= (1f - margin) && pageBottom <= (1f - margin)

        val state = if (fullyVisible && pageArea >= 0.30f) PageDetectionState.ALIGNED
                    else PageDetectionState.PARTIAL

        return Pair(state, normalizedRect)
    }

    /**
     * Converts a normalized rect (0..1 in image space) to pixel coordinates in the PreviewView,
     * accounting for FILL_CENTER scaling.
     */
    private fun imageRectToViewRect(normalizedRect: RectF, imgW: Int, imgH: Int): RectF? {
        val previewView = previewViewRef?.get() ?: return null
        val viewW = previewView.width.toFloat()
        val viewH = previewView.height.toFloat()
        if (viewW == 0f || viewH == 0f) return null

        // FILL_CENTER: scale so both dimensions fill, then center
        val scale = maxOf(viewW / imgW, viewH / imgH)
        val scaledW = imgW * scale
        val scaledH = imgH * scale
        val offsetX = (viewW - scaledW) / 2f
        val offsetY = (viewH - scaledH) / 2f

        return RectF(
            offsetX + normalizedRect.left  * scaledW,
            offsetY + normalizedRect.top   * scaledH,
            offsetX + normalizedRect.right * scaledW,
            offsetY + normalizedRect.bottom* scaledH
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
        private const val ANALYSIS_INTERVAL_MS = 800L   // ~1.25 fps
    }
}

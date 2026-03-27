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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraManager(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // Page detection
    private var pageDetectionCallback: ((PageDetectionState) -> Unit)? = null
    private var isAnalyzing = false
    private var analysisEnabled = true
    private var lastAnalysisMs = 0L
    private val analysisRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // ── Camera setup ─────────────────────────────────────────────────────────

    suspend fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onPageDetected: ((PageDetectionState) -> Unit)? = null
    ) {
        pageDetectionCallback = onPageDetected

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
            // Reset so guidance resumes immediately
            lastAnalysisMs = 0L
            pageDetectionCallback?.invoke(PageDetectionState.SEARCHING)
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
                        continuation.resume(bitmap)
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

        // Account for rotation: if 90° or 270°, width/height are swapped
        val rotation = image.imageInfo.rotationDegrees
        val imgW = if (rotation == 90 || rotation == 270) image.height else image.width
        val imgH = if (rotation == 90 || rotation == 270) image.width else image.height

        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)
        analysisRecognizer.process(inputImage)
            .addOnSuccessListener { result ->
                val state = evaluateDetection(result.textBlocks, imgW, imgH)
                pageDetectionCallback?.invoke(state)
            }
            .addOnFailureListener {
                pageDetectionCallback?.invoke(PageDetectionState.SEARCHING)
            }
            .addOnCompleteListener {
                image.close()
                isAnalyzing = false
            }
    }

    /**
     * Determines detection state by checking whether recognized text blocks
     * collectively fit within the central guide zone (86% of frame) and cover
     * enough area to represent a full page of text.
     */
    private fun evaluateDetection(
        blocks: List<com.google.mlkit.vision.text.Text.TextBlock>,
        imageWidth: Int,
        imageHeight: Int
    ): PageDetectionState {
        if (blocks.isEmpty()) return PageDetectionState.SEARCHING

        val normalizedBounds = blocks.mapNotNull { block ->
            val b = block.boundingBox ?: return@mapNotNull null
            RectF(
                b.left.toFloat() / imageWidth,
                b.top.toFloat() / imageHeight,
                b.right.toFloat() / imageWidth,
                b.bottom.toFloat() / imageHeight
            )
        }
        if (normalizedBounds.isEmpty()) return PageDetectionState.SEARCHING

        // Union bounding box of all text blocks
        val pageLeft   = normalizedBounds.minOf { it.left }
        val pageTop    = normalizedBounds.minOf { it.top }
        val pageRight  = normalizedBounds.maxOf { it.right }
        val pageBottom = normalizedBounds.maxOf { it.bottom }

        // Guide zone: centre 86% of frame (7% padding on each side)
        val gLeft = 0.07f; val gTop = 0.07f
        val gRight = 0.93f; val gBottom = 0.93f

        val allWithin = pageLeft >= gLeft && pageTop >= gTop &&
                        pageRight <= gRight && pageBottom <= gBottom

        val guideArea = (gRight - gLeft) * (gBottom - gTop)
        val pageArea  = (pageRight - pageLeft) * (pageBottom - pageTop)
        val coverage  = pageArea / guideArea

        return when {
            allWithin && coverage >= 0.30f -> PageDetectionState.ALIGNED
            pageArea  >= 0.04f             -> PageDetectionState.PARTIAL
            else                           -> PageDetectionState.SEARCHING
        }
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

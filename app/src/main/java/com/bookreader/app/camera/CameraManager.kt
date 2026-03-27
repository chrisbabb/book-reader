package com.bookreader.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/**
 * Manages camera preview, photo capture, and real-time page detection.
 *
 * Page detection uses ML Kit Object Detection in STREAM_MODE:
 *  - Entirely on-device, no network required
 *  - Tracks the most prominent object (the book page) across frames at ~6 fps
 *  - Returns an axis-aligned bounding box which drives the overlay rectangle
 */
class CameraManager(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var pageDetectionCallback: ((PageDetectionState, FloatArray?) -> Unit)? = null
    private var previewViewRef: WeakReference<PreviewView>? = null

    private var isAnalyzing = false
    private var analysisEnabled = true
    private var lastAnalysisMs = 0L

    // ML Kit Object Detection — STREAM_MODE tracks objects across consecutive frames
    private val objectDetector: ObjectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .build()
    )

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

        // RGBA_8888 → direct Bitmap conversion without YUV dance
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(ContextCompat.getMainExecutor(context), ::throttledAnalyze) }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                preview, imageCapture, imageAnalysis
            ).cameraControl.cancelFocusAndMetering()
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed: ${e.message}")
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

    suspend fun capturePhoto(): Bitmap = suspendCancellableCoroutine { cont ->
        val capture = imageCapture ?: run {
            cont.resumeWithException(IllegalStateException("Camera not initialized"))
            return@suspendCancellableCoroutine
        }
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        cont.resumeWith(Result.success(jpegProxyToBitmap(image)))
                    } finally {
                        image.close()
                    }
                }
                override fun onError(e: ImageCaptureException) = cont.resumeWithException(e)
            }
        )
    }

    fun shutdown() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        objectDetector.close()
    }

    // ── Frame analysis ────────────────────────────────────────────────────────

    private fun throttledAnalyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (!analysisEnabled || isAnalyzing || now - lastAnalysisMs < ANALYSIS_INTERVAL_MS) {
            image.close()
            return
        }
        lastAnalysisMs = now
        isAnalyzing = true

        val bitmap = rgbaProxyToBitmap(image)
        image.close()

        objectDetector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { objects ->
                if (objects.isEmpty()) {
                    pageDetectionCallback?.invoke(PageDetectionState.SEARCHING, null)
                    isAnalyzing = false
                    return@addOnSuccessListener
                }

                // Pick the most prominent object — largest area in the frame
                val box = objects.maxByOrNull {
                    it.boundingBox.width().toLong() * it.boundingBox.height()
                }!!.boundingBox

                val W = bitmap.width.toFloat()
                val H = bitmap.height.toFloat()
                val nL = box.left  / W
                val nT = box.top   / H
                val nR = box.right / W
                val nB = box.bottom / H
                val area = (nR - nL) * (nB - nT)

                if (area < MIN_PAGE_AREA) {
                    pageDetectionCallback?.invoke(PageDetectionState.SEARCHING, null)
                    isAnalyzing = false
                    return@addOnSuccessListener
                }

                // Four corners of the axis-aligned bounding box (normalised 0..1)
                val normCorners = floatArrayOf(nL, nT, nR, nT, nR, nB, nL, nB)
                val viewCorners = imagePointsToViewPoints(normCorners, bitmap.width, bitmap.height)

                val m = EDGE_MARGIN
                val aligned = nL >= m && nT >= m && nR <= 1f - m && nB <= 1f - m &&
                              area >= MIN_ALIGNED_AREA
                val state = if (aligned) PageDetectionState.ALIGNED else PageDetectionState.PARTIAL

                pageDetectionCallback?.invoke(state, viewCorners)
                isAnalyzing = false
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Object detection failed: ${e.message}")
                pageDetectionCallback?.invoke(PageDetectionState.SEARCHING, null)
                isAnalyzing = false
            }
    }

    // ── Coordinate transformation ─────────────────────────────────────────────

    private fun imagePointsToViewPoints(corners: FloatArray, imgW: Int, imgH: Int): FloatArray? {
        val pv = previewViewRef?.get() ?: return null
        val vW = pv.width.toFloat()
        val vH = pv.height.toFloat()
        if (vW == 0f || vH == 0f) return null

        val scale = max(vW / imgW, vH / imgH)
        val sW = imgW * scale
        val sH = imgH * scale
        val ox = (vW - sW) / 2f
        val oy = (vH - sH) / 2f

        return FloatArray(corners.size) { i ->
            if (i % 2 == 0) ox + corners[i] * sW else oy + corners[i] * sH
        }
    }

    // ── Bitmap helpers ────────────────────────────────────────────────────────

    /** RGBA_8888 ImageProxy (from ImageAnalysis) → correctly-rotated Bitmap. */
    private fun rgbaProxyToBitmap(proxy: ImageProxy): Bitmap {
        val plane = proxy.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val W = proxy.width; val H = proxy.height
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        if (rowStride == W * 4) {
            bmp.copyPixelsFromBuffer(buffer)
        } else {
            val row = ByteArray(rowStride)
            val data = ByteArray(W * H * 4)
            var dst = 0
            repeat(H) {
                buffer.get(row, 0, rowStride)
                System.arraycopy(row, 0, data, dst, W * 4)
                dst += W * 4
            }
            bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(data))
        }
        val rot = proxy.imageInfo.rotationDegrees
        return if (rot != 0) {
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            Bitmap.createBitmap(bmp, 0, 0, W, H, m, true)
        } else bmp
    }

    /** JPEG ImageProxy (from ImageCapture) → correctly-rotated Bitmap. */
    private fun jpegProxyToBitmap(image: ImageProxy): Bitmap {
        val buf = image.planes[0].buffer
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val rot = image.imageInfo.rotationDegrees
        return if (rot != 0) {
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } else bmp
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            ProcessCameraProvider.getInstance(context).also { future ->
                future.addListener(
                    { cont.resume(future.get()) },
                    ContextCompat.getMainExecutor(context)
                )
            }
        }

    companion object {
        private const val TAG = "CameraManager"
        private const val ANALYSIS_INTERVAL_MS = 150L   // ~6 fps
        private const val MIN_PAGE_AREA = 0.04f         // ignore tiny detections
        private const val MIN_ALIGNED_AREA = 0.20f      // at least 20% of frame to be "aligned"
        private const val EDGE_MARGIN = 0.05f           // 5% from frame edge = fully visible
    }
}

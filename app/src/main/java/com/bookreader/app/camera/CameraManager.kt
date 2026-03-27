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
import com.bookreader.app.ai.PageDetectorAI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/**
 * Manages camera preview, photo capture, and real-time page detection.
 *
 * Detection strategy:
 *  - Primary: Claude Vision API via [PageDetectorAI].
 *    Frames are sent to Claude every [AI_INTERVAL_MS] ms (throttled).
 *    The overlay always shows the last known corners while a new call is in flight —
 *    detection is never blocked waiting for the API.
 *  - Fallback: When no [pageDetectorAI] is provided (no API key configured), the
 *    overlay shows SEARCHING so the user knows to configure a key.
 */
class CameraManager(
    private val context: Context,
    private val detectionScope: CoroutineScope? = null,
    private val pageDetectorAI: PageDetectorAI? = null
) {

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var pageDetectionCallback: ((PageDetectionState, FloatArray?) -> Unit)? = null
    private var previewViewRef: WeakReference<PreviewView>? = null

    private var analysisEnabled = true
    private var lastAnalysisMs = 0L

    // Guards against concurrent AI calls — but NEVER blocks the overlay from updating
    @Volatile private var aiCallActive = false

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
                    try { cont.resumeWith(Result.success(jpegProxyToBitmap(image))) }
                    finally { image.close() }
                }
                override fun onError(e: ImageCaptureException) = cont.resumeWithException(e)
            }
        )
    }

    fun shutdown() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
    }

    // ── Frame analysis ────────────────────────────────────────────────────────

    private fun throttledAnalyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (!analysisEnabled || now - lastAnalysisMs < AI_INTERVAL_MS) {
            image.close()
            return
        }

        // No AI configured — tell the user to set up a key
        val ai = pageDetectorAI
        val scope = detectionScope
        if (ai == null || scope == null) {
            image.close()
            pageDetectionCallback?.invoke(PageDetectionState.SEARCHING, null)
            return
        }

        // Already waiting for a response — skip this frame but don't block the overlay
        if (aiCallActive) {
            image.close()
            return
        }

        lastAnalysisMs = now
        aiCallActive = true

        val bitmap = rgbaProxyToBitmap(image)
        image.close()

        scope.launch {
            try {
                val (state, normCorners) = ai.detectPage(bitmap)
                val viewCorners = normCorners?.let {
                    imagePointsToViewPoints(it, bitmap.width, bitmap.height)
                }
                pageDetectionCallback?.invoke(state, viewCorners)
                Log.d(TAG, "AI detection: $state corners=${normCorners != null}")
            } catch (e: Exception) {
                Log.w(TAG, "AI detection error: ${e.message}")
                pageDetectionCallback?.invoke(PageDetectionState.SEARCHING, null)
            } finally {
                aiCallActive = false
            }
        }
    }

    // ── Coordinate transformation ─────────────────────────────────────────────

    private fun imagePointsToViewPoints(corners: FloatArray, imgW: Int, imgH: Int): FloatArray? {
        val pv = previewViewRef?.get() ?: return null
        val vW = pv.width.toFloat(); val vH = pv.height.toFloat()
        if (vW == 0f || vH == 0f) return null
        val scale = max(vW / imgW, vH / imgH)
        val sW = imgW * scale; val sH = imgH * scale
        val ox = (vW - sW) / 2f; val oy = (vH - sH) / 2f
        return FloatArray(corners.size) { i ->
            if (i % 2 == 0) ox + corners[i] * sW else oy + corners[i] * sH
        }
    }

    // ── Bitmap helpers ────────────────────────────────────────────────────────

    private fun rgbaProxyToBitmap(proxy: ImageProxy): Bitmap {
        val plane = proxy.planes[0]
        val buf = plane.buffer; val stride = plane.rowStride
        val W = proxy.width; val H = proxy.height
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        if (stride == W * 4) {
            bmp.copyPixelsFromBuffer(buf)
        } else {
            val row = ByteArray(stride); val data = ByteArray(W * H * 4); var dst = 0
            repeat(H) { buf.get(row, 0, stride); System.arraycopy(row, 0, data, dst, W * 4); dst += W * 4 }
            bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(data))
        }
        val rot = proxy.imageInfo.rotationDegrees
        return if (rot != 0) Bitmap.createBitmap(bmp, 0, 0, W, H, Matrix().apply { postRotate(rot.toFloat()) }, true)
        else bmp
    }

    private fun jpegProxyToBitmap(image: ImageProxy): Bitmap {
        val buf = image.planes[0].buffer
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val rot = image.imageInfo.rotationDegrees
        return if (rot != 0) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        else bmp
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            ProcessCameraProvider.getInstance(context).also { f ->
                f.addListener({ cont.resume(f.get()) }, ContextCompat.getMainExecutor(context))
            }
        }

    companion object {
        private const val TAG = "CameraManager"
        private const val AI_INTERVAL_MS = 500L   // Max 2 AI calls per second
    }
}

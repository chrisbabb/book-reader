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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text.TextBlock
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

/**
 * Manages camera preview, photo capture, and real-time page detection.
 *
 * Detection strategy (entirely on-device, no network):
 *
 *  1. ML Kit text recognition finds all text blocks in the frame.
 *  2. If two pages are visible (open book), the block list is split at the
 *     spine gap and the side with more blocks is selected as the target page.
 *  3. The corner points of the selected blocks are fed into a minimum-area
 *     bounding rectangle (convex hull + rotating calipers) so the outline
 *     follows any tilt angle.
 *  4. The rectangle is expanded outward by PAGE_EXPAND so the outline covers
 *     the full physical page including white margins, not just the text area.
 *  5. The EMA smoothing in PageDetectionOverlay provides fluid animation.
 */
class CameraManager(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var pageDetectionCallback: ((PageDetectionState, FloatArray?) -> Unit)? = null
    private var previewViewRef: WeakReference<PreviewView>? = null

    private var isAnalyzing = false
    private var analysisEnabled = true
    private var lastAnalysisMs = 0L

    private var textRecognizer: TextRecognizer? = null

    // ── Camera setup ──────────────────────────────────────────────────────────

    suspend fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onPageDetected: ((PageDetectionState, FloatArray?) -> Unit)? = null
    ) {
        pageDetectionCallback = onPageDetected
        previewViewRef = WeakReference(previewView)
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

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
        textRecognizer?.close()
        textRecognizer = null
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

        val recognizer = textRecognizer ?: run { isAnalyzing = false; return }

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                processTextBlocks(result.textBlocks, bitmap.width, bitmap.height)
                isAnalyzing = false
            }
            .addOnFailureListener {
                pageDetectionCallback?.invoke(PageDetectionState.SEARCHING, null)
                isAnalyzing = false
            }
    }

    // ── Page detection logic ──────────────────────────────────────────────────

    private fun processTextBlocks(blocks: List<TextBlock>, imgW: Int, imgH: Int) {
        if (blocks.isEmpty()) {
            pageDetectionCallback?.invoke(PageDetectionState.SEARCHING, null)
            return
        }

        // Select only the dominant page's blocks (ignores the other page of an open book)
        val pageBlocks = dominantPage(blocks, imgW)

        if (pageBlocks.isEmpty()) {
            pageDetectionCallback?.invoke(PageDetectionState.SEARCHING, null)
            return
        }

        // Collect every corner point from every block (normalised 0..1)
        val points = mutableListOf<PointF>()
        for (block in pageBlocks) {
            val corners = block.cornerPoints
            if (corners != null && corners.size == 4) {
                corners.forEach { pt ->
                    points.add(PointF(pt.x.toFloat() / imgW, pt.y.toFloat() / imgH))
                }
            } else {
                val b = block.boundingBox ?: continue
                points.add(PointF(b.left.toFloat()  / imgW, b.top.toFloat()    / imgH))
                points.add(PointF(b.right.toFloat() / imgW, b.top.toFloat()    / imgH))
                points.add(PointF(b.right.toFloat() / imgW, b.bottom.toFloat() / imgH))
                points.add(PointF(b.left.toFloat()  / imgW, b.bottom.toFloat() / imgH))
            }
        }

        if (points.size < 3) {
            pageDetectionCallback?.invoke(PageDetectionState.SEARCHING, null)
            return
        }

        // Minimum-area rotated bounding rectangle of the text block corners
        val textCorners = minimumBoundingRectangle(points)
            ?: run {
                pageDetectionCallback?.invoke(PageDetectionState.SEARCHING, null)
                return
            }

        // Sanity-check: text area must be at least 2% of the frame
        val xs = floatArrayOf(textCorners[0], textCorners[2], textCorners[4], textCorners[6])
        val ys = floatArrayOf(textCorners[1], textCorners[3], textCorners[5], textCorners[7])
        val textArea = (xs.max() - xs.min()) * (ys.max() - ys.min())
        if (textArea < 0.02f) {
            pageDetectionCallback?.invoke(PageDetectionState.SEARCHING, null)
            return
        }

        // Expand each corner outward from the centroid to cover the full page
        // (text normally occupies ~70-80% of the page; expanding 1.3× covers the margins)
        val cx = xs.average().toFloat()
        val cy = ys.average().toFloat()
        val pageCorners = FloatArray(8) { i ->
            val isX = i % 2 == 0
            val centre = if (isX) cx else cy
            centre + (textCorners[i] - centre) * PAGE_EXPAND
        }

        // Clamp to [0,1] so the rectangle doesn't go off-screen
        for (i in pageCorners.indices step 2) {
            pageCorners[i]     = pageCorners[i].coerceIn(0f, 1f)
            pageCorners[i + 1] = pageCorners[i + 1].coerceIn(0f, 1f)
        }

        val viewCorners = imagePointsToViewPoints(pageCorners, imgW, imgH)
            ?: run {
                pageDetectionCallback?.invoke(PageDetectionState.SEARCHING, null)
                return
            }

        // Aligned = all 4 page corners well within the frame
        val m = EDGE_MARGIN
        val pgXs = floatArrayOf(pageCorners[0], pageCorners[2], pageCorners[4], pageCorners[6])
        val pgYs = floatArrayOf(pageCorners[1], pageCorners[3], pageCorners[5], pageCorners[7])
        val pgArea = (pgXs.max() - pgXs.min()) * (pgYs.max() - pgYs.min())
        val aligned = pgXs.all { it in m..(1f - m) } &&
                      pgYs.all { it in m..(1f - m) } &&
                      pgArea >= MIN_ALIGNED_AREA

        val state = if (aligned) PageDetectionState.ALIGNED else PageDetectionState.PARTIAL
        pageDetectionCallback?.invoke(state, viewCorners)
    }

    /**
     * Returns the subset of [blocks] belonging to the dominant (most visible) page.
     *
     * When an open book is in view both pages produce text blocks. The book spine
     * creates a horizontal gap between the two sets. We detect that gap and keep
     * only the side with more blocks (the page more fully in view).
     */
    private fun dominantPage(blocks: List<TextBlock>, imgW: Int): List<TextBlock> {
        if (blocks.size < 3) return blocks   // too few to split reliably

        // Sort by the horizontal centre of each block
        val sorted = blocks.sortedBy { it.boundingBox?.centerX() ?: 0 }

        // Find the widest horizontal gap between consecutive blocks
        var maxGap = 0f
        var gapIdx = -1
        for (i in 0 until sorted.size - 1) {
            val curRight  = (sorted[i].boundingBox?.right  ?: 0).toFloat() / imgW
            val nextLeft  = (sorted[i + 1].boundingBox?.left ?: 0).toFloat() / imgW
            val gap = nextLeft - curRight
            if (gap > maxGap) { maxGap = gap; gapIdx = i }
        }

        // No spine gap large enough → single page in view
        if (maxGap < SPINE_GAP_MIN) return blocks

        val left  = sorted.subList(0, gapIdx + 1)
        val right = sorted.subList(gapIdx + 1, sorted.size)

        // Choose the side with more text blocks (= more fully in view)
        return if (left.size >= right.size) left else right
    }

    // ── Geometry helpers ──────────────────────────────────────────────────────

    /** Convex hull + rotating calipers → minimum-area bounding rectangle. */
    private fun minimumBoundingRectangle(points: List<PointF>): FloatArray? {
        val hull = convexHull(points)
        if (hull.size < 2) return null

        var minArea = Float.MAX_VALUE
        var best: FloatArray? = null

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
                best = floatArrayOf(tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y)
            }
        }
        return best
    }

    /** Andrew's monotone chain convex hull. */
    private fun convexHull(pts: List<PointF>): List<PointF> {
        if (pts.size <= 1) return pts
        val s = pts.sortedWith(compareBy({ it.x }, { it.y }))
        val lo = mutableListOf<PointF>()
        val hi = mutableListOf<PointF>()
        for (p in s) {
            while (lo.size >= 2 && cross(lo[lo.size - 2], lo[lo.size - 1], p) <= 0f) lo.removeLast()
            lo.add(p)
        }
        for (p in s.reversed()) {
            while (hi.size >= 2 && cross(hi[hi.size - 2], hi[hi.size - 1], p) <= 0f) hi.removeLast()
            hi.add(p)
        }
        lo.removeLast(); hi.removeLast()
        return lo + hi
    }

    private fun cross(o: PointF, a: PointF, b: PointF) =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

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
        private const val ANALYSIS_INTERVAL_MS = 200L   // 5 fps
        private const val PAGE_EXPAND     = 1.28f       // text ~78% of page → expand to ~100%
        private const val SPINE_GAP_MIN   = 0.07f       // 7% frame-width gap = likely book spine
        private const val EDGE_MARGIN     = 0.04f       // 4% from frame edge = "fully in view"
        private const val MIN_ALIGNED_AREA = 0.18f      // page must cover ≥18% of frame
    }
}

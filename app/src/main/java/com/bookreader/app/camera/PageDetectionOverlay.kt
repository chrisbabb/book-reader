package com.bookreader.app.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Draws a guide rectangle over the camera preview to help the user frame a book page.
 *
 * Three visual states:
 *  SEARCHING — red brackets   "Point camera at book page"
 *  PARTIAL   — amber brackets "Move closer or straighten the page"
 *  ALIGNED   — green brackets "Page aligned — hold steady"
 *
 * The area outside the guide is dimmed so the user (or caregiver) can clearly see
 * where the page should be positioned.
 */
class PageDetectionOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var state = PageDetectionState.SEARCHING

    // Dim layer behind guide rect
    private val dimPaint = Paint().apply {
        color = 0xAA000000.toInt()
        xfermode = null
    }
    // Clear the guide hole
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 0f, 2f, Color.BLACK)
    }

    private val guideRect = RectF()

    fun updateState(newState: PageDetectionState) {
        if (state != newState) {
            state = newState
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val padH = w * 0.07f
        val padV = h * 0.10f
        guideRect.set(padH, padV, w - padH, h - padV)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        // Draw dim layer with a clear hole for the guide area
        val sc = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRoundRect(guideRect, 12f, 12f, clearPaint)
        canvas.restoreToCount(sc)

        // Corner bracket colour
        bracketPaint.color = when (state) {
            PageDetectionState.SEARCHING -> Color.RED
            PageDetectionState.PARTIAL   -> 0xFFFF9900.toInt()   // amber
            PageDetectionState.ALIGNED   -> 0xFF44DD44.toInt()   // green
        }

        val bLen = min(guideRect.width(), guideRect.height()) * 0.11f
        drawCornerBrackets(canvas, guideRect, bLen)

        // Hint label just below the guide rectangle
        val label = when (state) {
            PageDetectionState.SEARCHING -> "Point camera at a book page"
            PageDetectionState.PARTIAL   -> "Move closer — show the full page"
            PageDetectionState.ALIGNED   -> "Page aligned — hold steady"
        }
        canvas.drawText(label, guideRect.centerX(), guideRect.bottom + 52f, labelPaint)
    }

    private fun drawCornerBrackets(canvas: Canvas, r: RectF, len: Float) {
        val path = Path()
        // Top-left
        path.moveTo(r.left, r.top + len); path.lineTo(r.left, r.top); path.lineTo(r.left + len, r.top)
        // Top-right
        path.moveTo(r.right - len, r.top); path.lineTo(r.right, r.top); path.lineTo(r.right, r.top + len)
        // Bottom-left
        path.moveTo(r.left, r.bottom - len); path.lineTo(r.left, r.bottom); path.lineTo(r.left + len, r.bottom)
        // Bottom-right
        path.moveTo(r.right - len, r.bottom); path.lineTo(r.right, r.bottom); path.lineTo(r.right, r.bottom - len)
        canvas.drawPath(path, bracketPaint)
    }
}

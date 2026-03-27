package com.bookreader.app.camera

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Draws a dynamic quadrilateral that tracks the detected book page in real time.
 * The rectangle follows the page at any angle.
 *
 *  SEARCHING — no rectangle; shows a hint label
 *  PARTIAL   — red outline + transparent fill; page not fully in view
 *  ALIGNED   — green outline + transparent fill; entire page visible
 *
 * Corner positions animate smoothly over 300 ms using ValueAnimator so the
 * rectangle glides fluidly between AI-detected positions.
 */
class PageDetectionOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var state = PageDetectionState.SEARCHING

    // Currently displayed corners (animated): x0,y0, x1,y1, x2,y2, x3,y3 in view pixels
    private var displayCorners: FloatArray? = null

    private var currentAnimator: ValueAnimator? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.CENTER
        setShadowLayer(8f, 0f, 2f, Color.BLACK)
    }

    /**
     * Called from the main thread (via LiveData observers) with fresh detection data.
     * [corners] is a FloatArray of 8 values: x0,y0, x1,y1, x2,y2, x3,y3, or null when not detected.
     */
    fun updateDetection(newState: PageDetectionState, corners: FloatArray?) {
        state = newState

        if (corners == null || corners.size != 8) {
            currentAnimator?.cancel()
            displayCorners = null
            invalidate()
            return
        }

        // Animate from current display position to the new AI-detected position
        val from = displayCorners?.clone() ?: corners.clone()

        currentAnimator?.cancel()
        currentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIM_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedFraction
                val d = displayCorners ?: FloatArray(8).also { displayCorners = it }
                for (i in from.indices) {
                    d[i] = from[i] + (corners[i] - from[i]) * t
                }
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val c = displayCorners
        if (c == null || state == PageDetectionState.SEARCHING) {
            canvas.drawText(
                "Point camera at a book page",
                width / 2f,
                height / 2f,
                labelPaint
            )
            return
        }

        when (state) {
            PageDetectionState.PARTIAL -> {
                fillPaint.color   = 0x33FF2222.toInt()
                strokePaint.color = Color.RED
            }
            PageDetectionState.ALIGNED -> {
                fillPaint.color   = 0x3322DD55.toInt()
                strokePaint.color = 0xFF22CC44.toInt()
            }
            else -> return
        }

        val path = Path().apply {
            moveTo(c[0], c[1])
            lineTo(c[2], c[3])
            lineTo(c[4], c[5])
            lineTo(c[6], c[7])
            close()
        }
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)

        // Label centered below the detected rectangle
        val centerX = (c[0] + c[2] + c[4] + c[6]) / 4f
        val bottomY = maxOf(c[1], c[3], c[5], c[7])
        val labelY  = (bottomY + 56f).coerceAtMost(height.toFloat() - 8f)
        val label   = if (state == PageDetectionState.ALIGNED)
            "Page aligned — hold steady"
        else
            "Move closer — show full page"
        canvas.drawText(label, centerX, labelY, labelPaint)
    }

    companion object {
        private const val ANIM_DURATION_MS = 300L
    }
}

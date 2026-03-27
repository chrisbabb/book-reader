package com.bookreader.app.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Draws a dynamic rectangle that tracks the detected book page in real time.
 *
 *  SEARCHING — no rectangle; shows a "Point camera at a book page" hint
 *  PARTIAL   — solid red rectangle around the detected page area
 *  ALIGNED   — solid green rectangle; entire page is visible
 */
class PageDetectionOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var state = PageDetectionState.SEARCHING
    private var pageRect: RectF? = null

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.CENTER
        setShadowLayer(8f, 0f, 2f, Color.BLACK)
    }

    fun updateDetection(newState: PageDetectionState, newRect: RectF?) {
        state = newState
        pageRect = newRect
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val rect = pageRect
        when {
            state == PageDetectionState.SEARCHING || rect == null -> {
                // Just show a hint label centered on screen
                canvas.drawText(
                    "Point camera at a book page",
                    width / 2f,
                    height / 2f,
                    labelPaint
                )
            }
            state == PageDetectionState.PARTIAL -> {
                // Semi-transparent red fill + red border
                fillPaint.color = 0x33FF2222.toInt()
                boxPaint.color = Color.RED
                canvas.drawRect(rect, fillPaint)
                canvas.drawRect(rect, boxPaint)
                // Label below the rect
                val labelY = (rect.bottom + 56f).coerceAtMost(height.toFloat() - 8f)
                canvas.drawText("Move closer — show full page", rect.centerX(), labelY, labelPaint)
            }
            state == PageDetectionState.ALIGNED -> {
                // Semi-transparent green fill + green border
                fillPaint.color = 0x3322DD44.toInt()
                boxPaint.color = 0xFF22CC44.toInt()
                canvas.drawRect(rect, fillPaint)
                canvas.drawRect(rect, boxPaint)
                val labelY = (rect.bottom + 56f).coerceAtMost(height.toFloat() - 8f)
                canvas.drawText("Page aligned — hold steady", rect.centerX(), labelY, labelPaint)
            }
        }
    }
}

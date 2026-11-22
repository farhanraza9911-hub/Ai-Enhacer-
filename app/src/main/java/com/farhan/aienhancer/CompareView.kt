package com.farhan.aienhancer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Before/After comparison slider. Left = original, right = enhanced; beech ka handle drag karein.
 * [zoomable] = true (full-screen) mein do ungli se zoom aur ek ungli se pan bhi hota hai.
 */
class CompareView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var zoomable = false

    private var before: Bitmap? = null
    private var after: Bitmap? = null
    private var split = 0.5f                 // 0..1 (view width ka hissa)

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(2f)
        setShadowLayer(dp(3f), 0f, 0f, Color.argb(160, 0, 0, 0))
    }
    private val knobFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val knobArrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4F46E5")
        strokeWidth = dp(2.2f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0) }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(12f)
    }
    private val fit = Matrix()
    private val user = Matrix()              // zoom/pan (zoomable mode)
    private val drawM = Matrix()
    private val dest = RectF()

    private var dragging = false
    private var panning = false
    private var lastX = 0f
    private var lastY = 0f
    private var zoom = 1f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val nz = (zoom * d.scaleFactor).coerceIn(1f, 8f)
            val f = nz / zoom
            zoom = nz
            user.postScale(f, f, d.focusX, d.focusY)
            clampPan()
            invalidate()
            return true
        }
    })

    /** [after] null ho to sirf [before] dikhta hai. */
    fun setBitmaps(before: Bitmap?, after: Bitmap?) {
        this.before = before
        this.after = after
        invalidate()
    }

    fun resetZoom() {
        user.reset()
        zoom = 1f
        invalidate()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun baseBitmap(): Bitmap? = after ?: before

    private fun updateFit() {
        val b = baseBitmap() ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val s = min(vw / b.width, vh / b.height)
        val dx = (vw - b.width * s) / 2f
        val dy = (vh - b.height * s) / 2f
        fit.reset()
        fit.postScale(s, s)
        fit.postTranslate(dx, dy)
    }

    private fun clampPan() {
        val b = baseBitmap() ?: return
        drawM.set(fit)
        drawM.postConcat(user)
        val r = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        drawM.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else if (r.left > 0) dx = -r.left
        else if (r.right < width) dx = width - r.right
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else if (r.top > 0) dy = -r.top
        else if (r.bottom < height) dy = height - r.bottom
        user.postTranslate(dx, dy)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        updateFit()
    }

    override fun onDraw(canvas: Canvas) {
        val a = after
        val bf = before
        val base = baseBitmap() ?: return
        updateFit()
        drawM.set(fit)
        if (zoomable) drawM.postConcat(user)

        // Dono bitmaps ko base ke frame mein draw karte hain (size alag ho sakti hai, aspect wahi hota hai)
        dest.set(0f, 0f, base.width.toFloat(), base.height.toFloat())

        val sx = split * width
        if (a != null && bf != null) {
            canvas.save()
            canvas.clipRect(0f, 0f, sx, height.toFloat())
            canvas.concat(drawM)
            canvas.drawBitmap(bf, null, dest, paint)
            canvas.restore()

            canvas.save()
            canvas.clipRect(sx, 0f, width.toFloat(), height.toFloat())
            canvas.concat(drawM)
            canvas.drawBitmap(a, null, dest, paint)
            canvas.restore()

            // handle
            canvas.drawLine(sx, 0f, sx, height.toFloat(), linePaint)
            val cy = height / 2f
            val kr = dp(18f)
            canvas.drawCircle(sx, cy, kr, knobFill)
            val ar = dp(5f)
            canvas.drawLine(sx - ar - dp(2f), cy, sx - dp(2f) - 0f, cy - ar, knobArrow)
            canvas.drawLine(sx - ar - dp(2f), cy, sx - dp(2f), cy + ar, knobArrow)
            canvas.drawLine(sx + ar + dp(2f), cy, sx + dp(2f), cy - ar, knobArrow)
            canvas.drawLine(sx + ar + dp(2f), cy, sx + dp(2f), cy + ar, knobArrow)

            drawLabel(canvas, "Original", dp(10f), true)
            drawLabel(canvas, "Enhanced", width - dp(10f), false)
        } else {
            canvas.save()
            canvas.concat(drawM)
            canvas.drawBitmap(base, null, dest, paint)
            canvas.restore()
        }
    }

    private fun drawLabel(c: Canvas, text: String, x: Float, left: Boolean) {
        val w = labelText.measureText(text)
        val padH = dp(8f)
        val h = dp(22f)
        val top = dp(10f)
        val l = if (left) x else x - w - 2 * padH
        c.drawRoundRect(l, top, l + w + 2 * padH, top + h, dp(11f), dp(11f), labelBg)
        c.drawText(text, l + padH, top + h / 2f + labelText.textSize / 3f, labelText)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (before == null || after == null) return false
        if (zoomable) scaleDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = ev.x
                lastY = ev.y
                val nearHandle = abs(ev.x - split * width) < dp(40f)
                dragging = !zoomable || nearHandle || zoom <= 1.01f
                panning = !dragging
                if (dragging) moveSplit(ev.x)
            }
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount == 1 && !scaleDetector.isInProgress) {
                    if (dragging) {
                        moveSplit(ev.x)
                    } else if (panning) {
                        user.postTranslate(ev.x - lastX, ev.y - lastY)
                        clampPan()
                        invalidate()
                    }
                    lastX = ev.x
                    lastY = ev.y
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                dragging = false
                panning = false
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // ek ungli uthne par jo bachi usse pan jari rakhein
                val i = if (ev.actionIndex == 0) 1 else 0
                lastX = ev.getX(i)
                lastY = ev.getY(i)
                panning = zoomable
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                panning = false
                if (ev.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun moveSplit(x: Float) {
        split = max(0f, min(1f, x / max(1, width)))
        invalidate()
    }
}

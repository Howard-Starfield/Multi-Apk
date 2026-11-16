package dev.clonelab.app.switcher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** A 2 x 3 grid of dots, the usual "drag me" grip, centred in its bounds. */
class GripDotsDrawable(private val dotRadius: Float, private val gap: Float, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    override fun draw(canvas: Canvas) {
        val step = dotRadius * 2 + gap
        val left = bounds.exactCenterX() - step / 2
        val top = bounds.exactCenterY() - step
        for (row in 0 until ROWS) {
            for (column in 0 until COLUMNS) {
                canvas.drawCircle(left + column * step, top + row * step, dotRadius, paint)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val ROWS = 3
        const val COLUMNS = 2
    }
}

package dev.clonelab.app.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/** A rounded colour tile with a large letter and the clone number underneath. */
class TextIconDrawable(
    private val letter: String,
    private val number: Int,
    private val color: Int,
) : Drawable() {

    private val tile = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@TextIconDrawable.color }
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        val radius = size * CORNER
        canvas.drawRoundRect(RectF(b), radius, radius, tile)
        letterPaint.textSize = size * LETTER_SIZE
        numberPaint.textSize = size * NUMBER_SIZE
        val cx = b.exactCenterX()
        canvas.drawText(letter, cx, b.top + size * LETTER_BASELINE, letterPaint)
        canvas.drawText(number.toString(), cx, b.top + size * NUMBER_BASELINE, numberPaint)
    }

    override fun setAlpha(alpha: Int) {
        tile.alpha = alpha
        letterPaint.alpha = alpha
        numberPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        tile.colorFilter = colorFilter
        letterPaint.colorFilter = colorFilter
        numberPaint.colorFilter = colorFilter
    }

    @Deprecated("Required by Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val CORNER = 0.22f
        const val LETTER_SIZE = 0.52f
        const val NUMBER_SIZE = 0.22f
        const val LETTER_BASELINE = 0.62f
        const val NUMBER_BASELINE = 0.88f
    }
}

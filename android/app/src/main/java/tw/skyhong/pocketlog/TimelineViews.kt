package tw.skyhong.pocketlog

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Proportional stacked bar for category totals. The legend rows beside it carry the readable values. */
@SuppressLint("ViewConstructor")
class CategoryBarView(context: Context, private val segments: List<Pair<Int, Long>>) : View(context) {
    private val ui = Ui(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val rect = RectF()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onMeasure(widthSpec: Int, heightSpec: Int) = setMeasuredDimension(MeasureSpec.getSize(widthSpec), ui.dp(14))
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        clip.reset(); rect.set(0f, 0f, w.toFloat(), h.toFloat()); clip.addRoundRect(rect, h / 2f, h / 2f, Path.Direction.CW)
    }
    override fun onDraw(canvas: Canvas) {
        val total = segments.sumOf { it.second }.coerceAtLeast(1)
        canvas.save(); canvas.clipPath(clip)
        paint.color = Palette.HAIRLINE; canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        var x = 0f
        segments.forEach { (color, value) ->
            val w = width * value.toFloat() / total
            paint.color = color; canvas.drawRect(x, 0f, x + w, height.toFloat(), paint)
            x += w
        }
        canvas.restore()
    }
}

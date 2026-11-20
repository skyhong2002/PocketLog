package tw.skyhong.pocketlog

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

internal data class ChartSpan(val start: Float, val end: Float, val color: Int)

@SuppressLint("ViewConstructor")
internal class ActivityTrack(
    context: Context,
    private val spans: List<ChartSpan>,
    private val available: Boolean,
) : View(context) {
    private val ui = Ui(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val middle = height / 2f
        val radius = ui.dp(5).toFloat()
        paint.color = Palette.HAIRLINE
        if (available) {
            canvas.drawRoundRect(0f, middle - radius, width.toFloat(), middle + radius, radius, radius, paint)
        } else {
            canvas.drawRect(0f, middle, width.toFloat(), middle + ui.dp(1), paint)
        }
        spans.forEach { span ->
            paint.color = span.color
            val left = span.start * width
            val right = span.end * width
            canvas.drawRect(left, middle - radius, right, middle + radius, paint)
        }
        paint.color = Palette.BORDER
        for (step in 1..3) {
            val x = width * step / 4f
            canvas.drawLine(x, middle - radius - ui.dp(3), x, middle + radius + ui.dp(3), paint)
        }
    }
}

@SuppressLint("ViewConstructor")
internal class CategoryRing(
    context: Context,
    private val slices: List<Pair<Int, Long>>,
) : View(context) {
    private val ui = Ui(context)
    private val total = slices.sumOf { it.second }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ui.dp(13).toFloat()
    }
    private val amount = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.INK
        textAlign = Paint.Align.CENTER
        textSize = ui.sp(14f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val duration = TimelineMath.duration(total)

    init {
        contentDescription = "已記錄總時間，$duration"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(cx, cy) - arc.strokeWidth / 2 - ui.dp(4)).coerceAtLeast(0f)
        if (radius == 0f || total <= 0) return
        var start = -90f
        slices.forEach { (color, ms) ->
            val sweep = 360f * (ms.toDouble() / total).toFloat()
            arc.color = color
            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, start, sweep, false, arc)
            start += sweep
        }
        val valueWidth = amount.measureText(duration)
        val textWidth = radius * 1.6f
        val oldSize = amount.textSize
        if (valueWidth > textWidth) amount.textSize *= textWidth / valueWidth
        canvas.drawText(duration, cx, cy - (amount.ascent() + amount.descent()) / 2, amount)
        amount.textSize = oldSize
    }
}

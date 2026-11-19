package tw.skyhong.pocketlog

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import java.time.LocalDate
import java.time.ZoneId

/** Shared duration axis for the week. The existing day rows expose the values and actions to TalkBack. */
@SuppressLint("ViewConstructor")
class WeekDurationChart(
    context: Context,
    private val days: List<WeekDay>,
    private val today: LocalDate,
    zone: ZoneId,
) : View(context) {
    private val ui = Ui(context)
    private val amounts = days.map { day ->
        if (!day.available || day.date > today) 0L else {
            TimelineMath.coveredInDay(day.date, zone, day.items.map { TimelineMath.Interval(it.start, it.end) })
        }
    }
    private val hourMs = 3_600_000L
    private val maxHours = (((amounts.maxOrNull() ?: 0L) + hourMs - 1) / hourMs).coerceAtLeast(1)
    private val dailyScale = maxHours * hourMs
    private val axisLabels = listOf(TimelineMath.duration(dailyScale), TimelineMath.duration(dailyScale / 2), "0")
    private val labels = days.map { "${it.date.monthValue}/${it.date.dayOfMonth}" }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.BORDER; strokeWidth = ui.dp(1).toFloat() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.MUTED; textSize = ui.sp(11f) }
    private var shade: LinearGradient? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = ui.dp(122) + (text.textSize * 2).toInt()
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        shade = if (height > 0) {
            LinearGradient(0f, 0f, 0f, height.toFloat(),
                Palette.tint(Palette.ACCENT, .58f), Palette.ACCENT_DARK, Shader.TileMode.CLAMP)
        } else null
    }

    override fun onDraw(canvas: Canvas) {
        if (days.isEmpty()) return
        val top = text.textSize
        val bottom = height - text.textSize - ui.dp(8)
        if (bottom <= top) return
        val axisWidth = axisLabels.maxOf { text.measureText(it) }
        val showAxis = axisWidth < width * .35f
        val left = if (showAxis) axisWidth + ui.dp(8) else 0f
        val dayWidth = (width - left) / days.size
        for ((index, label) in axisLabels.withIndex()) {
            val y = top + (bottom - top) * index / (axisLabels.size - 1)
            canvas.drawLine(left, y, width.toFloat(), y, grid)
            if (showAxis) canvas.drawText(label, axisWidth - text.measureText(label), y + text.textSize * .25f, text)
        }
        for ((index, day) in days.withIndex()) {
            val center = left + dayWidth * (index + .5f)
            val amount = amounts[index]
            val halfBar = dayWidth * .29f
            val barTop = bottom - (bottom - top) * amount / dailyScale
            val radius = ui.dp(4).toFloat()
            fill.shader = if (amount > 0) shade else null
            fill.color = if (amount > 0) Palette.ACCENT else if (day.available && day.date <= today) Palette.ACCENT_SOFT else Palette.HAIRLINE
            canvas.drawRoundRect(center - halfBar, minOf(barTop, bottom - ui.dp(2)), center + halfBar, bottom, radius, radius, fill)
            val full = labels[index]
            val label = if (text.measureText(full) <= dayWidth - ui.dp(2)) full else day.date.dayOfMonth.toString()
            if (text.measureText(label) <= dayWidth - ui.dp(2)) {
                canvas.drawText(label, center - text.measureText(label) / 2, height - ui.dp(2).toFloat(), text)
            }
        }
    }
}

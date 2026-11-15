package tw.skyhong.pocketlog

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** One real activity interval to draw. Titles/colors come from the user's journal and workspace. */
data class TimelineItem(val start: Long, val end: Long, val title: String, val label: String, val color: Int)

private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Proportional 04:00–04:00 day canvas. Empty hours stay empty; overlaps split into lanes. The canvas is
 * hidden from accessibility services because the activity list (default with TalkBack) carries the same
 * cards as individually actionable rows.
 */
@SuppressLint("ViewConstructor")
class DayTimelineView(context: Context, day: LocalDate, private val zone: ZoneId, private val items: List<TimelineItem>, private val now: Long, private val open: (Int) -> Unit) : View(context) {
    private val ui = Ui(context)
    private val dayStart = TimelineMath.dayStart(day, zone)
    private val dayEnd = TimelineMath.dayStart(day.plusDays(1), zone)
    private val dayHours = (dayEnd - dayStart) / 3_600_000f
    private val ticks = (0..dayHours.toInt()).map { dayStart + it * 3_600_000L }.let { if (it.last() == dayEnd) it else it + dayEnd }
    private val hourLabels = ticks.map { java.time.Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern(if (dayHours == 24f) "HH:mm" else "HH:mm XXX")) }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ui.sp(14f); color = Palette.INK; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ui.sp(12f); color = Palette.MUTED }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.BORDER; strokeWidth = ui.dp(1).toFloat(); style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(ui.dp(4).toFloat(), ui.dp(4).toFloat()), 0f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hourHeight = maxOf(ui.dp(64).toFloat(), (titlePaint.textSize + metaPaint.textSize) * 2.6f)
    private val gutter = hourLabels.maxOf { metaPaint.measureText(it) } + ui.dp(14)
    private val top = ui.dp(10).toFloat()
    private val rects = items.map { RectF() }
    private val path = Path()
    private var pressed = -1
    private var downX = 0f; private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }

    fun yFor(ms: Long): Int = (top + (ms - dayStart).toFloat() / 3_600_000f * hourHeight).toInt()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) = setMeasuredDimension(MeasureSpec.getSize(widthSpec), (top * 2 + hourHeight * dayHours).toInt())

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val left = gutter + ui.dp(4); val available = (w - left - ui.dp(2)).coerceAtLeast(0f)
        val minimumHeight = maxOf(ui.dp(20).toFloat(), titlePaint.textSize + ui.dp(8))
        val bottom = yFor(dayEnd).toFloat()
        val visible = items.indices.filter { items[it].end > dayStart && items[it].start < dayEnd && items[it].end > items[it].start }
        rects.forEach(RectF::setEmpty)
        val vertical = visible.map { index ->
            val item = items[index]
            val from = item.start.coerceIn(dayStart, dayEnd); val to = item.end.coerceIn(dayStart, dayEnd)
            val height = maxOf(minimumHeight, (yFor(to) - yFor(from) - ui.dp(2)).toFloat()).coerceAtMost(bottom - top)
            // Reserve the rendered minimum size, including at the bottom day boundary.
            val y = (yFor(from).toFloat() + 1).coerceAtMost(bottom - height)
            y to height
        }
        val placements = TimelineMath.lanes(vertical.map { (y, height) -> TimelineMath.Interval(y.toLong(), kotlin.math.ceil(y + height).toLong()) })
        visible.forEachIndexed { position, index ->
            val p = placements[position]; val laneWidth = available / p.lanes
            val gap = minOf(ui.dp(3).toFloat(), laneWidth * .2f)
            val (y, height) = vertical[position]
            rects[index].set(left + p.lane * laneWidth, y, left + (p.lane + 1) * laneWidth - if (p.lane + 1 < p.lanes) gap else 0f, y + height)
        }
    }

    override fun onDraw(canvas: Canvas) {
        ticks.forEachIndexed { index, at ->
            val y = yFor(at).toFloat()
            path.reset(); path.moveTo(gutter, y); path.lineTo(width.toFloat(), y); canvas.drawPath(path, linePaint)
            canvas.drawText(hourLabels[index], 0f, y + metaPaint.textSize * .36f, metaPaint)
        }
        val radius = ui.dp(8).toFloat(); val stripe = ui.dp(4).toFloat()
        items.forEachIndexed { i, item ->
            val r = rects[i]
            if (r.width() <= 0f || item.end <= dayStart || item.start >= dayEnd) return@forEachIndexed
            fill.color = Palette.tint(item.color, if (i == pressed) .48f else .30f); canvas.drawRoundRect(r, radius, radius, fill)
            canvas.save(); canvas.clipRect(r.left, r.top, r.left + stripe, r.bottom)
            fill.color = Palette.darken(item.color, .86f); canvas.drawRoundRect(r, radius, radius, fill); canvas.restore()
            // Keep a text cue on narrow short cards at large font sizes. Truly dense
            // clusters still have the complete, accessible list as their text view.
            val pad = minOf(ui.dp(10).toFloat(), (r.width() - stripe).coerceAtLeast(0f) * .1f)
            val textWidth = r.width() - stripe - pad * 2
            if (textWidth < titlePaint.measureText("…") || r.height() < titlePaint.textSize + ui.dp(4)) return@forEachIndexed
            val range = "${clock.format(java.time.Instant.ofEpochMilli(item.start).atZone(zone))}–${clock.format(java.time.Instant.ofEpochMilli(item.end).atZone(zone))}"
            val twoLines = r.height() >= titlePaint.textSize + metaPaint.textSize + ui.dp(14)
            val rangeWidth = metaPaint.measureText(range)
            val inline = !twoLines && textWidth > rangeWidth + ui.dp(80)
            val titleWidth = if (inline) textWidth - rangeWidth - ui.dp(8) else textWidth
            val baseline = r.top + (if (twoLines) ui.dp(6) + titlePaint.textSize else (r.height() + titlePaint.textSize * .72f) / 2)
            canvas.save(); canvas.clipRect(r)
            val title = TextUtils.ellipsize(item.title, titlePaint, titleWidth, TextUtils.TruncateAt.END).toString().ifEmpty { "…" }
            canvas.drawText(title, r.left + stripe + pad, baseline, titlePaint)
            if (inline) canvas.drawText(range, r.right - pad - rangeWidth, baseline, metaPaint)
            if (twoLines) canvas.drawText(TextUtils.ellipsize("$range · ${item.label}", metaPaint, textWidth, TextUtils.TruncateAt.END).toString(), r.left + stripe + pad, baseline + metaPaint.textSize + ui.dp(4), metaPaint)
            canvas.restore()
        }
        if (now in dayStart until dayEnd) {
            val y = yFor(now).toFloat(); fill.color = Palette.ACCENT
            canvas.drawRect(gutter, y - ui.dp(1), width.toFloat(), y + ui.dp(1), fill)
            canvas.drawCircle(gutter, y, ui.dp(4).toFloat(), fill)
        }
    }

    @SuppressLint("ClickableViewAccessibility") // Card actions are exposed through the accessible list.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = rects.indices.lastOrNull { rects[it].contains(event.x, event.y) } ?: -1
                downX = event.x; downY = event.y; invalidate(); return pressed >= 0
            }
            MotionEvent.ACTION_MOVE -> if (pressed >= 0 && (abs(event.x - downX) > slop || abs(event.y - downY) > slop)) { pressed = -1; invalidate() }
            MotionEvent.ACTION_UP -> {
                val hit = pressed; pressed = -1; invalidate()
                if (hit >= 0 && rects[hit].contains(event.x, event.y)) { playSoundEffect(android.view.SoundEffectConstants.CLICK); open(hit) }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return super.onTouchEvent(event)
    }
}

/** One week day in the overview grid. [available] is false outside the readable 29-day window. */
data class WeekDay(val date: LocalDate, val available: Boolean, val offline: Boolean, val items: List<TimelineItem>)

/** Compact seven-column week; tap a column to open that day. Per-day rows below carry the text equivalent. */
@SuppressLint("ViewConstructor")
class WeekTimelineView(context: Context, private val days: List<WeekDay>, private val selected: LocalDate, private val today: LocalDate, private val zone: ZoneId, private val now: Long, private val pick: (LocalDate) -> Unit) : View(context) {
    private val ui = Ui(context)
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ui.sp(12f); color = Palette.MUTED }
    private val number = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ui.sp(15f); color = Palette.INK; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressAxis = days.firstOrNull()?.let { TimelineMath.weekUsesProgressAxis(it.date, zone) } ?: false
    private val axisTicks = if (progressAxis) {
        (0..4).map { it / 4f to "${it * 25}%" }
    } else {
        (0..20 step 4).map { it / 24f to "%02d".format((it + TimelineMath.DAY_START_HOUR) % 24) }
    }
    private val gutter = axisTicks.maxOf { label.measureText(it.second) } + ui.dp(10)
    // Transition-day badges get their own row, including at large system font sizes.
    private val header = label.textSize + number.textSize + ui.dp(22) + if (progressAxis) label.textSize + ui.dp(6) else 0f
    private val hour = maxOf(ui.dp(18).toFloat(), label.textSize * 1.4f)
    private val names = listOf("一", "二", "三", "四", "五", "六", "日")
    private val rect = RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) = setMeasuredDimension(MeasureSpec.getSize(widthSpec), (header + hour * 24 + maxOf(ui.dp(8).toFloat(), label.textSize)).toInt())

    private fun columnWidth() = (width - gutter) / 7f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Compact chart labels fit their column; the day rows keep the full system font size.
        number.textSize = ui.sp(15f)
        val available = (columnWidth() - ui.dp(4)).coerceAtLeast(1f)
        val measured = number.measureText("31")
        if (measured > available) number.textSize *= available / measured
    }

    override fun onDraw(canvas: Canvas) {
        val col = columnWidth()
        label.textAlign = Paint.Align.LEFT
        for ((fraction, text) in axisTicks) {
            val y = header + fraction * 24 * hour
            paint.color = Palette.HAIRLINE; canvas.drawRect(gutter, y, width.toFloat(), y + ui.dp(1), paint)
            canvas.drawText(text, 0f, y + label.textSize * .36f, label)
        }
        paint.color = Palette.HAIRLINE
        canvas.drawRect(gutter, header + 24 * hour, width.toFloat(), header + 24 * hour + ui.dp(1), paint)
        days.forEachIndexed { i, day ->
            val x = gutter + i * col
            val isSelected = day.date == selected
            label.textAlign = Paint.Align.CENTER
            canvas.drawText(names[day.date.dayOfWeek.value - 1], x + col / 2, label.textSize + ui.dp(2), label)
            val cy = label.textSize + ui.dp(8) + number.textSize * .6f
            if (isSelected) { paint.color = Palette.ACCENT; canvas.drawCircle(x + col / 2, cy, minOf(number.textSize * .95f, (col / 2 - ui.dp(2)).coerceAtLeast(0f)), paint) }
            number.color = when { isSelected -> android.graphics.Color.WHITE; day.available -> Palette.INK; else -> Palette.MUTED }
            canvas.drawText(day.date.dayOfMonth.toString(), x + col / 2, cy + number.textSize * .36f, number)
            if (day.date == today && !isSelected) { paint.color = Palette.ACCENT; canvas.drawRect(x + col / 2 - ui.dp(8), cy + number.textSize * .8f, x + col / 2 + ui.dp(8), cy + number.textSize * .8f + ui.dp(2), paint) }
            rect.set(x + ui.dp(2), header, x + col - ui.dp(2), header + hour * 24)
            paint.color = if (isSelected) Palette.ACCENT_SOFT else if (day.available) Palette.CANVAS else Palette.HAIRLINE
            canvas.drawRoundRect(rect, ui.dp(6).toFloat(), ui.dp(6).toFloat(), paint)
            val start = TimelineMath.dayStart(day.date, zone)
            val length = TimelineMath.dayLength(day.date, zone)
            if (length != 86_400_000L) {
                val hours = length / 3_600_000.0
                val badge = if (length % 3_600_000L == 0L) "${length / 3_600_000L}h" else "${hours}h"
                // The readable day rows below retain the full duration when narrow columns truncate.
                val visible = TextUtils.ellipsize(badge, label, (col - ui.dp(4)).coerceAtLeast(0f), TextUtils.TruncateAt.END)
                canvas.drawText(visible.toString(), x + col / 2, header - ui.dp(3), label)
            }
            day.items.forEach { item ->
                val top = header + TimelineMath.dayFraction(day.date, zone, item.start) * 24 * hour
                val bottom = header + TimelineMath.dayFraction(day.date, zone, item.end) * 24 * hour
                if (bottom <= top) return@forEach
                paint.color = item.color
                rect.set(x + ui.dp(4), top, x + col - ui.dp(4), maxOf(bottom, top + ui.dp(2)))
                canvas.drawRoundRect(rect, ui.dp(2).toFloat(), ui.dp(2).toFloat(), paint)
            }
            if (day.date == today && now >= start) {
                val y = header + TimelineMath.dayFraction(day.date, zone, now) * 24 * hour
                paint.color = Palette.ACCENT_DARK; canvas.drawRect(x + ui.dp(2), y - ui.dp(1), x + col - ui.dp(2), y + ui.dp(1), paint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility") // The per-day list below offers the same navigation.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) return true
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val index = ((event.x - gutter) / columnWidth()).toInt()
            val day = days.getOrNull(index)
            if (event.x >= gutter && day != null && day.available) { playSoundEffect(android.view.SoundEffectConstants.CLICK); pick(day.date) }
            return true
        }
        return super.onTouchEvent(event)
    }
}

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

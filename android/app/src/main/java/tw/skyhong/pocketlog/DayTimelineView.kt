package tw.skyhong.pocketlog

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup

/** Empty hours share a fixed scale; only hours with crowded cards gain space. */
@SuppressLint("ViewConstructor")
internal class DayTimelineView(context: Context) : ViewGroup(context) {
    private val ui = Ui(context)
    private val hourHeight = ui.dp(96)
    private val gutter = ui.dp(60)
    private val inset = ui.dp(24)
    private val gap = ui.dp(6)
    private val axis = Paint().apply {
        color = Palette.BORDER
        strokeWidth = ui.dp(1).toFloat()
    }
    private val ticks = mutableListOf<Long>()
    private val blocks = mutableListOf<TimelineMath.Interval>()
    private var tickPositions = emptyList<Int>()
    private var blockPositions = emptyList<Int>()

    init { setWillNotDraw(false) }

    fun y(at: Long): Int = tickPositions.getOrNull(ticks.indexOf(at)) ?: inset

    fun scrollAnchor(y: Int): Pair<Long, Int>? {
        if (tickPositions.size < 2 || y !in tickPositions.first() until tickPositions.last()) return null
        val index = tickPositions.indexOfLast { it <= y }
        return ticks[index] to (y - tickPositions[index])
    }

    fun scrollPosition(anchor: Pair<Long, Int>): Int {
        val (hour, offset) = anchor
        val index = ticks.indexOf(hour)
        if (index < 0 || index + 1 >= tickPositions.size) return y(hour)
        return tickPositions[index] + offset.coerceAtMost(tickPositions[index + 1] - tickPositions[index] - 1)
    }

    fun addTick(at: Long, view: View) {
        ticks.add(at)
        addView(view, LayoutParams(gutter, inset * 2))
    }

    fun setBlocks(times: List<TimelineMath.Interval>, views: List<View>) {
        removeViews(ticks.size, childCount - ticks.size)
        blocks.clear()
        blocks.addAll(times)
        views.forEach { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val cardWidth = (width - gutter - ui.dp(2)).coerceAtLeast(0)
        for (i in ticks.indices) getChildAt(i).measure(exact(gutter), exact(inset * 2))
        val heights = blocks.mapIndexed { index, time ->
            val child = getChildAt(ticks.size + index)
            child.measure(exact(cardWidth), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val durationHeight = TimelineMath.timeOffset(time.start, time.end, hourHeight).coerceAtMost(ui.dp(144))
            val height = maxOf(child.measuredHeight, durationHeight, ui.dp(60))
            child.measure(exact(cardWidth), exact(height))
            height
        }
        val geometry = TimelineMath.readableAxis(ticks, blocks.zip(heights), hourHeight, inset, gap)
        tickPositions = geometry.ticks
        blockPositions = geometry.blocks
        setMeasuredDimension(width, resolveSize(tickPositions.last() + inset, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        ticks.forEachIndexed { i, at -> getChildAt(i).layout(0, y(at) - inset, gutter, y(at) + inset) }
        blockPositions.forEachIndexed { i, top ->
            val card = getChildAt(ticks.size + i)
            card.layout(gutter, top, gutter + card.measuredWidth, top + card.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        tickPositions.forEach { at -> canvas.drawLine(gutter.toFloat(), at.toFloat(), width.toFloat(), at.toFloat(), axis) }
        canvas.drawLine(gutter.toFloat(), inset.toFloat(), gutter.toFloat(), tickPositions.last().toFloat(), axis)
    }

    private fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
}

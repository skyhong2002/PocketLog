package tw.skyhong.pocketlog

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup

/** A scrollable day measured in elapsed time. Children share the axis without reserving row space. */
@SuppressLint("ViewConstructor")
internal class DayTimelineView(context: Context, private val from: Long, private val to: Long) : ViewGroup(context) {
    private data class Block(val start: Long, val end: Long, val lane: Int, val lanes: Int)
    private val ui = Ui(context)
    companion object { const val HOUR_DP = 96 }

    private val hourHeight = ui.dp(HOUR_DP)
    private val gutter = ui.dp(60)
    private val inset = ui.dp(24)
    private val axis = Paint().apply {
        color = Palette.BORDER
        strokeWidth = ui.dp(1).toFloat()
    }
    private val blocks = mutableListOf<Block>()
    private val ticks = mutableListOf<Long>()

    init { setWillNotDraw(false) }

    fun y(at: Long): Int = inset + TimelineMath.timeOffset(from, at.coerceIn(from, to), hourHeight)

    fun addTick(at: Long, view: View) {
        ticks.add(at)
        addView(view, LayoutParams(gutter, inset * 2))
    }

    fun setBlocks(positions: List<Pair<TimelineMath.Interval, TimelineMath.Placement>>, views: List<View>) {
        removeViews(ticks.size, childCount - ticks.size)
        blocks.clear()
        positions.zip(views).forEach { (position, view) ->
            val (time, lane) = position
            blocks.add(Block(time.start, time.end, lane.lane, lane.lanes))
            addView(view, LayoutParams(0, 0))
        }
    }

    private fun left(block: Block, width: Int): Int =
        gutter + (width - gutter).coerceAtLeast(0) * block.lane / block.lanes

    private fun right(block: Block, width: Int): Int =
        (gutter + (width - gutter).coerceAtLeast(0) * (block.lane + 1) / block.lanes - ui.dp(2)).coerceAtLeast(left(block, width))

    private fun bottom(block: Block) = maxOf(y(block.start) + 1, y(block.end))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        for (i in ticks.indices) getChildAt(i).measure(exact(gutter), exact(inset * 2))
        blocks.forEachIndexed { i, block ->
            getChildAt(ticks.size + i).measure(exact(right(block, width) - left(block, width)), exact(bottom(block) - y(block.start)))
        }
        setMeasuredDimension(width, resolveSize(y(to) + inset, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        ticks.forEachIndexed { i, at -> getChildAt(i).layout(0, y(at) - inset, gutter, y(at) + inset) }
        blocks.forEachIndexed { i, block ->
            getChildAt(ticks.size + i).layout(left(block, width), y(block.start), right(block, width), bottom(block))
        }
    }

    override fun onDraw(canvas: Canvas) {
        ticks.forEach { at -> canvas.drawLine(gutter.toFloat(), y(at).toFloat(), width.toFloat(), y(at).toFloat(), axis) }
        canvas.drawLine(gutter.toFloat(), inset.toFloat(), gutter.toFloat(), y(to).toFloat(), axis)
    }

    private fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
}

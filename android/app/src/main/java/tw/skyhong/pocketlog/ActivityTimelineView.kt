package tw.skyhong.pocketlog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** A day-length timeline from actual intervals; no synthetic records for empty hours. */
class ActivityTimelineView(context: Context, private val cards: JSONArray, private val workspace: JSONObject?, date: LocalDate, private val open: (JSONObject) -> Unit) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hits = mutableListOf<Pair<RectF, JSONObject>>()
    private val start = date.atTime(4, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val end = date.plusDays(1).atTime(4, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    init { contentDescription = "${date} 活動時間軸。下方另有可操作的完整活動清單。"; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) { setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (1440 * density).toInt()) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); hits.clear()
        paint.textSize = 11 * density
        for (i in 0 until 24) {
            val y = i * 60 * density
            paint.color = Color.rgb(120, 120, 125); canvas.drawText("%02d:00".format((i + 4) % 24), 0f, y + 14 * density, paint)
            paint.color = Color.rgb(210, 210, 215); canvas.drawLine(48 * density, y, width.toFloat(), y, paint)
        }
        val styles = workspace?.optJSONArray("categories")
        val active = mutableListOf<Pair<Long, Int>>()
        for (i in 0 until cards.length()) {
            val card = cards.getJSONObject(i)
            val from = card.getLong("start_ms"); val to = card.getLong("end_ms")
            active.removeAll { it.first <= from }
            var lane = 0; while (active.any { it.second == lane }) lane++
            active += to to lane
            var color = Color.rgb(160, 150, 180)
            if (styles != null) for (j in 0 until styles.length()) {
                val style = styles.getJSONObject(j)
                if (style.getString("id") == card.getString("category")) color = runCatching { Color.parseColor(style.getString("color")) }.getOrDefault(color)
            }
            val y = (from - start).toFloat() / (end - start) * height
            val h = maxOf(22 * density, (to - from).toFloat() / (end - start) * height)
            val left = minOf((52 + lane * 12) * density, width * .7f)
            val rect = RectF(left, y, width.toFloat() - 3 * density, y + h - density)
            paint.color = color; canvas.drawRoundRect(rect, 5 * density, 5 * density, paint)
            canvas.save(); canvas.clipRect(rect)
            paint.color = Color.rgb(30, 30, 35); paint.textSize = 12 * density
            canvas.drawText(card.getString("title"), left + 8 * density, y + 16 * density, paint)
            canvas.restore(); hits += rect to card
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val hit = hits.lastOrNull { it.first.contains(event.x, event.y) }
            if (hit != null) { performClick(); open(hit.second); return true }
        }
        return event.action == MotionEvent.ACTION_DOWN || super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}

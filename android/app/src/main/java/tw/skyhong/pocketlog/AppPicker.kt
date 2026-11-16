package tw.skyhong.pocketlog

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import java.util.Locale

/** A local, temporary edit. Only Fill returns a new list; capture settings are saved separately. */
internal object AppPicker {
    const val MAX_APPS = 2000
    const val MAX_QUERY = 160
    data class App(val packageName: String, val label: String)

    class Selection(apps: List<App>, initial: Set<String>) {
        val apps = apps.take(MAX_APPS).distinctBy { it.packageName }
        private val chosen = initial.toMutableSet()
        val selected: Set<String> get() = chosen.toSet()

        fun matches(query: String): List<App> {
            val term = query.take(MAX_QUERY).trim().lowercase(Locale.ROOT)
            return apps.filter { it.label.lowercase(Locale.ROOT).contains(term) || it.packageName.lowercase(Locale.ROOT).contains(term) }
        }

        fun isSelected(app: App): Boolean = app.packageName in chosen
        fun toggle(app: App) {
            if (!chosen.remove(app.packageName)) chosen.add(app.packageName)
        }
    }

    fun show(activity: Activity, apps: List<App>, initial: Set<String>, onFill: (Set<String>) -> Unit): AlertDialog {
        val ui = Ui(activity)
        val state = Selection(apps, initial)
        val metrics = activity.windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val availableHeight = metrics.bounds.height() - insets.top - insets.bottom
        val content = BoundedColumn(activity, minOf(ui.dp(520), (availableHeight * .65f).toInt())).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), ui.dp(4), ui.dp(16), 0)
            setBackgroundColor(Palette.PAPER)
            isSaveEnabled = false
        }
        val search = EditText(activity).apply {
            hint = "搜尋 app 名稱或套件"
            contentDescription = "搜尋 app 名稱或套件"
            textSize = 16f
            setTextColor(Palette.INK)
            setHintTextColor(Palette.MUTED)
            backgroundTintList = ColorStateList.valueOf(Palette.ACCENT)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(MAX_QUERY))
            minimumHeight = ui.dp(48)
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        content.addView(search, ui.lp())
        val count = ui.text(content, "", 14f, Palette.ACCENT_DARK, top = 8, bottom = 8).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val results = FrameLayout(activity)
        content.addView(results, ui.lp(height = 0, weight = 1f))
        val list = ListView(activity).apply {
            divider = null
            setSelector(ui.ripple(null, 10f))
            isSaveEnabled = false
        }
        val help = ui.text(null, "未列出的手動套件會保留。填入後請按「儲存清單」。", 13f, Palette.MUTED).apply {
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(12))
        }
        list.addHeaderView(help, null, false)
        results.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val emptyScroll = ScrollView(activity).apply { isFillViewport = true }
        val empty = ui.text(null, "", 15f, Palette.MUTED).apply { setPadding(ui.dp(4), ui.dp(16), ui.dp(4), ui.dp(16)) }
        emptyScroll.addView(empty)
        results.addView(emptyScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        var shown = state.matches("")
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView as? CheckedTextView ?: CheckedTextView(activity).apply {
                    textSize = 16f
                    setTextColor(Palette.INK)
                    minimumHeight = ui.dp(72)
                    setPadding(ui.dp(4), ui.dp(12), ui.dp(8), ui.dp(12))
                    val attrs = activity.obtainStyledAttributes(intArrayOf(android.R.attr.listChoiceIndicatorMultiple))
                    try { checkMarkDrawable = attrs.getDrawable(0) } finally { attrs.recycle() }
                    checkMarkTintList = ColorStateList.valueOf(Palette.ACCENT)
                    isSaveEnabled = false
                }
                val app = getItem(position)
                row.text = SpannableString("${app.label}\n${app.packageName}").apply {
                    setSpan(StyleSpan(Typeface.BOLD), 0, app.label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(Palette.MUTED), app.label.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(RelativeSizeSpan(.85f), app.label.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                row.contentDescription = "${app.label}，${app.packageName}"
                row.isChecked = state.isSelected(app)
                return row
            }
        }
        list.adapter = adapter
        fun updateCount() {
            count.text = "已選 ${state.selected.size} · 顯示 ${shown.size}/${state.apps.size}"
        }
        fun filter(query: String) {
            shown = state.matches(query)
            updateCount()
            empty.text = if (state.apps.isEmpty()) {
                "沒有可顯示的 app。\n僅列出可啟動的 app；請取消後手動輸入完整套件名稱。已填的套件會保留。"
            } else {
                "找不到符合的 app。\n試試其他名稱或套件，或取消後手動輸入完整套件名稱。"
            }
            emptyScroll.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
            list.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
            adapter.notifyDataSetChanged()
            list.setSelection(0)
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val index = position - list.headerViewsCount
            if (index in shown.indices) {
                state.toggle(shown[index])
                adapter.notifyDataSetChanged()
                updateCount()
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
        })
        filter("")
        return AlertDialog.Builder(activity).setTitle("選擇 app").setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("填入清單") { _, _ -> onFill(state.selected) }
            .create().also { dialog ->
                // Dialog windows have independent flags. Follow the app's optional screen protection.
                if (RecordingSettings(activity).protectAppScreen) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                dialog.show()
            }
    }

    /** Leaves room for the title/actions; the result list and empty guidance scroll at large fonts. */
    private class BoundedColumn(context: Context, private val maxHeight: Int) : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maxHeight
                else minOf(maxHeight, MeasureSpec.getSize(heightMeasureSpec))
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }
}

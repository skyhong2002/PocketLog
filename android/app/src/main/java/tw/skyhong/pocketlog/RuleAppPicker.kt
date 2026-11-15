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

/** Single local rule draft selection; no settings or consent are persisted. */
internal object RuleAppPicker {
    private const val MAX_APPS = AppPicker.MAX_APPS
    private const val MAX_QUERY = AppPicker.MAX_QUERY

    fun show(activity: Activity, apps: List<AppPicker.App>, onSelect: (String) -> Unit): AlertDialog {
        val ui = Ui(activity)
        val bounded = apps.take(MAX_APPS).distinctBy { it.packageName }
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
        val help = ui.text(null, "點選填入，不會儲存。", 13f, Palette.MUTED).apply {
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(12))
        }
        list.addHeaderView(help, null, false)
        results.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val emptyScroll = ScrollView(activity).apply { isFillViewport = true }
        val empty = ui.text(null, "", 15f, Palette.MUTED).apply { setPadding(ui.dp(4), ui.dp(16), ui.dp(4), ui.dp(16)) }
        emptyScroll.addView(empty)
        results.addView(emptyScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fun matches(query: String): List<AppPicker.App> {
            val term = query.take(MAX_QUERY).trim().lowercase(Locale.ROOT)
            return bounded.filter { it.label.lowercase(Locale.ROOT).contains(term) || it.packageName.lowercase(Locale.ROOT).contains(term) }
        }
        var shown = matches("")
        var picker: AlertDialog? = null
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
                    isSaveEnabled = false
                }
                val app = getItem(position)
                row.text = SpannableString("${app.label}\n${app.packageName}").apply {
                    setSpan(StyleSpan(Typeface.BOLD), 0, app.label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(Palette.MUTED), app.label.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(RelativeSizeSpan(.85f), app.label.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                row.contentDescription = "${app.label}，${app.packageName}"
                row.isChecked = false
                return row
            }
        }
        list.adapter = adapter
        fun updateCount() {
            count.text = "顯示 ${shown.size}/${bounded.size} 個 app"
        }
        fun filter(query: String) {
            shown = matches(query)
            updateCount()
            empty.text = if (bounded.isEmpty()) {
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
                onSelect(shown[index].packageName)
                picker?.dismiss()
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
        })
        filter("")
        return AlertDialog.Builder(activity).setTitle("選擇規則 app").setView(content)
            .setNegativeButton("取消", null)
            .create().also { dialog ->
                // Dialog windows have independent flags. Protect this window before its first frame.
                dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                picker = dialog
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

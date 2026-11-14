package tw.skyhong.pocketlog

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

/** PocketLog sage/paper palette from docs/UI_GUIDELINES.md. Text pairs meet WCAG AA on paper. */
object Palette {
    const val CANVAS = 0xFFF2F5EF.toInt()
    const val PAPER = 0xFFFCFDF9.toInt()
    const val ACCENT = 0xFF52765F.toInt()
    const val ACCENT_DARK = 0xFF3B5A47.toInt()
    const val ACCENT_SOFT = 0xFFDDE8DA.toInt()
    const val INK = 0xFF303A32.toInt()
    const val MUTED = 0xFF5D6A5F.toInt()
    const val BORDER = 0xFFD8E0D3.toInt()
    const val HAIRLINE = 0xFFE6EBE2.toInt()
    const val DANGER = 0xFFA3352C.toInt()
    const val DANGER_SOFT = 0xFFFAE9E6.toInt()
    const val WARN_SOFT = 0xFFFAF0D9.toInt()
    const val WARN_INK = 0xFF684C12.toInt()
    const val FALLBACK_CATEGORY = 0xFFB1A797.toInt()

    /** Mixes a category color toward paper for readable dark text on a tinted card. */
    fun tint(color: Int, amount: Float): Int {
        fun mix(a: Int, b: Int) = (a * amount + b * (1 - amount)).toInt()
        return Color.rgb(mix(Color.red(color), Color.red(PAPER)), mix(Color.green(color), Color.green(PAPER)), mix(Color.blue(color), Color.blue(PAPER)))
    }
    fun darken(color: Int, factor: Float) = Color.rgb((Color.red(color) * factor).toInt(), (Color.green(color) * factor).toInt(), (Color.blue(color) * factor).toInt())
    fun parse(value: String?): Int = runCatching { Color.parseColor(requireNotNull(value)) }.getOrDefault(FALLBACK_CATEGORY)
}

/**
 * Small builder for platform Views. Sizes use dp/sp so system font scale and display density apply;
 * interactive controls keep a 48 dp minimum target.
 */
class Ui(val context: Context) {
    enum class Tone { PRIMARY, TONAL, OUTLINE, TEXT, DANGER }
    enum class Kind { INFO, WARN, ERROR, SUCCESS }
    enum class Field { TEXT, MULTILINE, NUMBER, SECRET, URL }

    private val metrics = context.resources.displayMetrics
    fun dp(value: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics).toInt()
    fun dp(value: Int): Int = dp(value.toFloat())
    fun sp(value: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, metrics)

    fun shape(fill: Int, radius: Float = 16f, stroke: Int? = null, strokeWidth: Float = 1f) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(strokeWidth).coerceAtLeast(1), stroke)
    }
    fun ripple(content: Drawable?, radius: Float = 16f): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x22303A32), content, shape(Color.BLACK, radius))

    fun lp(width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, bottom: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(width, height, weight).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    fun column(parent: ViewGroup? = null, padding: Int = 0, spacing: Int = 0): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        if (spacing > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = GradientDrawable().apply { setSize(1, dp(spacing)); setColor(Color.TRANSPARENT) } }
        parent?.addView(this, lp())
    }
    fun horizontal(parent: ViewGroup? = null, spacing: Int = 8): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        if (spacing > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = GradientDrawable().apply { setSize(dp(spacing), 1); setColor(Color.TRANSPARENT) } }
        parent?.addView(this, lp())
    }

    fun text(parent: ViewGroup?, value: CharSequence, size: Float = 15f, color: Int = Palette.INK, bold: Boolean = false, top: Int = 0, bottom: Int = 0): TextView =
        TextView(context).apply {
            text = value; textSize = size; setTextColor(color); setLineSpacing(0f, 1.18f)
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            parent?.addView(this, lp(top = top, bottom = bottom))
        }
    fun pageTitle(parent: ViewGroup?, value: String) = text(parent, value, 26f).apply { isAccessibilityHeading = true }
    fun heading(parent: ViewGroup?, value: String, top: Int = 0) = text(parent, value, 18f, bold = true, top = top, bottom = 4).apply { isAccessibilityHeading = true }
    fun body(parent: ViewGroup?, value: CharSequence, top: Int = 0, bottom: Int = 0) = text(parent, value, 15f, top = top, bottom = bottom)
    fun meta(parent: ViewGroup?, value: CharSequence, top: Int = 0, bottom: Int = 0) = text(parent, value, 13f, Palette.MUTED, top = top, bottom = bottom)
    /** Group label above a set of cards, like a settings section title. */
    fun groupLabel(parent: ViewGroup, value: String) = text(parent, value, 14f, Palette.ACCENT_DARK, bold = true, top = 12, bottom = 8).apply {
        isAccessibilityHeading = true; setPadding(dp(4), 0, dp(4), 0)
    }

    fun card(parent: ViewGroup, fill: Int = Palette.PAPER, padding: Int = 16, bottom: Int = 12): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; background = shape(fill, 18f, Palette.BORDER)
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        parent.addView(this, lp(bottom = bottom))
    }

    fun button(parent: ViewGroup?, label: String, tone: Tone = Tone.PRIMARY, weight: Float = 0f, onClick: (View) -> Unit): Button = Button(context).apply {
        text = label; isAllCaps = false; textSize = 15f; stateListAnimator = null; elevation = 0f
        minHeight = dp(48); minimumHeight = dp(48); minWidth = dp(48); minimumWidth = dp(48)
        setPadding(dp(18), dp(10), dp(18), dp(10)); gravity = Gravity.CENTER
        val (fill, stroke, ink) = when (tone) {
            Tone.PRIMARY -> Triple(Palette.ACCENT, null, Color.WHITE)
            Tone.TONAL -> Triple(Palette.ACCENT_SOFT, null, Palette.ACCENT_DARK)
            Tone.OUTLINE -> Triple(Palette.PAPER, Palette.BORDER, Palette.INK)
            Tone.TEXT -> Triple(Color.TRANSPARENT, null, Palette.ACCENT_DARK)
            Tone.DANGER -> Triple(Palette.DANGER_SOFT, null, Palette.DANGER)
        }
        background = ripple(shape(fill, 24f, stroke), 24f); setTextColor(ink)
        if (tone == Tone.PRIMARY || tone == Tone.DANGER) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setOnClickListener { onClick(it) }
        parent?.addView(this, if (weight > 0f) lp(0, weight = weight) else lp(top = 4, bottom = 4))
    }
    /** Up to two side-by-side actions; text wraps instead of truncating under large font scales. */
    fun actions(parent: ViewGroup, vararg items: Pair<String, Tone>, onClick: (Int) -> Unit): List<Button> {
        val row = horizontal(parent, 8).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8) }
        return items.mapIndexed { index, (label, tone) -> button(row, label, tone, 1f) { onClick(index) } }
    }

    fun banner(parent: ViewGroup, value: CharSequence, kind: Kind = Kind.INFO, bottom: Int = 12): TextView {
        val (fill, ink) = when (kind) {
            Kind.INFO -> Palette.ACCENT_SOFT to Palette.ACCENT_DARK
            Kind.SUCCESS -> Palette.ACCENT_SOFT to Palette.ACCENT_DARK
            Kind.WARN -> Palette.WARN_SOFT to Palette.WARN_INK
            Kind.ERROR -> Palette.DANGER_SOFT to Palette.DANGER
        }
        return text(parent, value, 14f, ink, bottom = bottom).apply {
            background = shape(fill, 12f); setPadding(dp(14), dp(12), dp(14), dp(12))
        }
    }

    fun pill(parent: ViewGroup?, value: String, on: Boolean): TextView = TextView(context).apply {
        text = value; textSize = 12f; setTextColor(if (on) Palette.ACCENT_DARK else Palette.MUTED)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        background = shape(if (on) Palette.ACCENT_SOFT else Palette.HAIRLINE, 12f); setPadding(dp(10), dp(4), dp(10), dp(4))
        parent?.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun icon(res: Int, tint: Int = Palette.INK, size: Int = 24): ImageView = ImageView(context).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Tappable navigation row: title, optional description and status pill, chevron. The whole row is
     * one accessible button with a combined label.
     */
    fun row(parent: ViewGroup, title: String, detail: String? = null, state: Pair<String, Boolean>? = null, divider: Boolean = true, onClick: (() -> Unit)?): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56)
            setPadding(dp(4), dp(10), dp(4), dp(10))
            if (onClick != null) { isClickable = true; isFocusable = true; background = ripple(null, 12f); setOnClickListener { onClick() } }
        }
        val texts = column(row).apply { layoutParams = lp(0, weight = 1f) }
        text(texts, title, 16f)
        if (detail != null) meta(texts, detail, top = 2)
        if (state != null) pill(row, state.first, state.second).apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) }
        if (onClick != null) row.addView(icon(R.drawable.ic_chevron, Palette.MUTED, 20).apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6) })
        row.contentDescription = listOfNotNull(title, state?.first, detail).joinToString("，")
        if (divider && parent.childCount > 0 && parent.getChildAt(parent.childCount - 1).tag == ROW_TAG) parent.addView(View(context).apply { setBackgroundColor(Palette.HAIRLINE) }, lp(height = 1))
        row.tag = ROW_TAG
        parent.addView(row, lp())
        return row
    }

    fun field(parent: ViewGroup, label: String, hint: String = "", kind: Field = Field.TEXT, value: String? = null, help: String? = null): EditText {
        val title = text(parent, label, 14f, Palette.INK, bold = true, top = 8, bottom = 6)
        val edit = EditText(context).apply {
            this.hint = hint; textSize = 16f; setTextColor(Palette.INK); setHintTextColor(0xFF7F8A80.toInt())
            isSaveEnabled = false; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            minHeight = dp(48); setPadding(dp(14), dp(12), dp(14), dp(12))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), shape(Palette.PAPER, 12f, Palette.ACCENT, 2f))
                addState(intArrayOf(), shape(Palette.PAPER, 12f, Palette.BORDER))
            }
            inputType = when (kind) {
                Field.TEXT -> InputType.TYPE_CLASS_TEXT
                Field.MULTILINE -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                Field.NUMBER -> InputType.TYPE_CLASS_NUMBER
                Field.SECRET -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                Field.URL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            if (kind == Field.MULTILINE) { minLines = 3; gravity = Gravity.TOP or Gravity.START; isSingleLine = false }
            if (value != null) setText(value)
            id = View.generateViewId()
        }
        title.labelFor = edit.id
        parent.addView(edit, lp())
        if (help != null) meta(parent, help, top = 4)
        return edit
    }

    fun spinner(parent: ViewGroup, label: String?, values: List<String>, selected: Int = 0): Spinner {
        val title = label?.let { text(parent, it, 14f, Palette.INK, bold = true, top = 8, bottom = 6) }
        return Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, values)
            setSelection(selected.coerceIn(0, (values.size - 1).coerceAtLeast(0))); minimumHeight = dp(48)
            id = View.generateViewId(); title?.labelFor = id
            if (label != null) contentDescription = label
            parent.addView(this, lp())
        }
    }

    fun checkbox(parent: ViewGroup, label: String, checked: Boolean = false): CheckBox = CheckBox(context).apply {
        text = label; isChecked = checked; textSize = 15f; setTextColor(Palette.INK); minHeight = dp(48)
        buttonTintList = ColorStateList.valueOf(Palette.ACCENT); setPadding(dp(6), dp(8), 0, dp(8))
        parent.addView(this, lp())
    }

    @Suppress("UseSwitchCompatOrMaterialCode") // Platform widget on purpose: no AppCompat/Material dependency.
    fun switchRow(parent: ViewGroup, label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit): Switch = Switch(context).apply {
        text = if (detail == null) label else "$label\n$detail"; textSize = 15f; setTextColor(Palette.INK); isChecked = checked; minHeight = dp(56)
        thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Palette.ACCENT, Color.WHITE))
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Palette.ACCENT_SOFT, Palette.BORDER))
        setOnCheckedChangeListener { _, value -> onChange(value) }
        parent.addView(this, lp())
    }

    /** Single-choice segmented control; each segment is a 48 dp button announcing its selected state. */
    /** Returns a getter for the current choice; selection updates in place so accessibility focus stays put. */
    fun segmented(parent: ViewGroup, options: List<String>, selected: Int, description: String, onSelect: (Int) -> Unit = {}): () -> Int {
        var current = selected
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; background = shape(Palette.HAIRLINE, 24f); setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val segments = options.map { option ->
            TextView(context).apply {
                text = option; textSize = 14f; gravity = Gravity.CENTER; minHeight = dp(44); setPadding(dp(8), dp(6), dp(8), dp(6))
                isClickable = true; isFocusable = true
                bar.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        fun paint() = segments.forEachIndexed { index, view ->
            val on = index == current
            view.setTextColor(if (on) Color.WHITE else Palette.INK)
            view.typeface = Typeface.create(Typeface.DEFAULT, if (on) Typeface.BOLD else Typeface.NORMAL)
            view.background = ripple(if (on) shape(Palette.ACCENT, 20f) else null, 20f)
            view.isSelected = on
            view.contentDescription = "$description：${options[index]}${if (on) "，已選取" else ""}"
        }
        segments.forEachIndexed { index, view -> view.setOnClickListener { if (index != current) { current = index; paint(); onSelect(index) } } }
        paint()
        parent.addView(bar, lp())
        return { current }
    }

    fun progress(parent: ViewGroup, fraction: Float, color: Int = Palette.ACCENT, description: String? = null): View = object : View(context) {
        private val track = shape(Palette.HAIRLINE, 6f)
        private val fill = shape(color, 6f)
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(10))
        override fun onDraw(canvas: android.graphics.Canvas) {
            track.setBounds(0, 0, width, height); track.draw(canvas)
            val end = (width * fraction.coerceIn(0f, 1f)).toInt()
            if (end > 0) { fill.setBounds(0, 0, end.coerceAtLeast(height), height); fill.draw(canvas) }
        }
    }.also {
        if (description == null) it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO else it.contentDescription = description
        parent.addView(it, lp(top = 6, bottom = 6))
    }

    fun stat(parent: ViewGroup, label: String, value: String, detail: String?, weight: Float = 1f): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; background = shape(Palette.CANVAS, 14f); setPadding(dp(14), dp(12), dp(14), dp(12))
        text(this, label, 13f, Palette.MUTED)
        text(this, value, 22f, Palette.ACCENT_DARK, top = 2)
        if (detail != null) text(this, detail, 12f, Palette.MUTED, top = 2)
        isFocusable = true; contentDescription = listOfNotNull(label, value, detail).joinToString("，")
        parent.addView(this, if (weight > 0f) lp(0, weight = weight) else lp(bottom = 8))
    }

    fun spinnerRow(parent: ViewGroup, value: String): LinearLayout = horizontal(parent, 12).apply {
        setPadding(0, dp(8), 0, dp(8))
        addView(ProgressBar(context).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(Palette.ACCENT) }, LinearLayout.LayoutParams(dp(24), dp(24)))
        text(this, value, 15f, Palette.MUTED).layoutParams = lp(0, weight = 1f)
    }

    /** A real empty state with an explanation and, when possible, a recovery action. */
    fun empty(parent: ViewGroup, title: String, detail: String, action: String? = null, onAction: (() -> Unit)? = null): LinearLayout = card(parent, Palette.PAPER, 20).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        text(this, title, 17f, bold = true, bottom = 6).gravity = Gravity.CENTER
        text(this, detail, 14f, Palette.MUTED).gravity = Gravity.CENTER
        if (action != null && onAction != null) button(this, action, Tone.TONAL) { onAction() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12); gravity = Gravity.CENTER_HORIZONTAL }
        }
    }

    fun swatch(color: Int, size: Int = 10): View = View(context).apply {
        background = shape(color, size / 2f); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    /** Brand mark decoded at small size; the 1024 px source would waste memory as a header image. */
    fun logo(size: Int): ImageView = ImageView(context).apply {
        val options = BitmapFactory.Options().apply { inSampleSize = (1024 / dp(size).coerceAtLeast(1)).coerceIn(1, 16).takeHighestOneBit() }
        setImageBitmap(BitmapFactory.decodeResource(resources, R.drawable.ic_launcher_art, options))
        scaleType = ImageView.ScaleType.CENTER_CROP
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, view.width * .24f)
        }
        clipToOutline = true; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    companion object { private const val ROW_TAG = "pocketlog-row" }
}

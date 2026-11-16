package tw.skyhong.pocketlog

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import tw.skyhong.pocketlog.Ui.Field
import tw.skyhong.pocketlog.Ui.Kind
import tw.skyhong.pocketlog.Ui.Tone
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

/**
 * Native PocketLog shell with exactly three destinations: 時間軸, 回顧 and 設定.
 * Pages are built from platform Views via [Ui]; no WebView, no new dependencies.
 */
class MainActivity : Activity() {
    private enum class Tab(val label: String, val icon: Int) {
        TIMELINE("時間軸", R.drawable.ic_nav_timeline), REVIEW("回顧", R.drawable.ic_nav_weekly), SETTINGS("設定", R.drawable.ic_nav_settings)
    }

    private lateinit var ui: Ui
    private lateinit var shell: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var page: LinearLayout
    private lateinit var statusStrip: LinearLayout
    private lateinit var statusSpinner: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var navBar: LinearLayout
    private lateinit var navRow: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val appNames = LocalAppNames()
    private var busy = false
    private var generation = 0
    private var statusVersion = 0
    private var navWanted = false
    private var barsBottom = 0
    private var imeBottom = 0
    private var back: (() -> Unit)? = null
    private val backCallback = OnBackInvokedCallback { handleBack() }
    private var backRegistered = false
    private var refreshOnResume: (() -> Unit)? = null
    private var timelineWatch: TimelineUpdateWatch? = null
    private var visible = false
    private var tab = Tab.TIMELINE
    private var restoreTab: Tab? = null
    private var categoryFilter: String? = null

    private var credentials: Credentials? = null
    private var workspace: JSONObject? = null
    /** Last known server state of automatic AI summaries; null until read (or offline). */
    private var aiAutomatic: Boolean? = null
    private var day = TimelineMath.journalDay()
    private var loaded: JSONObject? = null
    private var replayDialog: AlertDialog? = null
    private var reviewWeekly = false
    private var questionDrafts = EditorDrafts(20, 1000)
    /** One conversation per reviewed period (day or week), kept in memory only. */
    private var conversations = linkedMapOf<String, String>()
    private var pendingAsk: JSONObject? = null
    private var pendingBrief: JSONObject? = null
    private var exportBytes: ByteArray? = null
    private data class RetainedEditors(val questions: EditorDrafts, val conversations: LinkedHashMap<String, String>, val weekly: Boolean, val ask: JSONObject?, val brief: JSONObject?, val export: ByteArray?)
    private fun clearDrafts() { questionDrafts.clear(); conversations.clear(); categoryFilter = null; exportBytes?.fill(0); exportBytes = null }
    private val baseCategories = listOf("unsorted", "work", "learning", "communication", "social_media", "entertainment", "news_reading", "life_admin", "rest", "idle")
    private val categoryLabels = listOf("未分類", "工作", "學習", "溝通", "社群", "娛樂", "新聞／閱讀", "生活事務", "休息", "閒置")
    // Server defaults (server/src/workspace.rs) so offline pages still distinguish categories by color.
    private val defaultColors = listOf("#b1a797", "#b9a4ed", "#87bcb4", "#e4be7c", "#e69c9c", "#d4a9c8", "#9bbce1", "#b4c493", "#80bda1", "#92949e")
    private val categories: List<String> get() = workspace?.optJSONArray("categories")?.let { styles -> (0 until styles.length()).map { styles.getJSONObject(it).getString("id") } } ?: baseCategories
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val promise = "手機自動記下你看過的畫面，你的 AI 幫你整理成可回顧的一天。"

    // ---------------------------------------------------------------- lifecycle and shell

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        (lastNonConfigurationInstance as? RetainedEditors)?.let {
            questionDrafts = it.questions; conversations = it.conversations; reviewWeekly = it.weekly
            pendingAsk = it.ask; pendingBrief = it.brief; exportBytes = it.export
        }
        applyScreenProtection()
        // Upgrade path: existing screen-context grants include screenshots by default.
        runCatching { synchronized(SyncEngine.lock) { RecordingSettings(this).applyScreenshotDefault() } }
        window.setDecorFitsSystemWindows(false)
        ui = Ui(this)
        restoreTab = state?.getString("tab")?.let { name -> Tab.entries.firstOrNull { it.name == name } }
        state?.getString("day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { TimelineMath.inHistory(it, journalDay()) }?.let { day = it }
        reviewWeekly = state?.getBoolean("review_weekly", reviewWeekly) ?: reviewWeekly
        categoryFilter = state?.getString("category_filter")?.take(80)
        buildShell()
        boot()
    }

    override fun onRetainNonConfigurationInstance(): Any {
        // Transfer the confirmed snapshot in memory only; the old Activity must not wipe it.
        val pendingExport = exportBytes; exportBytes = null
        return RetainedEditors(questionDrafts, conversations, reviewWeekly, pendingAsk, pendingBrief, pendingExport)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        // Only navigation state; never journal content or credentials.
        out.putString("tab", tab.name); out.putString("day", day.toString()); out.putBoolean("review_weekly", reviewWeekly)
        categoryFilter?.let { out.putString("category_filter", it) }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        timelineWatch?.resume()
    }

    override fun onStop() {
        visible = false
        timelineWatch?.pause()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Observe permission restoration before the next normal scan; never query old events here.
        executor.execute {
            runCatching { synchronized(SyncEngine.lock) { UsageCollector(this@MainActivity).reconcilePermission() } }
                .onFailure { runOnUiThread { if (!isDestroyed) notice("無法更新權限狀態，請稍後再同步一次。", Kind.ERROR) } }
        }
        // Returning from Android permission screens: show the new permission state.
        val refresh = refreshOnResume ?: return
        if (busy) return
        val y = scroll.scrollY
        refresh(); scroll.post { scroll.scrollTo(0, y) }
    }

    /** PocketLog's own screen protection is an optional setting, off by default. */
    private fun applyScreenProtection() {
        if (RecordingSettings(this).protectAppScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun buildShell() {
        shell = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.CANVAS) }
        header = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = ui.dp(64)
            setPadding(ui.dp(8), ui.dp(6), ui.dp(12), ui.dp(6))
        }
        shell.addView(header, ui.lp())
        scroll = ScrollView(this@MainActivity).apply { isFillViewport = true; isNestedScrollingEnabled = true }
        page = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(24)) }
        scroll.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Keep a readable column on tablets and landscape; phones keep the 16 dp gutter.
        scroll.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val side = maxOf(ui.dp(16), (right - left - ui.dp(720)) / 2)
            if (page.paddingLeft != side) page.post { page.setPadding(side, page.paddingTop, side, page.paddingBottom) }
        }
        shell.addView(scroll, ui.lp(height = 0, weight = 1f))

        statusStrip = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)); minimumHeight = ui.dp(48)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setOnClickListener { if (!busy) visibility = View.GONE }
        }
        statusSpinner = ProgressBar(this@MainActivity).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(Palette.ACCENT); visibility = View.GONE }
        statusStrip.addView(statusSpinner, LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { marginEnd = ui.dp(12) })
        statusText = ui.text(statusStrip, "", 14f).apply { layoutParams = ui.lp(0, weight = 1f) }
        shell.addView(statusStrip, ui.lp().apply { setMargins(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(8)) })

        navBar = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.PAPER) }
        navBar.addView(View(this@MainActivity).apply { setBackgroundColor(Palette.BORDER) }, ui.lp(height = 1))
        navRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(ui.dp(4), 0, ui.dp(4), 0) }
        navBar.addView(navRow, ui.lp())
        shell.addView(navBar, ui.lp())

        // targetSdk 36 is edge-to-edge: pad for status/navigation bars, cutouts and the keyboard.
        shell.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            barsBottom = bars.bottom; imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
            view.setPadding(bars.left, bars.top, bars.right, 0)
            applyBottomInsets()
            WindowInsets.CONSUMED
        }
        setContentView(shell)
    }

    private fun applyBottomInsets() {
        val keyboard = imeBottom > barsBottom
        val showNav = navWanted && !keyboard
        navBar.visibility = if (showNav) View.VISIBLE else View.GONE
        navBar.setPadding(0, 0, 0, barsBottom)
        shell.setPadding(shell.paddingLeft, shell.paddingTop, shell.paddingRight, when { keyboard -> imeBottom; showNav -> 0; else -> barsBottom })
    }

    private fun renderNav() {
        navRow.removeAllViews()
        Tab.entries.forEach { item ->
            val on = item == tab
            val cell = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; minimumHeight = ui.dp(64)
                setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8)); isClickable = true; isFocusable = true; background = ui.ripple(null, 16f)
                isSelected = on; contentDescription = item.label + if (on) "，目前頁面" else ""
                setOnClickListener { if (!busy) go(item) else notice("請等目前的操作完成", Kind.WARN) }
            }
            val pill = FrameLayout(this@MainActivity).apply { if (on) background = ui.shape(Palette.ACCENT_SOFT, 16f); setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4)) }
            pill.addView(ui.icon(item.icon, if (on) Palette.ACCENT_DARK else Palette.MUTED).apply { layoutParams = FrameLayout.LayoutParams(ui.dp(24), ui.dp(24)) })
            cell.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            cell.addView(ui.text(null, item.label, 12f, if (on) Palette.ACCENT_DARK else Palette.MUTED, bold = on).apply {
                gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(4) })
            navRow.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /**
     * Starts a new page. Bumping [generation] discards results of loads started by the previous page.
     * Root tabs return to the timeline on Back; sub-pages pass their own [back].
     */
    private fun screen(title: String, subtitle: String? = null, tab: Tab? = null, back: (() -> Unit)? = null, nav: Boolean = true, logo: Boolean = false,
                       action: Triple<Int, String, () -> Unit>? = null, refresh: (() -> Unit)? = null): LinearLayout {
        generation++
        timelineWatch?.close(); timelineWatch = null
        if (tab != null) this.tab = tab
        refreshOnResume = refresh
        header.removeAllViews()
        when {
            back != null -> header.addView(iconButton(R.drawable.ic_back, "返回") { handleBack() })
            logo -> header.addView(ui.logo(36).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(ui.dp(8), 0, ui.dp(12), 0) })
            else -> header.addView(View(this@MainActivity), LinearLayout.LayoutParams(ui.dp(8), 1))
        }
        val titles = ui.column(header).apply { layoutParams = ui.lp(0, weight = 1f) }
        ui.text(titles, title, 22f).apply { isAccessibilityHeading = true; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        if (subtitle != null) ui.meta(titles, subtitle)
        if (action != null) header.addView(iconButton(action.first, action.second, action.third))
        page.removeAllViews(); scroll.scrollTo(0, 0)
        if (!busy) statusStrip.visibility = View.GONE
        navWanted = nav; applyBottomInsets(); if (nav) renderNav()
        setBack(back ?: if (nav && this.tab != Tab.TIMELINE) ({ go(Tab.TIMELINE) }) else null)
        return page
    }

    private fun go(target: Tab) = when (target) { Tab.TIMELINE -> home(); Tab.REVIEW -> review(); Tab.SETTINGS -> settings() }

    private fun iconButton(res: Int, label: String, onClick: () -> Unit) = ImageButton(this@MainActivity).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(Palette.INK); background = ui.ripple(null, 24f)
        contentDescription = label; scaleType = ImageView.ScaleType.CENTER
        layoutParams = LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)); setOnClickListener { if (!busy) onClick() }
    }

    private fun setBack(action: (() -> Unit)?) {
        back = action
        if (action != null && !backRegistered) { onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback); backRegistered = true }
        else if (action == null && backRegistered) { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback); backRegistered = false }
    }
    private fun handleBack() { if (busy) notice("請等目前的操作完成", Kind.WARN) else back?.invoke() }
    // The manifest opts Android 14/15 into OnBackInvokedCallback too, so gesture and
    // button Back both reach backCallback; with none registered the system default applies.

    // ---------------------------------------------------------------- status, loading and actions

    private val hideStatus = Runnable { if (!busy) statusStrip.visibility = View.GONE }

    private fun notice(message: String, kind: Kind = Kind.SUCCESS) {
        statusVersion++
        handler.removeCallbacks(hideStatus)
        statusSpinner.visibility = View.GONE
        val (fill, ink) = when (kind) {
            Kind.SUCCESS, Kind.INFO -> Palette.ACCENT_SOFT to Palette.ACCENT_DARK
            Kind.WARN -> Palette.WARN_SOFT to Palette.WARN_INK
            Kind.ERROR -> Palette.DANGER_SOFT to Palette.DANGER
        }
        statusStrip.background = ui.shape(fill, 14f); statusText.setTextColor(ink); statusText.text = message
        statusStrip.contentDescription = if (kind == Kind.ERROR) "錯誤：$message" else message
        statusStrip.visibility = View.VISIBLE
        if (kind == Kind.SUCCESS || kind == Kind.INFO) handler.postDelayed(hideStatus, 6_000)
    }

    private fun showBusy(message: String) {
        handler.removeCallbacks(hideStatus)
        statusSpinner.visibility = View.VISIBLE
        statusStrip.background = ui.shape(Palette.PAPER, 14f, Palette.BORDER); statusText.setTextColor(Palette.INK); statusText.text = message
        statusStrip.contentDescription = message; statusStrip.visibility = View.VISIBLE
        navRow.alpha = .55f
    }

    /** User-facing failure text. Never includes response bodies, tokens or journal content. */
    private fun describe(error: Throwable): String = when (error) {
        is ApiFailure -> when (error.status) {
            401 -> "伺服器不接受這把金鑰：可能打錯、過期或已撤銷。請到「設定 → 連線」更換。"
            403 -> "這把金鑰沒有這個權限。手機請使用「日誌（手機 app）」金鑰。"
            404 -> "伺服器沒有這項功能，請更新 PocketLog 伺服器。"
            409 -> "資料剛在其他裝置改過，請重新整理後再試。"
            413 -> "內容太大，伺服器不接受。"
            423 -> "伺服器已暫停記錄。"
            429 -> "太頻繁或已達用量上限，請稍後再試。"
            in 500..599 -> "伺服器暫時無法處理（HTTP ${error.status}），請稍後再試。"
            else -> "伺服器拒絕這個請求（HTTP ${error.status}）。"
        }
        is UnknownHostException -> "找不到伺服器。請檢查網址，並確認手機已連上網路（使用 Tailscale 時請確認已連線）。"
        is SSLException -> "無法驗證伺服器的 HTTPS 憑證。PocketLog 只接受系統信任的憑證。"
        is SocketTimeoutException -> "連線逾時，伺服器可能離線或網路太慢。"
        is ConnectException -> "連不上伺服器。請確認服務正在執行、連接埠正確。"
        is IOException -> "網路連線失敗，資料仍加密保存在手機。"
        is org.json.JSONException -> "伺服器回應的格式不對，請確認伺服器與 app 版本相符。"
        else -> "無法完成，請檢查網路與伺服器。"
    }

    /** Reads for the current page. Stale results are dropped; failures become an inline retry state. */
    private fun <T> load(slot: LinearLayout, message: String, work: () -> T, render: (T) -> Unit) {
        val token = generation
        slot.removeAllViews(); ui.spinnerRow(slot, message)
        executor.execute {
            val result = runCatching(work)
            runOnUiThread {
                if (token != generation || isDestroyed || isFinishing) return@runOnUiThread
                slot.removeAllViews()
                result.mapCatching { render(it) }.onFailure { error ->
                    slot.removeAllViews()
                    ui.empty(slot, "無法載入", describe(error), "重試") { load(slot, message, work, render) }
                }
            }
        }
    }

    /** One mutation at a time; navigation waits so completion handlers always apply to their own page. */
    private fun <T> task(message: String = "處理中…", work: () -> T, failed: ((Throwable) -> Unit)? = null, discard: ((T) -> Unit)? = null, done: (T) -> Unit) {
        if (busy) { notice("上一個操作還在處理中", Kind.WARN); return }
        busy = true; showBusy(message)
        executor.execute {
            val result = runCatching(work)
            runOnUiThread {
                busy = false; navRow.alpha = 1f; statusSpinner.visibility = View.GONE
                if (isDestroyed || isFinishing) {
                    result.onSuccess { discard?.invoke(it) }
                    return@runOnUiThread
                }
                val error = result.exceptionOrNull()
                if (error != null) { notice(describe(error), Kind.ERROR); failed?.invoke(error); return@runOnUiThread }
                val before = statusVersion; statusStrip.visibility = View.GONE
                val thrown = runCatching { @Suppress("UNCHECKED_CAST") done(result.getOrNull() as T) }.exceptionOrNull()
                if (thrown != null) notice(describe(thrown), Kind.ERROR) else if (statusVersion == before) notice("已完成")
            }
        }
    }

    private fun dialog() = AlertDialog.Builder(this)
    /** Dialog windows do not inherit the Activity's flags; apply the optional protection before showing. */
    private fun protect(dialog: AlertDialog) { if (RecordingSettings(this).protectAppScreen) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    private fun AlertDialog.Builder.showSecure(): AlertDialog = create().also { protect(it); it.show() }
    /** Confirmation whose cancel path also runs [cancel], so a switch that asked can snap back. */
    private fun confirm(title: String, message: String, positive: String, cancel: () -> Unit = {}, ok: () -> Unit) {
        dialog().setTitle(title).setMessage(message).setNegativeButton("取消") { _, _ -> cancel() }.setOnCancelListener { cancel() }
            .setPositiveButton(positive) { _, _ -> ok() }.showSecure()
    }

    // ---------------------------------------------------------------- shared formatting

    private fun api() = PocketLogApi(requireNotNull(credentials))
    private fun journalDay() = TimelineMath.journalDay()
    private fun range(first: LocalDate, days: Long = 1) = TimelineMath.window(first, days, zone, System.currentTimeMillis())
    private fun dayPath(selected: LocalDate = day) = range(selected).let { "/v1/timeline?from_ms=${it.first}&to_ms=${it.second}" }
    private fun dateTitle(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern(if (resources.configuration.fontScale >= 1.3f) "M/d E" else "M月d日 EEEE", Locale.TRADITIONAL_CHINESE))
    private fun md(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("M/d（E）", Locale.TRADITIONAL_CHINESE))
    private fun relative(d: LocalDate): String = when (val diff = ChronoUnit.DAYS.between(d, journalDay())) { 0L -> "今天"; 1L -> "昨天"; else -> "$diff 天前" }
    private fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    private fun stamp(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    private fun host(): String = credentials?.endpoint?.removePrefix("https://") ?: "未連線"
    private fun style(id: String): JSONObject? = workspace?.optJSONArray("categories")?.let { s -> (0 until s.length()).map { s.getJSONObject(it) }.firstOrNull { it.optString("id") == id } }
    private fun label(id: String): String = style(id)?.optString("name")?.takeIf { it.isNotBlank() } ?: categoryLabels.getOrElse(baseCategories.indexOf(id)) { id }
    private fun colorFor(id: String): Int = style(id)?.let { Palette.parse(it.optString("color")) } ?: Palette.parse(defaultColors.getOrNull(baseCategories.indexOf(id)))
    private fun sourceLabel(card: JSONObject) = when {
        card.optBoolean("pending_edit") -> "你改過 · 待同步"
        else -> when (card.optString("source")) { "user" -> "你改過"; "ai" -> "AI 整理"; "local_excerpt" -> "畫面文字"; else -> "只有 app" }
    }
    private fun cardsOf(data: JSONObject?): List<JSONObject> = data?.optJSONArray("cards")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    private fun cardTitle(card: JSONObject): String = appNames.title(card).ifBlank { appNames.name(card.optString("app")) }.ifBlank { "活動" }
    private fun readJournal(path: String): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper())
        val data = SyncEngine.readTimeline(this@MainActivity, path)
        val apps = data.optJSONArray("apps")
        val packages = cardsOf(data).asSequence().map { it.optString("app") } +
            (0 until (apps?.length() ?: 0)).asSequence().map { apps!!.getJSONObject(it).optString("app") }
        appNames.prepare(packages) { app ->
            val info = packageManager.getApplicationInfo(app, android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
            packageManager.getApplicationLabel(info).toString()
        }
        return data
    }
    private fun intervals(list: List<JSONObject>) = list.map { TimelineMath.Interval(it.getLong("start_ms"), it.getLong("end_ms")) }
    private fun totals(list: List<JSONObject>): List<Pair<String, Long>> =
        list.groupBy { it.getString("category") }.map { (id, rows) -> id to rows.sumOf { it.getLong("end_ms") - it.getLong("start_ms") } }.sortedByDescending { it.second }
    private fun watcher(after: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = after(s?.toString().orEmpty())
    }
    private fun version(): String = runCatching { packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionName }.getOrNull() ?: "?"
    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun step(parent: ViewGroup, number: String, title: String, detail: String) {
        val row = ui.horizontal(parent, 12).apply { gravity = Gravity.TOP; setPadding(0, ui.dp(6), 0, ui.dp(6)); isScreenReaderFocusable = true; contentDescription = "$number. $title。$detail" }
        row.addView(TextView(this@MainActivity).apply {
            text = number; gravity = Gravity.CENTER; textSize = 14f; setTextColor(Palette.ACCENT_DARK); typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = ui.shape(Palette.ACCENT_SOFT, 14f); minWidth = ui.dp(28); minHeight = ui.dp(28); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, wrap())
        val column = ui.column(row).apply { layoutParams = ui.lp(0, weight = 1f) }
        ui.text(column, title, 16f, bold = true).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        ui.meta(column, detail, top = 2).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Callout card with a recovery action, for permission/paused/offline states. */
    private fun callout(parent: ViewGroup, message: String, kind: Kind, action: String?, onAction: (() -> Unit)?) {
        val (fill, ink) = when (kind) { Kind.WARN -> Palette.WARN_SOFT to Palette.WARN_INK; Kind.ERROR -> Palette.DANGER_SOFT to Palette.DANGER; else -> Palette.ACCENT_SOFT to Palette.ACCENT_DARK }
        val box = ui.column(parent).apply { background = ui.shape(fill, 14f); setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(6)); (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(12) }
        ui.text(box, message, 14f, ink)
        if (action != null && onAction != null) ui.button(box, action, Tone.TEXT) { onAction() }.apply {
            layoutParams = wrap(); setPadding(0, ui.dp(8), ui.dp(8), ui.dp(8))
        }
    }

    /** Category bars with durations and percentages (回顧 › 時間分配). */
    private fun categoryBars(parent: ViewGroup, totals: List<Pair<String, Long>>) {
        val sum = totals.sumOf { it.second }.coerceAtLeast(1)
        totals.forEach { (id, ms) ->
            val percent = (ms.toDouble() / sum * 100).roundToInt()
            val row = ui.horizontal(parent, 10).apply { minimumHeight = ui.dp(28); isScreenReaderFocusable = true; contentDescription = "${label(id)}，${TimelineMath.duration(ms)}，$percent%" }
            row.addView(ui.swatch(colorFor(id), 12))
            ui.text(row, label(id), 15f).layoutParams = ui.lp(0, weight = 1f)
            ui.text(row, "${TimelineMath.duration(ms)} · $percent%", 14f, Palette.MUTED).layoutParams = wrap()
            ui.progress(parent, ms.toFloat() / sum, colorFor(id), null)
        }
    }

    /** A horizontally scrolling row of small chips. */
    private fun chipRow(parent: ViewGroup): LinearLayout {
        val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = ui.horizontal(null, 6)
        scroller.addView(row); parent.addView(scroller, ui.lp(top = 4, bottom = 4))
        return row
    }

    /** Previous / date picker / next, plus 今天 when another period is shown. */
    private fun dateNav(parent: ViewGroup, step: Long = 1, label: String = dateTitle(day), onChange: (LocalDate) -> Unit) {
        val today = journalDay(); val earliest = today.minusDays(TimelineMath.HISTORY_DAYS)
        fun target(delta: Long) = day.plusDays(delta).coerceIn(earliest, today)
        fun samePeriod(d: LocalDate) = if (step == 7L) TimelineMath.weekStart(d) == TimelineMath.weekStart(day) else d == day
        val row = ui.horizontal(parent, 8).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(12) }
        val prev = target(-step); val next = target(step)
        val unit = if (step == 7L) "週" else "天"
        row.addView(iconButton(R.drawable.ic_back, "前一$unit") { onChange(prev) }.apply { if (samePeriod(prev)) { isEnabled = false; alpha = .35f; contentDescription = "前一$unit（已到最早日期）" } })
        ui.button(row, label, Tone.OUTLINE, 1f) {
            DatePickerDialog(this@MainActivity, { _, y, m, d ->
                val picked = LocalDate.of(y, m + 1, d)
                if (TimelineMath.inHistory(picked, today)) onChange(picked) else notice("只能查看最近 10 年", Kind.WARN)
            }, day.year, day.monthValue - 1, day.dayOfMonth).apply {
                datePicker.minDate = earliest.atStartOfDay(zone).toInstant().toEpochMilli()
                datePicker.maxDate = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            }.show()
        }.contentDescription = "$label，選擇日期"
        row.addView(iconButton(R.drawable.ic_chevron, "後一$unit") { onChange(next) }.apply { if (samePeriod(next)) { isEnabled = false; alpha = .35f; contentDescription = "後一$unit（已是最新）" } })
        if (!samePeriod(today)) ui.button(row, "今天", Tone.TEXT) { onChange(today) }.layoutParams = wrap()
    }

    // ---------------------------------------------------------------- first run, connection and onboarding

    private fun boot() {
        val body = screen("PocketLog", "私人活動日誌", nav = false, logo = true)
        val slot = ui.column(body)
        load(slot, "開啟加密的連線資料…", { synchronized(SyncEngine.lock) { CredentialStore(this@MainActivity).load() } }) { saved ->
            credentials = saved
            if (saved == null) setup() else { SyncEngine.schedule(this@MainActivity); enterWorkspace() }
        }
        // Recovery path if Keystore or the stored connection cannot be read.
        ui.button(body, "打不開？重設這支手機的資料與連線", Tone.TEXT) { confirmReset() }
    }

    private fun confirmReset(title: String = "重設這支手機？", message: String = "金鑰失效或連線資料損毀時使用。會停止記錄、刪除還沒上傳的資料，然後重新連線。伺服器上的記錄不會被刪除。") {
        dialog().setTitle(title).setMessage(message).setNegativeButton("取消", null).setPositiveButton("清除並重新設定") { _, _ ->
            task("清除這支手機的資料…", { synchronized(SyncEngine.lock) { SyncEngine.clearLocal(this@MainActivity); CredentialStore(this@MainActivity).clear(); RecordingSettings(this@MainActivity).clearRejectedCredentials() } }) {
                clearDrafts(); credentials = null; workspace = null; loaded = null; aiAutomatic = null; setup(); notice("已清除這支手機的連線與待上傳資料；伺服器上的記錄仍保留。", Kind.INFO)
            }
        }.showSecure()
    }

    private fun parseConnection(rawUrl: String, rawToken: String): Credentials {
        val url = rawUrl.trim().let { if (it.isNotEmpty() && "://" !in it) "https://$it" else it }
        require(url.isNotEmpty()) { "請填寫伺服器網址。" }
        val endpoint = try { EndpointPolicy.normalize(url) }
        catch (e: URISyntaxException) { throw IllegalArgumentException("網址格式不正確。") }
        catch (e: IllegalArgumentException) { throw IllegalArgumentException("網址：${e.message ?: "格式不正確"}。只填 https://主機:連接埠，不含路徑。") }
        val key = rawToken.filterNot(Char::isWhitespace)
        require(key.isNotEmpty()) { "請貼上裝置金鑰。" }
        require(key.length == 64) { "金鑰應該是 64 個字元，目前是 ${key.length} 個。請確認完整複製。" }
        require(EndpointPolicy.validToken(key)) { "金鑰只能包含 0–9 與 a–f。" }
        return Credentials(endpoint, key)
    }

    private fun setup() {
        workspace = null
        val body = screen("連接你的伺服器", "第一次設定", nav = false, logo = true)
        ui.card(body).apply {
            ui.text(this, "把一天，收進口袋。", 20f, bold = true, bottom = 6).isAccessibilityHeading = true
            ui.body(this, promise)
            ui.meta(this, "記錄會存到你自己的 PocketLog 伺服器。連線之後才會問你要不要開始記錄。", top = 6)
        }
        val form = ui.card(body)
        val endpoint = ui.field(form, "伺服器網址", "https://your-server.example", Field.URL)
        val token = ui.field(form, "裝置金鑰", "貼上 64 個字元的金鑰", Field.SECRET)
        val counter = ui.meta(form, "已輸入 0 / 64 字元", top = 4)
        token.addTextChangedListener(watcher { value ->
            val n = value.count { !it.isWhitespace() }
            counter.text = if (n == 64) "長度正確（64 字元）" else "已輸入 $n / 64 字元"
        })
        val error = ui.banner(form, "", Kind.ERROR, 0).apply { visibility = View.GONE; (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE }
        fun fail(message: String) { error.text = message; error.visibility = View.VISIBLE }
        ui.button(form, "連線") {
            val candidate = try { parseConnection(endpoint.text.toString(), token.text.toString()) } catch (e: IllegalArgumentException) { fail(e.message ?: "請檢查網址與金鑰。"); return@button }
            error.visibility = View.GONE
            task("正在確認伺服器與金鑰…", {
                synchronized(SyncEngine.lock) {
                    PocketLogApi(candidate).request(dayPath())
                    CredentialStore(this@MainActivity).save(candidate)
                    RecordingSettings(this@MainActivity).clearRejectedCredentials()
                    UsageCollector(this@MainActivity).reset()
                }
            }, failed = { fail(describe(it)) }) {
                clearDrafts(); credentials = candidate; token.text.clear(); SyncEngine.schedule(this@MainActivity); notice("已連線到 ${host()}"); enterWorkspace()
            }
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12) }
        ui.meta(form, "金鑰加密保存在這支手機，只放在 HTTPS 標頭，不會出現在網址裡。", top = 8)
        val help = ui.column(null)
        ui.button(body, "不知道網址或金鑰在哪裡？", Tone.TEXT) {
            help.visibility = if (help.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (help.visibility == View.VISIBLE) help.announceForAccessibility("說明已展開")
        }.apply { contentDescription = "展開或收起：如何取得伺服器網址與裝置金鑰" }
        body.addView(help, ui.lp())
        help.visibility = View.GONE
        ui.card(help).apply {
            step(this, "1", "伺服器網址", "用瀏覽器打開 PocketLog 網站的 HTTPS 位址，例如 https://my-pc.your-tailnet.ts.net:12446。只填到連接埠，不含路徑；手機也要連得到（使用 Tailscale 時先登入同一個 tailnet）。")
            step(this, "2", "裝置金鑰", "在網站「設定 → 連線與裝置 → 管理裝置金鑰」建立一把「日誌（手機 app）」金鑰，複製 64 個字元。金鑰只顯示一次，可以單獨撤銷。")
        }
    }

    private fun enterWorkspace() {
        val body = screen("PocketLog", host(), nav = false, logo = true)
        val slot = ui.column(body)
        load(slot, "讀取設定…", { api().request("/v1/workspace") }) {
            workspace = it
            if (it.optInt("onboarding_version") >= 1) go(restoreTab ?: Tab.TIMELINE).also { restoreTab = null } else onboarding(0)
        }
        ui.button(body, "離線開啟時間軸", Tone.TEXT) { go(restoreTab ?: Tab.TIMELINE) }
    }

    /** Three skippable steps: the promise, connect AI, start recording. */
    private fun onboarding(step: Int, exit: (() -> Unit)? = null) {
        val body = screen("開始使用", "${step + 1} / 3", nav = false, back = if (step > 0) ({ onboarding(step - 1, exit) }) else exit)
        val dots = ui.horizontal(body, 6).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(16); contentDescription = "第 ${step + 1} 步，共 3 步"; isFocusable = true }
        repeat(3) { i -> dots.addView(View(this@MainActivity).apply { background = ui.shape(if (i <= step) Palette.ACCENT else Palette.BORDER, 3f) }, LinearLayout.LayoutParams(0, ui.dp(6), 1f)) }
        fun later() = if (exit != null) exit() else finishOnboarding { home() }
        when (step) {
            0 -> {
                ui.card(body).apply {
                    ui.text(this, promise, 20f, bottom = 12).isAccessibilityHeading = true
                    ui.heading(this, "會記下")
                    ui.body(this, "• 你允許的 app 的畫面文字和截圖\n• 什麼時候用了哪個 app")
                    ui.heading(this, "不會記下", top = 12)
                    ui.body(this, "• 有密碼或輸入欄位的畫面、鎖定畫面\n• 你排除的 app，以及銀行、密碼管理等 app\n• 受保護的畫面（例如影片 app）只留文字，沒有截圖")
                    ui.meta(this, "所有內容都存在你自己的伺服器。", top = 12)
                }
                ui.actions(body, "稍後再說" to Tone.OUTLINE, "繼續" to Tone.PRIMARY) { if (it == 0) later() else onboarding(1, exit) }
            }
            1 -> {
                ui.card(body).apply {
                    ui.text(this, "連接你的 AI", 20f, bottom = 8).isAccessibilityHeading = true
                    ui.body(this, "選一個 AI 服務並貼上金鑰，AI 就會把截圖整理成活動卡。沒有 AI 也能用，只是只會顯示 app 名稱。")
                    ui.button(this, "設定 AI", Tone.TONAL) { aiSettings { onboarding(1, exit) } }
                }
                ui.actions(body, "稍後再說" to Tone.OUTLINE, "繼續" to Tone.PRIMARY) { onboarding(2, exit) }
            }
            else -> {
                ui.card(body).apply {
                    ui.text(this, "開始自動記錄", 20f, bottom = 8).isAccessibilityHeading = true
                    ui.body(this, "接下來會請你開兩個 Android 權限，並選擇要記錄的 app。隨時可以在「設定 → 記錄」關掉。")
                }
                ui.actions(body, "稍後再說" to Tone.OUTLINE, "開始" to Tone.PRIMARY) { index ->
                    if (exit != null) { if (index == 0) exit() else autoRecordFlow(0, exit); return@actions }
                    finishOnboarding { if (index == 0) home() else autoRecordFlow(0) { home() } }
                }
            }
        }
    }

    private fun finishOnboarding(next: () -> Unit) {
        val current = workspace ?: run { next(); return }
        if (current.optInt("onboarding_version") >= 1) { next(); return }
        val updated = JSONObject(current.toString()).put("onboarding_version", 1)
        task("儲存…", { api().request("/v1/workspace", updated, "PUT") }, failed = { next() }) { workspace = it; next() }
    }

    // ---------------------------------------------------------------- 時間軸

    private fun home() {
        loaded = null
        val body = screen(dateTitle(day), "${relative(day)} · 04:00 起", Tab.TIMELINE, logo = true,
            action = Triple(R.drawable.ic_sync, "同步並重新整理") { task("同步中…", { SyncEngine.sync(this@MainActivity) }) { home() } },
            refresh = null)
        dateNav(body) { day = it; home() }
        val status = ui.column(body)
        val slot = ui.column(body)
        refreshOnResume = { statusLine(status) }
        load(slot, "讀取時間軸…", {
            val latest = runCatching { api().request("/v1/workspace") }.getOrNull()
            latest to readJournal(dayPath())
        }) { (latest, data) ->
            if (latest != null) workspace = latest
            renderTimeline(slot, data)
            startTimelineWatch(slot, data)
        }
        statusLine(status)
    }

    private fun accessibilityServiceOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val ours = ComponentName(this, ContextService::class.java)
        return enabled.split(':').any { it.equals(ours.flattenToString(), true) || it.equals(ours.flattenToShortString(), true) }
    }

    /** The one tappable status line. Local state renders at once; AI and upload counts follow. */
    private fun statusLine(parent: LinearLayout) {
        val token = generation
        val s = RecordingSettings(this)
        fun draw(waiting: Int) {
            val inputs = StatusLine.Inputs(s.isAuthRejected(credentials), s.remotePaused, s.hasUsageGrant, s.hasUsagePermission(), s.contextEnabled, accessibilityServiceOn(), aiAutomatic, waiting)
            val state = StatusLine.select(inputs)
            parent.removeAllViews()
            val warn = StatusLine.needsAction(state)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = ui.dp(48)
                setPadding(ui.dp(14), ui.dp(8), ui.dp(8), ui.dp(8))
                background = ui.ripple(ui.shape(if (warn) Palette.WARN_SOFT else Palette.ACCENT_SOFT, 14f), 14f)
                isClickable = true; isFocusable = true
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                setOnClickListener { if (!busy) openStatus(state) }
            }
            row.addView(View(this).apply { background = ui.shape(if (warn) Palette.WARN_INK else Palette.ACCENT, 5f) }, LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)).apply { marginEnd = ui.dp(10) })
            val text = StatusLine.text(state, waiting)
            ui.text(row, text, 14f, if (warn) Palette.WARN_INK else Palette.ACCENT_DARK).layoutParams = ui.lp(0, weight = 1f)
            row.addView(ui.icon(R.drawable.ic_chevron, Palette.MUTED, 20))
            row.contentDescription = "$text，開啟設定"
            parent.addView(row, ui.lp(bottom = 12))
        }
        draw(0)
        if (credentials == null) return
        executor.execute {
            val waiting = runCatching { synchronized(SyncEngine.lock) { SyncEngine.waitingCaptures(this) } }.getOrDefault(0)
            val ai = runCatching { api().request("/v1/ai").let { it.optBoolean("enabled") && it.optBoolean("auto_summarize") } }.getOrNull()
            runOnUiThread {
                if (ai != null) aiAutomatic = ai
                if (token == generation && !isDestroyed && parent.isAttachedToWindow) draw(waiting)
            }
        }
    }

    private fun openStatus(state: StatusLine.State) = when (state) {
        StatusLine.State.KEY_REJECTED -> connectionRecovery()
        StatusLine.State.NOT_STARTED -> autoRecordFlow(0) { home() }
        StatusLine.State.NEEDS_USAGE_ACCESS -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        StatusLine.State.NEEDS_ACCESSIBILITY -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        StatusLine.State.AI_MISSING -> aiSettings { home() }
        else -> settings()
    }

    /** Keep the confirmed page until an explicit read; no background collection or cache writes. */
    private fun startTimelineWatch(slot: LinearLayout, data: JSONObject, checkRead: () -> JSONObject = { api().request(dayPath()) }, reloadRead: () -> JSONObject = { readJournal(dayPath()) }) {
        timelineWatch?.close(); timelineWatch = null
        if (day != journalDay() || data.optBoolean("pending_card_edits")) return
        val selected = day
        val token = generation
        val box = ui.column(null).apply {
            background = ui.shape(Palette.ACCENT_SOFT, 14f)
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(6))
            visibility = View.GONE
        }
        val message = ui.text(box, "", 14f, Palette.ACCENT_DARK).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        var state = TimelineUpdateWatch.State.CURRENT
        ui.button(box, "載入最新活動", Tone.TEXT) {
            if (state == TimelineUpdateWatch.State.ROLLOVER) { day = journalDay(); home() }
            else reloadTimeline(slot, checkRead, reloadRead)
        }
        slot.addView(box, 0, ui.lp(bottom = 12))
        fun current() = token == generation && selected == day && slot.isAttachedToWindow && !isDestroyed && !isFinishing
        fun eligible(): Boolean {
            if (!current() || !visible || busy || !hasWindowFocus() || currentFocus is EditText) return false
            val manager = getSystemService(android.net.ConnectivityManager::class.java)
            val network = manager?.getNetworkCapabilities(manager.activeNetwork) ?: return false
            return network.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (!RecordingSettings(this@MainActivity).wifiOnly || network.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        }
        timelineWatch = TimelineUpdateWatch(handler, executor, selected, data, ::eligible,
            checkRead, { next ->
                if (!current()) return@TimelineUpdateWatch
                state = next
                val previousHeight = if (box.visibility == View.VISIBLE) box.height + (box.layoutParams as LinearLayout.LayoutParams).bottomMargin else 0
                val preserve = slot.top + box.top < scroll.scrollY
                val outerY = scroll.scrollY
                message.text = when (next) {
                    TimelineUpdateWatch.State.UPDATED -> "AI 整理好新的活動了。"
                    TimelineUpdateWatch.State.FAILED -> "暫時無法檢查新活動，目前內容仍保留。"
                    TimelineUpdateWatch.State.ROLLOVER -> "新的一天開始了。"
                    TimelineUpdateWatch.State.CURRENT -> ""
                }
                box.visibility = if (next == TimelineUpdateWatch.State.CURRENT) View.GONE else View.VISIBLE
                (box.getChildAt(1) as Button).text = if (next == TimelineUpdateWatch.State.ROLLOVER) "前往今天" else "載入最新活動"
                box.post {
                    if (current() && preserve) {
                        val height = if (box.visibility == View.VISIBLE) box.height + (box.layoutParams as LinearLayout.LayoutParams).bottomMargin else 0
                        scroll.scrollTo(0, outerY + height - previousHeight)
                    }
                }
            })
        if (visible) timelineWatch?.resume()
    }

    private fun reloadTimeline(slot: LinearLayout, checkRead: () -> JSONObject, reloadRead: () -> JSONObject) {
        timelineWatch?.close(); timelineWatch = null
        val outerY = scroll.scrollY
        load(slot, "讀取最新活動…", reloadRead) { fresh ->
            renderTimeline(slot, fresh)
            startTimelineWatch(slot, fresh, checkRead, reloadRead)
            slot.post {
                scroll.scrollTo(0, outerY)
                slot.isFocusableInTouchMode = true; slot.requestFocus()
            }
        }
    }

    private fun renderTimeline(slot: LinearLayout, data: JSONObject) {
        loaded = data
        if (data.optBoolean("offline")) ui.banner(slot, "離線中：顯示手機上的副本，伺服器可能有更新。", Kind.WARN)
        if (data.optBoolean("truncated")) ui.banner(slot, "這天的資料太多，只顯示一部分，統計可能不完整。", Kind.WARN)
        if (data.optBoolean("pending_card_edits")) ui.banner(slot, "你的修改已存在手機，正在等待上傳。", Kind.INFO)
        val list = cardsOf(data)
        if (list.isEmpty()) { emptyDay(slot); return }

        // Day summary: recorded time and one stacked category bar labelled by its top categories.
        ui.card(slot).apply {
            val tracked = TimelineMath.covered(intervals(list))
            ui.text(this, "已記錄 ${TimelineMath.duration(tracked)}", 20f, bold = true)
            val all = totals(list)
            val top = all.take(4)
            val rest = all.drop(4).sumOf { it.second }
            addView(CategoryBarView(this@MainActivity, top.map { colorFor(it.first) to it.second } + if (rest > 0) listOf(Palette.FALLBACK_CATEGORY to rest) else emptyList()), ui.lp(top = 10, bottom = 8))
            val legend = top.joinToString("　") { "${label(it.first)} ${TimelineMath.duration(it.second)}" } + if (rest > 0) "　其他 ${TimelineMath.duration(rest)}" else ""
            ui.meta(this, legend)
        }

        val present = list.map { it.getString("category") }.distinct()
        if (categoryFilter.let { it != null && it !in present }) categoryFilter = null
        val head = ui.horizontal(slot, 8)
        val count = ui.text(head, "", 16f, bold = true).apply { layoutParams = ui.lp(0, weight = 1f) }
        lateinit var filter: Button
        val holder = ui.card(slot, padding = 8)
        fun draw() {
            val shown = list.filter { categoryFilter == null || it.getString("category") == categoryFilter }
            count.text = if (categoryFilter == null) "${list.size} 張活動卡" else "${shown.size} / ${list.size} 張活動卡"
            filter.text = "篩選：${categoryFilter?.let(::label) ?: "全部"}"
            holder.removeAllViews()
            shown.forEachIndexed { i, card ->
                if (i > 0) holder.addView(View(this@MainActivity).apply { setBackgroundColor(Palette.HAIRLINE) }, ui.lp(height = 1))
                cardRow(holder, card)
            }
        }
        filter = ui.button(head, "", Tone.TEXT) {
            val options = listOf<String?>(null) + present
            dialog().setTitle("顯示哪個分類")
                .setSingleChoiceItems(options.map { it?.let(::label) ?: "全部" }.toTypedArray(), options.indexOf(categoryFilter).coerceAtLeast(0)) { d, which ->
                    categoryFilter = options[which]; d.dismiss(); draw()
                }.setNegativeButton("取消", null).showSecure()
        }.apply { layoutParams = wrap() }
        draw()
    }

    private fun emptyDay(slot: LinearLayout) {
        val s = RecordingSettings(this)
        ui.card(slot).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            if (day != journalDay()) {
                ui.text(this, "這天沒有記錄", 17f, bold = true, bottom = 6).gravity = Gravity.CENTER
                ui.meta(this, "空白的時間不會被補上虛構的活動。").gravity = Gravity.CENTER
                return@apply
            }
            ui.text(this, promise, 17f, bold = true, bottom = 10).gravity = Gravity.CENTER
            fun action(label: String, onClick: () -> Unit) = ui.button(this, label, Tone.PRIMARY) { onClick() }.apply {
                layoutParams = wrap().apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(4) }
            }
            when {
                s.isAuthRejected(credentials) -> action("重新連線") { connectionRecovery() }
                !s.hasUsageGrant -> action("開始記錄") { autoRecordFlow(0) { home() } }
                aiAutomatic == false -> action("設定 AI") { aiSettings { home() } }
                else -> ui.meta(this, "手機會在背景記錄，AI 整理後會出現在這裡。").gravity = Gravity.CENTER
            }
        }
    }

    private fun cardRow(parent: LinearLayout, c: JSONObject) {
        val start = c.getLong("start_ms"); val end = c.getLong("end_ms")
        val title = cardTitle(c)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; minimumHeight = ui.dp(56); setPadding(ui.dp(6), ui.dp(12), ui.dp(6), ui.dp(12))
            isClickable = true; isFocusable = true; background = ui.ripple(null, 12f); setOnClickListener { if (!busy) cardDetail(c) }
        }
        row.addView(View(this).apply { background = ui.shape(colorFor(c.getString("category")), 2f) }, LinearLayout.LayoutParams(ui.dp(4), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = ui.dp(12) })
        val column = ui.column(row).apply { layoutParams = ui.lp(0, weight = 1f) }
        val time = "${clock(start)}–${clock(end)} · ${TimelineMath.duration(end - start)}"
        ui.meta(column, time)
        ui.text(column, title, 16f, bold = true, top = 2).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val summary = c.optString("summary")
        if (summary.isNotBlank()) ui.text(column, summary, 14f, top = 2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val tags = ui.horizontal(column, 6).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(6) }
        categoryChip(tags, c.getString("category"))
        ui.meta(tags, sourceLabel(c))
        val badges = buildList {
            if (c.optBoolean("pending_context")) add("AI 整理中")
            if (c.optBoolean("protected_screen")) add("受保護畫面（無截圖）")
        }
        if (badges.isNotEmpty()) { val more = ui.horizontal(column, 6).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(4) }; badges.forEach { ui.pill(more, it, it == "AI 整理中") } }
        row.contentDescription = listOf(title, time, label(c.getString("category")), sourceLabel(c), *badges.toTypedArray(), summary).filter { it.isNotBlank() }.joinToString("，")
        parent.addView(row, ui.lp())
    }

    private fun categoryChip(parent: ViewGroup, id: String): TextView = TextView(this).apply {
        text = label(id); textSize = 12f; setTextColor(Palette.INK)
        background = ui.shape(Palette.tint(colorFor(id), .45f), 12f); setPadding(ui.dp(10), ui.dp(3), ui.dp(10), ui.dp(3))
        parent.addView(this, wrap())
    }

    /** Card detail: what happened, one-tap category, and text editing behind 修改文字. */
    private fun cardDetail(card: JSONObject) {
        val content = ui.column(null, 20)
        val start = card.getLong("start_ms"); val end = card.getLong("end_ms")
        val app = card.optString("app")
        ui.text(content, cardTitle(card), 19f, bold = true)
        ui.meta(content, listOfNotNull("${clock(start)}–${clock(end)} · ${TimelineMath.duration(end - start)}", app.takeIf { it.isNotBlank() }?.let { appNames.name(it) }).joinToString(" · "), top = 4)
        val tags = ui.horizontal(content, 6).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(8) }
        ui.pill(tags, sourceLabel(card), card.optString("source") == "ai")
        if (card.optBoolean("pending_context")) ui.pill(tags, "AI 整理中", true)
        if (card.optBoolean("protected_screen")) ui.pill(tags, "受保護畫面（無截圖）", false)
        val summary = card.optString("summary")
        if (summary.isNotBlank()) ui.body(content, summary, top = 12)
        if (card.optBoolean("protected_screen")) ui.meta(content, "這段時間的畫面受系統保護，沒有截圖；只依 app 名稱與畫面文字整理。", top = 8)
        if (card.optBoolean("pending_context")) ui.meta(content, "還有新的截圖或文字等 AI 整理，稍後再看。", top = 8)
        if (card.optString("source") == "ai") ui.meta(content, "AI 可能有誤，請核對。", top = 8)
        ui.heading(content, "分類", top = 16)
        ui.meta(content, "點一下就會儲存；你選的分類會優先於自動分類。")
        lateinit var detail: AlertDialog
        val chips = chipRow(content)
        (categories + card.getString("category")).distinct().forEach { id ->
            val on = id == card.getString("category")
            ui.button(chips, (if (on) "✓ " else "") + label(id), if (on) Tone.PRIMARY else Tone.OUTLINE) {
                if (!on) saveCard(card, cardTitleForSave(card), summary, id) { detail.dismiss() }
            }.apply { layoutParams = wrap(); isSelected = on; contentDescription = "${label(id)}，${if (on) "目前分類" else "改成這個分類"}" }
        }
        val actions = ui.horizontal(content, 4).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(8) }
        ui.button(actions, "修改文字", Tone.TEXT) { detail.dismiss(); editCardText(card) }.layoutParams = wrap()
        if (RecordingSettings(this).replayConsent) ui.button(actions, "看截圖", Tone.TEXT) { replayForCard(card) }.layoutParams = wrap()
        detail = dialog().setView(ScrollView(this).apply { addView(content) }).setPositiveButton("關閉", null).showSecure()
    }

    private fun cardTitleForSave(card: JSONObject) = RecordingRules.cap(cardTitle(card), 200)

    private fun saveCard(card: JSONObject, title: String, summary: String, category: String, after: () -> Unit) {
        val body = JSONObject().put("id", card.getString("id")).put("title", title).put("summary", summary).put("category", category)
        task("儲存中…", { SyncEngine.enqueue(this@MainActivity, "/v1/cards", body) }) {
            after(); home(); notice(if (category != card.getString("category")) "已改成「${label(category)}」" else "已儲存修改")
        }
    }

    private fun editCardText(card: JSONObject) {
        val content = ui.column(null, 20)
        val title = ui.field(content, "標題", "", Field.TEXT, cardTitleForSave(card))
        val summary = ui.field(content, "摘要", "", Field.MULTILINE, card.optString("summary")).apply { maxLines = 6 }
        val lengthHint = ui.meta(content, "", top = 8)
        fun updateLengths() {
            val t = title.text.toString(); val s = summary.text.toString()
            lengthHint.text = "標題 ${t.codePointCount(0, t.length)} / 200 字 · 摘要 ${s.codePointCount(0, s.length)} / 1000 字"
        }
        title.addTextChangedListener(watcher { title.error = null; updateLengths() })
        summary.addTextChangedListener(watcher { summary.error = null; updateLengths() })
        updateLengths()
        ui.meta(content, "你改過的文字會優先於 AI；離線時先加密存在手機。", top = 8)
        val editor = dialog().setTitle("修改文字").setView(ScrollView(this@MainActivity).apply { addView(content) })
            .setNegativeButton("取消", null).setPositiveButton("儲存", null).showSecure()
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val t = title.text.toString(); val s = summary.text.toString()
            val titleError = when {
                t.isBlank() -> "請填寫標題。"
                t.codePointCount(0, t.length) > 200 -> "標題超過 200 字。"
                t.contains('\u0000') -> "標題含有無法儲存的字元。"
                else -> null
            }
            val summaryError = when {
                s.codePointCount(0, s.length) > 1000 -> "摘要超過 1000 字。"
                s.contains('\u0000') -> "摘要含有無法儲存的字元。"
                else -> null
            }
            title.error = titleError; summary.error = summaryError
            if (titleError != null || summaryError != null) { (if (titleError != null) title else summary).requestFocus(); return@setOnClickListener }
            if (busy) { notice("上一個操作還在處理中，請稍後再儲存。", Kind.WARN); return@setOnClickListener }
            saveCard(card, t, s, card.getString("category")) { editor.dismiss() }
        }
    }

    /** Thumbnails exist only when the separate replay consent saved them; never raw analysis images. */
    private fun replayForCard(card: JSONObject) {
        val app = card.optString("app")
        task("讀取截圖…", { api().request("/v1/replay?from_ms=${card.getLong("start_ms")}&to_ms=${card.getLong("end_ms")}") }) { data ->
            val rows = data.getJSONArray("images")
            val images = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { app.isBlank() || it.optString("app") == app }
            if (images.isEmpty()) { notice("這段時間沒有保存的截圖（只保存 24 小時）。", Kind.INFO); return@task }
            dialog().setTitle("保存的截圖").setItems(images.map { stamp(it.getLong("at_ms")) }.toTypedArray()) { _, index -> openReplay(images[index]) }
                .setNegativeButton("關閉", null).showSecure()
        }
    }

    private fun openReplay(item: JSONObject) {
        task("讀取截圖…", work = {
            val response = api().request("/v1/replay/${item.getString("id")}")
            val bytes = android.util.Base64.decode(response.getString("jpeg_base64"), android.util.Base64.DEFAULT)
            try {
                require(bytes.size <= 24576)
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth in 1..480 && bounds.outHeight in 1..480)
                requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            } finally { bytes.fill(0) }
        }, failed = { replayUnavailable(it) }, discard = { it.recycle() }) { bitmap -> showReplayBitmap(bitmap, item) }
    }

    /** A missing image is normal under expiry or deletion by another client. */
    private fun replayUnavailable(error: Throwable) {
        if (error is ApiFailure && error.status == 404) notice("這張截圖已到期或被刪除。", Kind.WARN)
    }

    private fun showReplayBitmap(bitmap: android.graphics.Bitmap, item: JSONObject) {
        replayDialog?.dismiss()
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            contentDescription = "${appNames.name(item.getString("app"))} ${stamp(item.getLong("at_ms"))} 的截圖"
        }
        var confirmation: AlertDialog? = null
        val preview = dialog().setTitle(stamp(item.getLong("at_ms"))).setView(image)
            .setNegativeButton("關閉", null).setPositiveButton("刪除", null).create()
        preview.setOnDismissListener {
            confirmation?.dismiss(); confirmation = null
            image.setImageDrawable(null)
            if (!bitmap.isRecycled) bitmap.recycle()
            if (replayDialog === preview) replayDialog = null
        }
        try {
            protect(preview)
            replayDialog = preview
            preview.show()
            preview.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (confirmation?.isShowing == true || busy) return@setOnClickListener
                val confirm = dialog().setTitle("刪除這張截圖？")
                    .setMessage("只刪除這張保存的截圖，無法復原。活動卡和已送給 AI 的資料不會因此刪除。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("刪除") { _, _ ->
                        if (replayDialog === preview && preview.isShowing && !isDestroyed && !isFinishing) {
                            preview.dismiss()
                            task("刪除截圖…", { api().request("/v1/replay/${item.getString("id")}", method = "DELETE") }, failed = { replayUnavailable(it) }) { notice("已刪除截圖") }
                        }
                    }.create()
                confirmation = confirm
                confirm.setOnDismissListener {
                    if (confirmation === confirm) confirmation = null
                    if (preview.isShowing) preview.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
                }
                protect(confirm)
                confirm.show()
            }
        } catch (error: Throwable) {
            replayDialog = null
            preview.dismiss()
            image.setImageDrawable(null)
            if (!bitmap.isRecycled) bitmap.recycle()
            throw error
        }
    }

    // ---------------------------------------------------------------- 回顧

    private data class ReviewData(val overview: JSONObject, val ai: JSONObject?, val briefs: JSONObject?, val days: List<Pair<LocalDate, JSONObject?>>?, val conversation: JSONObject?)
    private fun periodKey(weekly: Boolean) = if (weekly) "w:${TimelineMath.weekStart(day)}" else "d:$day"

    private fun review() {
        val weekly = reviewWeekly
        val first = if (weekly) TimelineMath.weekStart(day) else day
        val span = if (weekly) "${md(first)} – ${md(first.plusDays(6))}" else dateTitle(day)
        val body = screen("回顧", if (weekly) "這一週" else relative(day), Tab.REVIEW)
        ui.segmented(body, listOf("日", "週"), if (weekly) 1 else 0, "回顧範圍") { reviewWeekly = it == 1; review() }
        View(this).also { body.addView(it, ui.lp(height = ui.dp(12))) }
        dateNav(body, if (weekly) 7 else 1, span) { day = it; review() }
        val slot = ui.column(body)
        val (from, to) = range(first, if (weekly) 7 else 1)
        val key = periodKey(weekly)
        load(slot, "整理這段時間…", {
            val api = api()
            val overview = readJournal("/v1/overview?from_ms=$from&to_ms=$to")
            val ai = runCatching { api.request("/v1/ai") }.getOrNull()
            val briefs = runCatching { api.request("/v1/briefs?from_ms=$from&to_ms=$to") }.getOrNull()
            val today = journalDay()
            val days = if (!weekly) null else (0L..6L).map { first.plusDays(it) }.map { d ->
                d to if (d > today || !TimelineMath.inHistory(d, today)) null else runCatching { readJournal(dayPath(d)) }.getOrNull()
            }
            val conversation = conversations[key]?.let { id -> runCatching { api.request("/v1/conversations/$id") }.getOrNull() }
            ReviewData(overview, ai, briefs, days, conversation)
        }) { data ->
            data.ai?.let { aiAutomatic = it.optBoolean("enabled") && it.optBoolean("auto_summarize") }
            renderReview(slot, data, weekly, from, to, key)
        }
    }

    private fun renderReview(slot: LinearLayout, data: ReviewData, weekly: Boolean, from: Long, to: Long, key: String) {
        if (data.overview.optBoolean("offline")) ui.banner(slot, "離線中：顯示手機上的副本。", Kind.WARN)
        val ai = data.ai
        val aiOn = ai?.optBoolean("enabled") == true
        val aiHost = AiProviders.host(ai?.optString("endpoint"))

        // AI 回顧
        ui.card(slot).apply {
            ui.heading(this, "AI 回顧")
            when {
                ai == null -> ui.meta(this, "目前讀不到 AI 設定（可能離線）。")
                !aiOn -> {
                    ui.body(this, "設定 AI 之後，可以一鍵整理這段時間。")
                    ui.button(this, "先設定 AI", Tone.TONAL) { aiSettings { review() } }
                }
                else -> {
                    val kind = if (weekly) "weekly" else "daily"
                    val rows = data.briefs?.optJSONArray("briefs") ?: JSONArray()
                    val brief = (0 until rows.length()).map { rows.getJSONObject(it) }.lastOrNull { it.optString("kind") == kind }
                    if (brief != null) {
                        ui.body(this, brief.getString("text"), top = 4)
                        ui.meta(this, "AI 產生，可能有誤，請核對。", top = 8)
                        val buttons = ui.horizontal(this, 4)
                        ui.button(buttons, "重新產生", Tone.TEXT) { generateBrief(weekly, ai, from, to, fresh = true) }.layoutParams = wrap()
                        ui.button(buttons, "刪除", Tone.TEXT) {
                            confirm("刪除這份回顧？", "只刪除這份 AI 回顧，活動卡不受影響。", "刪除") {
                                task("刪除中…", { api().request("/v1/briefs/${brief.getString("id")}", method = "DELETE") }) { review(); notice("已刪除") }
                            }
                        }.layoutParams = wrap()
                    } else {
                        if (data.briefs == null) ui.meta(this, "目前讀不到已保存的回顧。", bottom = 4)
                        ui.button(this, "產生 AI 回顧") { generateBrief(weekly, ai, from, to, fresh = false) }
                    }
                    ui.meta(this, "會把這段時間的活動卡送到 $aiHost", top = 4)
                }
            }
        }

        // 時間分配
        val categoriesJson = data.overview.optJSONArray("categories") ?: JSONArray()
        val rows = (0 until categoriesJson.length()).map { categoriesJson.getJSONObject(it).let { c -> c.getString("category") to c.getLong("duration_ms") } }.filter { it.second > 0 }
        ui.card(slot).apply {
            ui.heading(this, "時間分配")
            data.days?.let { weekBars(this, it) }
            if (rows.isEmpty()) ui.meta(this, "這段時間沒有活動。") else categoryBars(this, rows)
        }

        // 常用 app and 主題
        val apps = data.overview.optJSONArray("apps") ?: JSONArray()
        if (apps.length() > 0) ui.card(slot).apply {
            ui.heading(this, "常用 app")
            for (i in 0 until minOf(5, apps.length())) {
                val a = apps.getJSONObject(i)
                val row = ui.horizontal(this, 8).apply { minimumHeight = ui.dp(32) }
                ui.text(row, appNames.name(a.getString("app")), 15f).layoutParams = ui.lp(0, weight = 1f)
                ui.text(row, TimelineMath.duration(a.getLong("duration_ms")), 14f, Palette.MUTED).layoutParams = wrap()
            }
        }
        val keywords = data.overview.optJSONArray("title_keywords") ?: JSONArray()
        if (keywords.length() > 0) ui.card(slot).apply {
            ui.heading(this, "主題")
            val chips = chipRow(this)
            for (i in 0 until minOf(12, keywords.length())) ui.pill(chips, keywords.getJSONArray(i).getString(0), true)
        }

        // 問 AI
        ui.card(slot).apply {
            ui.heading(this, "問 AI")
            if (!aiOn) { ui.meta(this, if (ai == null) "目前讀不到 AI 設定。" else "設定 AI 之後，可以問這段時間的事。"); return@apply }
            val answers = ui.column(this)
            val turns = data.conversation?.optJSONArray("turns") ?: JSONArray()
            for (i in 0 until turns.length()) turns.getJSONObject(i).let { bubble(answers, it.getString("question"), true); bubble(answers, it.getString("answer"), false) }
            val draftKey = key
            val question = ui.field(this, "你的問題", "問問這段時間…", Field.MULTILINE, questionDrafts[draftKey] ?: "")
            question.filters = arrayOf(android.text.InputFilter.LengthFilter(1000))
            question.addTextChangedListener(watcher { questionDrafts.put(draftKey, it) })
            val examples = chipRow(this)
            listOf("我花最多時間在哪？", "這週學了什麼？", "有哪些分心的時段？").forEach { sample ->
                ui.button(examples, sample, Tone.TONAL) { question.setText(sample); ask(question, answers, ai, from, to, key) }.layoutParams = wrap()
            }
            ui.button(this, "問 AI") { ask(question, answers, ai, from, to, key) }
            ui.meta(this, "會把問題和這段時間的活動卡送到 $aiHost；問答保存在你的伺服器 30 天。", top = 4)
            if (turns.length() > 0) ui.button(this, "開始新的對話", Tone.TEXT) { conversations.remove(key); review() }.layoutParams = wrap()
        }
    }

    /** Seven small daily bars for the week view; tapping one opens that day. */
    private fun weekBars(parent: LinearLayout, days: List<Pair<LocalDate, JSONObject?>>) {
        val totals = days.map { (_, data) -> TimelineMath.covered(intervals(cardsOf(data))) }
        val max = totals.maxOrNull()?.coerceAtLeast(1) ?: 1
        val row = ui.horizontal(parent, 6).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(12); gravity = Gravity.BOTTOM }
        days.forEachIndexed { i, (date, _) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM; isClickable = true; isFocusable = true
                background = ui.ripple(null, 8f); contentDescription = "${md(date)}，${TimelineMath.duration(totals[i])}，開啟這一天"
                setOnClickListener { if (!busy) { day = date; reviewWeekly = false; review() } }
            }
            val track = FrameLayout(this)
            track.addView(View(this).apply { background = ui.shape(if (date == day) Palette.ACCENT else Palette.ACCENT_SOFT, 4f) },
                FrameLayout.LayoutParams(ui.dp(18), (ui.dp(64) * totals[i].toFloat() / max).toInt().coerceAtLeast(if (totals[i] > 0) ui.dp(3) else 0), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            cell.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(64)))
            ui.text(cell, date.format(DateTimeFormatter.ofPattern("E", Locale.TRADITIONAL_CHINESE)).takeLast(1), 12f, Palette.MUTED, top = 4).apply { gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun bubble(parent: LinearLayout, value: String, mine: Boolean) {
        if (!mine) ui.text(parent, "AI 回答，請核對", 12f, Palette.MUTED, top = 8)
        parent.addView(ui.text(null, value, 15f, if (mine) android.graphics.Color.WHITE else Palette.INK).apply {
            background = ui.shape(if (mine) Palette.ACCENT else Palette.CANVAS, 16f); setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            contentDescription = (if (mine) "你：" else "AI：") + value
        }, wrap().apply {
            gravity = if (mine) Gravity.END else Gravity.START; topMargin = ui.dp(if (mine) 12 else 4)
            if (mine) marginStart = ui.dp(40) else marginEnd = ui.dp(40)
        })
    }

    private fun generateBrief(weekly: Boolean, ai: JSONObject, from: Long, to: Long, fresh: Boolean) {
        val kind = if (weekly) "weekly" else "daily"
        val provider = ai.getString("provider_id")
        // One identity per attempt so a retry replays instead of calling the model twice.
        val request = pendingBrief?.takeIf { !fresh && it.optLong("from_ms") == from && it.optLong("to_ms") == to && it.optString("kind") == kind && it.optString("provider_id") == provider }
            ?: JSONObject().put("id", UUID.randomUUID().toString()).put("from_ms", from).put("to_ms", to).put("kind", kind).put("provider_id", provider).put("consent", true).also { pendingBrief = it }
        task("AI 正在整理…", { api().request("/v1/briefs", request) }, failed = { error ->
            if (error is ApiFailure && error.status == 409) { pendingBrief = null; notice("AI 設定剛改過，請再按一次。", Kind.WARN); review() }
        }) { pendingBrief = null; review(); notice("AI 回顧完成，請核對。") }
    }

    private fun ask(question: EditText, answers: LinearLayout, ai: JSONObject, from: Long, to: Long, key: String) {
        val text = question.text.toString().trim()
        if (text.isBlank()) { notice("請輸入問題", Kind.WARN); return }
        val conversation = conversations.getOrPut(key) { UUID.randomUUID().toString() }
        val provider = ai.getString("provider_id")
        val asked = RecordingRules.cap(text, 1000)
        // Reuse the request id when retrying the same question so the server can replay the answer.
        val request = pendingAsk?.takeIf { it.optString("conversation_id") == conversation && it.optString("question") == asked && it.optLong("from_ms") == from && it.optString("provider_id") == provider }
            ?: JSONObject().put("conversation_id", conversation).put("request_id", UUID.randomUUID().toString()).put("question", asked)
                .put("from_ms", from).put("to_ms", to).put("provider_id", provider).put("consent", true).also { pendingAsk = it }
        val token = generation
        task("等待 AI 回答…", { api().request("/v1/ask", request) }, failed = { error ->
            if (error is ApiFailure && error.status == 409) { pendingAsk = null; notice("AI 設定或這段對話剛改過，請重新整理後再問。", Kind.WARN) }
        }) { result ->
            pendingAsk = null
            questionDrafts.removeIfUnchanged(key, question.text.toString())
            if (token != generation) return@task
            question.text.clear()
            bubble(answers, asked, true); bubble(answers, result.getString("answer"), false)
            notice("AI 已回答，請核對。")
        }
    }

    // ---------------------------------------------------------------- 設定

    private fun effectiveAllowedCount(settings: RecordingSettings): Int {
        val allowed = settings.allowed
        return allowed.count { RecordingRules.contextAllowed(it, allowed, settings.exclusions, packageName) }
    }

    private fun recordingSummary(s: RecordingSettings): String = when {
        s.isAuthRejected(credentials) -> "金鑰失效，已停止"
        s.remotePaused -> "所有裝置已暫停"
        !s.hasUsageGrant -> "沒有在記錄"
        s.contextEnabled -> "記錄 ${effectiveAllowedCount(s)} 個 app 的畫面文字${if (s.imagesEnabled) "與截圖" else ""}"
        else -> "只記錄 app 使用時段"
    }

    private fun settings() {
        val s = RecordingSettings(this@MainActivity)
        val body = screen("設定", null, Tab.SETTINGS, refresh = { settings() })
        if (s.isAuthRejected(credentials)) connectionRecoveryCallout(body)

        ui.groupLabel(body, "記錄")
        ui.card(body, padding = 8).apply {
            ui.meta(this, recordingSummary(s)).setPadding(ui.dp(4), ui.dp(8), ui.dp(4), 0)
            ui.switchRow(this, "自動記錄", "手機自動記下你看過的畫面", s.hasUsageGrant) { on ->
                if (busy) { settings(); return@switchRow }
                if (on) autoRecordFlow(0) { settings() } else confirm("停止自動記錄？", "之後不再記錄 app 使用時段、畫面文字和截圖。還沒上傳的截圖會刪除；已上傳的記錄保留。", "停止", { settings() }) { stopRecording(s) }
            }.apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) }
            ui.switchRow(this, "截圖", if (s.contextEnabled) "和畫面文字一起記錄" else "需要先開啟自動記錄", s.imagesEnabled) { on -> toggleImages(s, on) }.apply {
                setPadding(ui.dp(4), 0, ui.dp(4), 0); isEnabled = s.contextEnabled || s.imagesEnabled
            }
            ui.row(this, "記錄哪些 app", "記錄 ${s.allowed.size} 個 · 排除 ${s.exclusions.size} 個") { appListsPage() }
            ui.row(this, "畫面文字", if (s.contextEnabled) "允許的 app 每 10 秒記一次" else "目前只記錄 app 使用時段", (if (s.contextEnabled) "開啟" else if (s.hasContextGrant) "已保存" else "關閉") to s.contextEnabled) { toggleContext(s) }
            val usage = s.hasUsagePermission()
            ui.row(this, "使用情況存取", "Android 權限：知道你用了哪個 app", (if (usage) "已允許" else "未允許") to usage) { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            val service = accessibilityServiceOn()
            ui.row(this, "輔助使用", "Android 權限：讀取畫面文字與截圖", (if (service) "已開啟" else "未開啟") to service) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            ui.row(this, "保存截圖供回看", if (s.replayConsent) "可在活動卡裡回看 24 小時內的截圖" else "預設關閉", (if (s.replayConsent) "開啟" else "關閉") to s.replayConsent) { toggleReplay(s) }
            ui.row(this, "疑似密碼的文字", "畫面出現疑似金鑰或長數字時略過", (if (s.privacyShield) "略過" else "不檢查") to s.privacyShield) { privacyShieldDialog(s) }
            ui.row(this, "暫停所有裝置", if (s.remotePaused) "已暫停，點此恢復" else "在伺服器暫停每一台裝置", (if (s.remotePaused) "已暫停" else "運作中") to !s.remotePaused) { manageRemotePause(s) }
            ui.switchRow(this, "只用 Wi‑Fi 上傳", "關閉後也會用行動數據", s.wifiOnly) { checked ->
                if (checked == s.wifiOnly) return@switchRow
                task("更新…", { synchronized(SyncEngine.lock) { s.wifiOnly = checked }; SyncEngine.schedule(this@MainActivity) }) { notice(if (checked) "只用 Wi‑Fi 上傳" else "也會用行動數據上傳") }
            }.apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) }
            ui.meta(this, "截圖在充電時上傳，平常只記錄、不耗流量。", top = 4, bottom = 4).setPadding(ui.dp(4), 0, ui.dp(4), 0)
            ui.row(this, "最近一次記錄", CaptureDiagnostics.snapshot().outcome.title) { captureDiagnostics { settings() } }
        }

        ui.groupLabel(body, "AI")
        ui.card(body, padding = 8).apply {
            val slot = ui.column(this)
            load(slot, "讀取 AI 設定…", { api().request("/v1/ai") }) { config -> aiRows(slot, config) { settings() } }
        }

        ui.groupLabel(body, "分類")
        ui.card(body, padding = 8).apply {
            val styles = workspace?.optJSONArray("categories")
            val rules = workspace?.optJSONArray("rules")
            ui.row(this, "分類與自動規則", if (styles != null) "${styles.length()} 個分類 · ${rules?.length() ?: 0} 條規則" else "名稱、顏色與自動分類") { categoriesPage() }
        }

        ui.groupLabel(body, "連線")
        ui.card(body, padding = 8).apply {
            ui.row(this, "伺服器", host(), onClick = null)
            ui.row(this, "立即同步", s.status()) { task("同步中…", { SyncEngine.sync(this@MainActivity) }) { settings(); notice(RecordingSettings(this@MainActivity).status(), Kind.INFO) } }
            val queue = ui.column(this)
            load(queue, "讀取待上傳資料…", { synchronized(SyncEngine.lock) { val q = JournalQueue(this@MainActivity); Triple(q.counts(), q.observationBacklog().first, SyncEngine.charging(this@MainActivity)) } }) { (counts, captures, charging) ->
                val (pending, rejected) = counts
                ui.row(queue, "待上傳", if (pending == 0) "沒有" else "$pending 筆" + if (captures > 0) "，其中 $captures 張截圖${if (charging) "正在上傳" else "等充電時上傳"}" else "", onClick = null)
                if (rejected > 0) {
                    ui.row(queue, "重試被拒絕的 $rejected 筆", "先確認網址與金鑰正確") {
                        task("重試中…", { synchronized(SyncEngine.lock) { JournalQueue(this@MainActivity).retryRejected(requireNotNull(credentials)); s.nextAttempt = 0 }; SyncEngine.sync(this@MainActivity) }) { settings(); notice(RecordingSettings(this@MainActivity).status(), Kind.INFO) }
                    }
                    ui.row(queue, "刪除被拒絕的資料", "無法復原") {
                        confirm("刪除被拒絕的資料？", "這些資料不會再上傳。", "刪除") {
                            task("刪除中…", { synchronized(SyncEngine.lock) { JournalQueue(this@MainActivity).clearRejected() } }) { settings(); notice("已刪除") }
                        }
                    }
                }
            }
            ui.row(this, "更換伺服器或金鑰", "清除這支手機的連線與待上傳資料，再重新設定") {
                confirmReset("更換連線？", "會停止記錄，刪除這支手機還沒上傳的資料，然後重新輸入網址與金鑰。伺服器上的記錄不會被刪除。")
            }
        }

        ui.groupLabel(body, "資料")
        ui.card(body, padding = 8).apply {
            val storage = ui.column(this)
            load(storage, "讀取儲存空間…", { api().request("/v1/storage") }) { info -> storageRows(storage, info) }
            ui.switchRow(this, "防止截圖這個 app", "開啟後，PocketLog 的畫面無法被截圖或錄影", s.protectAppScreen) { on ->
                task("更新…", { s.protectAppScreen = on }) { applyScreenProtection(); notice(if (on) "已防止截圖這個 app" else "已允許截圖這個 app") }
            }.apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) }
            ui.row(this, "匯出這一天", "${md(day)} 的活動卡，存成 JSON 檔") { export() }
            ui.row(this, "清除這支手機的資料", "停止記錄並刪除還沒上傳的資料；伺服器資料保留") {
                confirmReset("清除這支手機的資料？", "會停止記錄並刪除還沒上傳的資料。伺服器上的記錄仍保留。")
            }
            ui.row(this, "永久刪除伺服器上的全部記錄", "無法復原") {
                confirm("永久刪除全部記錄？", "會刪除伺服器上所有活動、截圖與文字，並停止記錄、清除這支手機的待上傳資料。無法復原。", "全部刪除") {
                    task("刪除中…", { synchronized(SyncEngine.lock) { SyncEngine.clearLocal(this@MainActivity); api().request("/v1/journal", method = "DELETE") } }) { clearDrafts(); settings(); notice("已刪除伺服器上的全部記錄，記錄已停止。", Kind.INFO) }
                }
            }
        }

        ui.groupLabel(body, "說明")
        ui.card(body, padding = 8).apply {
            ui.row(this, "使用說明", "沒有活動時怎麼辦、目前限制") { helpPage() }
            ui.row(this, "重新看一次介紹", null) { onboarding(0) { settings() } }
            ui.row(this, "PocketLog ${version()}", "MIT 授權 · 自架私人日誌 · 不做分析追蹤", onClick = null)
        }
    }

    private fun connectionRecoveryCallout(parent: ViewGroup) {
        callout(parent, "伺服器不接受這把金鑰，手機已停止記錄。手機上的資料仍加密保存。", Kind.ERROR, "重新連線") { connectionRecovery() }
    }

    private fun connectionRecovery() {
        confirmReset("更換失效的連線？", "請先在網站「設定 → 連線與裝置 → 管理裝置金鑰」建立新的手機金鑰。\n\n繼續會停止記錄、刪除這支手機還沒上傳的資料，再重新輸入網址與金鑰。伺服器上的記錄不會被刪除。")
    }

    private fun stopRecording(s: RecordingSettings) {
        task("停止記錄…", { synchronized(SyncEngine.lock) { UsageCollector(this@MainActivity).reset(); s.enabled = false; JournalQueue(this@MainActivity).clearContexts() } }) {
            SyncEngine.schedule(this@MainActivity); settings(); notice("已停止記錄；還沒上傳的截圖已刪除。")
        }
    }

    /**
     * 自動記錄 guided sequence: explain → 使用情況存取 → 輔助使用 → choose apps → done.
     * Each step is one screen with one main button; the underlying consents stay independent.
     */
    private fun autoRecordFlow(step: Int, done: () -> Unit) {
        val s = RecordingSettings(this)
        val titles = listOf("自動記錄", "允許使用情況存取", "允許輔助使用", "選擇要記錄的 app", "開始記錄了")
        val body = screen(titles[step], if (step in 1..3) "$step / 3" else null, nav = false, back = done, refresh = { autoRecordFlow(step, done) })
        fun next() = autoRecordFlow(step + 1, done)
        when (step) {
            0 -> {
                if (s.isAuthRejected(credentials)) { connectionRecoveryCallout(body); return }
                ui.card(body).apply {
                    ui.text(this, promise, 18f, bold = true, bottom = 10)
                    ui.heading(this, "會記下")
                    ui.body(this, "• 你允許的 app 在前景時，每 10 秒記下畫面文字和一張截圖（畫面沒變就略過）\n• 什麼時候用了哪個 app")
                    ui.heading(this, "不會記下", top = 12)
                    ui.body(this, "• 有密碼或輸入欄位的畫面、鎖定畫面\n• 你排除的 app，以及銀行、密碼管理等 app\n• 受保護的畫面（例如影片、銀行 app）只留文字，沒有截圖")
                    ui.meta(this, "截圖先加密存在手機，充電時才上傳到你的伺服器。AI 整理在伺服器上進行，不耗手機電量。尚未完成全天耗電量測。", top = 12)
                }
                if (s.remotePaused) { callout(body, "伺服器已暫停所有裝置，請先在「設定 → 記錄 → 暫停所有裝置」恢復。", Kind.WARN, "前往設定") { settings() }; return }
                ui.button(body, "同意並繼續") {
                    if (s.hasUsageGrant) { next(); return@button }
                    task("開始記錄…", { synchronized(SyncEngine.lock) { require(!s.remotePaused && !s.isAuthRejected(credentials)); UsageCollector(this@MainActivity).reset(); s.enabled = true; JournalQueue(this@MainActivity).clearContexts() } }) {
                        SyncEngine.schedule(this@MainActivity); next()
                    }
                }
            }
            1 -> {
                val granted = s.hasUsagePermission()
                ui.card(body).apply {
                    ui.body(this, "Android 需要你允許「使用情況存取」，PocketLog 才知道你什麼時候用了哪個 app。")
                    ui.meta(this, "在接下來的畫面找到 PocketLog，打開開關後按返回。", top = 8)
                }
                if (granted) { ui.banner(body, "已允許。", Kind.SUCCESS); ui.button(body, "繼續") { next() } }
                else ui.button(body, "開啟 Android 設定") { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }
            2 -> {
                val on = accessibilityServiceOn() && s.contextEnabled
                ui.card(body).apply {
                    ui.body(this, "「輔助使用」讓 PocketLog 讀取你允許的 app 的畫面文字並截圖。")
                    ui.meta(this, "在接下來的畫面點「PocketLog」，打開後按返回。Android 會顯示一段標準警告。", top = 8)
                }
                if (on) { ui.banner(body, "已開啟。", Kind.SUCCESS); ui.button(body, "繼續") { next() } }
                else ui.button(body, "開啟 Android 設定") {
                    task("準備中…", { synchronized(SyncEngine.lock) { require(s.enabled); s.contextEnabled = true; s.analysisConsent = true; s.imagesEnabled = true } }) {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }
                ui.button(body, "只記錄 app 使用時段", Tone.TEXT) { autoRecordFlow(4, done) }
            }
            3 -> {
                ui.card(body).apply {
                    ui.body(this, "只有你選的 app 會被記下畫面。建議從瀏覽器、閱讀和工作 app 開始；聊天或私人 app 可以不選。")
                    ui.meta(this, "銀行、密碼管理、驗證器等 app 一律不記錄。", top = 8)
                }
                val summary = ui.meta(body, if (s.allowed.isEmpty()) "還沒有選擇 app。" else "已選擇 ${effectiveAllowedCount(s)} 個 app。", bottom = 8)
                summary.setPadding(ui.dp(4), 0, ui.dp(4), 0)
                ui.button(body, if (s.allowed.isEmpty()) "選擇 app" else "修改選擇", Tone.TONAL) {
                    task("讀取已安裝的 app…", { installedApps() }) { apps ->
                        val initial = s.allowed.ifEmpty { RecordingRules.suggested(apps.map { it.packageName }, s.exclusions, packageName) }
                        AppPicker.show(this@MainActivity, apps, initial) { chosen ->
                            task("儲存…", { synchronized(SyncEngine.lock) { s.allowed = chosen; JournalQueue(this@MainActivity).clearContexts() } }) { autoRecordFlow(3, done) }
                        }
                        if (s.allowed.isEmpty()) notice("已先勾選建議的 app，可以再調整。", Kind.INFO)
                    }
                }
                if (s.allowed.isNotEmpty()) ui.button(body, "完成") { next() }
            }
            else -> {
                ui.card(body).apply {
                    ui.text(this, recordingSummary(s), 18f, bold = true, bottom = 8)
                    ui.body(this, "手機會在背景記錄，AI 整理後會出現在時間軸。截圖在充電時上傳。")
                    ui.meta(this, "隨時可以在「設定 → 記錄」調整或關閉。", top = 8)
                }
                ui.button(body, "完成") { done() }
            }
        }
    }

    private fun installedApps(): List<AppPicker.App> =
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), android.content.pm.PackageManager.ResolveInfoFlags.of(0))
            .take(AppPicker.MAX_APPS).map { AppPicker.App(it.activityInfo.packageName, RecordingRules.cap(it.loadLabel(packageManager).toString(), 80)) }
            .distinctBy { it.packageName }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

    /** Disclose retained independent choices before releasing an upstream capture gate. */
    private fun savedCaptureDisclosure(s: RecordingSettings): String {
        val saved = buildList {
            if (s.hasContextGrant) add("畫面文字")
            if (s.hasImagesGrant) add("截圖")
            if (s.hasReplayGrant) add("保存截圖供回看")
        }
        if (saved.isEmpty()) return ""
        return "\n\n恢復後會沿用這支手機已同意的：" + saved.joinToString("、") + "。不想沿用的話，請取消並在設定逐項關閉。"
    }

    private fun manageRemotePause(s: RecordingSettings) {
        task("讀取狀態…", { api().request("/v1/collection") }) { control ->
            val paused = !control.getBoolean("paused")
            confirm(if (paused) "暫停所有裝置？" else "恢復所有裝置？",
                (if (paused) "伺服器會拒收新的記錄，並刪除還沒整理的截圖與文字。離線的手機要等下次連線才會知道。" else "每台裝置會依自己原本的設定繼續記錄。") + if (!paused) savedCaptureDisclosure(s) else "",
                if (paused) "暫停" else "恢復") {
                task("更新…", {
                    synchronized(SyncEngine.lock) {
                        val result = api().request("/v1/collection", JSONObject(control.toString()).put("paused", paused), "PUT")
                        s.remotePaused = paused; UsageCollector(this@MainActivity).reset(); if (paused) JournalQueue(this@MainActivity).clearCollected(); result
                    }
                }) { settings(); notice(if (paused) "已暫停所有裝置" else "已恢復記錄") }
            }
        }
    }

    private fun toggleContext(s: RecordingSettings) {
        if (s.hasContextGrant) {
            confirm("關閉畫面文字？", "之後只記錄 app 使用時段，不再記下畫面文字與截圖。還沒上傳的截圖會刪除。", "關閉") {
                task("關閉中…", { synchronized(SyncEngine.lock) { s.contextEnabled = false; s.analysisConsent = false; JournalQueue(this@MainActivity).clearContexts() } }) { settings(); notice("已關閉畫面文字") }
            }
            return
        }
        if (s.isAuthRejected(credentials)) { connectionRecovery(); return }
        if (!s.enabled) { autoRecordFlow(0) { settings() }; return }
        confirm("記錄畫面文字與截圖？", "你允許的 app 在前景時，每 10 秒記下畫面文字和一張截圖（最長邊 720 像素），畫面沒變就略過。有密碼或輸入欄位的畫面、鎖定畫面會略過；受保護的畫面只留文字。截圖可能含私人內容，請只允許你願意記錄的 app。內容加密存在手機，充電時上傳到你的伺服器並長期保存；伺服器開啟 AI 自動整理時會送給你設定的 AI。\n\n下一步會開啟 Android 輔助使用設定。", "同意") {
            task("開啟中…", { synchronized(SyncEngine.lock) { require(s.enabled); s.contextEnabled = true; s.analysisConsent = true; s.imagesEnabled = true } }) {
                if (s.allowed.isEmpty()) autoRecordFlow(3) { settings() } else settings()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }

    private fun toggleImages(s: RecordingSettings, on: Boolean) {
        if (busy) { settings(); return }
        if (!on) {
            task("關閉截圖…", { synchronized(SyncEngine.lock) { s.imagesEnabled = false; JournalQueue(this@MainActivity).clearContexts() } }) { settings(); notice("已關閉截圖，只記錄畫面文字；還沒上傳的截圖已刪除。") }
            return
        }
        if (!s.contextEnabled) { settings(); notice("請先開啟自動記錄與畫面文字", Kind.WARN); return }
        task("開啟截圖…", { synchronized(SyncEngine.lock) { require(s.contextEnabled); s.imagesEnabled = true } }) { settings(); notice("已開啟截圖") }
    }

    private fun toggleReplay(s: RecordingSettings) {
        if (s.hasReplayGrant) {
            task("關閉中…", { synchronized(SyncEngine.lock) { s.replayConsent = false; JournalQueue(this@MainActivity).clearContexts() } }) { settings(); notice("已關閉保存截圖供回看") }
            return
        }
        if (s.isAuthRejected(credentials)) { connectionRecovery(); return }
        if (!s.imagesEnabled) { notice("請先開啟截圖", Kind.WARN); return }
        confirm("保存截圖供回看？", "之後的截圖會在伺服器另外保存一份縮圖（最多 24 小時、256 張），可以從活動卡打開回看。可能包含私人內容。關閉時不會刪除已保存的縮圖，它們會自然到期。", "同意") {
            task("更新…", { synchronized(SyncEngine.lock) { require(s.imagesEnabled); s.replayConsent = true; JournalQueue(this@MainActivity).clearContexts() } }) { settings(); notice("已開啟保存截圖供回看") }
        }
    }

    private fun privacyShieldDialog(s: RecordingSettings) {
        confirm("疑似密碼的文字", "開啟時，畫面文字出現疑似 API 金鑰、token、密碼或 13–19 位長數字時，整個畫面都不記錄。這是保守的文字檢查，可能誤判，也認不出只在圖片裡的秘密。有密碼或輸入欄位的畫面無論如何都會略過。",
            if (s.privacyShield) "關閉檢查" else "開啟檢查") {
            task("更新…", { synchronized(SyncEngine.lock) { s.privacyShield = !s.privacyShield; JournalQueue(this@MainActivity).clearContexts() } }) { settings(); notice(if (s.privacyShield) "已開啟檢查" else "已關閉檢查", Kind.INFO) }
        }
    }

    private fun appListsPage() {
        val s = RecordingSettings(this@MainActivity)
        val body = screen("記錄哪些 app", "套件名稱，每行一個", tab = Tab.SETTINGS, back = { settings() })
        val excluded: EditText; val allowed: EditText
        ui.card(body).apply {
            ui.heading(this, "要記錄畫面的 app")
            ui.meta(this, "只有這些 app 會被記下畫面文字與截圖。")
            allowed = ui.field(this, "記錄的 app", "例如 org.example.reader", Field.MULTILINE, s.allowed.sorted().joinToString("\n"))
            ui.button(this, "從已安裝的 app 選擇", Tone.TONAL) { chooseApps(allowed, suggest = true) }
        }
        ui.card(body).apply {
            ui.heading(this, "排除的 app")
            ui.meta(this, "完全不記錄，連使用時段也不記。排除優先於上面的清單。PocketLog 自己、銀行、密碼管理與驗證器等 app 會自動排除，但無法認出全部，請自行加入。")
            excluded = ui.field(this, "排除的 app", "例如 com.example.private", Field.MULTILINE, s.exclusions.sorted().joinToString("\n"))
            ui.button(this, "從已安裝的 app 選擇", Tone.TONAL) { chooseApps(excluded, suggest = false) }
        }
        ui.button(body, "儲存") {
            val lists = runCatching { RecordingRules.packages(excluded.text.toString()) to RecordingRules.packages(allowed.text.toString()) }.getOrNull()
            if (lists == null) { notice("請輸入完整的套件名稱，例如 com.example.app", Kind.WARN); return@button }
            task("儲存…", { synchronized(SyncEngine.lock) { s.exclusions = lists.first; s.allowed = lists.second; JournalQueue(this@MainActivity).clearContexts(); UsageCollector(this@MainActivity).reset() } }) { settings(); notice("已儲存") }
        }
    }

    private fun chooseApps(field: EditText, suggest: Boolean) {
        // Views belong to the main thread, including validation of a manually edited list.
        val initial = try {
            RecordingRules.packages(field.text.toString())
        } catch (_: IllegalArgumentException) {
            val message = "請輸入完整的套件名稱，例如 com.example.app"
            field.error = message
            field.requestFocus()
            notice(message, Kind.WARN)
            return
        }
        field.error = null
        task("讀取已安裝的 app…", { installedApps() }) { apps ->
            val start = if (suggest && initial.isEmpty()) RecordingRules.suggested(apps.map { it.packageName }, RecordingSettings(this).exclusions, packageName) else initial
            AppPicker.show(this@MainActivity, apps, start) { chosen -> field.setText(chosen.sorted().joinToString("\n")) }
            notice("選好後記得按「儲存」", Kind.INFO)
        }
    }

    private fun helpPage() {
        val body = screen("說明", null, tab = Tab.SETTINGS, back = { settings() }, refresh = { helpPage() })
        val s = RecordingSettings(this@MainActivity)
        ui.card(body, padding = 8).apply {
            ui.row(this, "伺服器", host(), (if (credentials != null) "已連線" else "未連線") to (credentials != null), onClick = null)
            ui.row(this, "記錄", recordingSummary(s), onClick = null)
            ui.row(this, "最近一次記錄", CaptureDiagnostics.snapshot().outcome.title) { captureDiagnostics { helpPage() } }
            ui.row(this, "最近同步", s.status(), onClick = null)
        }
        ui.groupLabel(body, "時間軸沒有活動時")
        ui.card(body).apply {
            ui.body(this, "1. 確認「設定 → 記錄 → 自動記錄」已開啟，兩個 Android 權限都已允許。\n2. 確認這個 app 有在「記錄哪些 app」裡，而且沒有被排除。\n3. app 使用時段大約每 15 分鐘整理一次，由 Android 決定時間；可在時間軸右上角同步。\n4. 截圖要等充電時才上傳，AI 整理後活動卡才會有標題與摘要。\n5. 換了 AI 設定後，要重新打開「自動整理新截圖」。")
        }
        ui.groupLabel(body, "目前限制")
        ui.card(body).apply {
            ui.meta(this, "手機在擷取前排除敏感畫面，沒有截圖自動遮罩，請不要允許敏感 app。受保護的畫面（例如影片、銀行 app）Android 不提供截圖，只會記錄文字。全天耗電量仍待實機量測。")
            ui.button(this, "使用文件與原始碼（GitHub）", Tone.OUTLINE) { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/skyhong2002/PocketLog"))) }
        }
    }

    private fun captureDiagnostics(back: () -> Unit) {
        val body = screen("最近一次記錄", "只在這支手機，不含畫面內容", tab, back = back, action = Triple(R.drawable.ic_sync, "重新檢查", { captureDiagnostics(back) }), refresh = { captureDiagnostics(back) })
        val settings = RecordingSettings(this)
        if (settings.isAuthRejected(credentials)) connectionRecoveryCallout(body)
        else if (!settings.contextEnabled) callout(body, "畫面文字沒有開啟，目前只記錄 app 使用時段。", Kind.INFO, "前往設定") { settings() }
        else if (!accessibilityServiceOn() || !settings.hasUsagePermission()) callout(body, "Android 權限還沒開齊，請檢查使用情況存取與輔助使用。", Kind.WARN, "前往設定") { settings() }
        else if (effectiveAllowedCount(settings) == 0) callout(body, "還沒有選擇要記錄的 app，或全部都被排除。", Kind.WARN, "選擇 app") { appListsPage() }
        val state = CaptureDiagnostics.snapshot()
        ui.card(body).apply {
            ui.text(this, state.outcome.title, 19f, bold = true)
            ui.meta(this, state.atMs?.let { stamp(it) } ?: "這次開啟 app 之後還沒有結果", top = 4)
            ui.body(this, state.outcome.detail, top = 12)
            ui.heading(this, "最近一次保存", top = 16)
            ui.body(this, state.lastQueuedMs?.let { stamp(it) } ?: "還沒有", top = 6)
        }
        ui.card(body).apply {
            ui.heading(this, "哪些畫面會略過？")
            ui.body(this, "沒有在「記錄哪些 app」裡的 app、排除的 app、鎖定畫面，以及有密碼或輸入欄位的畫面（包含搜尋列和訊息輸入框）。和上一次完全相同的畫面也會略過，以節省電量。", top = 8)
            ui.body(this, "受保護的畫面（例如影片、銀行 app）Android 不提供截圖，PocketLog 不會繞過，只記錄 app 名稱與畫面文字。", top = 10)
            ui.meta(this, "這頁只保留這次開啟 app 期間的一個結果，不保存 app 名稱、畫面文字或圖片，也不會上傳。", top = 12)
        }
    }

    // ---------------------------------------------------------------- AI settings

    private fun aiSettings(back: () -> Unit) {
        val body = screen("AI", "整理截圖、產生回顧、回答問題", tab = null, back = back, nav = false)
        val slot = ui.column(body)
        load(slot, "讀取 AI 設定…", { api().request("/v1/ai") }) { config -> renderAiForm(slot, config, back) }
    }

    private fun aiState(config: JSONObject): String =
        if (config.optBoolean("enabled")) "已連接：${AiProviders.detect(config.optString("protocol"), config.optString("endpoint")).label} · ${config.optString("model")}" else "尚未設定"

    /** Mika-style form: provider, key, model (prefilled), screenshots, 測試並儲存. */
    private fun renderAiForm(slot: LinearLayout, config: JSONObject, back: () -> Unit) {
        if (!config.optBoolean("available")) { ui.empty(slot, "伺服器還不支援在 app 裡設定 AI", "請先更新 PocketLog 伺服器。"); return }
        val enabled = config.optBoolean("enabled")
        aiAutomatic = enabled && config.optBoolean("auto_summarize")
        val saved = if (enabled) AiProviders.detect(config.optString("protocol"), config.optString("endpoint")) else AiProviders.Preset.OPENAI
        ui.banner(slot, aiState(config), if (enabled) Kind.SUCCESS else Kind.INFO)
        val presets = AiProviders.Preset.entries
        val form = ui.card(slot)
        val provider = ui.spinner(form, "AI 服務", presets.map { it.label }, presets.indexOf(saved))
        val key = ui.field(form, "API 金鑰", if (enabled && config.optBoolean("has_key")) "已保存；留白就沿用" else "貼上金鑰", Field.SECRET,
            help = "換了服務就要重新貼金鑰。本機的 Ollama 可以留白。")
        val model = ui.field(form, "模型", "", Field.TEXT, if (enabled) config.optString("model") else saved.suggestedModel)
        val endpointLabelIndex = form.childCount
        val endpoint = ui.field(form, "網址", "https://your-provider.example/v1", Field.URL, if (enabled && saved.needsEndpoint) config.optString("endpoint") else "",
            help = "OpenAI 相容的網址，例如 Ollama 的 https://主機/v1。不能包含金鑰或查詢參數。")
        val endpointViews = (endpointLabelIndex until form.childCount).map { form.getChildAt(it) }
        val images = ui.switchRow(form, "讓 AI 看截圖", "關閉後只送畫面文字", if (enabled) config.optBoolean("image_support") else saved.vision) {}
        var current = saved
        fun showEndpoint() = endpointViews.forEach { it.visibility = if (current.needsEndpoint) View.VISIBLE else View.GONE }
        showEndpoint()
        provider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val next = presets[position]
                if (next == current) return
                // Keep a typed model; replace an untouched suggestion with the new provider's one.
                if (model.text.isBlank() || model.text.toString() == current.suggestedModel || (enabled && model.text.toString() == config.optString("model"))) model.setText(next.suggestedModel)
                images.isChecked = next.vision
                current = next; showEndpoint()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        ui.meta(form, "金鑰經 HTTPS 存到你的 PocketLog 伺服器，不會傳回手機。API 費用由你的帳號支付。", top = 8)
        ui.button(form, "測試並儲存") {
            val preset = presets[provider.selectedItemPosition]
            val url = runCatching { preset.endpoint(model.text.toString(), endpoint.text.toString()) }.getOrNull()
            if (url == null) { notice(if (model.text.isBlank()) "請填寫模型" else "請檢查網址：需要 HTTPS，不能包含金鑰或查詢參數。", Kind.WARN); return@button }
            val request = JSONObject().put("protocol", preset.protocol).put("endpoint", url).put("model", model.text.toString().trim())
                .put("response_format", config.optString("response_format").ifBlank { "json_schema" }).put("image_support", images.isChecked)
            if (key.text.isNotBlank()) request.put("api_key", key.text.toString().trim())
            confirm("儲存並測試？", "設定會存到你的伺服器，並送一段固定的測試文字到 ${AiProviders.host(url)}（不含你的記錄）。儲存後「自動整理新截圖」會先關閉，確認沒問題後再打開。", "儲存並測試") {
                // Do not keep the key in the editor across the network call or Activity destruction.
                key.text.clear()
                task("儲存並測試…", {
                    synchronized(SyncEngine.lock) {
                        val api = api()
                        val stored = try { api.request("/v1/ai", request, "PUT") } finally { request.remove("api_key") }
                        // The probe is bound to the identity just saved; a changed provider returns 409 and is not retried.
                        val test: Throwable? = if (!stored.optBoolean("probe_provider_binding")) UnsupportedOperationException()
                            else runCatching { api.request("/v1/ai/test", JSONObject().put("provider_id", stored.getString("provider_id"))) }.exceptionOrNull()
                        stored to test
                    }
                }) { (_, test) ->
                    aiSettings(back)
                    when {
                        test == null -> notice("已儲存，測試成功。可以打開「自動整理新截圖」。")
                        test is UnsupportedOperationException -> notice("已儲存。伺服器版本較舊，無法測試；請更新伺服器。", Kind.WARN)
                        test is ApiFailure && test.status == 409 -> notice("設定剛被其他裝置改過，沒有測試。請核對後再試。", Kind.WARN)
                        else -> notice("已儲存，但測試失敗：${describe(test)}", Kind.ERROR)
                    }
                }
            }
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12) }
        if (enabled) {
            ui.card(slot, padding = 8).apply { aiSwitches(this, config) { aiSettings(back) } }
            ui.button(slot, "停用 AI 並刪除金鑰", Tone.DANGER) {
                confirm("停用 AI？", "刪除伺服器上的 AI 設定與金鑰。已送出的請求無法撤回，已產生的活動卡與回顧會保留。", "停用並刪除") {
                    task("停用 AI…", { synchronized(SyncEngine.lock) { api().request("/v1/ai", method = "DELETE") } }) { aiAutomatic = false; aiSettings(back); notice("已停用 AI 並刪除金鑰") }
                }
            }
        }
    }

    /** 設定 › AI rows: current state, then the two switches once a provider is saved. */
    private fun aiRows(parent: LinearLayout, config: JSONObject, after: () -> Unit) {
        aiAutomatic = config.optBoolean("enabled") && config.optBoolean("auto_summarize")
        ui.row(parent, "AI 服務", if (config.optBoolean("available")) aiState(config) else "伺服器還不支援") { aiSettings { settings() } }
        if (config.optBoolean("enabled")) aiSwitches(parent, config, after)
    }

    private fun aiSwitches(parent: LinearLayout, config: JSONObject, after: () -> Unit) {
        val target = AiProviders.host(config.optString("endpoint"))
        val auto = config.optBoolean("auto_summarize")
        ui.switchRow(parent, "自動整理新截圖", "新的畫面文字${if (config.optBoolean("image_support")) "與截圖" else ""}會自動送到 $target", auto) { on ->
            if (busy) { after(); return@switchRow }
            fun commit() = task(if (on) "開啟中…" else "關閉中…", {
                api().request("/v1/ai/auto", JSONObject().put("provider_id", config.getString("provider_id")).put("enabled", on), "PUT")
            }, failed = { error ->
                when ((error as? ApiFailure)?.status) {
                    401, 403 -> notice("這把金鑰不能變更 AI 設定，請改用網站。", Kind.WARN)
                    409 -> { notice("AI 設定剛被改過，已重新載入。", Kind.WARN); after() }
                    else -> after()
                }
            }) { aiAutomatic = on; after(); notice(if (on) "已開啟自動整理" else "已關閉自動整理") }
            if (on) confirm("開啟自動整理？", "所有裝置新記下的畫面文字${if (config.optBoolean("image_support")) "與截圖" else ""}，以及還沒整理的內容，會經你的伺服器送到 $target（${config.optString("model")}），整理成活動卡。API 費用由你支付；已送出的內容無法撤回。換了 AI 設定後會自動關閉。", "開啟", { after() }) { commit() }
            else commit()
        }.apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) }
        val schedule = ui.column(parent)
        load(schedule, "讀取回顧排程…", { api().request("/v1/briefs/schedule") }) { current ->
            val on = current.optBoolean("enabled") && current.optString("provider_id") == config.optString("provider_id") && (current.optBoolean("daily") || current.optBoolean("weekly"))
            ui.switchRow(schedule, "每天自動產生回顧", "每天${"%02d:%02d".format(current.optInt("minute_of_day") / 60, current.optInt("minute_of_day") % 60)}整理前一天，週一另外整理上一週", on) { enable ->
                if (busy) { after(); return@switchRow }
                val request = JSONObject(current.toString()).put("enabled", enable).put("provider_id", config.optString("provider_id"))
                    .put("timezone", current.optString("timezone").ifBlank { zone.id })
                if (enable) request.put("daily", true).put("weekly", true)
                fun commit() = task("更新…", { api().request("/v1/briefs/schedule", request, "PUT") }, failed = { error ->
                    if ((error as? ApiFailure)?.status == 409) notice("排程剛被改過，已重新載入。", Kind.WARN)
                    after()
                }) { after(); notice(if (enable) "已開啟每天自動產生回顧" else "已關閉自動回顧") }
                if (enable) confirm("每天自動產生回顧？", "每天會把前一天（週一另外加上上一週）的活動卡送到 $target 產生回顧，回顧保存 30 天。只送活動卡，不送截圖或畫面文字。API 費用由你支付。", "開啟", { after() }) { commit() }
                else commit()
            }.apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) }
        }
    }

    /** Server capture budget: oldest screenshots are removed first; text is kept. */
    private fun storageRows(parent: LinearLayout, info: JSONObject) {
        val gib = 1024.0 * 1024 * 1024
        fun size(bytes: Long) = if (bytes >= gib) "%.1f GB".format(bytes / gib) else "%.0f MB".format(bytes / (1024.0 * 1024))
        val used = info.optLong("used_bytes"); val limit = info.optLong("limit_bytes")
        val textOnly = if (info.isNull("text_only_before_ms")) null else info.optLong("text_only_before_ms")
        ui.row(parent, "伺服器空間", "已用 ${size(used)} / ${size(limit)} · 截圖 ${info.optLong("image_count")} 張", onClick = null)
        if (textOnly != null) ui.meta(parent, "${Instant.ofEpochMilli(textOnly).atZone(ZoneId.systemDefault()).toLocalDate()} 以前的截圖已因空間上限刪除，文字保留。", top = 4, bottom = 4).setPadding(ui.dp(4), 0, ui.dp(4), 0)
        ui.row(parent, "空間上限", "超過時從最舊的截圖開始刪除，文字永久保留") { storageLimitDialog(info) }
    }

    private fun storageLimitDialog(info: JSONObject) {
        val gib = 1024L * 1024 * 1024
        val min = info.optLong("min_limit_bytes", gib); val max = info.optLong("max_limit_bytes", 1024 * gib)
        val field = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText((info.optLong("limit_bytes", 10 * gib) / gib).toString())
            hint = "GB"
        }
        dialog().setTitle("伺服器空間上限（GB）").setMessage("可設定 ${min / gib}–${max / gib} GB。調低後，伺服器會刪除最舊的截圖直到低於上限，無法復原。").setView(field)
            .setNegativeButton("取消", null).setPositiveButton("儲存") { _, _ ->
                val value = field.text.toString().toLongOrNull()?.times(gib)
                if (value == null || value < min || value > max) { notice("請輸入 ${min / gib}–${max / gib} 之間的整數", Kind.WARN); return@setPositiveButton }
                task("更新…", {
                    api().request("/v1/storage", JSONObject().put("revision", info.getLong("revision")).put("limit_bytes", value), "PUT")
                }, failed = { error ->
                    when ((error as? ApiFailure)?.status) {
                        409 -> { notice("空間設定剛在其他裝置改過，已重新載入。", Kind.WARN); settings() }
                        401, 403 -> notice("這把金鑰不能變更空間設定。", Kind.WARN)
                    }
                }) { settings(); notice("已更新空間上限") }
            }.showSecure()
    }

    private fun export() {
        task("讀取這一天…", { readJournal(dayPath()) }) { data ->
            dialog().setTitle("匯出 ${md(day)}").setMessage("檔案含這天的活動卡標題與摘要。請存到你信任的位置；匯出的檔案不受 app 加密保護。")
                .setNegativeButton("取消", null).setPositiveButton("選擇位置") { _, _ ->
                    exportBytes?.fill(0); exportBytes = data.toString(2).toByteArray()
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type = "application/json"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE, "pocketlog-$day.json") }, 20)
                }.showSecure()
        }
    }

    // ---------------------------------------------------------------- categories and rules

    private fun categoriesPage(retained: JSONObject? = null) {
        val body = screen("分類", "網站與手機共用", tab = Tab.SETTINGS, back = { settings() })
        val slot = ui.column(body)
        val render: (JSONObject) -> Unit = { current ->
            if (retained == null) workspace = current
            val styles = current.getJSONArray("categories")
            ui.card(slot).apply {
                for (i in 0 until styles.length()) {
                    val c = styles.getJSONObject(i)
                    val row = ui.horizontal(this, 10).apply { minimumHeight = ui.dp(36) }
                    row.addView(ui.swatch(Palette.parse(c.optString("color")), 12))
                    ui.text(row, c.getString("name"), 15f).layoutParams = ui.lp(0, weight = 1f)
                }
                val rules = current.optJSONArray("rules")?.length() ?: 0
                ui.meta(this, if (rules == 0) "還沒有自動分類規則。" else "$rules 條自動分類規則。", top = 8)
                ui.button(this, "編輯分類與自動規則", Tone.TONAL) { categoryRulesEditor(JSONObject(current.toString())) }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12) }
            }
        }
        if (retained != null) render(retained)
        else load(slot, "讀取分類…", { api().request("/v1/workspace") }, render)
    }

    private fun categoryRulesEditor(draft: JSONObject) = categoryRulesEditor(draft, JSONObject(draft.toString()))

    private fun categoryRulesEditor(draft: JSONObject, baseline: JSONObject, continuation: Pair<String, String>? = null) {
        val body = screen("分類與自動規則", "儲存後同步到網站", tab = Tab.SETTINGS, back = {
            dialog().setTitle("放棄還沒儲存的修改？").setNegativeButton("繼續編輯", null).setPositiveButton("放棄") { _, _ -> categoriesPage() }.showSecure()
        })
        ui.banner(body, "規則由上往下套用，第一個符合的生效；同時填 app 和關鍵字時兩個都要符合。你手動選的分類永遠優先。還有活動在用的分類不能移除。")
        // Rebuilding keeps captured drafts; stable IDs keep the next action in view.
        val editorGeneration = generation
        fun continueAt(id: String, action: String, view: View) {
            if (continuation != (id to action)) return
            body.post {
                if (generation != editorGeneration || !view.isAttachedToWindow) return@post
                view.isFocusableInTouchMode = true
                view.requestFocus()
                view.requestRectangleOnScreen(android.graphics.Rect(0, 0, view.width, view.height), true)
            }
        }
        val styles = draft.getJSONArray("categories")
        val rules = draft.optJSONArray("rules") ?: JSONArray().also { draft.put("rules", it) }
        val capture = mutableListOf<() -> Unit>()
        fun remember() = capture.forEach { it() }
        ui.button(body, "試算規則（不儲存）", Tone.TONAL) {
            remember()
            rulePreview(JSONObject(draft.toString())) { categoryRulesEditor(draft, baseline) }
        }
        ui.groupLabel(body, "分類")
        for (i in 0 until styles.length()) {
            val c = styles.getJSONObject(i)
            val card = ui.card(body)
            val top = ui.horizontal(card, 10)
            val swatch = ui.swatch(Palette.parse(c.optString("color")), 16).also { top.addView(it) }
            ui.meta(top, if (c.getString("id").startsWith("custom_")) "自訂分類" else "內建分類").layoutParams = ui.lp(0, weight = 1f)
            val name = ui.field(card, "名稱", "最多 40 字", Field.TEXT, c.getString("name"))
            continueAt(c.getString("id"), "name", name)
            val color = ui.field(card, "顏色", "#RRGGBB", Field.TEXT, c.getString("color"))
            color.addTextChangedListener(watcher { value -> swatch.background = ui.shape(Palette.parse(value), 8f) })
            capture.add { c.put("name", name.text.toString().trim()).put("color", color.text.toString().trim()) }
            if (c.getString("id").startsWith("custom_")) ui.button(card, "移除這個分類", Tone.TEXT) {
                remember(); styles.remove(i)
                for (j in rules.length() - 1 downTo 0) if (rules.getJSONObject(j).getString("category") == c.getString("id")) rules.remove(j)
                val next = if (styles.length() > 0) styles.getJSONObject(i.coerceAtMost(styles.length() - 1)).getString("id") else "categories"
                categoryRulesEditor(draft, baseline, next to when { next == "categories" -> "add"; next.startsWith("custom_") -> "remove"; else -> "name" })
            }.also { continueAt(c.getString("id"), "remove", it) }
        }
        ui.button(body, "新增分類", Tone.TONAL) {
            if (styles.length() >= 40) { notice("最多 40 個分類", Kind.WARN); return@button }
            remember()
            val id = "custom_" + UUID.randomUUID().toString().replace("-", "")
            styles.put(JSONObject().put("id", id).put("name", "新分類").put("color", "#9bbce1").put("role", "neutral"))
            categoryRulesEditor(draft, baseline, id to "name")
        }.also { continueAt("categories", "add", it) }
        ui.groupLabel(body, "自動分類規則")
        val ids = (0 until styles.length()).map { styles.getJSONObject(it).getString("id") }
        if (rules.length() == 0) ui.meta(body, "還沒有規則。沒有符合規則的活動使用 AI 或預設分類。", bottom = 8)
        for (i in 0 until rules.length()) {
            val r = rules.getJSONObject(i)
            val card = ui.card(body)
            val enabled = ui.checkbox(card, "規則 ${i + 1} 啟用", r.optBoolean("enabled", true))
            val app = ui.field(card, "app 套件名稱", "留白表示所有 app", Field.TEXT, r.optString("app").takeUnless { it == "null" } ?: "")
            continueAt(r.getString("id"), "app", app)
            ui.button(card, "從已安裝的 app 選擇", Tone.TONAL) { chooseRuleApp(app) }
            val keyword = ui.field(card, "標題包含", "留白表示不限", Field.TEXT, r.optString("title_contains").takeUnless { it == "null" } ?: "")
            val category = ui.spinner(card, "套用分類", (0 until styles.length()).map { styles.getJSONObject(it).getString("name") }, ids.indexOf(r.getString("category")).coerceAtLeast(0))
            capture.add { r.put("enabled", enabled.isChecked).put("app", app.text.toString().trim().ifBlank { null } ?: JSONObject.NULL).put("title_contains", keyword.text.toString().trim().ifBlank { null } ?: JSONObject.NULL).put("category", ids[category.selectedItemPosition]) }
            val labels = if (i > 0) arrayOf("上移" to Tone.OUTLINE, "刪除規則" to Tone.DANGER) else arrayOf("刪除規則" to Tone.DANGER)
            ui.actions(card, *labels) { index ->
                remember()
                if (i > 0 && index == 0) {
                    val previous = rules.getJSONObject(i - 1); rules.put(i - 1, r); rules.put(i, previous)
                    categoryRulesEditor(draft, baseline, r.getString("id") to if (i > 1) "up" else "delete")
                } else {
                    rules.remove(i)
                    val next = if (rules.length() > 0) rules.getJSONObject(i.coerceAtMost(rules.length() - 1)).getString("id") else "rules"
                    categoryRulesEditor(draft, baseline, next to if (next == "rules") "add" else "delete")
                }
            }.forEachIndexed { index, button -> continueAt(r.getString("id"), if (i > 0 && index == 0) "up" else "delete", button) }
        }
        ui.button(body, "新增規則", Tone.TONAL) {
            if (rules.length() >= 32) { notice("最多 32 條規則", Kind.WARN); return@button }
            remember()
            val id = UUID.randomUUID().toString()
            rules.put(JSONObject().put("id", id).put("category", ids.first()).put("app", JSONObject.NULL).put("title_contains", JSONObject.NULL).put("enabled", true))
            categoryRulesEditor(draft, baseline, id to "app")
        }.also { continueAt("rules", "add", it) }
        ui.actions(body, "放棄修改" to Tone.OUTLINE, "儲存" to Tone.PRIMARY) { index ->
            if (index == 0) { categoriesPage(); return@actions }
            remember()
            saveClassification(draft, baseline)
        }
    }

    private fun chooseRuleApp(field: EditText) {
        val token = generation
        fun current() = token == generation && field.isAttachedToWindow && !isDestroyed && !isFinishing
        task("讀取已安裝的 app…", { installedApps() }) { apps ->
            if (!current()) return@task
            RuleAppPicker.show(this@MainActivity, apps) { chosen ->
                if (current()) {
                    field.setText(chosen)
                    field.error = null
                    field.setSelection(field.text.length)
                }
            }.setOnDismissListener {
                field.post {
                    if (current()) {
                        field.requestFocus()
                        field.requestRectangleOnScreen(android.graphics.Rect(0, 0, field.width, field.height), true)
                    }
                }
            }
        }
    }

    private fun saveClassification(draft: JSONObject, baseline: JSONObject) {
        val payload = JSONObject(draft.toString())
        task("儲存分類與規則…", { api().request("/v1/workspace", payload, "PUT") }, failed = { error ->
            if (error is ApiFailure && error.status == 409) classificationRecovery(draft, baseline, payload.getLong("revision"))
        }) { workspace = it; categoriesPage(); notice("已儲存，網站也會看到") }
    }

    private fun classificationRecovery(draft: JSONObject, baseline: JSONObject, attemptedRevision: Long) {
        val body = screen("分類草稿還在", "尚未儲存", tab = Tab.SETTINGS, back = { categoryRulesEditor(draft, baseline) })
        ui.banner(body, "你的分類與規則草稿還保留在這裡。看過最新設定後再決定；離開這個畫面或關閉 app 前請先儲存。")
        ui.button(body, "回到草稿", Tone.OUTLINE) { categoryRulesEditor(draft, baseline) }
        val slot = ui.column(body)
        load(slot, "讀取最新設定…", { api().request("/v1/workspace") }) { latest ->
            val card = ui.card(slot)
            when (ClassificationRecovery.kind(baseline, latest, attemptedRevision)) {
                ClassificationRecovery.Kind.USED -> {
                    ui.heading(card, "分類還有活動在用")
                    ui.body(card, "移除的分類還有活動在用。請還原分類，或先把那些活動改到其他分類。其他修改都還在。")
                    val categories = draft.getJSONArray("categories")
                    val existing = (0 until categories.length()).map { categories.getJSONObject(it).getString("id") }.toSet()
                    val remote = latest.getJSONArray("categories")
                    val removed = (0 until remote.length()).map { remote.getJSONObject(it) }.filter { it.getString("id") !in existing }
                    if (removed.isNotEmpty()) ui.button(card, "還原移除的分類，保留其他修改", Tone.TONAL) {
                        if (categories.length() + removed.size > 40) { notice("還原後超過 40 個分類，請先移除沒用到的新分類。", Kind.WARN); return@button }
                        removed.forEach { categories.put(JSONObject(it.toString())) }
                        categoryRulesEditor(draft, baseline)
                        notice("已還原分類，請檢查後再儲存。", Kind.INFO)
                    }
                }
                ClassificationRecovery.Kind.OTHER -> {
                    ui.heading(card, "其他設定改過了，分類沒變")
                    ui.body(card, "網站上的分類與規則沒有變，只有其他設定改過。可以直接再儲存你的草稿。")
                    ui.button(card, "再儲存一次") { saveClassification(ClassificationRecovery.candidate(draft, latest), baseline) }
                }
                ClassificationRecovery.Kind.CLASSIFICATION -> {
                    ui.heading(card, "網站上的分類也改過了")
                    ui.body(card, "你的草稿還沒覆蓋網站上的版本。比較之後可以回去編輯，或用你的草稿取代。")
                    val remote = latest.getJSONArray("categories")
                    ui.groupLabel(card, "網站上的分類")
                    for (i in 0 until remote.length()) remote.getJSONObject(i).let { c -> ui.body(card, "${c.getString("name")} · ${c.getString("color")}") }
                    val rules = latest.optJSONArray("rules") ?: JSONArray()
                    ui.groupLabel(card, "網站上的規則")
                    if (rules.length() == 0) ui.meta(card, "沒有規則。")
                    for (i in 0 until rules.length()) {
                        val r = rules.getJSONObject(i)
                        val target = (0 until remote.length()).map { remote.getJSONObject(it) }.firstOrNull { it.getString("id") == r.getString("category") }?.getString("name") ?: r.getString("category")
                        ui.body(card, "${i + 1}. ${if (r.getBoolean("enabled")) "啟用" else "停用"} · ${r.optString("app").takeUnless { it == "null" || it.isBlank() } ?: "所有 app"} · ${r.optString("title_contains").takeUnless { it == "null" || it.isBlank() } ?: "所有標題"} → $target")
                    }
                    ui.button(card, "用我的草稿取代", Tone.DANGER) {
                        confirm("取代網站上的分類與規則？", "會用你的草稿取代剛讀到的分類與規則，其他設定保留。不會刪除活動；還有活動在用的分類仍不能移除。", "取代") {
                            saveClassification(ClassificationRecovery.candidate(draft, latest), baseline)
                        }
                    }
                }
            }
            ui.button(slot, "重新讀取最新設定", Tone.OUTLINE) { classificationRecovery(draft, baseline, attemptedRevision) }
        }
    }

    private fun rulePreview(draft: JSONObject, back: () -> Unit) {
        val body = screen("試算規則", "確認結果後再回去儲存", tab = Tab.SETTINGS, back = back)
        val token = generation
        ui.banner(body, "用還沒儲存的規則試算一個例子。不會儲存設定、不會加入記錄，也不會用到 AI。")
        val styles = draft.getJSONArray("categories")
        val ids = (0 until styles.length()).map { styles.getJSONObject(it).getString("id") }
        val form = ui.card(body)
        val app = ui.field(form, "app 套件名稱", "例如 org.mozilla.firefox", Field.TEXT, help = "要和規則完全相同（區分大小寫）。")
        val title = ui.field(form, "活動標題", "例如 閱讀 Kotlin 文件", Field.TEXT, help = "關鍵字不分大小寫，可以留白。")
        app.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        app.filters = arrayOf(android.text.InputFilter.LengthFilter(200))
        title.filters = arrayOf(android.text.InputFilter.LengthFilter(200))
        val baseline = ui.spinner(form, "沒有符合規則時的分類", (0 until styles.length()).map { styles.getJSONObject(it).getString("name") }, ids.indexOf("unsorted").coerceAtLeast(0))
        val result = ui.body(form, "", top = 12).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        var attempt = 0
        fun invalidate() {
            attempt++
            if (result.text.isNotEmpty()) result.text = "例子改過了，請重新試算。"
        }
        app.addTextChangedListener(watcher { app.error = null; invalidate() })
        title.addTextChangedListener(watcher { title.error = null; invalidate() })
        baseline.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = invalidate()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = invalidate()
        }
        ui.button(form, "試算", Tone.PRIMARY) {
            if (busy) { notice("上一次試算還在處理中", Kind.INFO); return@button }
            val sampleApp = app.text.toString().trim()
            val sampleTitle = title.text.toString()
            if (!Regex("[A-Za-z0-9._-]{1,200}").matches(sampleApp)) {
                app.error = "請填完整的套件名稱，只能有英數字、點、底線或連字號。"; app.requestFocus(); return@button
            }
            if (sampleTitle.any { it.isISOControl() }) { title.error = "標題不能包含控制字元。"; title.requestFocus(); return@button }
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(app.windowToken, 0)
            val request = JSONObject().put("workspace", JSONObject(draft.toString())).put("sample", JSONObject()
                .put("app", sampleApp).put("title", sampleTitle).put("category", ids[baseline.selectedItemPosition]).put("edited", false))
            val revision = ++attempt
            result.text = "試算中…"
            task("試算規則…", { api().request("/v1/workspace/preview", request, "POST") }, failed = { error ->
                if (token == generation && revision == attempt) result.text = when {
                    error is ApiFailure && error.status == 404 -> "伺服器還不支援試算，請更新伺服器。分類仍可正常儲存。"
                    error is ApiFailure && error.status == 400 -> "規則或例子還不完整，請檢查 app、關鍵字與分類。"
                    else -> "試算沒有完成，請檢查連線後再試。"
                }
            }) { response ->
                if (token != generation || revision != attempt) return@task
                val category = response.getString("category")
                val name = (0 until styles.length()).map { styles.getJSONObject(it) }.firstOrNull { it.getString("id") == category }?.getString("name") ?: category
                val rules = draft.optJSONArray("rules") ?: JSONArray()
                val matched = response.optString("matched_rule_id")
                val index = (0 until rules.length()).firstOrNull { rules.getJSONObject(it).getString("id") == matched }
                val why = index?.let { "符合第 ${it + 1} 條規則，後面的規則不再套用。" } ?: "沒有符合任何啟用的規則，使用原本的分類。"
                result.text = "會分到「$name」\n$why\n這只是試算，回到編輯頁按「儲存」才會生效。"
                result.post { if (token == generation && revision == attempt) result.requestRectangleOnScreen(android.graphics.Rect(0, 0, result.width, result.height), false) }
            }
        }
    }

    // ---------------------------------------------------------------- document picker result

    @Deprecated("Legacy result API for the platform Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 20) return
        val bytes = exportBytes ?: run {
            if (resultCode == RESULT_OK) notice("匯出內容因 app 重新啟動而移除，請再匯出一次。", Kind.WARN)
            return
        }
        exportBytes = null
        if (resultCode != RESULT_OK || data?.data == null) { bytes.fill(0); return }
        if (busy) { bytes.fill(0); notice("上一個操作還在處理中，這次沒有匯出，請稍後再試。", Kind.WARN); return }
        task("匯出中…", { try { contentResolver.openOutputStream(requireNotNull(data.data), "wt").use { requireNotNull(it).write(bytes) } } finally { bytes.fill(0) } }, failed = {
            notice("匯出沒有完成。請檢查儲存位置與空間後再試；目的地可能留下不完整的檔案。", Kind.ERROR)
        }) { notice("已匯出。請妥善保管這個檔案。") }
    }

    override fun onDestroy() {
        timelineWatch?.close(); timelineWatch = null
        replayDialog?.dismiss(); replayDialog = null
        handler.removeCallbacksAndMessages(null)
        if (backRegistered) onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        executor.shutdown(); exportBytes?.fill(0); if (!isChangingConfigurations) clearDrafts(); pendingAsk = null; pendingBrief = null; credentials = null
        super.onDestroy()
    }
}

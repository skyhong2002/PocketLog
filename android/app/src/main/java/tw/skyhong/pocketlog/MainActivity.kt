package tw.skyhong.pocketlog

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
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
    private lateinit var headerDivider: View
    private lateinit var timelineDateBar: LinearLayout
    /** Short date/period that the sticky header shows once the page is scrolled. */
    private var headerSubtitle: TextView? = null
    private var stickyPeriod: String? = null
    private lateinit var scroll: ScrollView
    private lateinit var page: LinearLayout
    private lateinit var statusStrip: LinearLayout
    private lateinit var statusSpinner: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var navBar: LinearLayout
    private lateinit var navRow: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    /** Update checks and downloads never wait behind journal reads. */
    private val statusExecutor = Executors.newSingleThreadExecutor()
    private val replayExecutor = Executors.newSingleThreadExecutor()
    private val updateExecutor = Executors.newSingleThreadExecutor()
    private var updateChecking = false
    private val appNames = LocalAppNames()
    private val appIcons = android.util.LruCache<String, Bitmap>(LocalAppNames.MAX_PACKAGES)
    private val fallbackIcons = android.util.LruCache<String, Bitmap>(LocalAppNames.MAX_PACKAGES)
    private var busy = false
    @Volatile private var generation = 0
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
    private var aiConfigured: Boolean? = null
    private var waitingCaptures = 0
    /** Visible result of the last 測試並儲存, shown once on the AI page. */
    private var aiResult: Pair<String, Kind>? = null
    private var day = TimelineMath.journalDay()
    private var loaded: JSONObject? = null
    private var replayDialog: AlertDialog? = null
    private var activityDetail: android.app.Dialog? = null
    private var reviewWeekly = false
    private var questionDrafts = EditorDrafts(20, 1000)
    /** One conversation per reviewed period (day or week), kept in memory only. */
    private var conversations = linkedMapOf<String, String>()
    private var pendingAsk: JSONObject? = null
    private var pendingBrief: JSONObject? = null
    private var exportBytes: ByteArray? = null
    private data class RetainedEditors(val questions: EditorDrafts, val conversations: LinkedHashMap<String, String>, val weekly: Boolean, val ask: JSONObject?, val brief: JSONObject?, val export: ByteArray?)
    private fun clearDrafts() { questionDrafts.clear(); conversations.clear(); categoryFilter = null; exportBytes?.fill(0); exportBytes = null }
    private val baseCategories = listOf("unsorted", "work", "learning", "communication", "social_media", "entertainment", "news_reading", "life_admin", "rest", "idle", "distraction")
    private val categoryLabels = listOf("未分類", "工作", "學習", "溝通", "社群", "娛樂", "新聞／閱讀", "生活事務", "休息", "閒置", "分心")
    // Server defaults (server/src/workspace.rs) so offline pages still distinguish categories by color.
    private val defaultColors = listOf("#b1a797", "#b9a4ed", "#87bcb4", "#e4be7c", "#e69c9c", "#d4a9c8", "#9bbce1", "#b4c493", "#80bda1", "#92949e", "#e6a98a")
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
        autoUpdateCheck()
    }

    override fun onStop() {
        visible = false
        timelineWatch?.pause()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Observe permission restoration before the next normal scan; never query old events here.
        statusExecutor.execute {
            runCatching { synchronized(SyncEngine.lock) {
                // Upgrade path: existing screen-context grants include screenshots by default.
                RecordingSettings(this).applyScreenshotDefault()
                UsageCollector(this@MainActivity).reconcilePermission()
            } }
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
            setBackgroundColor(Palette.CANVAS)
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = ui.dp(64)
            setPadding(ui.dp(8), ui.dp(6), ui.dp(12), ui.dp(6))
        }
        shell.addView(header, ui.lp())
        timelineDateBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        shell.addView(timelineDateBar, ui.lp())
        // A divider appears once content scrolls under the header, so text is never visually cut.
        headerDivider = View(this@MainActivity).apply { setBackgroundColor(Palette.BORDER); visibility = View.INVISIBLE }
        shell.addView(headerDivider, ui.lp(height = 1))
        scroll = ScrollView(this@MainActivity).apply { isFillViewport = true; isNestedScrollingEnabled = true; clipToPadding = false }
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            headerDivider.visibility = if (y > 0) View.VISIBLE else View.INVISIBLE
            header.elevation = if (y > 0) ui.dp(3).toFloat() else 0f
            stickyPeriod?.let { period -> headerSubtitle?.apply { text = period; visibility = if (y > ui.dp(48)) View.VISIBLE else View.GONE } }
        }
        // Generous bottom room so the last row always clears the bottom navigation and gesture bar.
        page = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(96)) }
        scroll.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Keep a readable column on tablets and landscape; phones keep the 16 dp gutter.
        scroll.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val side = maxOf(ui.dp(16), (right - left - ui.dp(720)) / 2)
            if (page.paddingLeft != side) page.post {
                page.setPadding(side, page.paddingTop, side, page.paddingBottom)
                timelineDateBar.setPadding(side, 0, side, 0)
            }
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
        timelineDateBar.removeAllViews(); timelineDateBar.visibility = View.GONE
        when {
            back != null -> header.addView(iconButton(R.drawable.ic_back, "返回") { handleBack() })
            logo -> header.addView(ui.logo(36).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(ui.dp(8), 0, ui.dp(12), 0) })
            else -> header.addView(View(this@MainActivity), LinearLayout.LayoutParams(ui.dp(8), 1))
        }
        val titles = ui.column(header).apply { layoutParams = ui.lp(0, weight = 1f) }
        ui.text(titles, title, 22f).apply { isAccessibilityHeading = true; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        stickyPeriod = null
        headerSubtitle = ui.text(titles, subtitle ?: "", 14f, Palette.INK, bold = true).apply { visibility = if (subtitle != null) View.VISIBLE else View.GONE }
        if (action != null) header.addView(iconButton(action.first, action.second, action.third))
        page.removeAllViews(); scroll.scrollTo(0, 0); headerDivider.visibility = View.INVISIBLE
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
        is ApiProtocolFailure -> error.reason.message
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
        is UnknownHostException -> "找不到伺服器。請檢查網址，並確認手機已連上網路；使用 VPN／私人網路時請確認已連線。"
        is SSLException -> "無法驗證伺服器的 HTTPS 憑證。PocketLog 只接受系統信任的憑證。"
        is SocketTimeoutException -> "連線逾時，伺服器可能離線或網路太慢。"
        is ConnectException -> "連不上伺服器。請確認服務正在執行、連接埠正確。"
        is IOException -> "網路連線失敗，資料仍加密保存在手機。"
        is org.json.JSONException -> "伺服器回應缺少這項功能需要的資料，請更新 PocketLog 伺服器。"
        else -> "無法完成，請檢查網路與伺服器。"
    }

    /** Reads for the current page. Stale results are dropped; failures become an inline retry state. */
    private fun <T> load(slot: LinearLayout, message: String, work: () -> T, render: (T) -> Unit) {
        val token = generation
        slot.removeAllViews(); ui.spinnerRow(slot, message)
        executor.execute {
            if (token != generation) return@execute
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
    private fun dateTitle(d: LocalDate): String = TimelineMath.dayLabel(d)
    private fun md(d: LocalDate): String = TimelineMath.dayLabel(d)
    private fun relative(d: LocalDate): String = when (val diff = ChronoUnit.DAYS.between(d, journalDay())) { 0L -> "今天"; 1L -> "昨天"; else -> "$diff 天前" }
    private fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    private fun stamp(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    private fun host(): String = credentials?.endpoint?.removePrefix("https://")?.removePrefix("http://") ?: "未連線"
    private fun style(id: String): JSONObject? = workspace?.optJSONArray("categories")?.let { s -> (0 until s.length()).map { s.getJSONObject(it) }.firstOrNull { it.optString("id") == id } }
    private fun label(id: String): String = style(id)?.optString("name")?.takeIf { it.isNotBlank() } ?: categoryLabels.getOrElse(baseCategories.indexOf(id)) { id }
    private fun colorFor(id: String): Int = style(id)?.let { Palette.parse(it.optString("color")) } ?: Palette.parse(defaultColors.getOrNull(baseCategories.indexOf(id)))
    /** Only exceptions are shown on cards; the normal `AI 整理` is not. */
    private fun sourceException(card: JSONObject): String? = when {
        card.optBoolean("pending_edit") -> "你改過 · 待同步"
        else -> when (card.optString("source")) { "user" -> "你改過"; "ai" -> null; "local_excerpt" -> "畫面文字"; else -> null }
    }
    /** At most one exception badge; while AI is still working only `AI 整理中` shows. */
    private fun badgeKind(text: String) = when (text) { "AI 整理中" -> Ui.Badge.ACCENT; "受保護畫面" -> Ui.Badge.AMBER; else -> Ui.Badge.NEUTRAL }
    private fun badges(card: JSONObject): List<String> = listOfNotNull(when {
        card.optBoolean("pending_context") -> "AI 整理中"
        card.optBoolean("protected_screen") -> "受保護畫面"
        else -> sourceException(card)
    })
    private fun cardSummary(card: JSONObject): String =
        card.optString("summary").takeUnless { card.optString("source") == "app_only" }.orEmpty()
    /** `01:26–01:40 · 14 分`, or `01:26 · 不到 1 分` for a zero-length range. */
    private fun timeRange(start: Long, end: Long): String = TimelineMath.rangeLabel(clock(start), clock(end), end - start)
    private fun lastSyncText(ms: Long): String = when {
        ms <= 0 -> "還沒同步"
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() == LocalDate.now(zone) -> "上次同步 ${clock(ms)}"
        else -> "上次同步 ${Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().let { "${it.monthValue}月${it.dayOfMonth}日" }} ${clock(ms)}"
    }
    private fun cardsOf(data: JSONObject?): List<JSONObject> = data?.optJSONArray("cards")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    private fun cardTitle(card: JSONObject): String = appNames.title(card).ifBlank { appNames.name(card.optString("app")) }.ifBlank { "活動" }
    private fun readJournal(path: String): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper())
        val data = SyncEngine.readTimeline(this@MainActivity, path)
        val apps = data.optJSONArray("apps")
        val packages = cardsOf(data).asSequence().map { it.optString("app") } +
            (0 until (apps?.length() ?: 0)).asSequence().map { apps!!.getJSONObject(it).optString("app") }
        prepareApps(packages)
        return data
    }

    private fun prepareApps(packages: Sequence<String>) {
        check(Looper.myLooper() != Looper.getMainLooper())
        appNames.prepare(packages) { app ->
            val info = packageManager.getApplicationInfo(app, android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
            if (appIcons.get(app) == null) runCatching {
                val size = ui.dp(28)
                val icon = packageManager.getApplicationIcon(info)
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                icon.setBounds(0, 0, size, size)
                icon.draw(Canvas(bitmap))
                appIcons.put(app, bitmap)
            }
            packageManager.getApplicationLabel(info).toString()
        }
    }

    private fun appColor(app: String, category: String): Int {
        if (app.isBlank()) return colorFor(category)
        val hash = app.hashCode().toUInt() * 0x9e3779b9u
        return Color.HSVToColor(floatArrayOf((hash shr 16).toFloat() * (360f / 65536f), .53f, .70f))
    }

    private fun appTitle(parent: LinearLayout, app: String, title: String, category: String, size: Float, bold: Boolean) {
        val row = ui.horizontal(parent, 8).apply {
            if (parent.orientation == LinearLayout.HORIZONTAL) layoutParams = ui.lp(0, weight = 1f)
        }
        if (app.isNotBlank()) {
            val bitmap = appIcons.get(app)
            val icon = if (bitmap != null) ImageView(this).apply { setImageBitmap(bitmap) } else TextView(this).apply {
                val name = appNames.name(app)
                text = name.substring(0, name.offsetByCodePoints(0, 1))
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Palette.INK)
                background = ui.shape(Palette.tint(appColor(app, category), .20f), 7f)
            }
            icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            row.addView(icon, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
        }
        ui.text(row, title, size, bold = bold).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = ui.lp(0, weight = 1f)
        }
    }

    private fun intervals(list: List<JSONObject>) = list.map { TimelineMath.Interval(it.getLong("start_ms"), it.getLong("end_ms")) }
    private fun categoryIntervals(list: List<JSONObject>) = TimelineMath.categoryIntervals(list.map { card ->
        val source = card.optString("source")
        val priority = when {
            source == "user" -> 3
            card.optBoolean("screen_derived") -> 2
            source == "ai" || source == "local_excerpt" -> 1
            else -> 0
        }
        TimelineMath.CategoryInterval(card.getLong("start_ms"), card.getLong("end_ms"), card.getString("category"), priority, card.getString("id"))
    })
    private fun totals(list: List<JSONObject>): List<Pair<String, Long>> =
        categoryIntervals(list).groupBy { it.category }
            .map { (id, rows) -> id to rows.sumOf { it.end - it.start } }.sortedByDescending { it.second }
    private fun watcher(after: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = after(s?.toString().orEmpty())
    }
    private fun version(): String = BuildConfig.VERSION_NAME.removeSuffix("-sandbox")
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

    private fun bar(fraction: Float, color: Int): View = object : View(this) {
        private val track = ui.shape(Palette.HAIRLINE, 4f)
        private val fill = ui.shape(color, 4f)
        override fun onDraw(canvas: android.graphics.Canvas) {
            track.setBounds(0, 0, width, height); track.draw(canvas)
            val end = (width * fraction.coerceIn(0f, 1f)).toInt()
            if (end > 0) { fill.setBounds(0, 0, end.coerceAtLeast(height), height); fill.draw(canvas) }
        }
    }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** A destructive or account-changing row: warning colour, chevron, always behind a confirmation. */
    private fun dangerRow(parent: ViewGroup, title: String, detail: String?, onClick: () -> Unit) =
        ui.row(parent, title, detail) { onClick() }.also { row -> ((row.getChildAt(0) as LinearLayout).getChildAt(0) as TextView).setTextColor(Palette.DANGER) }

    /** Low-emphasis text action (e.g. delete); the caller confirms. */
    private fun quietAction(parent: ViewGroup, label: String, color: Int = Palette.MUTED, bold: Boolean = false, onClick: () -> Unit): Button =
        ui.button(parent, label, Tone.TEXT) { onClick() }.apply {
            setTextColor(color); textSize = 14f; layoutParams = wrap(); setPadding(ui.dp(4), ui.dp(8), ui.dp(12), ui.dp(8))
            typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }

    /** Bottom sheet with a drag handle; drag down or tap outside to close. A tap on the handle calls performClick. */
    @SuppressLint("ClickableViewAccessibility")
    private fun sheet(content: View): android.app.Dialog {
        val dialog = android.app.Dialog(this)
        val maxHeight = (resources.displayMetrics.heightPixels * .85f).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Palette.PAPER); val r = ui.dp(24).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(0, 0, 0, ui.dp(16))
        }
        val handleArea = FrameLayout(this).apply {
            minimumHeight = ui.dp(48); isClickable = true; isFocusable = true; contentDescription = "關閉"
            setOnClickListener { dialog.dismiss() }
        }
        handleArea.addView(iconButton(R.drawable.ic_close, "關閉") { dialog.dismiss() }, FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = ui.dp(8) })
        var downY = 0f
        handleArea.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downY = event.rawY; true }
                android.view.MotionEvent.ACTION_MOVE -> { root.translationY = (event.rawY - downY).coerceAtLeast(0f); true }
                android.view.MotionEvent.ACTION_UP -> {
                    val moved = event.rawY - downY
                    if (moved > ui.dp(96) || kotlin.math.abs(moved) < ui.dp(8)) { if (kotlin.math.abs(moved) < ui.dp(8)) view.performClick() else dialog.dismiss() }
                    else root.animate().translationY(0f).setDuration(150).start()
                    true
                }
                else -> { root.animate().translationY(0f).setDuration(150).start(); false }
            }
        }
        root.addView(handleArea, ui.lp())
        val scroller = object : ScrollView(this) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) = super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight - ui.dp(64), MeasureSpec.AT_MOST))
        }
        scroller.addView(content)
        root.addView(scroller, ui.lp())
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            if (RecordingSettings(this@MainActivity).protectAppScreen) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        dialog.show()
        return dialog
    }

    private fun chipRow(parent: ViewGroup): LinearLayout {
        val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(ui.dp(24)) }
        val row = ui.horizontal(null, 6)
        scroller.addView(row); parent.addView(scroller, ui.lp(top = 4, bottom = 4))
        return row
    }

    /** ‹ date › grouped together, plus a compact 今天 when another period is shown. The date opens a picker. */
    private fun dateNav(parent: ViewGroup, step: Long = 1, label: String = dateTitle(day), onChange: (LocalDate) -> Unit) {
        val today = journalDay(); val earliest = today.minusDays(TimelineMath.HISTORY_DAYS)
        fun target(delta: Long) = day.plusDays(delta).coerceIn(earliest, today)
        fun samePeriod(d: LocalDate) = if (step == 7L) TimelineMath.weekStart(d) == TimelineMath.weekStart(day) else d == day
        val row = ui.horizontal(parent, 0).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(8) }
        val prev = target(-step); val next = target(step)
        val unit = if (step == 7L) "週" else "天"
        row.addView(iconButton(R.drawable.ic_back, "前一$unit") { onChange(prev) }.apply { if (samePeriod(prev)) { isEnabled = false; alpha = .25f; contentDescription = "前一$unit（已到最早日期）" } })
        ui.button(row, label, Tone.TEXT) {
            DatePickerDialog(this@MainActivity, { _, y, m, d ->
                val picked = LocalDate.of(y, m + 1, d)
                if (TimelineMath.inHistory(picked, today)) onChange(picked) else notice("只能查看最近 10 年", Kind.WARN)
            }, day.year, day.monthValue - 1, day.dayOfMonth).apply {
                datePicker.minDate = earliest.atStartOfDay(zone).toInstant().toEpochMilli()
                datePicker.maxDate = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            }.show()
        }.apply { layoutParams = wrap(); setPadding(ui.dp(6), 0, ui.dp(6), 0); setTextColor(Palette.INK); textSize = 17f; typeface = android.graphics.Typeface.DEFAULT_BOLD; contentDescription = "$label，選擇日期" }
        row.addView(iconButton(R.drawable.ic_chevron, "後一$unit") { onChange(next) }.apply { if (samePeriod(next)) { isEnabled = false; alpha = .25f; contentDescription = "後一$unit（已是最新）" } })
        if (!samePeriod(today)) ui.button(row, if (step == 7L) "本週" else "今天", Tone.TEXT) { onChange(today) }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(48)); setPadding(ui.dp(12), 0, ui.dp(12), 0); textSize = 14f
        }
    }

    // ---------------------------------------------------------------- first run, connection and onboarding

    private fun boot() {
        val body = screen("PocketLog", "私人活動日誌", nav = false, logo = true)
        val slot = ui.column(body)
        load(slot, "開啟加密的連線資料…", { CredentialStore(this@MainActivity).load() }) { saved ->
            credentials = saved
            if (saved == null) setup() else { SyncEngine.schedule(this@MainActivity); enterWorkspace() }
        }
        // Recovery path if Keystore or the stored connection cannot be read.
        ui.button(body, "打不開？重設這支手機的資料與連線", Tone.TEXT) { confirmReset() }
    }

    private fun confirmReset(title: String = "重設這支手機？", message: String = "金鑰失效或連線資料損毀時使用。會停止記錄、刪除還沒上傳的資料，然後重新連線。伺服器上的記錄不會被刪除。") {
        dialog().setTitle(title).setMessage(message).setNegativeButton("取消", null).setPositiveButton("清除並重新設定") { _, _ ->
            task("清除這支手機的資料…", { SyncEngine.clearLocal(this@MainActivity) { CredentialStore(this@MainActivity).clear(); RecordingSettings(this@MainActivity).clearRejectedCredentials() } }) {
                clearDrafts(); credentials = null; workspace = null; loaded = null; aiAutomatic = null; aiConfigured = null; setup(); notice("已清除這支手機的連線與待上傳資料；伺服器上的記錄仍保留。", Kind.INFO)
            }
        }.showSecure()
    }

    private fun parseConnection(rawUrl: String, rawToken: String): Credentials {
        val url = rawUrl.trim().let { if (it.isNotEmpty() && "://" !in it) "https://$it" else it }
        require(url.isNotEmpty()) { "請填寫伺服器網址。" }
        val endpoint = try { EndpointPolicy.normalize(url) }
        catch (e: URISyntaxException) { throw IllegalArgumentException("網址格式不正確。") }
        catch (e: IllegalArgumentException) { throw IllegalArgumentException("網址：${e.message ?: "格式不正確"}。只填 https://主機:連接埠（VPN／私人網路可用 http://），不含路徑。") }
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
        val httpWarning = ui.text(form, "這個連線沒有 HTTPS，資料保護依賴你的 VPN／私人網路", 13f, Palette.WARN_INK, top = 4).apply { visibility = View.GONE }
        endpoint.addTextChangedListener(watcher { httpWarning.visibility = if (it.trim().startsWith("http://", ignoreCase = true)) View.VISIBLE else View.GONE })
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
                    PocketLogApi(candidate).verifyConnection(dayPath())
                    CredentialStore(this@MainActivity).save(candidate)
                    RecordingSettings(this@MainActivity).clearRejectedCredentials()
                    UsageCollector(this@MainActivity).reset()
                }
            }, failed = { fail(describe(it)) }) {
                clearDrafts(); credentials = candidate; token.text.clear(); SyncEngine.schedule(this@MainActivity); notice("已連線到 ${host()}"); enterWorkspace()
            }
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12) }
        ui.meta(form, "金鑰加密保存在這支手機，只放在連線標頭，不會出現在網址裡。公開網路上的伺服器一定要用 https。", top = 8)
        val help = ui.column(null)
        ui.button(body, "不知道網址或金鑰在哪裡？", Tone.TEXT) {
            help.visibility = if (help.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (help.visibility == View.VISIBLE) help.announceForAccessibility("說明已展開")
        }.apply { contentDescription = "展開或收起：如何取得伺服器網址與裝置金鑰" }
        body.addView(help, ui.lp())
        help.visibility = View.GONE
        ui.card(help).apply {
            step(this, "1", "伺服器網址", "也就是瀏覽器打開 PocketLog 網站的位址，例如 https://journal.example:12446 或 http://100.64.0.1:12446。只填到連接埠，不含路徑。手機也要連得到：在 VPN／私人網路（例如 Tailscale、ZeroTier、Headscale、WireGuard、區網） 裡請先讓手機加入同一個網路。")
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
        val selected = day
        lateinit var slot: LinearLayout
        val body = screen("時間軸", null, Tab.TIMELINE,
            action = Triple(R.drawable.ic_sync, "重新整理") {
                SyncEngine.schedule(this@MainActivity)
                if (loaded == null) home() else reloadTimeline(slot, { api().request(dayPath(selected)) }, { readJournal(dayPath(selected)) })
            })
        timelineDateBar.visibility = View.VISIBLE
        dateNav(timelineDateBar) { day = it; home() }
        ui.button(body, "搜尋活動", Tone.OUTLINE) { openSearchDialog() }.apply {
            layoutParams = wrap(); setPadding(ui.dp(16), 0, ui.dp(16), 0)
        }
        val status = ui.column(body)
        slot = ui.column(body)
        refreshOnResume = { statusLine(status) }
        load(slot, "讀取時間軸…", { readJournal(dayPath(selected)) }) { data ->
            renderTimeline(slot, data)
            startTimelineWatch(slot, data)
            statusLine(status, query = false)
        }
        statusLine(status) { latest ->
            val previous = workspace?.optLong("revision")
            workspace = latest
            val data = loaded
            if (data != null && previous != latest.optLong("revision")) {
                replaceTimeline(slot, data) { startTimelineWatch(slot, data) }
            }
        }
    }

    private data class SearchResult(
        val cards: List<JSONObject>,
        val scanned: Int,
        val cursor: TimelineSearch.Cursor?,
    )

    /** Opens a bounded search form; raw observations and screenshots are never queried. */
    private fun openSearchDialog() {
        val form = ui.column(null, 20)
        val query = ui.field(form, "關鍵字", "應用程式、標題或摘要")
        val ranges = listOf("最近 1 天", "最近 7 天", "最近 30 天")
        val range = ui.spinner(form, "搜尋範圍", ranges)
        val categoryIds = listOf<String?>(null) + categories
        val category = ui.spinner(form, "分類（可選）", categoryIds.map { it?.let(::label) ?: "全部分類" })
        val dialog = dialog().setTitle("搜尋活動").setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("取消", null).setPositiveButton("搜尋", null).showSecure()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val normalized = runCatching { TimelineSearch.normalizeQuery(query.text.toString()) }.getOrElse {
                query.error = "關鍵字最多 ${TimelineSearch.MAX_QUERY_CHARS} 字。"; query.requestFocus(); return@setOnClickListener
            }
            if (normalized == null) { query.error = "請輸入關鍵字。"; query.requestFocus(); return@setOnClickListener }
            dialog.dismiss()
            val days = listOf(1L, 7L, 30L)[range.selectedItemPosition.coerceIn(0, 2)]
            val selectedCategory = categoryIds[category.selectedItemPosition.coerceIn(0, categoryIds.lastIndex)]
            startSearch(normalized, days, selectedCategory)
        }
        query.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun startSearch(query: String, days: Long, category: String?, existing: List<JSONObject> = emptyList(), cursor: TimelineSearch.Cursor? = null, scanned: Int = 0) {
        val today = journalDay()
        val from = TimelineMath.dayStart(today.minusDays(days - 1), zone)
        val to = minOf(TimelineMath.dayStart(today.plusDays(1), zone), System.currentTimeMillis())
        val body = screen("搜尋活動", query, Tab.TIMELINE, back = { home() })
        val slot = ui.column(body)
        load(slot, if (existing.isEmpty()) "搜尋中…" else "繼續搜尋…", {
            fetchSearch(from, to, TimelineSearch.Request(query, category, cursor), existing, scanned)
        }) { result -> renderSearch(slot, result, query, days, category) }
    }

    private fun fetchSearch(from: Long, to: Long, request: TimelineSearch.Request, existing: List<JSONObject>, scannedBefore: Int): SearchResult {
        val cards = existing.toMutableList()
        var cursor = request.cursor
        var scanned = scannedBefore
        var requests = 0
        while (cursor != null || requests == 0) {
            if (requests >= TimelineSearch.MAX_REQUESTS || cards.size >= TimelineSearch.MAX_RESULTS) break
            val data = api().request(TimelineSearch.path(from, to, request.copy(cursor = cursor)))
            requests++
            scanned += data.optInt("scanned")
            cards += cardsOf(data)
            val next = data.optJSONObject("next_cursor")
            cursor = next?.let { TimelineSearch.Cursor(it.getLong("after_start_ms"), it.getString("after_id"), data.getLong("journal_epoch")) }
        }
        val found = cards.take(TimelineSearch.MAX_RESULTS)
        prepareApps(found.asSequence().map { it.optString("app") })
        return SearchResult(found, scanned, cursor)
    }

    private fun renderSearch(slot: LinearLayout, result: SearchResult, query: String, days: Long, category: String?) {
        val rangeLabel = when (days) { 1L -> "最近 1 天"; 7L -> "最近 7 天"; else -> "最近 30 天" }
        ui.meta(slot, "$rangeLabel · ${category?.let(::label) ?: "全部分類"}", bottom = 4)
        if (result.cards.isEmpty() && result.cursor == null) {
            ui.empty(slot, "沒有找到符合的活動", "只搜尋應用程式名稱、標題和摘要；原始畫面文字與截圖不會被搜尋。")
            return
        }
        val status = if (result.cursor == null) "找到 ${result.cards.size} 張活動卡，已搜尋完整期間。"
        else "已找到 ${result.cards.size} 張活動卡；還有記錄尚未檢查。"
        ui.banner(slot, "$status（已檢查 ${result.scanned} 筆）", Kind.INFO)
        var currentDay: LocalDate? = null
        result.cards.forEach { card ->
            val cardDay = Instant.ofEpochMilli(card.getLong("start_ms")).atZone(zone).let { if (it.hour < TimelineMath.DAY_START_HOUR) it.toLocalDate().minusDays(1) else it.toLocalDate() }
            if (cardDay != currentDay) {
                currentDay = cardDay
                ui.text(slot, TimelineMath.dayLabel(cardDay), 15f, Palette.ACCENT_DARK, bold = true, top = 8, bottom = 6).isAccessibilityHeading = true
            }
            cardRow(slot, card)
        }
        result.cursor?.let { next ->
            ui.button(slot, "繼續搜尋", Tone.OUTLINE) { startSearch(query, days, category, result.cards, next, result.scanned) }.apply {
                layoutParams = wrap(); setPadding(ui.dp(18), 0, ui.dp(18), 0)
            }
        }
    }

    private fun accessibilityServiceOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val ours = ComponentName(this, ContextService::class.java)
        return enabled.split(':').any { it.equals(ours.flattenToString(), true) || it.equals(ours.flattenToShortString(), true) }
    }

    /** The one tappable status line. Local state renders at once; AI and upload counts follow. */
    private fun statusLine(parent: LinearLayout, query: Boolean = true, workspaceUpdated: ((JSONObject) -> Unit)? = null) {
        val token = generation
        val s = RecordingSettings(this)
        fun draw(waiting: Int) {
            val pending = if (day == journalDay()) cardsOf(loaded).count { it.optBoolean("pending_context") } else 0
            val inputs = StatusLine.Inputs(s.isAuthRejected(credentials), s.remotePaused, s.hasUsageGrant, s.hasUsagePermission(), s.contextEnabled,
                accessibilityServiceOn(), aiConfigured, aiAutomatic, waiting, pending)
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
            val text = StatusLine.text(state, inputs)
            ui.text(row, text, 14f, if (warn) Palette.WARN_INK else Palette.ACCENT_DARK).layoutParams = ui.lp(0, weight = 1f)
            row.addView(ui.icon(R.drawable.ic_chevron, Palette.MUTED, 20))
            row.contentDescription = text
            parent.addView(row, ui.lp(bottom = 12))
        }
        draw(waitingCaptures)
        if (!query) return
        val connection = credentials ?: return
        statusExecutor.execute {
            if (token != generation) return@execute
            val waiting = runCatching { SyncEngine.waitingCaptures(this) }.getOrDefault(0)
            val ai = runCatching { PocketLogApi(connection).request("/v1/ai") }.getOrNull()
            runOnUiThread {
                if (token == generation && !isDestroyed && parent.isAttachedToWindow) {
                    if (ai != null) rememberAi(ai)
                    waitingCaptures = waiting
                    draw(waiting)
                }
            }
            if (workspaceUpdated != null && token == generation) {
                val latest = runCatching { PocketLogApi(connection).request("/v1/workspace") }.getOrNull()
                runOnUiThread {
                    if (latest != null && token == generation && !isDestroyed && parent.isAttachedToWindow) workspaceUpdated(latest)
                }
            }
        }
    }

    private fun rememberAi(config: JSONObject) {
        aiConfigured = config.optBoolean("available") && config.optBoolean("enabled")
        aiAutomatic = aiConfigured == true && config.optBoolean("auto_summarize")
    }

    private fun openStatus(state: StatusLine.State) = when (state) {
        StatusLine.State.KEY_REJECTED -> connectionRecovery()
        StatusLine.State.NOT_STARTED -> autoRecordFlow(0) { home() }
        StatusLine.State.NEEDS_USAGE_ACCESS -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        StatusLine.State.NEEDS_ACCESSIBILITY -> if (RecordingSettings(this).contextEnabled) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) else autoRecordFlow(2) { home() }
        StatusLine.State.AI_MISSING, StatusLine.State.AI_OFF -> aiSettings { home() }
        else -> settings()
    }

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
        if (busy) return
        task("正在更新活動…", reloadRead, failed = { error ->
            if (error is ApiFailure && error.status in setOf(401, 403)) {
                loaded = null
                timelineWatch?.close()
                timelineWatch = null
                slot.removeAllViews()
                ui.empty(slot, "無法載入", describe(error), "重試") { home() }
            }
        }) { fresh ->
            replaceTimeline(slot, fresh) { startTimelineWatch(slot, fresh, checkRead, reloadRead) }
        }
    }

    private fun replaceTimeline(slot: LinearLayout, data: JSONObject, watch: () -> Unit) {
        val oldY = scroll.scrollY
        val oldAxis = slot.findViewWithTag<DayTimelineView>("day-axis")
        val anchor = oldAxis?.scrollAnchor(oldY - slot.top - oldAxis.top)
        slot.removeAllViews()
        renderTimeline(slot, data, false)
        watch()
        slot.post {
            if (!slot.isAttachedToWindow) return@post
            val axis = slot.findViewWithTag<DayTimelineView>("day-axis")
            val y = if (axis != null && anchor != null) slot.top + axis.top + axis.scrollPosition(anchor) else oldY
            scroll.scrollTo(0, y)
        }
    }

    private fun renderTimeline(slot: LinearLayout, data: JSONObject) = renderTimeline(slot, data, true)

    private fun renderTimeline(slot: LinearLayout, data: JSONObject, jumpToActivity: Boolean) {
        loaded = data
        if (data.optBoolean("offline")) ui.banner(slot, "離線中：顯示手機上的副本，伺服器可能有更新。", Kind.WARN)
        if (data.optBoolean("truncated")) ui.banner(slot, "這天的資料太多，只顯示一部分，統計可能不完整。", Kind.WARN)
        if (data.optBoolean("pending_card_edits")) ui.banner(slot, "你的修改已存在手機，正在等待上傳。", Kind.INFO)
        val list = cardsOf(data)
        if (list.isEmpty()) emptyDay(slot)

        // Day summary: recorded time and one stacked category bar labelled by its top categories.
        if (list.isNotEmpty()) ui.card(slot).apply {
            val tracked = TimelineMath.covered(intervals(list))
            ui.text(this, "已記錄 ${TimelineMath.duration(tracked)}", 20f, bold = true)
            val all = totals(list)
            val top = all.take(4)
            val rest = all.drop(4).sumOf { it.second }
            addView(CategoryBarView(this@MainActivity, top.map { colorFor(it.first) to it.second } + if (rest > 0) listOf(Palette.FALLBACK_CATEGORY to rest) else emptyList()), ui.lp(top = 10, bottom = 8))
            // Each legend item is one unbreakable unit with its colour dot.
            val legend = ui.flow(this, 12)
            (top.map { colorFor(it.first) to "${label(it.first)} ${TimelineMath.duration(it.second)}" } + if (rest > 0) listOf(Palette.FALLBACK_CATEGORY to "其他 ${TimelineMath.duration(rest)}") else emptyList()).forEach { (color, text) ->
                val item = ui.horizontal(null, 6)
                item.addView(ui.swatch(color, 8))
                ui.text(item, text, 13f, Palette.MUTED).apply { isSingleLine = true }.layoutParams = wrap()
                legend.addView(item, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }

        val present = list.map { it.getString("category") }.distinct()
        if (categoryFilter.let { it != null && it !in present }) categoryFilter = null
        val head = ui.horizontal(slot, 8).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(12) }
        val count = ui.text(head, "", 16f, bold = true).apply { layoutParams = ui.lp(0, weight = 1f) }
        lateinit var filter: Button
        val from = TimelineMath.dayStart(day, zone)
        val to = TimelineMath.dayStart(day.plusDays(1), zone)
        val axis = DayTimelineView(this).apply { tag = "day-axis" }
        var shown = emptyList<JSONObject>()
        val hourMs = 3_600_000L
        for (at in generateSequence(from) { (it + hourMs).takeIf { next -> next < to } } + sequenceOf(to)) {
            val tick = ui.text(null, clock(at), 13f, Palette.MUTED).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                contentDescription = if (at == to) "隔天 ${clock(at)}" else clock(at)
                isAccessibilityHeading = true
                if (at < to) setOnClickListener {
                    val rows = shown.filter { it.getLong("start_ms") < at + hourMs && it.getLong("end_ms") > at }
                    if (rows.isNotEmpty()) hourDetails(clock(at), rows)
                }
            }
            axis.addTick(at, tick)
        }
        fun draw() {
            shown = list.filter { categoryFilter == null || it.getString("category") == categoryFilter }
            val times = intervals(shown)
            val entries = shown.zip(times).map { (card, time) ->
                TimelineMath.TimelineEntry(time, card.optString("source") == "app_only" &&
                    !card.optBoolean("pending_edit") && time.end - time.start <= 120_000)
            }
            val groups = TimelineMath.displayGroups(entries, from).map { members -> members.map(shown::get) }
            count.text = "${groups.size} 張活動卡"
            count.contentDescription = "${groups.size} 張活動卡，包含 ${shown.size} 筆紀錄"
            filter.text = "篩選：${categoryFilter?.let(::label) ?: "全部"} ▾"
            val windows = groups.map { cards ->
                TimelineMath.Interval(cards.first().getLong("start_ms").coerceAtMost(to - 1),
                    cards.maxOf { it.getLong("end_ms") })
            }
            axis.setBlocks(windows, groups.map(::timelineBlock))
        }
        filter = ui.button(head, "", Tone.CHIP) {
            val options = listOf<String?>(null) + present
            dialog().setTitle("顯示哪個分類")
                .setSingleChoiceItems(options.map { it?.let(::label) ?: "全部" }.toTypedArray(), options.indexOf(categoryFilter).coerceAtLeast(0)) { d, which ->
                    categoryFilter = options[which]; d.dismiss(); draw()
                }.setNegativeButton("取消", null).showSecure()
        }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(38)); minHeight = ui.dp(38); minimumHeight = ui.dp(38); setPadding(ui.dp(14), 0, ui.dp(14), 0); textSize = 13f }
        draw()
        slot.addView(axis, ui.lp())
        if (jumpToActivity && list.isNotEmpty() && page.indexOfChild(slot) >= 0) {
            val anchor = if (day == journalDay()) list.last() else list.first()
            val start = anchor.getLong("start_ms")
            val hour = from + ((start - from).coerceAtLeast(0) / hourMs) * hourMs
            val token = generation
            axis.post {
                if (token == generation && axis.isAttachedToWindow && scroll.scrollY == 0) {
                    scroll.scrollTo(0, slot.top + axis.top + axis.y(hour) - ui.dp(24))
                }
            }
        }
    }

    private fun timelineIcon(app: String, category: String): Bitmap? {
        if (app.isBlank()) return null
        appIcons.get(app)?.let { return it }
        fallbackIcons.get(app)?.let { return it }
        val size = ui.dp(18)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Palette.tint(appColor(app, category), .25f)
        val radius = ui.dp(5).toFloat()
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), radius, radius, paint)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = size * .64f
        paint.color = Palette.INK
        val name = appNames.name(app)
        canvas.drawText(name.substring(0, name.offsetByCodePoints(0, 1)),
            size / 2f, (size - paint.ascent() - paint.descent()) / 2f, paint)
        fallbackIcons.put(app, bitmap)
        return bitmap
    }

    private fun timelineBlock(cards: List<JSONObject>): View {
        val first = cards.first()
        val single = cards.size == 1
        val start = first.getLong("start_ms")
        val end = cards.maxOf { it.getLong("end_ms") }
        val apps = cards.map { it.optString("app") }.distinct()
        val app = apps.singleOrNull().orEmpty()
        val names = apps.take(2).joinToString("、", transform = appNames::name)
        val title = when {
            single -> cardTitle(first)
            apps.size == 1 -> "使用 $names"
            apps.size == 2 -> names
            else -> "${names}等 ${apps.size} 個 App"
        }
        val category = cards.map { it.getString("category") }.distinct().singleOrNull()
        val summary = if (single) cardSummary(first) else ""
        val badge = cards.firstOrNull { it.optBoolean("pending_context") }?.let { "AI 整理中" }
            ?: if (single) badges(first).firstOrNull() else if (cards.any { it.optBoolean("protected_screen") }) "受保護畫面" else null
        val meta = listOfNotNull(
            "${clock(start)}–${clock(end)}",
            if (single) TimelineMath.duration(end - start) else "${cards.size} 筆",
            category?.takeUnless { it == "unsorted" }?.let(::label),
            badge,
        ).joinToString(" · ")
        val categoryColor = category?.let(::colorFor) ?: Palette.FALLBACK_CATEGORY
        val fill = Palette.tint(categoryColor, .20f)
        val stripe = if (app.isNotBlank()) appColor(app, category ?: "unsorted") else categoryColor
        val surface = LayerDrawable(arrayOf(ui.shape(stripe, 9f), InsetDrawable(ui.shape(fill, 7f), ui.dp(4), 0, 0, 0)))
        return ui.column(null).apply {
            background = ui.ripple(surface, 9f)
            setPadding(ui.dp(13), ui.dp(7), ui.dp(9), ui.dp(7))
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            contentDescription = listOf(title, meta, summary).filter { it.isNotBlank() }.joinToString("，")
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            val row = ui.horizontal(this, 6)
            timelineIcon(app, category ?: "unsorted")?.let { bitmap ->
                row.addView(ImageView(this@MainActivity).apply {
                    setImageBitmap(bitmap)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(ui.dp(17), ui.dp(17)))
            }
            ui.text(row, title, 15f, bold = true).apply {
                layoutParams = ui.lp(0, weight = 1f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            ui.text(this, meta, 12f, Palette.MUTED).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (summary.isNotBlank()) ui.text(this, summary, 13f, Palette.MUTED, top = 2).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            setOnClickListener {
                if (!busy) {
                    if (single) cardDetail(first) else hourDetails("${clock(start)}–${clock(end)}", cards)
                }
            }
        }
    }

    private fun hourDetails(hour: String, cards: List<JSONObject>) {
        val content = ui.column(null).apply { setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(8)) }
        ui.heading(content, hour)
        lateinit var detail: android.app.Dialog
        cards.forEach { card ->
            val range = timeRange(card.getLong("start_ms"), card.getLong("end_ms"))
            ui.row(content, cardTitle(card), range) { detail.dismiss(); cardDetail(card) }
        }
        detail = sheet(content)
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
                aiConfigured == false -> action("設定 AI") { aiSettings { home() } }
                else -> ui.meta(this, "手機會在背景記錄，AI 整理後會出現在這裡。").gravity = Gravity.CENTER
            }
        }
    }

    /** App identity has a stable stripe and icon. The chip shows the activity category. */
    private fun cardRow(parent: LinearLayout, c: JSONObject) {
        val start = c.getLong("start_ms"); val end = c.getLong("end_ms")
        val title = cardTitle(c)
        val app = c.optString("app")
        val category = c.getString("category")
        val showCategory = !c.optBoolean("pending_context") && (category != "unsorted" || c.optString("source") != "app_only")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; minimumHeight = ui.dp(56)
            isClickable = true; isFocusable = true; clipToOutline = true
            background = ui.ripple(ui.shape(Palette.PAPER, 14f, Palette.BORDER), 14f); setOnClickListener { if (!busy) cardDetail(c) }
        }
        row.addView(View(this).apply { setBackgroundColor(appColor(app, category)) }, LinearLayout.LayoutParams(ui.dp(4), ViewGroup.LayoutParams.MATCH_PARENT))
        val column = ui.column(row).apply { layoutParams = ui.lp(0, weight = 1f); setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(10)) }
        // Row 1: time · duration, soft category chip and at most one badge.
        val meta = ui.flow(column, 6).apply { (layoutParams as LinearLayout.LayoutParams).apply { topMargin = 0; bottomMargin = ui.dp(4) } }
        val time = timeRange(start, end)
        ui.text(meta, time, 13f, Palette.MUTED).layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (showCategory) categoryChip(meta, category)
        val badges = badges(c)
        badges.forEach { ui.badge(meta, it, badgeKind(it)) }
        appTitle(column, app, title, category, 17f, bold = true)
        val summary = cardSummary(c)
        if (summary.isNotBlank()) ui.text(column, summary, 14f, Palette.MUTED, top = 2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        row.contentDescription = (listOfNotNull(title, time, label(category).takeIf { showCategory }) + badges + summary).filter { it.isNotBlank() }.joinToString("，")
        parent.addView(row, ui.lp(bottom = 12))
    }

    /** Soft category chip with a colour dot. */
    private fun categoryChip(parent: ViewGroup, id: String): TextView = TextView(this).apply {
        text = label(id); textSize = 12f; setTextColor(Palette.INK)
        background = ui.shape(Palette.tint(colorFor(id), .22f), 10f); setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
        setCompoundDrawablesRelativeWithIntrinsicBounds(dot(colorFor(id), 6), null, null, null); compoundDrawablePadding = ui.dp(4)
        parent.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun dot(color: Int, size: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(color); setSize(ui.dp(size), ui.dp(size))
    }

    /**
     * Card detail sheet, spec order: title → App · 時間 · 時長 → summary → one disclaimer line
     * (ai/local_excerpt only) → category chips → 修改文字.
     */
    private fun cardDetail(card: JSONObject) {
        val content = ui.column(null).apply { setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(8)) }
        val start = card.getLong("start_ms"); val end = card.getLong("end_ms")
        val app = card.optString("app")
        val title = cardTitle(card)
        val appName = appNames.name(app)
        appTitle(content, app, title, card.getString("category"), 19f, bold = true)
        ui.text(content, listOfNotNull(appName.takeIf { app.isNotBlank() && it != title }, timeRange(start, end)).joinToString(" · "), 14f, Palette.INK, top = 4)
        val summary = card.optString("summary")
        cardSummary(card).takeIf { it.isNotBlank() }?.let { ui.body(content, it, top = 8) }
        lateinit var detail: android.app.Dialog
        val note = when {
            card.optBoolean("pending_context") -> "AI 整理中：還有新的截圖或文字等 AI 整理，稍後再看。"
            card.optBoolean("protected_screen") -> "受保護的畫面只依 App 名稱與畫面文字整理。"
            card.optString("source") in setOf("ai", "local_excerpt") -> "AI 整理，可能有誤，請核對。"
            else -> null
        }
        note?.let { ui.text(content, it, 13f, Palette.MUTED, top = 4) }
        val replay = ui.column(content).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(14)
        }
        ui.text(content, "分類", 15f, Palette.INK, bold = true, top = 12).isAccessibilityHeading = true
        ui.text(content, "你選的分類會優先於自動分類。點一下就會儲存。", 13f, Palette.MUTED, bottom = 4)
        val current = card.getString("category")
        // Compact wrapping chips keep the detail sheet short on phones and desktop widths.
        val ids = (categories + current).distinct()
        val chips = ui.flow(content, 6)
        ids.forEach { id ->
            val on = id == current
            ui.button(chips, label(id), Tone.CHIP) {
                if (!on) saveCard(card, cardTitleForSave(card), summary, id) { detail.dismiss() }
            }.apply {
                textSize = 13f; gravity = Gravity.CENTER
                background = ui.ripple(ui.shape(if (on) Palette.ACCENT_SOFT else Palette.PAPER, 20f, Palette.BORDER), 20f)
                setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4))
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (on) Palette.ACCENT_DARK else Palette.INK)
                if (on) typeface = android.graphics.Typeface.DEFAULT_BOLD
                isSelected = on; contentDescription = "${label(id)}，${if (on) "目前分類" else "改成這個分類"}"
            }
        }
        quietAction(content, "修改文字", Palette.ACCENT_DARK) { detail.dismiss(); editCardText(card) }.apply {
            setCompoundDrawablesRelative(scaled(R.drawable.ic_edit, 18, Palette.ACCENT_DARK), null, null, null); compoundDrawablePadding = ui.dp(6)
        }
        activityDetail?.dismiss()
        detail = sheet(content)
        activityDetail = detail
        val dispose = showActivityReplay(replay, detail, card)
        detail.setOnDismissListener {
            dispose()
            if (activityDetail === detail) activityDetail = null
        }
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

    /** Replay loads only saved thumbnails for the lifetime of this activity detail. */
    private fun showActivityReplay(panel: LinearLayout, owner: android.app.Dialog, card: JSONObject): () -> Unit {
        ui.heading(panel, "畫面")
        val slot = ui.column(panel)
        val connection = credentials
        val window = ReplayFrames.window(card, System.currentTimeMillis())
        if (window == null || connection == null) {
            ui.meta(slot, if (window == null) "這段活動已超過截圖的 24 小時保存期限。" else "目前無法讀取保存的截圖。")
            return {}
        }

        val replay = PocketLogApi(connection)
        var request = 0
        var future: java.util.concurrent.Future<*>? = null
        var bitmap: Bitmap? = null
        var picture: ImageView? = null
        var active = true

        fun clearImage() {
            picture?.setImageDrawable(null)
            picture = null
            bitmap?.recycle()
            bitmap = null
        }

        fun <T> read(work: () -> T, discard: (T) -> Unit = {}, deliver: (Result<T>) -> Unit) {
            val id = ++request
            future?.cancel(false)
            future = replayExecutor.submit {
                val result = runCatching(work)
                runOnUiThread {
                    if (active && id == request && owner.isShowing && !isFinishing && !isDestroyed) deliver(result)
                    else result.onSuccess(discard)
                }
            }
        }

        fun failed(parent: LinearLayout, error: Throwable, missing: String, retry: () -> Unit) {
            val message = when (error) {
                is ApiFailure -> if (error.status == 404) missing else describe(error)
                is IllegalArgumentException -> "截圖格式無法顯示。"
                else -> describe(error)
            }
            ui.meta(parent, message)
            quietAction(parent, "重試") { retry() }
        }

        lateinit var refresh: (String?) -> Unit
        fun showAt(items: List<JSONObject>, index: Int) {
            clearImage()
            slot.removeAllViews()
            val item = items[index]
            val previewHeight = minOf(ui.dp(220), resources.displayMetrics.heightPixels / 3)
            val photoRow = ui.column(slot).apply {
                minimumHeight = previewHeight
                gravity = Gravity.CENTER_VERTICAL
            }
            ui.spinnerRow(photoRow, "讀取截圖…")
            val meta = ui.horizontal(slot, 4)
            if (items.size > 1) {
                ui.button(meta, "上一張", Tone.TEXT) { showAt(items, index - 1) }.apply {
                    layoutParams = wrap()
                    isEnabled = index > 0
                }
            }
            ui.text(meta, "${clock(item.getLong("at_ms"))}" + if (items.size > 1) " · ${index + 1}/${items.size}" else "",
                13f, Palette.MUTED).apply { gravity = Gravity.CENTER; layoutParams = ui.lp(0, weight = 1f) }
            if (items.size > 1) {
                ui.button(meta, "下一張", Tone.TEXT) { showAt(items, index + 1) }.apply {
                    layoutParams = wrap()
                    isEnabled = index + 1 < items.size
                }
            }

            read({ loadReplayBitmap(replay, item) }, Bitmap::recycle) { result ->
                photoRow.removeAllViews()
                result.onSuccess { image ->
                    bitmap = image
                    picture = ImageView(this).apply {
                        background = ui.shape(Palette.CANVAS, 12f, Palette.BORDER)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageBitmap(image)
                        contentDescription = "放大 ${clock(item.getLong("at_ms"))} 的截圖"
                        isFocusable = true
                        setOnClickListener {
                            if (!busy && !image.isRecycled) {
                                image.copy(Bitmap.Config.ARGB_8888, false)?.let { enlarged ->
                                    showReplayBitmap(enlarged, item) { refresh(item.getString("id")) }
                                }
                            }
                        }
                    }.also { photoRow.addView(it, ui.lp(height = previewHeight)) }
                }.onFailure { error ->
                    failed(photoRow, error, "這張截圖已到期或被刪除。") {
                        if (error is ApiFailure && error.status == 404) refresh(item.getString("id"))
                        else showAt(items, index)
                    }
                }
            }
        }

        refresh = refresh@ { removed ->
            if (!active || !owner.isShowing) return@refresh
            clearImage()
            slot.removeAllViews()
            ui.spinnerRow(slot, "尋找這段活動的截圖…")
            read({ ReplayFrames.matching(replay.request(window), card) }) { result ->
                slot.removeAllViews()
                result.onSuccess { rows ->
                    val items = rows.filterNot { it.getString("id") == removed }
                    if (items.isEmpty()) {
                        val message = when {
                            removed != null -> "這段活動已沒有保存的截圖。"
                            card.optBoolean("protected_screen") -> "這段沒有保存的截圖，部分畫面可能受保護。"
                            card.optBoolean("pending_context") -> "目前沒有可回看的截圖。剛保存的畫面需要等伺服器處理。"
                            else -> "這段時間沒有保存可回看的截圖。"
                        }
                        ui.meta(slot, message)
                        if (card.optBoolean("pending_context")) quietAction(slot, "重新檢查") { refresh(null) }
                    } else showAt(items, 0)
                }.onFailure { error -> failed(slot, error, "目前無法讀取保存的截圖。") { refresh(removed) } }
            }
        }
        refresh(null)
        return {
            active = false
            future?.cancel(false)
            clearImage()
        }
    }

    private fun loadReplayBitmap(api: PocketLogApi, item: JSONObject): Bitmap {
        val response = api.request("/v1/replay/${item.getString("id")}")
        val screenshot = response.optJSONObject("screenshot")
        val encoded = screenshot?.getString("base64") ?: response.getString("jpeg_base64")
        val mimeType = when (screenshot?.getString("format")) {
            null, "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> error("Unsupported replay image format")
        }
        val maxBytes = if (screenshot == null) 24 * 1024 else RecordingRules.MAX_IMAGE_BYTES
        require(encoded.length in 4..((maxBytes + 2) / 3 * 4))
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        try {
            require(bytes.size in 1..maxBytes)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outMimeType == mimeType)
            require(if (screenshot == null) bounds.outWidth in 1..480 && bounds.outHeight in 1..480
                else ContextImage.fitsDimensions(bounds.outWidth, bounds.outHeight))
            return requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        } finally { bytes.fill(0) }
    }

    /** A missing image is normal under expiry or deletion by another client. */
    private fun replayUnavailable(error: Throwable) {
        if (error is ApiFailure && error.status == 404) notice("這張截圖已到期或被刪除。", Kind.WARN)
    }

    private fun showReplayBitmap(bitmap: Bitmap, item: JSONObject, onDeleted: () -> Unit) {
        replayDialog?.dismiss()
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "${appNames.name(item.getString("app"))} ${stamp(item.getLong("at_ms"))} 的截圖"
        }
        var confirmation: AlertDialog? = null
        val frame = FrameLayout(this).apply {
            addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, resources.displayMetrics.heightPixels / 2))
        }
        val preview = dialog().setTitle(stamp(item.getLong("at_ms"))).setView(frame)
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
                            task("刪除截圖…", { api().request("/v1/replay/${item.getString("id")}", method = "DELETE") }, failed = { replayUnavailable(it) }) { onDeleted(); notice("已刪除截圖") }
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

    private data class ReviewData(val overview: JSONObject, val ai: JSONObject?, val briefs: JSONObject?, val days: List<Pair<LocalDate, JSONObject?>>, val conversation: JSONObject?)
    private fun periodKey(weekly: Boolean) = if (weekly) "w:${TimelineMath.weekStart(day)}" else "d:$day"

    private fun review() {
        val weekly = reviewWeekly
        val first = if (weekly) TimelineMath.weekStart(day) else day
        val span = if (weekly) TimelineMath.weekLabel(first) else dateTitle(day)
        val body = screen("回顧", null, Tab.REVIEW)
        stickyPeriod = span
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
            val dates = if (weekly) (0L..6L).map { first.plusDays(it) } else listOf(first)
            val days = dates.map { d ->
                d to if (d > today || !TimelineMath.inHistory(d, today)) null else runCatching { readJournal(dayPath(d)) }.getOrNull()
            }
            val conversation = conversations[key]?.let { id -> runCatching { api.request("/v1/conversations/$id") }.getOrNull() }
            ReviewData(overview, ai, briefs, days, conversation)
        }) { data ->
            data.ai?.let(::rememberAi)
            renderReview(slot, data, weekly, from, to, key)
        }
    }

    private fun renderReview(slot: LinearLayout, data: ReviewData, weekly: Boolean, from: Long, to: Long, key: String) {
        if (data.overview.optBoolean("offline")) ui.banner(slot, "離線中：顯示手機上的副本。", Kind.WARN)
        val ai = data.ai
        val aiOn = ai?.optBoolean("enabled") == true
        val aiHost = AiProviders.host(ai?.optString("endpoint"))
        val period = if (weekly) "這週" else if (day == journalDay()) "今天" else "這天"

        val categoryTotals = data.overview.optJSONArray("categories") ?: JSONArray()
        val rows = (0 until categoryTotals.length()).map {
            categoryTotals.getJSONObject(it).let { total -> total.getString("category") to total.getLong("duration_ms") }
        }.filter { it.second > 0 }
        ui.card(slot).apply {
            ui.heading(this, "時間分配")
            if (rows.isEmpty()) ui.body(this, "這段時間沒有活動。") else categoryDistribution(this, rows)
        }
        ui.card(slot).apply {
            ui.heading(this, if (weekly) "這週的活動時段" else "當天的活動時段")
            activityTracks(this, data.days, weekly)
        }

        // AI 回顧
        ui.card(slot).apply {
            ui.heading(this, "AI 回顧")
            when {
                ai == null -> ui.text(this, "目前讀不到 AI 設定（可能離線）。", 14f, Palette.MUTED)
                !aiOn -> {
                    ui.body(this, "設定 AI 之後，可以一鍵整理這段時間。")
                    ui.button(this, "先設定 AI", Tone.TONAL) { aiSettings { review() } }.layoutParams = wrap().apply { topMargin = ui.dp(8) }
                }
                else -> {
                    val kind = if (weekly) "weekly" else "daily"
                    val rows = data.briefs?.optJSONArray("briefs") ?: JSONArray()
                    val brief = (0 until rows.length()).map { rows.getJSONObject(it) }.lastOrNull { it.optString("kind") == kind }
                    if (brief != null) {
                        ui.body(this, brief.getString("text"), top = 4)
                        ui.text(this, "AI 產生，可能有誤，請對照時間軸。", 13f, Palette.MUTED, top = 8)
                        val actions = ui.horizontal(this, 8).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(8) }
                        ui.button(actions, "重新產生", Tone.OUTLINE) { generateBrief(weekly, ai, from, to, fresh = true) }.layoutParams = wrap()
                        actions.addView(View(this@MainActivity), ui.lp(0, 1, weight = 1f))
                        quietAction(actions, "刪除") {
                            confirm("刪除這份回顧？", "只刪除這份 AI 回顧，活動卡不受影響。無法復原。", "刪除") {
                                task("刪除中…", { api().request("/v1/briefs/${brief.getString("id")}", method = "DELETE") }) { review(); notice("已刪除") }
                            }
                        }
                    } else {
                        ui.body(this, if (data.briefs == null) "目前讀不到已保存的回顧。" else if (weekly) "還沒有這週的回顧。" else "還沒有${period}的回顧。")
                        ui.button(this, "產生 AI 回顧") { generateBrief(weekly, ai, from, to, fresh = false) }.layoutParams = wrap().apply { topMargin = ui.dp(10); bottomMargin = ui.dp(6) }
                    }
                    ui.text(this, "會把這段時間的活動卡送到 $aiHost", 13f, Palette.MUTED, top = 4)
                }
            }
        }

        if (weekly) ui.card(slot).apply {
            ui.heading(this, "這週每天")
            weekBars(this, data.days)
            val started = data.days.filter { it.first <= journalDay() }
            if (started.isNotEmpty() && started.all { (_, data) -> data != null && !data.optBoolean("truncated") }) {
                val average = started.sumOf { TimelineMath.covered(intervals(cardsOf(it.second))) } / started.size
                ui.text(this, "平均每天 ${TimelineMath.duration(average)}", 14f, Palette.INK, top = 8)
            }
        }

        // 常用 app and 主題
        val apps = data.overview.optJSONArray("apps") ?: JSONArray()
        if (apps.length() > 0) ui.card(slot).apply {
            ui.heading(this, "常用應用程式")
            for (i in 0 until minOf(5, apps.length())) {
                val a = apps.getJSONObject(i)
                val row = ui.horizontal(this, 8).apply { minimumHeight = ui.dp(36) }
                val app = a.getString("app")
                appTitle(row, app, appNames.name(app), "unsorted", 15f, bold = false)
                ui.text(row, TimelineMath.duration(a.getLong("duration_ms")), 14f, Palette.INK).apply { gravity = Gravity.END }.layoutParams = wrap()
            }
        }
        val keywords = data.overview.optJSONArray("title_keywords") ?: JSONArray()
        // Topics only when the server's model labelled them; local keywords are just card titles.
        if (keywords.length() > 0 && data.overview.optString("keyword_mode") in setOf("model", "mixed")) ui.card(slot).apply {
            ui.heading(this, "主題")
            val chips = ui.flow(this, 6)
            for (i in 0 until minOf(12, keywords.length())) ui.pill(chips, keywords.getJSONArray(i).getString(0), true)
        }

        // 問 AI
        ui.card(slot).apply {
            ui.heading(this, "問 AI")
            if (!aiOn) { ui.text(this, if (ai == null) "目前讀不到 AI 設定。" else "設定 AI 之後，可以問這段時間的事。", 14f, Palette.MUTED); return@apply }
            val answers = ui.column(this)
            val turns = data.conversation?.optJSONArray("turns") ?: JSONArray()
            if (turns.length() == 0) ui.text(answers, "問題和回答會出現在這裡。", 14f, Palette.MUTED, bottom = 4)
            for (i in 0 until turns.length()) turns.getJSONObject(i).let { bubble(answers, it.getString("question"), true); bubble(answers, it.getString("answer"), false) }
            // The send icon sits inside the input.
            val input = FrameLayout(this@MainActivity)
            addView(input, ui.lp(top = 4))
            val question = ui.field(input, null, "問問這段時間…", Field.MULTILINE, questionDrafts[key] ?: "").apply {
                minLines = 1; maxLines = 5; setPadding(ui.dp(14), ui.dp(12), ui.dp(52), ui.dp(12))
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            question.filters = arrayOf(android.text.InputFilter.LengthFilter(1000))
            question.addTextChangedListener(watcher { questionDrafts.put(key, it) })
            input.addView(iconButton(R.drawable.ic_send, "問 AI") { ask(question, answers, ai, from, to, key) }.apply {
                imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
                background = ui.ripple(android.graphics.drawable.InsetDrawable(android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Palette.ACCENT) }, ui.dp(6)), 24f)
            }, FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = ui.dp(2) })
            // Example chips in one horizontally scrollable row, smaller than the input.
            val examples = chipRow(this)
            (if (weekly) listOf("這週花最多時間在哪？", "這週學了什麼？", "這週有哪些分心的時段？") else listOf("今天花最多時間在哪？", "今天學了什麼？", "有哪些分心的時段？")).forEach { sample ->
                ui.button(examples, sample, Tone.CHIP) { question.setText(sample); ask(question, answers, ai, from, to, key) }.apply {
                    layoutParams = wrap(); textSize = 12.5f; setPadding(ui.dp(14), 0, ui.dp(14), 0)
                    background = ui.ripple(android.graphics.drawable.InsetDrawable(ui.shape(Palette.PAPER, 16f, Palette.BORDER), 0, ui.dp(8), 0, ui.dp(8)), 16f)
                }
            }
            // Keep "（不含截圖）" together with a no-break joiner between its characters.
            ui.text(this, "會把問題和這段時間的活動卡送到 $aiHost" + "（不含截圖）。".toList().joinToString("\u2060"), 13f, Palette.MUTED, top = 2)
            if (turns.length() > 0) quietAction(this, "開始新的對話", Palette.ACCENT_DARK) { conversations.remove(key); review() }
        }
    }

    private fun categoryDistribution(parent: LinearLayout, totals: List<Pair<String, Long>>) {
        val sum = totals.sumOf { it.second }
        val sideBySide = resources.configuration.screenWidthDp >= 340 && resources.configuration.fontScale <= 1.2f
        val layout = if (sideBySide) ui.horizontal(parent, 12) else parent
        val ringSize = ui.dp(128)
        layout.addView(CategoryRing(this, totals.map { colorFor(it.first) to it.second }),
            LinearLayout.LayoutParams(ringSize, ringSize).apply { gravity = Gravity.CENTER })
        val list = ui.column(layout)
        if (sideBySide) list.layoutParams = ui.lp(0, weight = 1f)
        totals.forEach { (category, ms) ->
            val percent = (ms.toDouble() / sum * 100).roundToInt()
            val row = ui.horizontal(list, 6).apply { minimumHeight = ui.dp(36) }
            row.addView(ui.swatch(colorFor(category), 8))
            val column = ui.column(row).apply { layoutParams = ui.lp(0, weight = 1f) }
            ui.text(column, label(category), 13f, bold = true)
            ui.text(column, "${TimelineMath.duration(ms)} · $percent%", 12f, Palette.MUTED)
        }
    }

    private fun activityTracks(parent: LinearLayout, days: List<Pair<LocalDate, JSONObject?>>, weekly: Boolean) {
        val date = days.first().first
        val showProgress = if (weekly) TimelineMath.weekUsesProgressAxis(date, zone) else TimelineMath.dayLength(date, zone) != 86_400_000L
        val labels = if (showProgress) listOf("04:00", "¼", "½", "¾", "04:00") else listOf("04", "10", "16", "22", "04")
        val axis = ui.horizontal(parent, 8).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        axis.addView(View(this), ui.lp(ui.dp(76), 1))
        val ticks = ui.horizontal(axis, 0).apply { layoutParams = ui.lp(0, weight = 1f) }
        labels.forEachIndexed { index, text ->
            ui.text(ticks, text, 11f, Palette.MUTED).apply {
                gravity = when (index) { 0 -> Gravity.START; labels.lastIndex -> Gravity.END; else -> Gravity.CENTER }
                layoutParams = ui.lp(0, weight = if (index == 0 || index == labels.lastIndex) .5f else 1f)
            }
        }

        fun track(name: String, d: LocalDate, marked: List<TimelineMath.CategoryInterval>, available: Boolean, details: String) {
            val row = ui.horizontal(parent, 8).apply {
                minimumHeight = ui.dp(30)
                isScreenReaderFocusable = true
                contentDescription = "$name，$details"
            }
            ui.text(row, name, 12f).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                .layoutParams = ui.lp(ui.dp(76))
            val spans = marked.map {
                ChartSpan(TimelineMath.dayFraction(d, zone, it.start), TimelineMath.dayFraction(d, zone, it.end), colorFor(it.category))
            }
            row.addView(ActivityTrack(this, spans, available), ui.lp(0, ui.dp(24), weight = 1f))
        }

        val today = journalDay()
        val categoryIds = linkedSetOf<String>()
        days.forEach { (d, data) ->
            val available = data != null
            val marked = categoryIntervals(cardsOf(data))
            categoryIds += marked.map { it.category }
            if (weekly) {
                val detail = when {
                    d > today -> "尚未到來"
                    !available -> "暫時讀不到記錄"
                    marked.isEmpty() -> "沒有記錄"
                    else -> marked.groupBy { it.category }.entries.joinToString("，") { (id, entries) ->
                        "${label(id)} ${TimelineMath.duration(entries.sumOf { it.end - it.start })}"
                    }
                }
                val dayLabel = TimelineMath.weekdayShort(d)
                val suffix = when {
                    d > today -> " · 未到"
                    !available -> " · 缺資料"
                    else -> ""
                }
                track(dayLabel + suffix, d, marked, available, detail)
            } else if (marked.isEmpty()) {
                ui.body(parent, if (available) "這天沒有活動。" else "暫時讀不到這天的記錄。")
            } else {
                marked.groupBy { it.category }.forEach { (id, entries) ->
                    val detail = entries.take(5).joinToString("、") { "${clock(it.start)}–${clock(it.end)}" } +
                        if (entries.size > 5) "，共 ${entries.size} 段" else ""
                    track(label(id), d, entries, true, detail)
                }
            }
        }
        if (weekly && categoryIds.isNotEmpty()) {
            val legend = ui.flow(parent, 10)
            categoryIds.forEach { id ->
                ui.text(legend, label(id), 12f, Palette.MUTED).apply {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(dot(colorFor(id), 7), null, null, null)
                    compoundDrawablePadding = ui.dp(4)
                }.layoutParams = wrap()
            }
        }
        if (days.any { (_, data) -> data?.optBoolean("truncated") == true }) ui.meta(parent, "部分活動未載入，圖表只顯示已載入的時段。", top = 4)
        if (showProgress) ui.meta(parent, "夏令時間切換日使用當天經過的時間比例。", top = 4)
    }

    private fun weekBars(parent: LinearLayout, days: List<Pair<LocalDate, JSONObject?>>) {
        val today = journalDay()
        val chartDays = days.map { (date, data) -> WeekDurationDay(date, data != null, intervals(cardsOf(data))) }
        parent.addView(WeekDurationChart(this, chartDays, today, zone), ui.lp(top = 4))
        val list = ui.column(parent)
        for (entry in chartDays) {
            val ms = TimelineMath.coveredInDay(entry.date, zone, entry.intervals)
            val detail = when {
                entry.date > today -> "尚未到來"
                !entry.available -> "暫時讀不到這天的記錄"
                ms == 0L -> "沒有記錄"
                else -> TimelineMath.duration(ms)
            }
            ui.row(list, md(entry.date), detail, onClick = if (entry.available && entry.date <= today) ({
                if (!busy) { day = entry.date; reviewWeekly = false; review() }
            }) else null)
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
        !s.hasUsageGrant -> "尚未開始記錄"
        s.contextEnabled -> "記錄 ${effectiveAllowedCount(s)} 個 app 的畫面文字${if (s.imagesEnabled) "與截圖" else ""}"
        else -> "只記錄 app 使用時段"
    }

    private fun settings() {
        val s = RecordingSettings(this@MainActivity)
        val body = screen("設定", null, Tab.SETTINGS, refresh = { settings() })
        if (s.isAuthRejected(credentials)) connectionRecoveryCallout(body)
        fun pad(view: View) = view.setPadding(ui.dp(4), 0, ui.dp(4), 0)

        ui.groupLabel(body, "記錄")
        ui.card(body, padding = 8).apply {
            ui.switchRow(this, "自動記錄", recordingSummary(s) + "\n截圖在充電時上傳，平常只記錄、不耗流量。", s.hasUsageGrant) { on ->
                if (busy) { settings(); return@switchRow }
                if (on) autoRecordFlow(0) { settings() } else confirm("停止自動記錄？", "之後不再記錄 app 使用時段、畫面文字和截圖。還沒上傳的截圖會刪除；已上傳的記錄保留。", "停止", { settings() }) { stopRecording(s) }
            }.also(::pad)
            if (s.hasUsageGrant && !s.remotePaused) {
                val missing = nextStep(s)
                // The one missing step, aligned with the other rows; only its title is tinted.
                missing?.let { (title, action) -> ui.row(this, title, "自動記錄已開啟，還差這一步") { action() }.also { warnTitle(it) } }
                val permissionMissing = missing != null && missing.first != "選擇要記錄的 app"
                ui.switchRow(this, "截圖", if (permissionMissing) "需要先${missing!!.first}" else "和畫面文字一起記錄", s.imagesEnabled && !permissionMissing) { on ->
                    if (busy) { settings(); return@switchRow }
                    toggleImages(s, on)
                }.apply { pad(this); isEnabled = !permissionMissing }
                ui.row(this, "記錄哪些 app", "記錄 ${effectiveAllowedCount(s)} 個 · 排除 ${s.exclusions.size} 個") { appListsPage() }
            }
            ui.switchRow(this, "暫停所有裝置", if (s.remotePaused) "所有裝置都不記錄" else "在伺服器暫停每一台裝置", s.remotePaused) { on ->
                if (busy || on == s.remotePaused) { settings(); return@switchRow }
                manageRemotePause(s)
            }.also(::pad)
            ui.row(this, "進階", "畫面文字、Wi‑Fi、權限") { recordingAdvanced() }
        }

        ui.groupLabel(body, "AI")
        ui.card(body, padding = 8).apply {
            val slot = ui.column(this)
            load(slot, "讀取 AI 設定…", { api().request("/v1/ai") }) { config ->
                rememberAi(config)
                val enabled = config.optBoolean("available") && config.optBoolean("enabled")
                val provider = AiProviders.detect(config.optString("protocol"), config.optString("endpoint")).short
                ui.row(slot, if (!config.optBoolean("available")) "伺服器還不支援在 app 裡設定 AI" else if (enabled) provider else "尚未設定 AI",
                    if (enabled) "${config.optString("model")} · 自動整理${if (config.optBoolean("auto_summarize")) "開啟" else "關閉"}" else "選一個 AI 服務並貼上金鑰") { aiSettings { settings() } }
            }
        }

        ui.groupLabel(body, "分類")
        ui.card(body, padding = 8).apply {
            val styles = workspace?.optJSONArray("categories")
            val rules = workspace?.optJSONArray("rules")
            ui.row(this, "分類與規則", if (styles != null) "${styles.length()} 個分類 · ${rules?.length() ?: 0} 條自動規則" else "名稱、顏色與自動分類") { categoriesPage() }
        }

        ui.groupLabel(body, "連線與裝置")
        ui.card(body, padding = 8).apply {
            ui.row(this, "已連線到你的伺服器", host(), onClick = null)
            if (EndpointPolicy.isPlainHttp(credentials?.endpoint)) {
                // Its own row with a warning icon.
                val warning = ui.horizontal(this, 10).apply {
                    minimumHeight = ui.dp(48); setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = ui.shape(Palette.WARN_SOFT, 12f)
                    (layoutParams as LinearLayout.LayoutParams).apply { topMargin = ui.dp(4); bottomMargin = ui.dp(4) }
                }
                warning.addView(ui.icon(R.drawable.ic_warning, Palette.WARN_INK, 20))
                ui.text(warning, "這個連線沒有 HTTPS，資料保護依賴你的 VPN／私人網路", 14f, Palette.WARN_INK).layoutParams = ui.lp(0, weight = 1f)
            }
            val sync = ui.column(this)
            load(sync, "讀取同步狀態…", { synchronized(SyncEngine.lock) { val q = JournalQueue(this@MainActivity); Triple(q.counts().second, q.observationBacklog().first, SyncEngine.charging(this@MainActivity)) } }) { (rejected, captures, charging) ->
                val waiting = if (captures > 0) " · $captures 張截圖${if (charging) "正在上傳" else "等充電時上傳"}" else ""
                ui.row(sync, "立即同步", lastSyncText(s.lastSync) + waiting, chevron = false) { task("同步中…", { SyncEngine.sync(this@MainActivity) }) { settings(); notice(RecordingSettings(this@MainActivity).status(), Kind.INFO) } }.also { trailing(it, R.drawable.ic_sync) }
                if (rejected > 0) {
                    ui.row(sync, "重試被拒絕的 $rejected 筆", "先確認網址與金鑰正確") {
                        task("重試中…", { synchronized(SyncEngine.lock) { JournalQueue(this@MainActivity).retryRejected(requireNotNull(credentials)); s.nextAttempt = 0 }; SyncEngine.sync(this@MainActivity) }) { settings(); notice(RecordingSettings(this@MainActivity).status(), Kind.INFO) }
                    }
                    dangerRow(sync, "刪除被拒絕的資料", "無法復原") {
                        confirm("刪除被拒絕的資料？", "這些資料不會再上傳。", "刪除") {
                            task("刪除中…", { synchronized(SyncEngine.lock) { JournalQueue(this@MainActivity).clearRejected() } }) { settings(); notice("已刪除") }
                        }
                    }
                }
            }
            ui.row(this, "更換伺服器或金鑰", "換成另一台伺服器或新的裝置金鑰") {
                confirmReset("更換連線？", "會停止記錄，刪除這支手機還沒上傳的資料，然後重新輸入網址與金鑰。伺服器上的記錄不會被刪除。")
            }
        }

        ui.groupLabel(body, "資料")
        ui.card(body, padding = 8).apply {
            val storage = ui.column(this)
            load(storage, "讀取儲存空間…", { api().request("/v1/storage") }) { info -> storageRows(storage, info) }
            ui.switchRow(this, "防止截圖這個應用程式", "開啟後，PocketLog 的畫面無法被截圖或錄影", s.protectAppScreen) { on ->
                task("更新…", { s.protectAppScreen = on }) { applyScreenProtection(); notice(if (on) "已防止截圖這個應用程式" else "已允許截圖這個應用程式") }
            }.also(::pad)
            ui.row(this, "匯出這一天", "${md(day)}的活動卡，存成檔案") { export() }
            // Destructive actions last, separated, low emphasis, each behind a 無法復原 confirmation.
            addView(View(this@MainActivity).apply { setBackgroundColor(Palette.HAIRLINE) }, ui.lp(height = 1, top = 8))
            ui.text(this, "刪除", 13f, Palette.MUTED, top = 16, bottom = 2).setPadding(ui.dp(4), 0, ui.dp(4), 0)
            val deletes = ui.column(this)
            quietAction(deletes, "清除這支手機的資料", Palette.INK) {
                confirmReset("清除這支手機的資料？", "會停止記錄並刪除這支手機還沒上傳的資料。伺服器上的記錄仍保留。無法復原。")
            }
            quietAction(deletes, "永久刪除伺服器上的全部記錄", Palette.DANGER) {
                confirm("永久刪除全部記錄？", "會刪除伺服器上所有活動、截圖與文字，並停止記錄、清除這支手機的待上傳資料。無法復原。", "全部刪除") {
                    task("刪除中…", { SyncEngine.clearLocal(this@MainActivity) { api().request("/v1/journal", method = "DELETE") } }) { clearDrafts(); settings(); notice("已刪除伺服器上的全部記錄，記錄已停止。", Kind.INFO) }
                }
            }
        }

        ui.groupLabel(body, "說明")
        ui.card(body, padding = 8).apply {
            ui.row(this, "使用說明", "沒有活動時怎麼辦、目前限制") { helpPage() }
            ui.row(this, "重新看一次介紹", null) { onboarding(0) { settings() } }
            updateRows(this)
        }
    }

    /** 設定 › 說明: 檢查更新 (current version + last result) and 自動檢查更新; test builds never contact GitHub. */
    private fun updateRows(parent: LinearLayout) {
        if (!BuildConfig.UPDATE_CHECK) {
            ui.row(parent, "版本 ${version()}", "測試版不會自動更新", onClick = null)
            return
        }
        val updater = AppUpdater(this)
        val result = updater.lastResult.ifBlank { "還沒檢查" }
        ui.row(parent, "檢查更新", "目前版本 ${version()} · $result", chevron = false) { manualUpdateCheck() }.also { trailing(it, R.drawable.ic_sync) }
        ui.switchRow(parent, "自動檢查更新", "開啟 app 時檢查，最多每 12 小時一次；只連到 GitHub", updater.autoCheck) { on -> updater.autoCheck = on }
            .setPadding(ui.dp(4), 0, ui.dp(4), 0)
    }

    /** Replaces a row's chevron with an action icon (e.g. 立即同步, 檢查更新). */
    private fun trailing(row: LinearLayout, icon: Int) = row.addView(ui.icon(icon, Palette.ACCENT_DARK, 20).apply { (layoutParams as LinearLayout.LayoutParams).marginStart = ui.dp(8) })

    /** A tinted icon with explicit bounds, for setCompoundDrawablesRelative (not the intrinsic-size variant). */
    private fun scaled(res: Int, size: Int, tint: Int) = getDrawable(res)?.mutate()?.apply { setTint(tint); setBounds(0, 0, ui.dp(size), ui.dp(size)) }

    private fun warnTitle(row: LinearLayout) = ((row.getChildAt(0) as LinearLayout).getChildAt(0) as TextView).setTextColor(Palette.WARN_INK)

    /** The one missing step while 自動記錄 is on, as a clear next action (never contradictory rows). */
    private fun nextStep(s: RecordingSettings): Pair<String, () -> Unit>? = when {
        !s.hasUsagePermission() -> "開啟使用情況存取" to { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        !s.contextEnabled -> "開啟畫面文字與截圖" to { autoRecordFlow(2) { settings() } }
        !accessibilityServiceOn() -> "開啟輔助使用" to { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        effectiveAllowedCount(s) == 0 -> "選擇要記錄的 app" to { autoRecordFlow(3) { settings() } }
        else -> null
    }

    /** 設定 › 記錄 › 進階: independent consents as switches, permissions as navigation rows. */
    private fun recordingAdvanced() {
        val s = RecordingSettings(this)
        val body = screen("進階", "記錄", tab = Tab.SETTINGS, back = { settings() }, refresh = { recordingAdvanced() })
        fun pad(view: View) = view.setPadding(ui.dp(4), 0, ui.dp(4), 0)
        val again = { recordingAdvanced() }
        ui.card(body, padding = 8).apply {
            ui.switchRow(this, "畫面文字", "關閉後只記錄 app 使用時段，也不會截圖", s.contextEnabled) { on ->
                if (busy) { again(); return@switchRow }
                toggleContext(s, again)
            }.also(::pad)
            ui.switchRow(this, "保存截圖供回看", "在伺服器另存 24 小時內的截圖，可從活動卡打開", s.replayConsent) { _ ->
                if (busy) { again(); return@switchRow }
                toggleReplay(s, again)
            }.also(::pad)
            ui.switchRow(this, "略過疑似密碼的畫面", "畫面出現疑似金鑰、token 或長數字時整個略過", s.privacyShield) { _ ->
                if (busy) { again(); return@switchRow }
                privacyShieldDialog(s, again)
            }.also(::pad)
            ui.switchRow(this, "只用 Wi‑Fi 上傳", "截圖只在充電時上傳；開啟後其他資料也只用 Wi‑Fi", s.wifiOnly) { checked ->
                if (checked == s.wifiOnly) return@switchRow
                task("更新…", { synchronized(SyncEngine.lock) { s.wifiOnly = checked }; SyncEngine.schedule(this@MainActivity) }, failed = { again() }) { notice(if (checked) "只用 Wi‑Fi 上傳" else "也會用行動數據上傳") }
            }.also(::pad)
        }
        ui.groupLabel(body, "Android 權限")
        ui.card(body, padding = 8).apply {
            ui.row(this, "使用情況存取", if (s.hasUsagePermission()) "已允許" else "尚未允許") { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            ui.row(this, "輔助使用", if (accessibilityServiceOn()) "已開啟" else "尚未開啟") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val last = CaptureDiagnostics.snapshot()
        if (last.atMs != null) ui.card(body, padding = 8).apply { ui.row(this, "最近一次記錄", "${clock(last.atMs)} · ${last.outcome.title}") { captureDiagnostics { recordingAdvanced() } } }
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

    private fun manageRemotePause(s: RecordingSettings, after: () -> Unit = { settings() }) {
        task("讀取狀態…", { api().request("/v1/collection") }, failed = { after() }) { control ->
            val paused = !control.getBoolean("paused")
            confirm(if (paused) "暫停所有裝置？" else "恢復所有裝置？",
                (if (paused) "伺服器會拒收新的記錄，並刪除還沒整理的截圖與文字。離線的手機要等下次連線才會知道。" else "每台裝置會依自己原本的設定繼續記錄。") + if (!paused) savedCaptureDisclosure(s) else "",
                if (paused) "暫停" else "恢復", { after() }) {
                task("更新…", {
                    synchronized(SyncEngine.lock) {
                        val result = api().request("/v1/collection", JSONObject(control.toString()).put("paused", paused), "PUT")
                        s.remotePaused = paused; UsageCollector(this@MainActivity).reset(); if (paused) JournalQueue(this@MainActivity).clearCollected(); result
                    }
                }, failed = { after() }) { after(); notice(if (paused) "已暫停所有裝置" else "已恢復記錄") }
            }
        }
    }

    private fun toggleContext(s: RecordingSettings, after: () -> Unit = { settings() }) {
        if (s.hasContextGrant) {
            confirm("關閉畫面文字？", "之後只記錄 app 使用時段，不再記下畫面文字與截圖。還沒上傳的截圖會刪除。", "關閉", after) {
                task("關閉中…", { synchronized(SyncEngine.lock) { s.contextEnabled = false; s.analysisConsent = false; JournalQueue(this@MainActivity).clearContexts() } }, failed = { after() }) { after(); notice("已關閉畫面文字") }
            }
            return
        }
        if (s.isAuthRejected(credentials)) { connectionRecovery(); return }
        if (!s.enabled) { autoRecordFlow(0) { settings() }; return }
        confirm("記錄畫面文字與截圖？", "你允許的 app 在前景時，每 10 秒記下畫面文字和一張截圖（每張最多 300 KB，優先保留原始尺寸），畫面沒變就略過。有密碼或輸入欄位的畫面、鎖定畫面會略過；受保護的畫面只留文字。截圖可能含私人內容，請只允許你願意記錄的 app。內容加密存在手機，充電時上傳到你的伺服器並長期保存；伺服器開啟 AI 自動整理時會送給你設定的 AI。\n\n下一步會開啟 Android 輔助使用設定。", "同意", after) {
            task("開啟中…", { synchronized(SyncEngine.lock) { require(s.enabled); s.contextEnabled = true; s.analysisConsent = true; s.imagesEnabled = true } }, failed = { after() }) {
                if (s.allowed.isEmpty()) autoRecordFlow(3) { settings() } else after()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }

    private fun toggleImages(s: RecordingSettings, on: Boolean) {
        if (!on) {
            task("關閉截圖…", { synchronized(SyncEngine.lock) { s.imagesEnabled = false; JournalQueue(this@MainActivity).clearContexts() } }, failed = { settings() }) { settings(); notice("已關閉截圖，只記錄畫面文字；還沒上傳的截圖已刪除。") }
            return
        }
        task("開啟截圖…", { synchronized(SyncEngine.lock) { require(s.contextEnabled); s.imagesEnabled = true } }, failed = { settings() }) { settings(); notice("已開啟截圖") }
    }

    private fun toggleReplay(s: RecordingSettings, after: () -> Unit) {
        if (s.hasReplayGrant) {
            task("關閉中…", { synchronized(SyncEngine.lock) { s.replayConsent = false; JournalQueue(this@MainActivity).clearContexts() } }, failed = { after() }) { after(); notice("已關閉保存截圖供回看") }
            return
        }
        if (s.isAuthRejected(credentials)) { connectionRecovery(); return }
        if (!s.imagesEnabled) { after(); notice("請先開啟截圖", Kind.WARN); return }
        confirm("保存截圖供回看？", "之後的截圖會在伺服器另外保存一份截圖（最多 24 小時、256 張），可以從活動卡打開回看。可能包含私人內容。關閉時不會刪除已保存的截圖，它們會自然到期。", "同意", after) {
            task("更新…", { synchronized(SyncEngine.lock) { require(s.imagesEnabled); s.replayConsent = true; JournalQueue(this@MainActivity).clearContexts() } }, failed = { after() }) { after(); notice("已開啟保存截圖供回看") }
        }
    }

    private fun privacyShieldDialog(s: RecordingSettings, after: () -> Unit) {
        confirm("略過疑似密碼的畫面", "開啟時，畫面文字出現疑似 API 金鑰、token、密碼或 13–19 位長數字時，整個畫面都不記錄。這是保守的文字檢查，可能誤判，也認不出只在圖片裡的秘密。有密碼或輸入欄位的畫面無論如何都會略過。",
            if (s.privacyShield) "關閉檢查" else "開啟檢查", after) {
            task("更新…", { synchronized(SyncEngine.lock) { s.privacyShield = !s.privacyShield; JournalQueue(this@MainActivity).clearContexts() } }, failed = { after() }) { after(); notice(if (s.privacyShield) "已開啟檢查" else "已關閉檢查", Kind.INFO) }
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
            ui.row(this, if (credentials != null) "已連線" else "未連線", host(), onClick = null)
            ui.row(this, "記錄", recordingSummary(s), onClick = null)
            CaptureDiagnostics.snapshot().let { last -> if (last.atMs != null) ui.row(this, "最近一次記錄", "${clock(last.atMs)} · ${last.outcome.title}") { captureDiagnostics { helpPage() } } }
            ui.row(this, lastSyncText(s.lastSync), s.status(), onClick = null)
        }
        ui.groupLabel(body, "時間軸沒有活動時")
        ui.card(body).apply {
            ui.body(this, "1. 確認「設定 → 記錄 → 自動記錄」已開啟，兩個 Android 權限都已允許。\n2. 確認這個 app 有在「記錄哪些 app」裡，而且沒有被排除。\n3. app 使用時段大約每 15 分鐘整理一次，由 Android 決定時間；可在設定的同步區手動上傳。\n4. 截圖要等充電時才上傳，AI 整理後活動卡才會有標題與摘要。\n5. 換了 AI 設定後，要重新打開「自動整理新截圖」。")
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
        if (config.optBoolean("enabled")) "已連接：${AiProviders.detect(config.optString("protocol"), config.optString("endpoint")).short} · ${config.optString("model")}" else "尚未設定 AI"

    /**
     * Order: AI 服務 → (API 網址 for 自訂) → 金鑰 → 模型 + 載入模型清單 → 讓 AI 看截圖 → 測試並儲存 → 自動化.
     */
    private fun renderAiForm(slot: LinearLayout, config: JSONObject, back: () -> Unit) {
        if (!config.optBoolean("available")) { ui.empty(slot, "伺服器還不支援在 app 裡設定 AI", "請先更新 PocketLog 伺服器。"); return }
        val enabled = config.optBoolean("enabled")
        rememberAi(config)
        val saved = if (enabled) AiProviders.detect(config.optString("protocol"), config.optString("endpoint")) else AiProviders.Preset.OPENAI
        headerSubtitle?.apply { text = aiState(config); visibility = View.VISIBLE }
        val presets = AiProviders.Preset.entries
        var current = saved
        val form = ui.card(slot)
        ui.text(form, "AI 服務", 14f, Palette.INK, bold = true, bottom = 4)
        val grid = ui.column(form)
        val providerHint = ui.text(form, "", 13f, Palette.MUTED, top = 2)
        val endpointStart = form.childCount
        val endpoint = ui.field(form, "API 網址", "http://127.0.0.1:8317/v1", Field.URL, if (enabled && saved.needsEndpoint) config.optString("endpoint").removeSuffix("/chat/completions") else "",
            help = "填到 /v1 就好，伺服器會自動加上 /chat/completions。公開網路要用 https。")
        val endpointViews = (endpointStart until form.childCount).map { form.getChildAt(it) }
        val key = ui.field(form, "API 金鑰", "", Field.SECRET)
        val keyLink = quietAction(form, "", Palette.ACCENT_DARK) { current.keyUrl?.let { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it))) } }.apply {
            setPadding(0, ui.dp(8), ui.dp(12), ui.dp(8)); paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setCompoundDrawablesRelative(null, null, scaled(R.drawable.ic_external, 16, Palette.ACCENT_DARK), null); compoundDrawablePadding = ui.dp(4)
        }
        val model = ui.field(form, "模型", "", Field.TEXT, if (enabled) config.optString("model") else saved.suggestedModel, help = "已填入建議的模型，可以改，或載入清單選擇。")
        fun requestBase(preset: AiProviders.Preset): JSONObject? {
            val url = runCatching { preset.endpoint(model.text.toString().ifBlank { preset.suggestedModel.ifBlank { "model" } }, endpoint.text.toString()) }.getOrNull() ?: return null
            return JSONObject().put("protocol", preset.protocol).put("endpoint", url).also { if (key.text.isNotBlank()) it.put("api_key", key.text.toString().trim()) }
        }
        lateinit var loadModels: Button
        loadModels = ui.button(form, "載入模型清單", Tone.OUTLINE) {
            val request = requestBase(current) ?: run { notice("請先檢查 API 網址。", Kind.WARN); return@button }
            loadModels.isEnabled = false; loadModels.text = "載入中…"
            fun restore() { loadModels.isEnabled = true; loadModels.text = "載入模型清單" }
            task("讀取模型清單…", { try { api().request("/v1/ai/models", request) } finally { request.remove("api_key") } }, failed = { error ->
                restore()
                notice(when ((error as? ApiFailure)?.status) {
                    429 -> "伺服器正在測試 AI，請稍後再試。"
                    502 -> "讀不到模型清單，請確認網址與金鑰；也可以直接輸入模型名稱。"
                    404 -> "伺服器還不支援載入模型清單，請直接輸入模型名稱。"
                    else -> describe(error)
                }, Kind.WARN)
            }) { response ->
                restore()
                val list = response.optJSONArray("models") ?: JSONArray()
                val ids = (0 until list.length()).map { list.getString(it) }
                if (ids.isEmpty()) { notice("沒有找到模型，請直接輸入模型名稱。", Kind.WARN); return@task }
                dialog().setTitle("選擇模型").setItems(ids.toTypedArray()) { _, which -> model.setText(ids[which]) }.setNegativeButton("取消", null).showSecure()
            }
        }.apply { layoutParams = wrap().apply { topMargin = ui.dp(4); bottomMargin = ui.dp(12) } }
        val images = ui.switchRow(form, "讓 AI 看截圖", "模型要能讀圖片；關閉時只送畫面文字。", if (enabled) config.optBoolean("image_support") else saved.vision) {}
        val buttons = mutableListOf<Button>()
        var refreshNote: () -> Unit = {}
        fun paint() {
            presets.forEachIndexed { i, preset ->
                val on = preset == current
                // Rounded-rectangle tiles (12 dp); selection is a tonal fill, never a dark outline.
                buttons[i].background = ui.ripple(ui.shape(if (on) Palette.ACCENT_SOFT else Palette.PAPER, 12f, if (on) null else Palette.BORDER), 12f)
                buttons[i].setTextColor(Palette.ACCENT_DARK)
                buttons[i].typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                val name = (if (on) "✓ " else "") + preset.short
                buttons[i].text = if (preset.needsEndpoint) android.text.SpannableString("$name\nOpenAI 相容（CLIProxyAPI、LiteLLM、Ollama…）").apply {
                    setSpan(android.text.style.RelativeSizeSpan(.75f), name.length + 1, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(android.text.style.ForegroundColorSpan(Palette.MUTED), name.length + 1, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else name
                buttons[i].isSelected = on; buttons[i].contentDescription = preset.label + if (on) "，已選取" else ""
            }
            providerHint.visibility = View.GONE
            keyLink.text = "取得 ${current.short} API 金鑰"
            keyLink.visibility = if (current.keyUrl != null) View.VISIBLE else View.GONE
            key.hint = when {
                enabled && config.optBoolean("has_key") && current == saved -> "已儲存金鑰 ••••，貼上新的會取代"
                current.needsEndpoint -> "代理不需要金鑰可留白"
                else -> "貼上金鑰"
            }
            endpointViews.forEach { it.visibility = if (current.needsEndpoint) View.VISIBLE else View.GONE }
            refreshNote()
        }
        // Equal-width two-column grid, text left-aligned, ✓ on the selected tile.
        var gridRow: LinearLayout? = null
        presets.forEachIndexed { index, preset ->
            if (index % 2 == 0) gridRow = ui.horizontal(grid, 8).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(8) }
            buttons += ui.button(gridRow, preset.short, Tone.CHIP, 1f) {
                if (preset == current) return@button
                // Keep a typed model; replace an untouched suggestion with the new provider's one.
                if (model.text.isBlank() || model.text.toString() == current.suggestedModel || (enabled && model.text.toString() == config.optString("model"))) model.setText(preset.suggestedModel)
                images.isChecked = preset.vision
                current = preset; paint()
            }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(ui.dp(14), ui.dp(10), ui.dp(12), ui.dp(10)) }
        }
        if (presets.size % 2 == 1) gridRow?.addView(View(this), ui.lp(0, 1, weight = 1f))
        paint()
        ui.text(form, "金鑰加密傳到你的伺服器，不會傳回手機。\nAPI 費用由你自己的帳號支付。", 13f, Palette.MUTED, top = 8)
        ui.button(form, "測試並儲存") {
            val preset = current
            val url = runCatching { preset.endpoint(model.text.toString(), endpoint.text.toString()) }.getOrNull()
            if (url == null) { notice(if (model.text.isBlank()) "請填寫模型" else "請檢查 API 網址：公開網路要用 https，不能包含金鑰或查詢參數。", Kind.WARN); return@button }
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
                }, failed = { error -> aiResult = "沒有儲存：${describe(error)}" to Kind.ERROR; aiSettings(back) }) { (_, test) ->
                    aiResult = when {
                        test == null -> "✓ 測試成功，已儲存。可以打開下方的「自動整理新截圖」。" to Kind.SUCCESS
                        test is UnsupportedOperationException -> "已儲存，但伺服器版本較舊，無法測試；請更新伺服器。" to Kind.WARN
                        test is ApiFailure && test.status == 409 -> "設定剛被其他裝置改過，沒有測試。請核對後再試。" to Kind.WARN
                        else -> "已儲存，但測試失敗：${describe(test)}" to Kind.ERROR
                    }
                    aiSettings(back)
                }
            }
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12) }
        val testNote = ui.text(form, "", 13f, Palette.MUTED, top = 4)
        fun updateTestNote() {
            val target = AiProviders.host(runCatching { current.endpoint(model.text.toString().ifBlank { current.suggestedModel.ifBlank { "model" } }, endpoint.text.toString()) }.getOrNull())
            testNote.text = if (target.isBlank()) "會送一段固定的測試文字（不含你的記錄）。" else "會送一段固定的測試文字到 $target（不含你的記錄）。"
        }
        updateTestNote()
        endpoint.addTextChangedListener(watcher { updateTestNote() })
        refreshNote = { updateTestNote() }
        aiResult?.let { (message, kind) -> ui.banner(form, message, kind, 0).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(8); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE } }
        aiResult = null
        if (enabled) {
            // Final 停用 AI row: destructive text with a divider above it.
            form.addView(View(this).apply { setBackgroundColor(Palette.HAIRLINE) }, ui.lp(height = 1, top = 16))
            form.apply {
              quietAction(this, "停用 AI", Palette.DANGER) {
                confirm("停用 AI？", "會刪除伺服器上的 AI 設定與金鑰，無法復原。已送出的請求無法撤回，已產生的活動卡與回顧會保留。", "停用並刪除") {
                    task("停用 AI…", { synchronized(SyncEngine.lock) { api().request("/v1/ai", method = "DELETE") } }) { aiConfigured = false; aiAutomatic = false; aiSettings(back); notice("已停用並刪除金鑰") }
                }
              }
              ui.text(this, "停用後不再整理新截圖，也不能產生回顧或問 AI。", 13f, Palette.MUTED)
            }
            ui.groupLabel(slot, "自動化")
            // Same 16 dp padding as the form so every switch ends on one trailing keyline.
            ui.card(slot).apply { aiSwitches(this, config) { aiSettings(back) } }
        }
        // Room below the last action so it is never half-cut by the gesture bar.
        View(this).also { slot.addView(it, ui.lp(height = ui.dp(48))) }
    }

    private fun aiSwitches(parent: LinearLayout, config: JSONObject, after: () -> Unit) {
        val target = AiProviders.host(config.optString("endpoint"))
        val auto = config.optBoolean("auto_summarize")
        ui.switchRow(parent, "自動整理新截圖", "新截圖會自動送到 $target 整理", auto) { on ->
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
        }
        val schedule = ui.column(parent)
        load(schedule, "讀取回顧排程…", { api().request("/v1/briefs/schedule") }) { current ->
            val on = current.optBoolean("enabled") && current.optString("provider_id") == config.optString("provider_id") && (current.optBoolean("daily") || current.optBoolean("weekly"))
            ui.switchRow(schedule, "每天自動產生回顧", if (on) "每天 ${"%02d:%02d".format(Locale.ROOT, current.optInt("minute_of_day") / 60, current.optInt("minute_of_day") % 60)} 產生前一天的回顧" else "關閉中：可在「回顧」手動產生", on) { enable ->
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
            }
        }
    }

    /** Server capture budget: oldest screenshots are removed first; text is kept. */
    private fun storageRows(parent: LinearLayout, info: JSONObject) {
        val gib = 1024.0 * 1024 * 1024
        // Under 1 GB: whole MB; otherwise GB with one decimal.
        fun size(bytes: Long) = if (bytes >= gib) "%.1f GB".format(Locale.ROOT, bytes / gib) else "%.0f MB".format(Locale.ROOT, bytes / (1024.0 * 1024))
        val used = info.optLong("used_bytes"); val limit = info.optLong("limit_bytes")
        val textOnly = if (info.isNull("text_only_before_ms")) null else info.optLong("text_only_before_ms")
        ui.row(parent, "伺服器空間", "已用 ${size(used)} / ${size(limit)}", onClick = null)
        parent.addView(bar(if (limit > 0) used.toFloat() / limit else 0f, Palette.ACCENT), ui.lp(height = ui.dp(6), bottom = 8).apply { marginStart = ui.dp(4); marginEnd = ui.dp(4) })
        if (textOnly != null) ui.meta(parent, "${dateTitle(Instant.ofEpochMilli(textOnly).atZone(ZoneId.systemDefault()).toLocalDate())} 以前的截圖已因空間上限刪除，文字保留。", top = 4, bottom = 4).setPadding(ui.dp(4), 0, ui.dp(4), 0)
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

    // ---------------------------------------------------------------- in-app updates (release builds only)

    /** On foreground, at most every 12 h, when enabled; never on sandbox/debug builds. */
    private fun autoUpdateCheck() {
        if (!BuildConfig.UPDATE_CHECK || updateChecking) return
        val updater = AppUpdater(this)
        if (!Updates.due(updater.autoCheck, System.currentTimeMillis(), updater.lastCheck)) return
        updateChecking = true
        updateExecutor.execute {
            val release = runCatching { updater.check() }.getOrNull()
            runOnUiThread {
                updateChecking = false
                if (release != null && release.versionCode != updater.skippedCode && visible && !busy && !isFinishing) offerUpdate(release)
            }
        }
    }

    private fun manualUpdateCheck() {
        if (!BuildConfig.UPDATE_CHECK) return
        task("檢查更新…", { AppUpdater(this).check() }, failed = { error -> AppUpdater(this).lastResult = error.message ?: "檢查沒有完成"; notice(error.message ?: "檢查沒有完成", Kind.WARN) }) { release ->
            if (release == null) { notice("已是最新版", Kind.INFO); if (tab == Tab.SETTINGS) settings() } else offerUpdate(release)
        }
    }

    /** 有新版本 dialog: plain-text notes (never HTML or links), 稍後 / 略過這個版本 / 更新. */
    private fun offerUpdate(release: Updates.Release) {
        val notes = TextView(this).apply {
            text = release.notes.ifBlank { "這個版本沒有附說明。" }; textSize = 14f; setTextColor(Palette.INK); setTextIsSelectable(false)
            autoLinkMask = 0; linksClickable = false; setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(8))
        }
        dialog().setTitle("有新版本 ${release.version}").setView(ScrollView(this).apply { addView(notes) })
            .setNegativeButton("稍後", null)
            .setNeutralButton("略過這個版本") { _, _ -> AppUpdater(this).skippedCode = release.versionCode }
            .setPositiveButton("更新") { _, _ -> startUpdate(release) }.showSecure()
    }

    private fun startUpdate(release: Updates.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            dialog().setTitle("允許安裝更新").setMessage("Android 需要你允許 PocketLog 安裝更新。接下來的畫面打開「允許這個來源」，返回後再按一次更新。")
                .setNegativeButton("取消", null).setPositiveButton("前往設定") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
                }.showSecure()
            return
        }
        val updater = AppUpdater(this)
        var cancelled = false
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; isIndeterminate = true }
        val label = ui.text(null, "下載中…", 14f, Palette.INK)
        val box = ui.column(null).apply { setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0); addView(label); addView(bar, ui.lp(top = 8)) }
        val progress = dialog().setTitle("更新 ${release.version}").setView(box).setCancelable(false)
            .setNegativeButton("取消") { _, _ -> cancelled = true }.showSecure()
        updateExecutor.execute {
            val result = runCatching {
                val file = updater.download(release, { cancelled }) { done, total ->
                    runOnUiThread {
                        if (total > 0) { bar.isIndeterminate = false; bar.progress = (done * 1000 / total).toInt() }
                        val shown = "下載中… ${done / (1024 * 1024)} / ${if (total > 0) "${total / (1024 * 1024)} MB" else "? MB"}"
                        label.text = shown
                    }
                }
                updater.install(file)
            }
            runOnUiThread {
                if (progress.isShowing) progress.dismiss()
                result.exceptionOrNull()?.let { error ->
                    val message = (error as? UpdateFailure)?.message ?: "更新沒有完成，請稍後再試"
                    updater.lastResult = message; updater.cleanup()
                    if (!isFinishing) notice(message, if (message == AppUpdater.CANCELLED) Kind.INFO else Kind.ERROR)
                } ?: notice("檢查碼正確，請在 Android 的畫面確認安裝。", Kind.INFO)
            }
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
            val enabled = ui.switchRow(card, "規則 ${i + 1}", "啟用", r.optBoolean("enabled", true)) {}
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
        activityDetail?.dismiss(); activityDetail = null
        handler.removeCallbacksAndMessages(null)
        if (backRegistered) onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        executor.shutdown(); statusExecutor.shutdown(); replayExecutor.shutdown(); updateExecutor.shutdown(); exportBytes?.fill(0); if (!isChangingConfigurations) clearDrafts(); pendingAsk = null; pendingBrief = null; credentials = null
        super.onDestroy()
    }
}

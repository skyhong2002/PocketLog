package tw.skyhong.pocketlog

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
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
 * Native PocketLog shell: header, scrollable page, status strip and bottom navigation rail.
 * Pages are built from platform Views via [Ui]; no WebView, no new dependencies.
 */
class MainActivity : Activity() {
    private enum class Tab(val label: String, val icon: Int) {
        TIMELINE("時間軸", R.drawable.ic_nav_timeline), DAILY("每日", R.drawable.ic_nav_daily), WEEKLY("每週", R.drawable.ic_nav_weekly),
        CHAT("Chat", R.drawable.ic_nav_chat), FLOW("Flow", R.drawable.ic_nav_flow), AGENTS("Agents", R.drawable.ic_nav_agents),
        SUPPORT("支援", R.drawable.ic_nav_support), SETTINGS("設定", R.drawable.ic_nav_settings)
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
    private lateinit var navScroll: HorizontalScrollView
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
    private var ticker: Runnable? = null
    private var visible = false
    private var tab = Tab.TIMELINE
    private var restoreTab: Tab? = null
    private var listMode = false
    private val mutedCategories = linkedSetOf<String>()

    private var credentials: Credentials? = null
    private var workspace: JSONObject? = null
    private var day = TimelineMath.journalDay()
    private var loaded: JSONObject? = null
    private var replayDialog: AlertDialog? = null
    private var reflectionInput: EditText? = null
    private var reflectionDrafts = EditorDrafts(29, 2000)
    private var questionDrafts = EditorDrafts(20, 1000)
    private data class RetainedEditors(val reflections: EditorDrafts, val questions: EditorDrafts, val conversation: String, val weekly: Boolean, val request: JSONObject?, val requestWeekly: Boolean)
    private var bindingReflection = false
    private fun clearDrafts() { reflectionDrafts.clear(); questionDrafts.clear(); mutedCategories.clear() }
    private var exportBytes: ByteArray? = null
    private var agentImportSource = "codex"
    private var pendingAsk: JSONObject? = null
    private var pendingAskWeekly = false
    private var chatWeekly = false
    private var conversationId = UUID.randomUUID().toString()
    private val baseCategories = listOf("unsorted", "work", "learning", "communication", "social_media", "entertainment", "news_reading", "life_admin", "rest", "idle")
    private val categoryLabels = listOf("未分類", "工作", "學習", "溝通", "社群", "娛樂", "新聞／閱讀", "生活事務", "休息", "閒置")
    // Server defaults (server/src/workspace.rs) so offline pages still distinguish categories by color.
    private val defaultColors = listOf("#b1a797", "#b9a4ed", "#87bcb4", "#e4be7c", "#e69c9c", "#d4a9c8", "#9bbce1", "#b4c493", "#80bda1", "#92949e")
    private val categories: List<String> get() = workspace?.optJSONArray("categories")?.let { styles -> (0 until styles.length()).map { styles.getJSONObject(it).getString("id") } } ?: baseCategories
    private val zone: ZoneId get() = ZoneId.systemDefault()

    // ---------------------------------------------------------------- lifecycle and shell

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        (lastNonConfigurationInstance as? RetainedEditors)?.let {
            reflectionDrafts = it.reflections; questionDrafts = it.questions; conversationId = it.conversation
            chatWeekly = it.weekly; pendingAsk = it.request; pendingAskWeekly = it.requestWeekly
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setDecorFitsSystemWindows(false)
        ui = Ui(this)
        listMode = getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        listMode = state?.getBoolean("list_mode", listMode) ?: listMode
        state?.getStringArrayList("muted_categories")?.take(100)?.let { mutedCategories.addAll(it) }
        restoreTab = state?.getString("tab")?.let { name -> Tab.entries.firstOrNull { it.name == name } }
        state?.getString("day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { TimelineMath.inHistory(it, journalDay()) }?.let { day = it }
        buildShell()
        boot()
    }

    override fun onRetainNonConfigurationInstance(): Any = RetainedEditors(reflectionDrafts, questionDrafts, conversationId, chatWeekly, pendingAsk, pendingAskWeekly)

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        // Only navigation state; never journal content or credentials.
        out.putString("tab", tab.name); out.putString("day", day.toString())
        out.putBoolean("list_mode", listMode); out.putStringArrayList("muted_categories", ArrayList(mutedCategories))
    }

    override fun onStart() {
        super.onStart()
        visible = true
        ticker?.let { handler.removeCallbacks(it); handler.post(it) }
    }

    override fun onStop() {
        visible = false
        ticker?.let(handler::removeCallbacks)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Returning from Android permission screens: show the new permission state.
        val refresh = refreshOnResume ?: return
        if (busy) return
        val y = scroll.scrollY
        refresh(); scroll.post { scroll.scrollTo(0, y) }
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
        navScroll = HorizontalScrollView(this@MainActivity).apply { isFillViewport = true; isHorizontalScrollBarEnabled = false }
        navRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(ui.dp(4), 0, ui.dp(4), 0) }
        navScroll.addView(navRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        navBar.addView(navScroll, ui.lp())
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
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; minimumWidth = ui.dp(72); minimumHeight = ui.dp(64)
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
            navRow.addView(cell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (on) navScroll.post { navScroll.smoothScrollTo((cell.left - (navScroll.width - cell.width) / 2).coerceAtLeast(0), 0) }
        }
    }

    /**
     * Starts a new page. Bumping [generation] discards results of loads started by the previous page.
     * Root tabs return to the timeline on Back; sub-pages pass their own [back].
     */
    private fun screen(title: String, subtitle: String? = null, tab: Tab? = null, back: (() -> Unit)? = null, nav: Boolean = true, logo: Boolean = false,
                       action: Triple<Int, String, () -> Unit>? = null, refresh: (() -> Unit)? = null): LinearLayout {
        generation++
        ticker?.let(handler::removeCallbacks); ticker = null
        if (tab != null) this.tab = tab
        refreshOnResume = refresh
        header.removeAllViews()
        when {
            back != null -> header.addView(iconButton(R.drawable.ic_back, "返回") { handleBack() })
            logo -> header.addView(ui.logo(36).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(ui.dp(8), 0, ui.dp(12), 0) })
            else -> header.addView(View(this@MainActivity), LinearLayout.LayoutParams(ui.dp(8), 1))
        }
        val titles = ui.column(header).apply { layoutParams = ui.lp(0, weight = 1f) }
        ui.text(titles, title, 22f).apply { isAccessibilityHeading = true; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        if (subtitle != null) ui.meta(titles, subtitle)
        if (action != null) header.addView(iconButton(action.first, action.second, action.third))
        page.removeAllViews(); scroll.scrollTo(0, 0)
        if (!busy) statusStrip.visibility = View.GONE
        navWanted = nav; applyBottomInsets(); if (nav) renderNav()
        setBack(back ?: if (nav && this.tab != Tab.TIMELINE) ({ go(Tab.TIMELINE) }) else null)
        return page
    }

    private fun go(target: Tab) = when (target) {
        Tab.TIMELINE -> home(); Tab.DAILY -> dailyReview(); Tab.WEEKLY -> weeklyTimeline(); Tab.CHAT -> chatPage()
        Tab.FLOW -> flowPage(); Tab.AGENTS -> agentsPage(); Tab.SUPPORT -> supportPage(); Tab.SETTINGS -> settings()
    }

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
    @Deprecated("Android 14/15 without predictive-back opt-in still deliver Back here")
    override fun onBackPressed() {
        if (back != null) handleBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

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
            401 -> "伺服器拒絕這把金鑰：可能打錯、已過期或已撤銷。請到「設定 → 更換伺服器或金鑰」。"
            403 -> "這把金鑰沒有此權限（例如「僅收集」金鑰）。手機請使用「日誌（手機 app）」金鑰。"
            404 -> "伺服器沒有這項功能，請更新 PocketLog 伺服器。"
            409 -> "資料已在其他裝置更新，請重新載入後再試。"
            413 -> "內容超過伺服器大小限制。"
            423 -> "伺服器已暫停收集。"
            429 -> "請求太頻繁或已達配額，請稍後再試。"
            in 500..599 -> "伺服器暫時無法處理（HTTP ${error.status}），請稍後再試。"
            else -> "伺服器拒絕請求（HTTP ${error.status}）。"
        }
        is UnknownHostException -> "找不到伺服器。請檢查網址，並確認手機已連上網路（使用 Tailscale 時請確認已連線）。"
        is SSLException -> "無法驗證伺服器的 HTTPS 憑證。PocketLog 只接受系統信任的憑證。"
        is SocketTimeoutException -> "連線逾時。伺服器可能離線或網路太慢。"
        is ConnectException -> "無法連上伺服器。請確認服務正在執行且連接埠正確。"
        is IOException -> "網路連線失敗，資料仍加密保留在手機。"
        is org.json.JSONException -> "伺服器回應格式不符，請確認伺服器與 app 版本相符。"
        else -> "無法完成。請檢查網路、前置設定與伺服器狀態。"
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
        if (busy) { notice("上一個操作尚在處理中", Kind.WARN); return }
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
    /** Dialog windows do not inherit the Activity's FLAG_SECURE; add it before showing private content. */
    private fun AlertDialog.Builder.showSecure(): AlertDialog = create().also { it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); it.show() }

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
    private fun sourceLabel(source: String) = when (source) { "user" -> "你的修改"; "ai" -> "AI 摘要，請核對"; "local_excerpt" -> "可見文字摘錄"; else -> "僅 app 使用紀錄" }
    private fun cardSource(card: JSONObject) = if (card.optBoolean("pending_edit")) "手機修改，待同步" else sourceLabel(card.optString("source"))
    private fun cardsOf(data: JSONObject?): List<JSONObject> = data?.optJSONArray("cards")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    private fun item(c: JSONObject) = TimelineItem(c.getLong("start_ms"), c.getLong("end_ms"), appNames.title(c), label(c.getString("category")) + (if (c.optBoolean("pending_edit")) " · 修改待同步" else "") + (if (c.optBoolean("pending_context")) " · 有待處理內容" else ""), colorFor(c.getString("category")))
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

    private fun step(parent: ViewGroup, number: String, title: String, detail: String) {
        val row = ui.horizontal(parent, 12).apply { gravity = Gravity.TOP; setPadding(0, ui.dp(6), 0, ui.dp(6)); isScreenReaderFocusable = true; contentDescription = "$number. $title。$detail" }
        row.addView(TextView(this@MainActivity).apply {
            text = number; gravity = Gravity.CENTER; textSize = 14f; setTextColor(Palette.ACCENT_DARK); typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = ui.shape(Palette.ACCENT_SOFT, 14f); minWidth = ui.dp(28); minHeight = ui.dp(28); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); setPadding(0, ui.dp(8), ui.dp(8), ui.dp(8))
        }
    }

    private fun legend(parent: ViewGroup, totals: List<Pair<String, Long>>) {
        ui.meta(parent, "分類占比依各卡片時間計算；同時進行的活動可能重疊。")
        val sum = totals.sumOf { it.second }.coerceAtLeast(1)
        parent.addView(CategoryBarView(this@MainActivity, totals.map { colorFor(it.first) to it.second }), ui.lp(top = 4, bottom = 8))
        totals.forEach { (id, ms) ->
            val row = ui.horizontal(parent, 10).apply { minimumHeight = ui.dp(32); isScreenReaderFocusable = true }
            row.addView(ui.swatch(colorFor(id), 12))
            ui.text(row, label(id), 15f).layoutParams = ui.lp(0, weight = 1f)
            ui.text(row, "${TimelineMath.duration(ms)} · ${ms * 100 / sum}%", 14f, Palette.MUTED).layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Previous/next and a date picker bounded to the readable 29-day window. */
    private fun dateNav(parent: ViewGroup, step: Long = 1, label: String = dateTitle(day), onChange: (LocalDate) -> Unit) {
        val today = journalDay(); val earliest = today.minusDays(TimelineMath.HISTORY_DAYS)
        fun target(delta: Long) = day.plusDays(delta).coerceIn(earliest, today)
        fun allowed(d: LocalDate) = d != day && (step == 1L || TimelineMath.weekStart(d) != TimelineMath.weekStart(day))
        val row = ui.horizontal(parent, 8).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(12) }
        val prev = target(-step); val next = target(step)
        val unit = if (step == 7L) "週" else "天"
        row.addView(iconButton(R.drawable.ic_back, "前一$unit") { onChange(prev) }.apply { if (!allowed(prev)) { isEnabled = false; alpha = .35f; contentDescription = "前一$unit（已到最早可讀日期）" } })
        ui.button(row, label, Tone.OUTLINE, 1f) {
            DatePickerDialog(this@MainActivity, { _, y, m, d ->
                val picked = LocalDate.of(y, m + 1, d)
                if (TimelineMath.inHistory(picked, today)) onChange(picked) else notice("只能查看最近 29 天", Kind.WARN)
            }, day.year, day.monthValue - 1, day.dayOfMonth).apply {
                datePicker.minDate = earliest.atStartOfDay(zone).toInstant().toEpochMilli()
                datePicker.maxDate = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            }.show()
        }.contentDescription = "$label，選擇日期"
        row.addView(iconButton(R.drawable.ic_chevron, "後一$unit") { onChange(next) }.apply { if (!allowed(next)) { isEnabled = false; alpha = .35f; contentDescription = "後一$unit（已是最新）" } })
    }

    // ---------------------------------------------------------------- first run, connection and onboarding

    private fun boot() {
        val body = screen("PocketLog", "私人活動日誌", nav = false, logo = true)
        val slot = ui.column(body)
        load(slot, "開啟加密連線資料…", { synchronized(SyncEngine.lock) { CredentialStore(this@MainActivity).load() } }) { saved ->
            credentials = saved
            if (saved == null) setup() else { SyncEngine.schedule(this@MainActivity); enterWorkspace() }
        }
        // Recovery path if Keystore or the stored connection cannot be read.
        ui.button(body, "無法開啟？重設本機資料與連線", Tone.TEXT) { confirmReset() }
    }

    private fun confirmReset(title: String = "重設本機資料？", message: String = "用於金鑰失效或連線資料損毀。會停止紀錄、刪除未同步資料與快取，然後重新設定連線。伺服器日誌不會被刪除。") {
        dialog().setTitle(title).setMessage(message).setNegativeButton("取消", null).setPositiveButton("清除並重新設定") { _, _ ->
            task("清除這支手機的資料…", { synchronized(SyncEngine.lock) { SyncEngine.clearLocal(this@MainActivity); CredentialStore(this@MainActivity).clear() } }) {
                clearDrafts(); credentials = null; workspace = null; loaded = null; setup(); notice("已清除這支手機的連線與待傳資料；伺服器日誌仍保留。", Kind.INFO)
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
        require(key.length == 64) { "金鑰應為 64 個字元，目前是 ${key.length} 個。請確認完整複製。" }
        require(EndpointPolicy.validToken(key)) { "金鑰只能包含 0–9 與 a–f。" }
        return Credentials(endpoint, key)
    }

    private fun setup() {
        workspace = null
        val body = screen("連接私人伺服器", "首次設定 · 約 2 分鐘", nav = false, logo = true)
        ui.card(body).apply {
            ui.text(this, "把一天，收進口袋。", 20f, bold = true, bottom = 6).isAccessibilityHeading = true
            ui.body(this, "連接自己的私人日誌。AI 與手機紀錄都可稍後設定，連線不會開始擷取。")
        }
        val form = ui.card(body)
        ui.heading(form, "輸入連線資料")
        val endpoint = ui.field(form, "伺服器網址", "https://your-server.example", Field.URL)
        val token = ui.field(form, "裝置金鑰", "貼上 64 字元金鑰", Field.SECRET)
        val counter = ui.meta(form, "已輸入 0 / 64 字元", top = 4)
        token.addTextChangedListener(watcher { value ->
            val n = value.count { !it.isWhitespace() }
            counter.text = if (n == 64) "長度正確（64 字元）" else "已輸入 $n / 64 字元"
        })
        val error = ui.banner(form, "", Kind.ERROR, 0).apply { visibility = View.GONE; (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE }
        fun fail(message: String) { error.text = message; error.visibility = View.VISIBLE }
        ui.button(form, "驗證並連線") {
            val candidate = try { parseConnection(endpoint.text.toString(), token.text.toString()) } catch (e: IllegalArgumentException) { fail(e.message ?: "請檢查網址與金鑰。"); return@button }
            error.visibility = View.GONE
            task("正在驗證伺服器與金鑰…", {
                synchronized(SyncEngine.lock) {
                    PocketLogApi(candidate).request(dayPath())
                    CredentialStore(this@MainActivity).save(candidate)
                    UsageCollector(this@MainActivity).reset()
                }
            }, failed = { fail(describe(it)) }) {
                clearDrafts(); credentials = candidate; token.text.clear(); SyncEngine.schedule(this@MainActivity); notice("已連線到 ${host()}"); enterWorkspace()
            }
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12) }
        ui.meta(form, "金鑰以 Android Keystore 加密保存在這支手機，只放在 HTTPS 標頭、不會出現在網址。驗證時只讀取今天的時間軸。", top = 8)
        val help = ui.column(null)
        ui.button(body, "不知道網址或金鑰在哪裡？", Tone.TEXT) {
            help.visibility = if (help.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (help.visibility == View.VISIBLE) help.announceForAccessibility("連線準備說明已展開")
        }.apply { contentDescription = "展開或收起：如何取得伺服器網址與裝置金鑰" }
        body.addView(help, ui.lp())
        help.visibility = View.GONE
        ui.groupLabel(help, "你需要準備兩樣東西")
        ui.card(help).apply {
            step(this, "1", "伺服器網址", "部署 PocketLog 後用瀏覽器開啟網站的 HTTPS 位址，例如 https://my-pc.your-tailnet.ts.net:12446。只填來源，不含路徑；這支手機也必須連得到（使用 Tailscale 時請先登入同一個 tailnet）。")
            step(this, "2", "裝置金鑰", "用部署時產生的 owner token 登入網站，到「設定 → 裝置金鑰與撤銷」，權限選「日誌（手機 app）」後建立。金鑰是 64 個英數字元，只顯示一次，可單獨撤銷。owner token 也能使用，但不建議放在手機上。")
        }
        ui.groupLabel(body, "連線之後")
        ui.card(body).apply {
            step(this, "1", "共用設定", "每日目標與分類在網站與手機共用；若已在網站完成，會自動略過。")
            step(this, "2", "選用：自備 AI", "可設定 OpenAI 相容、Anthropic 或 Gemini。不設定也能使用活動卡、回顧與匯出。")
            step(this, "3", "手機紀錄（另外同意）", "app 使用紀錄、畫面文字、縮圖與 AI 處理各自獨立同意，預設全部關閉。完成連線不會開始記錄；需要時到「設定 → 手機紀錄」開啟並授予 Android 權限。")
        }
    }

    private fun enterWorkspace() {
        val body = screen("PocketLog", host(), nav = false, logo = true)
        val slot = ui.column(body)
        load(slot, "讀取共用設定…", { api().request("/v1/workspace") }) {
            workspace = it
            if (it.optInt("onboarding_version") >= 1) go(restoreTab ?: Tab.TIMELINE).also { restoreTab = null } else onboarding(0)
        }
        ui.button(body, "離線開啟時間軸", Tone.TEXT) { go(restoreTab ?: Tab.TIMELINE) }
    }

    private fun onboarding(step: Int, exit: (() -> Unit)? = null) {
        val body = screen("開始使用", "共用設定 ${step + 1} / 4 · 網站與手機共用", nav = false,
            back = if (step > 0) ({ onboarding(step - 1, exit) }) else exit)
        val dots = ui.horizontal(body, 6).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(16); contentDescription = "第 ${step + 1} 步，共 4 步"; isFocusable = true }
        repeat(4) { i -> dots.addView(View(this@MainActivity).apply { background = ui.shape(if (i <= step) Palette.ACCENT else Palette.BORDER, 3f) }, LinearLayout.LayoutParams(0, ui.dp(6), 1f)) }
        val (headline, text) = when (step) {
            0 -> "把一天，收進口袋。" to "自動記錄、活動分類與時間軸，幫助你回看一天。這份設定在網站與手機共用，完成一次即可。"
            1 -> "先選擇，才開始記錄。" to "• 使用紀錄、畫面文字、圖片與 AI 各自獨立同意，預設關閉。\n• 排除清單優先於允許清單；密碼、輸入欄位、鎖定畫面與安全視窗在擷取前排除。\n• 原始情境最多保留 24 小時，活動與反思最多 30 天。\n• 伺服器管理者可以讀取日誌。"
            2 -> "為自己留一點專注。" to "目標與分類角色會同步到網站；它們用來整理你的一天，不是替你評分。"
            else -> "帶上你自己的 AI（選用）。" to "可設定 OpenAI 相容、Anthropic 或 Gemini API 與模型，也可以先使用無 AI 的活動卡。設定模型不代表同意傳送畫面。"
        }
        val hero = ui.card(body)
        ui.text(hero, headline, 22f, bottom = 8).isAccessibilityHeading = true
        ui.body(hero, text)
        val error = ui.banner(body, "", Kind.ERROR).apply { visibility = View.GONE }
        var focus: EditText? = null; var budget: EditText? = null
        if (step == 2) ui.card(body).apply {
            focus = ui.field(this, "每日專注目標（分鐘）", "270", Field.NUMBER, (workspace?.optInt("focus_minutes") ?: 270).toString())
            budget = ui.field(this, "分心預算（分鐘）", "60", Field.NUMBER, (workspace?.optInt("distraction_minutes") ?: 60).toString(), "0–1440。之後可在「設定 → 目標與分類」修改。")
        }
        if (step == 3) {
            ui.card(body).apply {
                ui.heading(this, "AI 設定")
                ui.meta(this, "金鑰保存在你的伺服器，不會讀回手機。可以現在設定，或之後到「設定 → Bring your own AI」。")
                ui.button(this, "先設定我的 AI", Tone.TONAL) { aiSettings { onboarding(3, exit) } }
            }
            ui.card(body, Palette.CANVAS).apply {
                ui.heading(this, "下一步：手機紀錄（另外同意）")
                ui.meta(this, "完成這裡不會開始記錄。要讓這支手機產生活動卡，請到「設定 → 手機紀錄」同意 app 使用紀錄，並在 Android 設定授予「使用情況存取權」。畫面文字與縮圖另有同意。")
            }
        }
        val labels = if (step > 0) arrayOf("上一步" to Tone.OUTLINE, (if (step == 3) "儲存並開始" else "繼續") to Tone.PRIMARY) else arrayOf("繼續" to Tone.PRIMARY)
        ui.actions(body, *labels) { index ->
            if (step > 0 && index == 0) { onboarding(step - 1, exit); return@actions }
            if (step == 2) {
                val a = focus?.text.toString().toIntOrNull(); val b = budget?.text.toString().toIntOrNull()
                if (a == null || b == null || a !in 0..1440 || b !in 0..1440) { error.text = "請填寫 0–1440 的整數分鐘。"; error.visibility = View.VISIBLE; return@actions }
                workspace?.put("focus_minutes", a)?.put("distraction_minutes", b)
            }
            if (step < 3) onboarding(step + 1, exit) else {
                val current = workspace ?: run { error.text = "共用設定尚未載入，請返回重試。"; error.visibility = View.VISIBLE; return@actions }
                val next = JSONObject(current.toString()).put("onboarding_version", 1)
                task("儲存共用設定…", { api().request("/v1/workspace", next, "PUT") }) {
                    workspace = it; home(); notice("設定完成。這支手機的紀錄仍是關閉的，可到「設定 → 手機紀錄」開啟。", Kind.INFO)
                }
            }
        }
    }

    // ---------------------------------------------------------------- timeline

    private fun home() {
        loaded = null; reflectionInput = null
        val body = screen(dateTitle(day), "${relative(day)} · 04:00 起", Tab.TIMELINE, logo = true,
            action = Triple(R.drawable.ic_sync, "同步並重新整理", { task("同步中…", { SyncEngine.sync(this@MainActivity) }) { home(); notice(RecordingSettings(this@MainActivity).status(), Kind.INFO) } }))
        dateNav(body) { day = it; home() }
        captureCallout(body)
        val slot = ui.column(body)
        val reflections = reflectionCard(body)
        dayActions(body)
        load(slot, "讀取時間軸…", {
            val latest = runCatching { api().request("/v1/workspace") }.getOrNull()
            latest to readJournal(dayPath())
        }) { (latest, data) ->
            if (latest != null) workspace = latest
            renderTimeline(slot, data); renderReflections(reflections, data)
        }
    }

    private fun captureCallout(parent: ViewGroup) {
        val s = RecordingSettings(this@MainActivity)
        when {
            s.remotePaused -> callout(parent, "所有裝置的收集已在伺服器暫停。時間軸仍可閱讀與編輯。", Kind.WARN, "管理暫停") { settings() }
            !s.enabled -> callout(parent, "手機紀錄未開啟，現有日誌仍可閱讀。", Kind.INFO, "設定紀錄") { settings() }
            !s.hasUsagePermission() -> callout(parent, "已同意記錄，但尚未授予 Android「使用情況存取權」，因此不會產生新活動。", Kind.ERROR, "開啟系統設定") { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            else -> ui.meta(parent, "同步狀態：${s.status()}", bottom = 8)
        }
    }

    private fun renderTimeline(slot: LinearLayout, data: JSONObject) {
        loaded = data
        if (data.optBoolean("offline")) ui.banner(slot, "離線：顯示加密快取；伺服器可能已有更新。", Kind.WARN)
        if (data.optBoolean("truncated")) ui.banner(slot, "這天資料已達顯示上限，統計可能不完整。", Kind.WARN)
        if (data.optBoolean("pending_card_edits")) ui.banner(slot, "活動卡先顯示手機上的修改，仍待伺服器同步確認。", Kind.INFO)
        if (data.optBoolean("pending_reflections")) ui.banner(slot, "反思有加密保存的變更等待同步；這裡先顯示手機上的版本。", Kind.INFO)
        val list = cardsOf(data)
        val card = ui.card(slot)
        ui.heading(card, "活動")
        val tracked = TimelineMath.covered(intervals(list))
        if (list.isEmpty()) {
            val s = RecordingSettings(this@MainActivity)
            ui.meta(card, "尚無活動")
            when {
                !s.enabled -> ui.empty(card, "這天還沒有活動", "手機紀錄尚未開啟；開啟後才會產生新的活動卡，不會補上同意前的時間。", "設定手機紀錄") { settings() }
                day == journalDay() -> ui.empty(card, "活動整理中", "活動會在背景整理後出現，最短約 15 分鐘，由 Android 決定實際時間。", "立即同步") { task("同步中…", { SyncEngine.sync(this@MainActivity) }) { home() } }
                else -> ui.empty(card, "這天沒有紀錄", "空白時段不會被補上虛構的活動。")
            }.apply { background = null; setPadding(0, ui.dp(12), 0, 0) }
            return
        }
        ui.meta(card, "整日已記錄 ${TimelineMath.duration(tracked)} · ${list.size} 張活動卡 · 點卡片可修改", bottom = 8)
        val pending = list.count { it.optBoolean("pending_context") }
        if (pending > 0) ui.meta(card, "$pending 張活動有待處理內容；不代表 AI 正在生成。稍後重新整理可查看結果。", bottom = 8)
        val count = ui.meta(card, "")
        ui.meta(card, "切換分類只影響活動顯示；下方目標與時間分配仍統計整日。", bottom = 4)
        val filters = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chips = ui.horizontal(null, 6)
        filters.addView(chips); card.addView(filters, ui.lp())
        val categories = list.map { it.getString("category") }.distinct()
        val buttons = linkedMapOf<String, Button>()
        lateinit var reset: Button
        val holder = ui.column(null)
        fun draw() {
            val shown = list.filter { it.getString("category") !in mutedCategories }
            val items = shown.map(::item)
            count.text = "顯示 ${shown.size} / ${list.size} 張活動卡"
            reset.isSelected = categories.none { it in mutedCategories }
            reset.contentDescription = "顯示全部分類，${if (reset.isSelected) "已選擇" else "未選擇"}"
            buttons.forEach { (id, button) ->
                val shownCategory = id !in mutedCategories
                button.text = "${if (shownCategory) "✓" else "○"} ${label(id)}"
                button.isSelected = shownCategory
                button.contentDescription = "${label(id)}，${if (shownCategory) "顯示中，點按隱藏" else "已隱藏，點按顯示"}"
            }
            holder.removeAllViews()
            if (shown.isEmpty()) {
                ui.empty(holder, "這些分類已隱藏", "整日活動仍保留。切換上方分類或顯示全部即可查看。", "重設分類篩選") {
                    mutedCategories.clear(); draw()
                }
            } else if (listMode) activityList(holder, shown, items) else {
                val viewport = ScrollView(this@MainActivity).apply { isNestedScrollingEnabled = true }
                val now = System.currentTimeMillis()
                val view = DayTimelineView(this@MainActivity, day, zone, items, now) { editCard(shown[it]) }
                viewport.addView(view)
                val height = (resources.displayMetrics.heightPixels * .58f).toInt().coerceAtLeast(ui.dp(320))
                holder.addView(viewport, ui.lp(height = height, top = 8))
                ui.meta(holder, "短活動密集或使用 TalkBack 時，可切換「清單」完整閱讀與修改每張卡片。", top = 6)
                val (start, end) = range(day)
                val focus = if (now in start..end && day == journalDay()) now else items.minOf { it.start }
                viewport.post { viewport.scrollTo(0, (view.yFor(focus) - height / 3).coerceAtLeast(0)) }
            }
        }
        reset = ui.button(chips, "顯示全部", Tone.TONAL) { mutedCategories.clear(); draw() }
        categories.forEach { id ->
            buttons[id] = ui.button(chips, label(id), Tone.OUTLINE) {
                if (!mutedCategories.remove(id)) mutedCategories.add(id)
                draw()
            }
        }
        ui.segmented(card, listOf("時間軸", "清單"), if (listMode) 1 else 0, "活動檢視") { listMode = it == 1; draw() }
        card.addView(holder, ui.lp()); draw()

        val stats = ui.card(slot)
        goals(stats, list)
        val summary = ui.card(slot)
        ui.heading(summary, "時間分配")
        legend(summary, totals(list))
    }

    private fun activityList(parent: LinearLayout, list: List<JSONObject>, items: List<TimelineItem>) {
        list.forEachIndexed { i, c ->
            if (i > 0) parent.addView(View(this@MainActivity).apply { setBackgroundColor(Palette.HAIRLINE) }, ui.lp(height = 1))
            val item = items[i]
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; minimumHeight = ui.dp(56); setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12))
                isClickable = true; isFocusable = true; background = ui.ripple(null, 12f); setOnClickListener { if (!busy) editCard(c) }
            }
            row.addView(View(this@MainActivity).apply { background = ui.shape(item.color, 2f) }, LinearLayout.LayoutParams(ui.dp(4), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = ui.dp(12) })
            val column = ui.column(row).apply { layoutParams = ui.lp(0, weight = 1f) }
            val range = "${clock(item.start)}–${clock(item.end)}"
            val detail = label(c.getString("category")) + if (c.optBoolean("pending_context")) " · 有待處理內容" else ""
            val meta = "$range · ${TimelineMath.duration(item.end - item.start)} · $detail · ${cardSource(c)}"
            ui.text(column, item.title, 16f, bold = true)
            ui.meta(column, meta, top = 2)
            val summary = c.optString("summary")
            if (summary.isNotBlank()) ui.text(column, summary, 14f, top = 4).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
            row.contentDescription = "${item.title}，$meta。$summary"
            parent.addView(row, ui.lp())
        }
    }

    private data class DayStats(val focus: Long, val distraction: Long, val longest: Long)
    private fun dayStats(list: List<JSONObject>): DayStats {
        val focusRows = list.filter { style(it.getString("category"))?.optString("role") == "focus" }
        val distraction = TimelineMath.covered(intervals(list.filter { style(it.getString("category"))?.optString("role") == "distraction" }))
        return DayStats(TimelineMath.covered(intervals(focusRows)), distraction, TimelineMath.longestMerged(intervals(focusRows)))
    }

    private fun goals(card: LinearLayout, list: List<JSONObject>) {
        ui.heading(card, "今日目標")
        if (workspace == null) { ui.meta(card, "共用目標尚未載入（離線）。連線後會顯示專注與分心統計。"); return }
        val stats = dayStats(list)
        val focusGoal = (workspace?.optInt("focus_minutes") ?: 270) * 60_000L
        val budget = (workspace?.optInt("distraction_minutes") ?: 60) * 60_000L
        val focusText = "${TimelineMath.duration(stats.focus)} / ${TimelineMath.duration(focusGoal)}"
        ui.horizontal(card, 0).apply { ui.text(this, "專注", 15f).layoutParams = ui.lp(0, weight = 1f); ui.text(this, focusText, 15f, Palette.ACCENT_DARK, bold = true).layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        ui.progress(card, TimelineMath.progress(stats.focus, focusGoal), Palette.ACCENT, "專注 $focusText")
        val over = stats.distraction > budget
        val distractionText = "${TimelineMath.duration(stats.distraction)} / ${TimelineMath.duration(budget)}"
        ui.horizontal(card, 0).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(8)
            ui.text(this, if (over) "分心（超過預算）" else "分心預算", 15f).layoutParams = ui.lp(0, weight = 1f)
            ui.text(this, distractionText, 15f, if (over) Palette.DANGER else Palette.INK, bold = true).layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        ui.progress(card, TimelineMath.progress(stats.distraction, budget), if (over) Palette.DANGER else 0xFFD39A84.toInt(), "分心 $distractionText")
        val tiles = ui.horizontal(card, 8).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(10) }
        ui.stat(tiles, "最長連續專注", TimelineMath.duration(stats.longest), null)
        ui.stat(tiles, "已記錄", TimelineMath.duration(TimelineMath.covered(intervals(list))), null)
        ui.meta(card, "專注與分心依分類角色計算，同類角色的重疊時段只計一次；不同角色可能同時進行。可在「設定 → 目標與分類」調整。", top = 8)
    }

    private fun reflectionCard(parent: ViewGroup): LinearLayout {
        val card = ui.card(parent)
        ui.heading(card, "每日反思")
        val draftKey = day.toString()
        val field = ui.field(card, "這一天有什麼值得記下？", "哪件事讓你恢復精神？", Field.MULTILINE, reflectionDrafts[draftKey] ?: "", help = "最多 2,000 字。未儲存的文字只在本次開啟 app 期間保留；儲存後會加密排入同步。留白儲存會刪除這一天的反思。")
        field.filters = arrayOf(android.text.InputFilter.LengthFilter(2000))
        field.addTextChangedListener(watcher { if (!bindingReflection) reflectionDrafts.put(draftKey, it) })
        reflectionInput = field
        ui.button(card, "儲存這一天的反思", Tone.TONAL) {
            val at = minOf(day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), System.currentTimeMillis())
            val submitted = field.text.toString()
            val body = JSONObject().put("id", "reflection-$day").put("at_ms", at).put("text", submitted)
            task("加密儲存中…", { SyncEngine.enqueue(this@MainActivity, "/v1/reflections", body) }) { reflectionDrafts.removeIfUnchanged(draftKey, submitted); notice(if (submitted.isBlank()) "已排入刪除這天的反思。" else "反思已加密加入佇列。") }
        }
        return ui.column(card)
    }

    private fun renderReflections(reflectionSlot: LinearLayout, data: JSONObject) {
        val reflections = data.optJSONArray("reflections") ?: JSONArray()
        for (i in 0 until reflections.length()) {
            val reflection = reflections.getJSONObject(i)
            if (reflection.getString("id") == "reflection-$day") { if (reflectionDrafts[day.toString()] == null) {
                bindingReflection = true
                try { reflectionInput?.setText(reflection.getString("text")) } finally { bindingReflection = false }
            } }
            else ui.meta(reflectionSlot, "其他反思：${reflection.getString("text")}", top = 8)
        }
    }

    private fun dayActions(parent: ViewGroup) {
        ui.groupLabel(parent, "這一天")
        val card = ui.card(parent, padding = 8)
        ui.row(card, "每日回顧與目標", "專注、分心、你的回顧與 AI 摘要") { go(Tab.DAILY) }
        ui.row(card, "活動概覽", "本週或最近 29 天的分類、常用 app 與關鍵字") { overview() }
        ui.row(card, "短期縮圖回放", "只顯示另行同意保存的縮圖") { replayPage() }
        ui.row(card, "匯出這一天", "私人 JSON，含活動、感受與反思") { export() }
    }

    private fun overview() {
        dialog().setTitle("活動概覽").setItems(arrayOf("所選日期的本週", "最近 29 天")) { _, choice ->
            val earliest = journalDay().minusDays(TimelineMath.HISTORY_DAYS)
            val start = if (choice == 0) maxOf(earliest, TimelineMath.weekStart(day)) else earliest
            val end = if (choice == 0) TimelineMath.weekStart(day).plusDays(7) else journalDay().plusDays(1)
            val from = TimelineMath.dayStart(start, zone); val to = minOf(TimelineMath.dayStart(end, zone), System.currentTimeMillis())
            task("讀取概覽…", { readJournal("/v1/overview?from_ms=$from&to_ms=$to") }) { data ->
                val panel = ui.column(null, 20)
                val categories = data.getJSONArray("categories")
                ui.heading(panel, "分類")
                val rows = (0 until categories.length()).map { categories.getJSONObject(it).let { c -> c.getString("category") to c.getLong("duration_ms") } }
                if (rows.isEmpty()) ui.meta(panel, "尚無活動") else legend(panel, rows)
                val apps = data.getJSONArray("apps")
                ui.heading(panel, "常用 app", top = 12)
                for (i in 0 until apps.length()) { val a = apps.getJSONObject(i); ui.body(panel, "${appNames.name(a.getString("app"))} · ${TimelineMath.duration(a.getLong("duration_ms"))}") }
                val keywords = data.getJSONArray("title_keywords")
                ui.heading(panel, "標題關鍵字", top = 12)
                ui.body(panel, (0 until keywords.length()).joinToString("、") { keywords.getJSONArray(it).getString(0) }.ifBlank { "—" })
                ui.meta(panel, "取自最近 1000 張活動卡片。", top = 8)
                if (data.optBoolean("offline")) ui.banner(panel, "離線快取，尚未包含最新資料。", Kind.WARN, 0)
                dialog().setTitle("${md(start)} 起的活動概覽").setView(ScrollView(this@MainActivity).apply { addView(panel) }).setPositiveButton("關閉", null).showSecure()
                notice("概覽已更新", Kind.INFO)
            }
        }.showSecure()
    }

    private fun export() {
        val data = loaded ?: run { notice("請等時間軸載入完成再匯出", Kind.WARN); return }
        dialog().setTitle("匯出私人日誌").setMessage("檔案含活動、感受與反思。請選擇你信任的儲存位置；匯出檔不受 app 加密與刪除保護。")
            .setNegativeButton("取消", null).setPositiveButton("選擇儲存位置") { _, _ ->
                exportBytes?.fill(0); exportBytes = data.toString(2).toByteArray()
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type = "application/json"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE, "pocketlog-$day.json") }, 20)
            }.showSecure()
    }

    private fun editCard(card: JSONObject) {
        val content = ui.column(null, 20)
        val app = card.optString("app")
        if (app.isNotBlank()) ui.meta(content, "App：${appNames.name(app)}" + if (appNames.name(app) != app) "\n套件：$app" else "")
        ui.meta(content, "${clock(card.getLong("start_ms"))}–${clock(card.getLong("end_ms"))} · ${TimelineMath.duration(card.getLong("end_ms") - card.getLong("start_ms"))} · ${cardSource(card)}")
        if (card.optBoolean("pending_context")) ui.banner(content, "伺服器仍有內容等待處理。通常在活動結束至少 12 分鐘後處理，工作程序暫停或重試時可能更久；這不代表 AI 正在生成。請稍後重新整理。", Kind.INFO)
        val title = ui.field(content, "標題", "", Field.TEXT, card.optString("title"))
        val summary = ui.field(content, "摘要", "", Field.MULTILINE, card.optString("summary")).apply { maxLines = 6 }
        // Keep the chooser and submitted IDs paired even if shared settings refresh.
        val choices = (categories + card.getString("category")).distinct()
        val category = ui.spinner(content, "分類", choices.map { label(it) }, choices.indexOf(card.getString("category")))
        val lengthHint = ui.meta(content, "", top = 8)
        fun updateLengths() {
            val t = title.text.toString(); val s = summary.text.toString()
            lengthHint.text = "標題 ${t.codePointCount(0, t.length)} / 200 字 · 摘要 ${s.codePointCount(0, s.length)} / 1000 字。超出時請自行縮短，內容不會被截掉。"
        }
        title.addTextChangedListener(watcher { title.error = null; updateLengths() })
        summary.addTextChangedListener(watcher { summary.error = null; updateLengths() })
        updateLengths()
        ui.meta(content, "你的修改優先於自動分類與 AI 摘要；離線時會加密保存並稍後同步。", top = 8)
        val feedback = ui.meta(content, "", top = 8).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val editor = dialog().setTitle("修改活動").setView(ScrollView(this@MainActivity).apply { addView(content) })
            .setNegativeButton("取消", null).setPositiveButton("儲存", null).showSecure()
        fun report(message: String) {
            feedback.text = message; feedback.visibility = View.VISIBLE
            // Text and visibility change its measured height; scroll after the new layout.
            feedback.post { if (editor.isShowing) feedback.requestRectangleOnScreen(android.graphics.Rect(0, 0, feedback.width, feedback.height), true) }
        }
        fun saving(value: Boolean) {
            title.isEnabled = !value; summary.isEnabled = !value; category.isEnabled = !value
            editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !value
            editor.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = !value
            editor.setCancelable(!value); editor.setCanceledOnTouchOutside(!value)
        }
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val t = title.text.toString(); val s = summary.text.toString()
            val titleError = when {
                t.isBlank() -> "請填寫活動標題。"
                t.codePointCount(0, t.length) > 200 -> "標題超過 200 字，請自行縮短。"
                t.contains('\u0000') -> "標題含有無法儲存的字元。"
                else -> null
            }
            val summaryError = when {
                s.codePointCount(0, s.length) > 1000 -> "摘要超過 1000 字，請自行縮短。"
                s.contains('\u0000') -> "摘要含有無法儲存的字元。"
                else -> null
            }
            title.error = titleError; summary.error = summaryError
            if (titleError != null || summaryError != null) {
                (if (titleError != null) title else summary).requestFocus()
                report("尚未儲存：請修正標示的欄位，輸入內容已保留。")
                return@setOnClickListener
            }
            if (busy) { report("上一個操作尚在處理中，請稍後再儲存。輸入內容已保留。"); return@setOnClickListener }
            val body = JSONObject().put("id", card.getString("id")).put("title", t).put("summary", s).put("category", choices[category.selectedItemPosition])
            saving(true); report("正在加密儲存，請稍候…")
            task("加密儲存中…", { SyncEngine.enqueue(this@MainActivity, "/v1/cards", body) }, failed = { error ->
                if (editor.isShowing) { saving(false); report("未能完成儲存：${describe(error)}。輸入內容已保留，請重試。") }
            }) { editor.dismiss(); go(tab); notice("已儲存修改，等待同步。") }
        }
    }

    // ---------------------------------------------------------------- daily and weekly

    private fun dailyReview() {
        reflectionInput = null
        val body = screen("每日回顧", "${dateTitle(day)} · ${relative(day)}", Tab.DAILY)
        dateNav(body) { day = it; dailyReview() }
        val slot = ui.column(body)
        load(slot, "整理這一天…", {
            val data = readJournal(dayPath())
            val (from, to) = range(day)
            data to runCatching { api().request("/v1/reviews?from_ms=$from&to_ms=$to") }.getOrNull()
        }) { (data, reviews) ->
            if (data.optBoolean("offline")) ui.banner(slot, "離線：顯示加密快取。", Kind.WARN)
            if (data.optBoolean("truncated")) ui.banner(slot, "資料達顯示上限，統計可能不完整。", Kind.WARN)
            if (data.optBoolean("pending_card_edits")) ui.banner(slot, "活動卡先顯示手機上的修改，仍待伺服器同步確認。", Kind.INFO)
            if (data.optBoolean("pending_reflections")) ui.banner(slot, "反思的手機版本等待同步。", Kind.INFO)
            val list = cardsOf(data)
            goals(ui.card(slot), list)
            ui.card(slot).apply {
                ui.heading(this, "時間分配")
                if (list.isEmpty()) ui.meta(this, "這天沒有活動卡。空白時段不會被補上虛構的活動。") else legend(this, totals(list))
            }
            ui.card(slot).apply {
                ui.heading(this, "我的回顧")
                val records = reviews?.optJSONArray("records")
                val saved = records?.let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("id") == "review-$day" && it.optString("kind") == "review" } }
                if (reviews == null || (reviews.optBoolean("truncated") && saved == null)) {
                    ui.empty(this, "尚無法確認已儲存的回顧", if (reviews == null) "請連線後重試；其他已快取的活動仍可閱讀。" else "回顧清單已達顯示上限，這裡不會把未知的選擇當作尚未填寫。", "重新讀取回顧") { dailyReview() }
                } else {
                    ui.meta(this, "整體來說，這一天偏向？只保存你的選擇。")
                    val selected = saved?.optString("rating")
                    val options = listOf("distracted" to "分心", "neutral" to "平衡", "focused" to "專注")
                    val buttons = ui.actions(this, *options.map { (id, name) -> (if (id == selected) "✓ $name" else name) to if (id == selected) Tone.PRIMARY else Tone.TONAL }.toTypedArray()) { index ->
                        val (rating, name) = options[index]
                        val body = recordBody("review-$day", TimelineMath.dayStart(day, zone), "review", "").put("rating", rating)
                        task("儲存回顧…", { api().request("/v1/reviews", body) }) { dailyReview(); notice("已記下你的回顧：$name") }
                    }
                    buttons.forEachIndexed { index, button ->
                        val (id, name) = options[index]
                        button.isSelected = id == selected
                        button.contentDescription = "$name，${if (id == selected) "已選擇" else "未選擇"}"
                    }
                }
            }
            val reflections = reflectionCard(slot)
            renderReflections(reflections, data)
            ui.card(slot, padding = 8).apply {
                ui.row(this, "每日 AI 回顧與排程", "只在你明確同意時傳送活動卡") { briefsPage(false) }
                ui.row(this, "複製這天的私人時間軸", "標題與摘要；只貼到你信任的位置") {
                    val value = list.joinToString("\n\n") { "${clock(it.getLong("start_ms"))}–${clock(it.getLong("end_ms"))} ${it.optString("title")}\n${it.optString("summary")}" }
                    val clip = ClipData.newPlainText("PocketLog", value).apply { description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) } }
                    getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
                    notice("已複製私人資料；請只貼到信任的位置。", Kind.INFO)
                }
            }
        }
    }

    private fun weeklyTimeline() {
        val first = TimelineMath.weekStart(day); val today = journalDay()
        val span = "${md(first)} – ${md(first.plusDays(6))}"
        val body = screen("每週", span, Tab.WEEKLY)
        dateNav(body, 7, span) { day = it; weeklyTimeline() }
        val slot = ui.column(body)
        val dates = (0L..6L).map { first.plusDays(it) }
        load(slot, "讀取本週…", {
            dates.map { d ->
                d to if (!TimelineMath.inHistory(d, today)) null else try { readJournal(dayPath(d)) } catch (e: ApiFailure) { throw e } catch (_: Exception) { null }
            }
        }) { days ->
            val week = days.map { (d, data) -> WeekDay(d, data != null, data?.optBoolean("offline") == true, cardsOf(data).map(::item)) }
            if (days.any { it.second?.optBoolean("pending_card_edits") == true }) ui.banner(slot, "活動卡先顯示手機上的修改，仍待伺服器同步確認。", Kind.INFO)
            if (week.any { it.offline }) ui.banner(slot, "部分日期為離線快取。", Kind.WARN)
            val limitedDays = days.count { it.second?.optBoolean("truncated") == true }
            if (limitedDays > 0) ui.banner(slot, "本週有 $limitedDays 天資料達顯示上限；時數與分類統計只涵蓋已載入的資料。", Kind.WARN)
            val all = days.flatMap { cardsOf(it.second) }
            val tiles = ui.horizontal(slot, 8).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(12) }
            ui.stat(tiles, if (limitedDays > 0) "已載入的記錄" else "本週已記錄", TimelineMath.duration(days.sumOf { TimelineMath.covered(intervals(cardsOf(it.second))) }), null)
            ui.stat(tiles, "有活動的天數", "${days.count { cardsOf(it.second).isNotEmpty() }} / 7", null)
            ui.card(slot).apply {
                ui.heading(this, "一週節奏")
                ui.meta(this, "每欄是一天，從 04:00 開始。點一欄開啟那一天。", bottom = 8)
                addView(WeekTimelineView(this@MainActivity, week, day, today, zone, System.currentTimeMillis()) { day = it; home() }, ui.lp())
            }
            ui.card(slot, padding = 8).apply {
                week.forEach { w ->
                    val data = days.first { it.first == w.date }.second
                    val list = cardsOf(data)
                    val top = totals(list).firstOrNull()
                    val detail = when {
                        w.date > today -> "尚未到來"
                        !TimelineMath.inHistory(w.date, today) -> "超出 29 天可讀範圍"
                        !w.available -> "無法讀取（離線且沒有快取）"
                        data?.optBoolean("truncated") == true -> "${TimelineMath.duration(TimelineMath.covered(intervals(list)))}（部分資料）" + (top?.let { " · 最多：${label(it.first)}" } ?: "")
                        list.isEmpty() -> "沒有活動"
                        else -> "${TimelineMath.duration(TimelineMath.covered(intervals(list)))} · 最多：${label(top!!.first)}"
                    }
                    ui.row(this, md(w.date) + if (w.date == today) " · 今天" else "", detail, onClick = if (w.available) ({ day = w.date; home() }) else null)
                }
            }
            if (all.isNotEmpty()) ui.card(slot).apply { ui.heading(this, "本週時間分配"); legend(this, totals(all)) }
            ui.card(slot, padding = 8).apply { ui.row(this, "每週 AI 回顧與排程", "只在你明確同意時傳送活動卡") { briefsPage(true) } }
        }
    }

    private fun briefsPage(weekly: Boolean) {
        val first = if (weekly) TimelineMath.weekStart(day) else day
        val body = screen(if (weekly) "每週 AI 回顧" else "每日 AI 回顧", if (weekly) "${md(first)} 起的一週" else dateTitle(day), back = { if (weekly) weeklyTimeline() else dailyReview() })
        ui.banner(body, "AI 回顧可能有誤，請核對活動。只傳送最多 100 張活動卡（24 KB），不含畫面、原始文字、感受與反思。摘要保留 30 天。")
        val (from, to) = range(first, if (weekly) 7 else 1)
        val slot = ui.column(body)
        load(slot, "讀取已保存的摘要…", { api().request("/v1/briefs?from_ms=$from&to_ms=$to") }) { data ->
            val rows = data.getJSONArray("briefs")
            if (rows.length() == 0) ui.empty(slot, "尚無摘要", "可以自行產生一次，或明確開啟排程。")
            for (i in 0 until rows.length()) {
                val b = rows.getJSONObject(i)
                ui.card(slot).apply {
                    ui.pill(this, if (b.optString("kind") == "weekly") "每週 · AI 產生" else "每日 · AI 產生", true)
                    ui.body(this, b.getString("text"), top = 8)
                    ui.button(this, "刪除這份摘要", Tone.TEXT) {
                        dialog().setTitle("永久刪除這份摘要？").setNegativeButton("取消", null).setPositiveButton("刪除") { _, _ ->
                            task("刪除中…", { api().request("/v1/briefs/${b.getString("id")}", method = "DELETE") }) { briefsPage(weekly); notice("已刪除摘要") }
                        }.showSecure()
                    }
                }
            }
        }
        ui.card(body).apply {
            ui.button(this, "產生並保存回顧") {
                task("確認 AI 設定…", { api().request("/v1/ai") }) { config ->
                    if (!config.optBoolean("enabled")) { notice("請先設定 AI", Kind.WARN); return@task }
                    briefConsentPage(weekly, config, from, to)
                    notice("尚未傳送；確認後才會產生回顧。", Kind.INFO)
                }
            }
            ui.row(this, "摘要排程設定", "獨立同意，預設關閉") { briefScheduleSettings(weekly) }
        }
    }

    private fun briefConsentPage(weekly: Boolean, config: JSONObject, from: Long, to: Long) {
        val body = screen("確認這次 AI 回顧", if (weekly) "每週回顧" else "每日回顧", back = { briefsPage(weekly) })
        ui.card(body).apply {
            ui.heading(this, "傳送給誰")
            ui.meta(this, "提供者端點")
            ui.body(this, config.getString("endpoint"), top = 4)
            ui.meta(this, "模型", top = 12)
            ui.body(this, config.getString("model"), top = 4)
            ui.meta(this, "經由你的私人 PocketLog 伺服器轉送；請確認這是你信任的提供者。", top = 12)
        }
        ui.card(body).apply {
            ui.heading(this, "這次傳送什麼")
            val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            ui.body(this, "${Instant.ofEpochMilli(from).atZone(zone).format(format)} – ${Instant.ofEpochMilli(to).atZone(zone).format(format)}")
            ui.meta(this, "時區：${zone.id}；包含開始、不包含結束時間。", top = 6)
            ui.body(this, "最多 100 張活動卡、24,000 位元組，只含活動的 app、時間、標題與摘要。", top = 12)
            ui.meta(this, "不含原始畫面、原始文字、感受、反思或 Agent 對話。", top = 8)
        }
        ui.card(body).apply {
            ui.heading(this, "費用與保存")
            ui.body(this, "API 費用由你承擔。模型輸出可能有誤，請核對活動。回顧保存在私人伺服器 30 天；已傳給模型的內容無法撤回。")
        }
        // One identity for retries from this confirmation page; provider-bound on the server.
        val request = JSONObject().put("id", UUID.randomUUID().toString()).put("from_ms", from).put("to_ms", to).put("kind", if (weekly) "weekly" else "daily").put("provider_id", config.getString("provider_id")).put("consent", true)
        ui.button(body, "同意並產生", Tone.PRIMARY) {
            task("等待模型回覆…", { api().request("/v1/briefs", request) }) { briefsPage(weekly); notice("已保存新的回顧") }
        }
        ui.button(body, "取消，返回回顧", Tone.TEXT) { briefsPage(weekly) }
    }

    private fun briefScheduleSettings(weekly: Boolean) {
        val body = screen("AI 摘要排程", "獨立同意，預設關閉", back = { briefsPage(weekly) })
        ui.banner(body, "自動傳送已完成日／週的最多 100 張活動卡（24 KB），不含原始畫面、感受與反思；可能產生模型費用，每期最多三次嘗試，不補跑歷史。更換模型後需重新同意，刪除日誌會停用排程。")
        val slot = ui.column(body)
        load(slot, "讀取排程…", { val api = api(); api.request("/v1/briefs/schedule") to api.request("/v1/ai") }) { (schedule, config) ->
            val card = ui.card(slot)
            val configured = config.optBoolean("enabled")
            ui.meta(card, if (configured) "提供者端點：${config.getString("endpoint")}\n模型：${config.getString("model")}" else "尚未設定 AI。請先到「設定 → AI」設定自己的模型；目前只能保存停用的排程。", bottom = 8)
            val enabled = ui.checkbox(card, "同意持續傳送給上述提供者與模型，並保存摘要 30 天", configured && schedule.getBoolean("enabled") && schedule.optString("provider_id") == config.optString("provider_id"))
            enabled.isEnabled = configured
            val timezone = ui.field(card, "IANA 時區", "Asia/Taipei", Field.TEXT, schedule.getString("timezone"))
            val minutes = ui.field(card, "每天幾點幾分", "04:00–23:59", Field.TEXT, "%02d:%02d".format(schedule.getInt("minute_of_day") / 60, schedule.getInt("minute_of_day") % 60))
            val daily = ui.checkbox(card, "每日摘要", schedule.getBoolean("daily"))
            val week = ui.checkbox(card, "每週摘要（上一個完整的週一到週一）", schedule.getBoolean("weekly"))
            ui.button(card, "儲存排程") {
                val time = runCatching { java.time.LocalTime.parse(minutes.text.toString().trim()) }.getOrNull()
                if (time == null || time.hour < 4) { notice("請填寫 04:00–23:59", Kind.WARN); return@button }
                val request = JSONObject(schedule.toString()).put("enabled", enabled.isChecked).put("provider_id", config.optString("provider_id", "")).put("timezone", timezone.text.toString().trim()).put("minute_of_day", time.hour * 60 + time.minute).put("daily", daily.isChecked).put("weekly", week.isChecked)
                task("儲存排程…", { api().request("/v1/briefs/schedule", request, "PUT") }) { schedule.put("revision", it.getLong("revision")); notice("排程已更新") }
            }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12) }
        }
    }

    // ---------------------------------------------------------------- chat

    private fun chatPage() {
        val body = screen("Chat", "詢問自己的 AI · ${if (chatWeekly) "本週" else dateTitle(day)}", Tab.CHAT)
        val slot = ui.column(body)
        load(slot, "讀取 AI 設定與對話…", {
            val api = api(); val config = api.request("/v1/ai"); val list = api.request("/v1/conversations").getJSONArray("conversations")
            val thread = if ((0 until list.length()).any { list.getJSONObject(it).getString("id") == conversationId }) api.request("/v1/conversations/$conversationId") else null
            config to thread
        }) { (config, saved) -> renderChat(slot, config, saved) }
    }

    private fun bubble(parent: LinearLayout, value: String, mine: Boolean) {
        if (!mine) ui.text(parent, "AI 回答，請核對", 12f, Palette.MUTED, top = 10)
        parent.addView(ui.text(null, value, 15f, if (mine) android.graphics.Color.WHITE else Palette.INK).apply {
            background = ui.shape(if (mine) Palette.ACCENT else Palette.CANVAS, 16f); setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            contentDescription = (if (mine) "你：" else "AI：") + value
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (mine) Gravity.END else Gravity.START; topMargin = ui.dp(if (mine) 12 else 4)
            if (mine) marginStart = ui.dp(40) else marginEnd = ui.dp(40)
        })
    }

    private fun renderChat(slot: LinearLayout, config: JSONObject, saved: JSONObject?) {
        val enabled = config.optBoolean("enabled")
        if (!enabled) ui.empty(slot, "尚未設定 AI", "Chat 使用你自己的模型。先在 Bring your own AI 設定端點與模型；PocketLog 不提供預設雲端 AI。", "設定 Bring your own AI") { aiSettings { chatPage() } }
        val first = if (chatWeekly) TimelineMath.weekStart(day).coerceAtLeast(journalDay().minusDays(TimelineMath.HISTORY_DAYS)) else day
        val last = if (chatWeekly) TimelineMath.weekStart(day).plusDays(7) else day.plusDays(1)
        fun window() = TimelineMath.dayStart(first, zone) to minOf(TimelineMath.dayStart(last, zone), System.currentTimeMillis())
        if (enabled) ui.card(slot).apply {
            ui.meta(this, "提供者")
            ui.text(this, config.getString("model"), 16f, bold = true)
            ui.meta(this, config.getString("endpoint"), bottom = 10)
            ui.segmented(this, listOf("所選日 ${md(day)}", "本週"), if (chatWeekly) 1 else 0, "提問範圍") { chatWeekly = it == 1; chatPage() }
            ui.meta(this, "每次只送範圍內最多 100 張活動卡的 app、時間、標題與摘要；不含原始文字、圖片、感受或反思。會附上最近 6 輪、6,000 字的對話。", top = 8)
            ui.button(this, "查看提問範圍的分類圖表", Tone.TONAL) {
                val (from, to) = window()
                task("讀取分類…", { api().request("/v1/overview?from_ms=$from&to_ms=$to") }) { overview ->
                    val panel = ui.column(null, 20); val rows = overview.getJSONArray("categories")
                    val data = (0 until rows.length()).map { rows.getJSONObject(it).let { c -> c.getString("category") to c.getLong("duration_ms") } }
                    if (data.isEmpty()) ui.meta(panel, "這個範圍沒有活動。") else legend(panel, data)
                    dialog().setTitle("所選範圍的實際活動分類").setView(ScrollView(this@MainActivity).apply { addView(panel) }).setPositiveButton("關閉", null).showSecure()
                    notice("圖表只依實際活動卡計算", Kind.INFO)
                }
            }
        }
        val thread = ui.card(slot)
        ui.heading(thread, "對話")
        val history = ui.column(null)
        val turns = mutableListOf<JSONObject>(); var cursor: JSONObject? = null
        val older = ui.button(thread, "載入更早的問答", Tone.TEXT) {}
        fun draw() {
            history.removeAllViews()
            if (turns.isEmpty()) ui.meta(history, "這是新的對話。問答保存在私人伺服器 30 天。")
            turns.forEach { bubble(history, it.getString("question"), true); bubble(history, it.getString("answer"), false) }
            older.visibility = if (cursor != null) View.VISIBLE else View.GONE
        }
        fun accept(data: JSONObject, prepend: Boolean) {
            val rows = data.getJSONArray("turns"); val list = (0 until rows.length()).map { rows.getJSONObject(it) }
            if (prepend) turns.addAll(0, list) else { turns.clear(); turns.addAll(list) }
            cursor = data.optJSONObject("next_cursor"); draw()
        }
        older.setOnClickListener {
            val before = cursor ?: return@setOnClickListener
            task("載入更早的問答…", { api().request("/v1/conversations/$conversationId?before_ms=${before.getLong("before_ms")}&before_id=${before.getString("before_id")}") }) { accept(it, true) }
        }
        thread.addView(history, ui.lp())
        if (saved != null) accept(saved, false) else draw()
        if (enabled) {
            val composer = ui.card(slot)
            val draftKey = "$conversationId|$day|$chatWeekly"
            val question = ui.field(composer, "你想問什麼？", "例如：今天哪段時間最專注？", Field.MULTILINE, questionDrafts[draftKey] ?: "", help = "問題草稿只在本次開啟 app 期間保留；切換範圍後需重新同意傳送。")
            question.filters = arrayOf(android.text.InputFilter.LengthFilter(1000))
            question.addTextChangedListener(watcher { questionDrafts.put(draftKey, it) })
            val consent = ui.checkbox(composer, "同意這次將活動卡、問題與最近 6 輪對話送到這個提供者，並在私人伺服器保存問答 30 天，承擔 API 費用。")
            ui.button(composer, "傳送這次提問") {
                if (question.text.isBlank()) { notice("請輸入問題", Kind.WARN); return@button }
                if (!consent.isChecked) { notice("請勾選這次的傳送同意", Kind.WARN); return@button }
                val (from, to) = window()
                val fresh = JSONObject().put("conversation_id", conversationId).put("request_id", UUID.randomUUID().toString()).put("question", RecordingRules.cap(question.text.toString(), 1000)).put("from_ms", from).put("to_ms", to).put("provider_id", config.getString("provider_id")).put("consent", true)
                // Reuse the request id when retrying the same question so the server can replay instead of calling the model twice.
                val request = pendingAsk?.takeIf { pendingAskWeekly == chatWeekly && it.optString("conversation_id") == conversationId && it.optString("question") == fresh.getString("question") && it.optLong("from_ms") == from && it.optString("provider_id") == config.getString("provider_id") } ?: fresh.also { pendingAsk = it; pendingAskWeekly = chatWeekly }
                consent.isChecked = false
                task("等待模型回覆…", { api().request("/v1/ask", request) }) {
                    pendingAsk = null
                    if (question.text.toString() == request.getString("question")) {
                        question.text.clear(); questionDrafts.removeIfUnchanged(draftKey, "")
                    }
                    turns += JSONObject().put("question", request.getString("question")).put("answer", it.getString("answer")); draw()
                    scroll.post { scroll.smoothScrollTo(0, thread.bottom) }
                    notice("已收到回答，請核對。")
                }
            }
        }
        ui.card(slot, padding = 8).apply {
            ui.row(this, "開啟新對話", null) { conversationId = UUID.randomUUID().toString(); chatPage() }
            ui.row(this, "已保存的對話", "保留 30 天") {
                task("讀取對話清單…", { api().request("/v1/conversations") }) { list ->
                    val rows = list.getJSONArray("conversations")
                    if (rows.length() == 0) { notice("還沒有保存的對話", Kind.INFO); return@task }
                    dialog().setTitle("選擇私人對話").setItems((0 until rows.length()).map { rows.getJSONObject(it).getString("title") }.toTypedArray()) { _, index ->
                        conversationId = rows.getJSONObject(index).getString("id"); chatPage()
                    }.setNegativeButton("取消", null).showSecure()
                }
            }
            ui.row(this, "刪除此對話", "從私人伺服器永久刪除") {
                dialog().setTitle("永久刪除此對話？").setNegativeButton("取消", null).setPositiveButton("刪除") { _, _ ->
                    task("刪除中…", { api().request("/v1/conversations/$conversationId", method = "DELETE") }) { conversationId = UUID.randomUUID().toString(); chatPage(); notice("已刪除對話") }
                }.showSecure()
            }
        }
    }

    // ---------------------------------------------------------------- flow and agents

    private fun recordBody(id: String, at: Long, kind: String, text: String) = JSONObject().put("id", id).put("at_ms", at).put("kind", kind).put("text", text).put("end_ms", JSONObject.NULL).put("rating", JSONObject.NULL).put("source", JSONObject.NULL)

    private fun flowPage() {
        val body = screen("Flow", "私人專注計時 · 網站與手機同步", Tab.FLOW)
        val slot = ui.column(body)
        val records = ui.column(body)
        load(slot, "讀取計時…", { api().request("/v1/focus") }) { data ->
            val session = data.optJSONObject("session")
            val card = ui.card(slot)
            if (session == null) {
                ui.heading(card, "開始一段專注")
                ui.meta(card, "計時保存在私人伺服器，可從網站或手機接續；完成時才存為手動專注紀錄。不會開啟畫面擷取或 AI，也不代表實際使用活動。", bottom = 4)
                val topic = ui.field(card, "這次想專注什麼？", "最多 300 字", Field.TEXT)
                val minutes = ui.field(card, "預計分鐘", "1–180", Field.NUMBER, "25")
                ui.button(card, "開始專注") {
                    val duration = minutes.text.toString().toIntOrNull()
                    if (topic.text.isBlank() || topic.text.toString().codePointCount(0, topic.text.length) > 300) { notice("請填寫 1–300 字的主題", Kind.WARN); return@button }
                    if (duration == null || duration !in 1..180) { notice("分鐘需介於 1–180", Kind.WARN); return@button }
                    val request = JSONObject().put("id", UUID.randomUUID().toString()).put("at_ms", System.currentTimeMillis()).put("minutes", duration).put("text", topic.text.toString().trim())
                    task("開始計時…", { api().request("/v1/focus", request) }) { flowPage() }
                }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12) }
            } else {
                ui.pill(card, "進行中", true)
                ui.text(card, session.getString("text"), 20f, top = 10).isAccessibilityHeading = true
                val total = session.getLong("minutes") * 60_000
                val remainingText = ui.text(card, "", 36f, Palette.ACCENT_DARK, top = 8)
                val note = ui.meta(card, "")
                val bar = ui.column(card)
                val token = generation
                fun update() {
                    val left = (session.getLong("at_ms") + total - System.currentTimeMillis()).coerceAtLeast(0)
                    remainingText.text = if (left == 0L) "時間到" else "${(left + 59_999) / 60_000} 分"
                    note.text = if (left == 0L) "計時已結束，可以儲存成果。" else "剩餘時間 · 共 ${session.getLong("minutes")} 分 · ${clock(session.getLong("at_ms"))} 開始"
                    bar.removeAllViews(); ui.progress(bar, 1f - left.toFloat() / total, Palette.ACCENT, null)
                }
                update()
                // UI-only refresh while this page is visible; no background sampling.
                ticker = object : Runnable { override fun run() { if (token == generation && visible) { update(); handler.postDelayed(this, 30_000) } } }.also { handler.postDelayed(it, 30_000) }
                val identity = JSONObject().put("id", session.getString("id"))
                ui.actions(card, "捨棄" to Tone.OUTLINE, "儲存並結束" to Tone.PRIMARY) { index ->
                    if (index == 1) task("儲存專注紀錄…", { api().request("/v1/focus/finish", identity) }) { flowPage(); notice("已存為專注紀錄") }
                    else dialog().setTitle("捨棄這次專注？").setMessage("不會保存這段計時。").setNegativeButton("取消", null).setPositiveButton("捨棄") { _, _ ->
                        task("捨棄中…", { api().request("/v1/focus", identity, "DELETE") }) { flowPage() }
                    }.showSecure()
                }
            }
            loadRecords("flow", records)
        }
    }

    private fun loadRecords(kind: String, parent: LinearLayout) {
        ui.groupLabel(parent, "${md(day)} 的${if (kind == "flow") "專注紀錄" else "工作摘要"}")
        val card = ui.card(parent)
        val (from, to) = range(day)
        load(card, "讀取紀錄…", { api().request("/v1/reviews?from_ms=$from&to_ms=$to") }) { data ->
            val rows = data.getJSONArray("records"); var found = false
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i); if (r.getString("kind") != kind) continue
                if (found) card.addView(View(this@MainActivity).apply { setBackgroundColor(Palette.HAIRLINE) }, ui.lp(height = 1, top = 8, bottom = 8))
                found = true
                ui.body(card, r.getString("text"))
                val detail = if (kind == "flow" && !r.isNull("end_ms")) "${clock(r.getLong("at_ms"))} · ${TimelineMath.duration(r.getLong("end_ms") - r.getLong("at_ms"))}" else "${clock(r.getLong("at_ms"))} · ${r.optString("source").takeIf { it.isNotBlank() && it != "null" } ?: ""}"
                ui.meta(card, detail.trimEnd(' ', '·'), top = 2)
            }
            if (!found) ui.meta(card, "這天還沒有這類紀錄。")
            if (data.optBoolean("truncated")) ui.meta(card, "紀錄達顯示上限。", top = 8)
        }
    }

    private fun agentsPage() {
        val body = screen("Agents", "Codex／Claude 工作紀錄", Tab.AGENTS)
        ui.banner(body, "只在你明確選取時匯入。PocketLog 不會掃描桌面 session、執行工具或回放錄影；匯入前請移除金鑰與私密對話。")
        ui.card(body).apply {
            ui.heading(this, "匯入對話")
            ui.meta(this, "選一個原生 JSONL 檔，在手機上解析後逐則核准；只有勾選的文字會傳到私人伺服器，不會傳給 AI。", bottom = 4)
            ui.button(this, "選擇 JSONL 並預覽") {
                dialog().setTitle("紀錄來源").setItems(arrayOf("Codex", "Claude")) { _, index ->
                    agentImportSource = if (index == 0) "codex" else "claude"
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 22)
                }.setNegativeButton("取消", null).show()
            }
            ui.row(this, "專案工作線與對話回放", "已核准的工作紀錄") { agentSessionsPage() }
        }
        ui.card(body).apply {
            ui.heading(this, "寫一筆工作摘要")
            val source = ui.spinner(this, "來源", listOf("Codex", "Claude"))
            val note = ui.field(this, "專案、成果與待辦", "最多 4000 字", Field.MULTILINE)
            ui.button(this, "儲存工作摘要", Tone.TONAL) {
                if (note.text.isBlank()) { notice("請填寫摘要", Kind.WARN); return@button }
                val request = recordBody(UUID.randomUUID().toString(), System.currentTimeMillis(), "agent", RecordingRules.cap(note.text.toString(), 4000)).put("source", if (source.selectedItemPosition == 0) "codex" else "claude")
                task("儲存中…", { api().request("/v1/reviews", request) }) { agentsPage(); notice("已儲存工作摘要") }
            }
        }
        ui.card(body).apply {
            ui.heading(this, "匯入工作摘要 JSON")
            ui.meta(this, "格式：{\"sessions\":[{\"id\":\"session-id\",\"at_ms\":毫秒時間戳,\"source\":\"codex\",\"text\":\"成果與待辦\"}]}。最多 20 筆、48 KB。")
            ui.button(this, "選擇 JSON 檔", Tone.OUTLINE) {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "application/json"; addCategory(Intent.CATEGORY_OPENABLE) }, 21)
            }
        }
        loadRecords("agent", ui.column(body))
    }

    private fun agentImportPreview(rows: JSONArray, source: String) {
        val body = screen("核准要匯入的對話", if (source == "codex") "Codex" else "Claude", tab = Tab.AGENTS, back = { agentsPage() })
        ui.banner(body, "只在手機解析所選檔案：近 30 天最後 40 則文字，每則最多 1000 字；工具輸出與附件不會匯入。只有勾選的文字會傳到私人伺服器。")
        val info = ui.card(body)
        val project = ui.field(info, "專案／工作線名稱", "最多 80 字，不要填完整路徑")
        val title = ui.field(info, "工作標題", "最多 200 字")
        if (rows.length() == 0) ui.empty(body, "沒有可匯入的文字", "檔案中沒有近 30 天的使用者或助理文字。", "返回") { agentsPage() }
        val selections = (0 until rows.length()).map { i ->
            val turn = rows.getJSONObject(i)
            val card = ui.card(body)
            val selected = ui.checkbox(card, "${if (turn.getString("role") == "user") "使用者" else "助理"} · ${stamp(turn.getLong("at_ms"))}")
            val value = ui.field(card, "核准後保存的文字", "", Field.MULTILINE, turn.getString("text"))
            Triple(turn, selected, value)
        }
        ui.button(body, "只匯入勾選的文字") {
            val turns = JSONArray(); selections.filter { it.second.isChecked }.forEach { (turn, _, value) -> turns.put(JSONObject(turn.toString()).put("text", value.text.toString().trim())) }
            if (turns.length() == 0 || project.text.isBlank() || title.text.isBlank()) { notice("請填寫專案、標題並勾選要保存的文字", Kind.WARN); return@button }
            val request = JSONObject().put("id", UUID.randomUUID().toString()).put("revision", 0).put("at_ms", turns.getJSONObject(turns.length() - 1).getLong("at_ms")).put("source", source).put("project", project.text.toString().trim()).put("title", title.text.toString().trim()).put("status", "completed").put("turns", turns)
            if (request.toString().toByteArray(Charsets.UTF_8).size > 64000) { notice("超過 64 KB，請減少選取文字", Kind.WARN); return@button }
            task("匯入中…", { api().request("/v1/agent-sessions", request, "PUT") }) { agentSessionsPage(); notice("已匯入 ${turns.length()} 則核准文字") }
        }
    }

    private val statusNames = listOf("active" to "進行中", "waiting" to "待接續", "completed" to "已完成")

    private fun agentSessionsPage() {
        val body = screen("專案工作線", "已核准的 Agents 紀錄", tab = Tab.AGENTS, back = { agentsPage() })
        val slot = ui.column(body)
        load(slot, "讀取工作線…", { api().request("/v1/agent-sessions") }) { data ->
            val rows = data.getJSONArray("sessions")
            if (rows.length() == 0) ui.empty(slot, "尚無已核准的工作紀錄", "從 Agents 頁面選擇 JSONL 檔並核准要保存的文字。", "返回 Agents") { agentsPage() }
            (0 until rows.length()).map { rows.getJSONObject(it) }.groupBy { it.getString("project") }.forEach { (project, sessions) ->
                ui.groupLabel(slot, project)
                val card = ui.card(slot, padding = 8)
                sessions.forEach { s ->
                    val status = statusNames.firstOrNull { it.first == s.getString("status") }?.second ?: s.getString("status")
                    ui.row(card, s.getString("title"), "${s.getString("source")} · ${s.getInt("turn_count")} 則", status to (s.getString("status") != "completed")) { agentSessionReplay(s.getString("id")) }
                }
            }
        }
    }

    private fun agentSessionReplay(id: String) {
        val body = screen("核准對話回放", null, tab = Tab.AGENTS, back = { agentSessionsPage() })
        val slot = ui.column(body)
        load(slot, "讀取對話…", { api().request("/v1/agent-sessions/$id") }) { session ->
            ui.card(slot).apply {
                ui.text(this, session.getString("title"), 20f).isAccessibilityHeading = true
                ui.meta(this, "${session.getString("project")} · ${session.getString("source")}。只顯示核准文字，不執行工具或連結。", top = 4)
                val state = ui.spinner(this, "狀態", statusNames.map { it.second }, statusNames.indexOfFirst { it.first == session.getString("status") })
                ui.button(this, "儲存狀態", Tone.TONAL) {
                    val request = JSONObject(session.toString()).put("status", statusNames[state.selectedItemPosition].first)
                    task("儲存中…", { api().request("/v1/agent-sessions", request, "PUT") }) { session.put("revision", it.getLong("revision")); notice("已儲存狀態") }
                }
            }
            val turns = session.getJSONArray("turns")
            val card = ui.card(slot)
            if (turns.length() == 0) { ui.meta(card, "沒有核准的文字。"); return@load }
            var index = 0
            val position = ui.meta(card, "")
            val content = ui.body(card, "", top = 8).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
            lateinit var buttons: List<android.widget.Button>
            fun render() {
                val t = turns.getJSONObject(index)
                position.text = "${index + 1} / ${turns.length()} · ${if (t.getString("role") == "user") "使用者" else "助理"} · ${stamp(t.getLong("at_ms"))}"
                content.text = t.getString("text")
                buttons[0].isEnabled = index > 0; buttons[0].alpha = if (index > 0) 1f else .4f
                buttons[1].isEnabled = index < turns.length() - 1; buttons[1].alpha = if (index < turns.length() - 1) 1f else .4f
            }
            buttons = ui.actions(card, "上一則" to Tone.OUTLINE, "下一則" to Tone.OUTLINE) { i ->
                index = if (i == 0) (index - 1).coerceAtLeast(0) else (index + 1).coerceAtMost(turns.length() - 1); render()
            }
            render()
            ui.button(slot, "刪除工作與核准對話", Tone.DANGER) {
                dialog().setTitle("永久刪除這個工作紀錄？").setNegativeButton("取消", null).setPositiveButton("刪除") { _, _ ->
                    task("刪除中…", { api().request("/v1/agent-sessions/$id", method = "DELETE") }) { agentSessionsPage(); notice("已刪除") }
                }.showSecure()
            }
        }
    }

    // ---------------------------------------------------------------- support

    private fun accessibilityServiceOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val ours = ComponentName(this, ContextService::class.java)
        return enabled.split(':').any { it.equals(ours.flattenToString(), true) || it.equals(ours.flattenToShortString(), true) }
    }

    private fun supportPage() {
        val body = screen("支援", "檢查連線與紀錄狀態", Tab.SUPPORT, refresh = { supportPage() })
        val s = RecordingSettings(this@MainActivity)
        val usage = s.hasUsagePermission()
        ui.groupLabel(body, "這支手機的狀態")
        val eligible = effectiveAllowedCount(s)
        captureAllowlistWarning(body, s, eligible)
        val card = ui.card(body, padding = 8)
        ui.row(card, "私人伺服器", host(), (if (credentials != null) "已設定" else "未連線") to (credentials != null)) { settings() }
        ui.row(card, "手機紀錄", if (s.enabled) "記錄同意後的 app 前景時段" else "預設關閉；需要時在設定開啟", (if (s.enabled) "開啟" else "關閉") to s.enabled) { settings() }
        ui.row(card, "Android 使用情況存取權", if (usage) "已授予" else "開啟紀錄後需要授予", (if (usage) "已授予" else "未授予") to usage) { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        ui.row(card, "伺服器收集", if (s.remotePaused) "所有裝置已暫停；恢復不會改變本機同意" else "未暫停", (if (s.remotePaused) "已暫停" else "運作中") to !s.remotePaused) { settings() }
        ui.row(card, "畫面文字", "允許 ${s.allowed.size} 個 · 可擷取 $eligible 個 · 排除 ${s.exclusions.size} 個", (if (!s.contextEnabled) "關閉" else if (eligible == 0) "待設定" else "已同意") to (s.contextEnabled && eligible > 0)) { settings() }
        if (s.contextEnabled) accessibilityServiceOn().let { on -> ui.row(card, "輔助使用服務", "畫面文字需要 PocketLog 的輔助使用服務", (if (on) "已開啟" else "未開啟") to on) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
        ui.row(card, "最近一次擷取檢查", CaptureDiagnostics.snapshot().outcome.title) { captureDiagnostics { supportPage() } }
        ui.row(card, "最近同步", s.status(), onClick = null)
        val queue = ui.column(card)
        load(queue, "讀取離線佇列…", { synchronized(SyncEngine.lock) { JournalQueue(this@MainActivity).counts() } }) { (pending, rejected) ->
            ui.row(queue, "離線佇列", "待傳 $pending · 被拒絕 $rejected · 過期／損毀／超限丟棄 ${s.dropped()}", (if (rejected > 0) "需處理" else "正常") to (rejected == 0)) { settings() }
        }
        ui.groupLabel(body, "沒有活動時")
        ui.card(body).apply {
            ui.body(this, "1. 確認「設定 → 手機紀錄」已開啟，並授予 Android 使用情況存取權。\n2. 確認伺服器沒有暫停收集，該 app 沒有被排除。\n3. 背景整理最短約 15 分鐘，由 Android 決定；可在時間軸按同步。\n4. 預設只用不限量網路上傳；行動數據下請在設定切換。\n5. 畫面文字與縮圖還需允許清單與各自同意。AI 設定改變後要重新同意。")
        }
        ui.groupLabel(body, "目前限制")
        ui.card(body).apply {
            ui.meta(this, "尚未移植官方 Dayflow 雲端 Flow、桌面 CLI 自動追蹤、錄影回放與 OCR 遮罩。手機採擷取前排除，請不要允許敏感 app。實機權限與全天電池量測仍需驗證。")
            ui.button(this, "使用文件與原始碼（GitHub）", Tone.OUTLINE) { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/skyhong2002/PocketLog"))) }
        }
    }

    private fun captureDiagnostics(back: () -> Unit) {
        val body = screen("擷取狀態", "本機狀態，不含畫面內容", tab, back = back, action = Triple(R.drawable.ic_sync, "重新檢查擷取狀態", { captureDiagnostics(back) }), refresh = { captureDiagnostics(back) })
        val settings = RecordingSettings(this)
        captureAllowlistWarning(body, settings, effectiveAllowedCount(settings))
        if (!settings.contextEnabled) callout(body, "畫面文字未開啟；此頁不會啟用任何紀錄。", Kind.INFO, "查看設定") { settings() }
        else if (!accessibilityServiceOn() || !settings.hasUsagePermission())
            callout(body, "系統權限未齊全，請檢查使用情況存取權與輔助使用服務。", Kind.WARN, "查看設定") { settings() }
        val state = CaptureDiagnostics.snapshot()
        ui.card(body).apply {
            ui.heading(this, "最近一次檢查結果")
            ui.text(this, state.outcome.title, 19f, bold = true, top = 8)
            ui.meta(this, state.atMs?.let { stamp(it) } ?: "本次執行尚無結果", top = 4)
            ui.body(this, state.outcome.detail, top = 12)
            ui.meta(this, "這是過去的檢查結果，不代表目前可擷取；請以本頁設定與權限提示為準。", top = 8)
            ui.heading(this, "最近排入本機佇列", top = 16)
            ui.body(this, state.lastQueuedMs?.let { stamp(it) } ?: "尚無成功排入的情境", top = 6)
            ui.meta(this, "排入佇列不代表已同步；請到設定檢查同步狀態。", top = 6)
        }
        ui.card(body).apply {
            ui.heading(this, "哪些畫面會被跳過？")
            ui.body(this, "允許清單以外、被排除的 app、鎖定與安全視窗不會擷取。只要包含密碼或可編輯欄位，就會排除整個視窗，包含搜尋列與訊息輸入框；不是只略過那個欄位。", top = 8)
            ui.body(this, "只有切換視窗的事件會觸發檢查，系統擷取嘗試最多每五分鐘一次；同一 app 內持續閱讀不會定期截圖。", top = 10)
            ui.meta(this, "此頁只保留本次 app 執行期間的一個結果與最近排入時間，不保存 app 名稱、畫面文字、圖片或錯誤內容，也不傳送診斷。重新開啟程序或更換連線後重設。", top = 12)
        }
    }

    // ---------------------------------------------------------------- settings

    private fun effectiveAllowedCount(settings: RecordingSettings): Int {
        val allowed = settings.allowed
        val excluded = settings.exclusions
        return allowed.count { RecordingRules.contextAllowed(it, allowed, excluded, packageName) }
    }

    private fun captureAllowlistWarning(parent: ViewGroup, settings: RecordingSettings, eligible: Int) {
        if (settings.contextEnabled && eligible == 0) callout(parent,
            "已同意畫面文字，但沒有可擷取的 app。允許清單可能是空的，或全部被排除；排除永遠優先。",
            Kind.WARN, "調整允許與排除清單") { appListsPage() }
    }

    private fun settings() {
        val s = RecordingSettings(this@MainActivity)
        val body = screen("設定", "連線、紀錄、隱私與資料", Tab.SETTINGS, refresh = { settings() })
        ui.groupLabel(body, "連線")
        ui.card(body, padding = 8).apply {
            ui.row(this, "私人伺服器", host(), onClick = null)
            ui.row(this, "更換伺服器或金鑰", "清除這支手機的連線與待傳資料，然後重新設定") {
                confirmReset("更換連線？", "會停止紀錄，刪除這支手機尚未同步的資料與快取，然後重新輸入網址與金鑰。伺服器日誌不會被刪除。")
            }
        }

        ui.groupLabel(body, "手機紀錄")
        ui.card(body, padding = 8).apply {
            ui.row(this, "App 使用紀錄", "只記錄同意後的 app 套件名稱與前景時段；不讀文字、不截圖", (if (s.enabled) "開啟" else "關閉") to s.enabled) { toggleUsage(s) }
            val usage = s.hasUsagePermission()
            ui.row(this, "Android 使用情況存取權", if (usage) "已授予；撤銷後會停止產生活動" else "開啟紀錄後需要在系統設定授予", (if (usage) "已授予" else "未授予") to usage) { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            ui.row(this, "所有裝置的收集", if (s.remotePaused) "已在伺服器暫停；恢復不會取代本機同意" else "可暫停所有裝置；離線手機要等同步才收到", (if (s.remotePaused) "已暫停" else "運作中") to !s.remotePaused) { manageRemotePause(s) }
            ui.row(this, "排除與允許清單", "排除 ${s.exclusions.size} 個 · 畫面文字允許 ${s.allowed.size} 個 app") { appListsPage() }
            ui.switchRow(this, "只用不限量網路上傳", "關閉後也會使用行動數據", s.wifiOnly) { checked ->
                if (checked == s.wifiOnly) return@switchRow
                task("更新網路設定…", { synchronized(SyncEngine.lock) { s.wifiOnly = checked }; SyncEngine.schedule(this@MainActivity) }) { notice(if (checked) "只在不限量網路上傳" else "也會使用行動網路上傳") }
            }.apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) }
        }

        ui.groupLabel(body, "畫面情境（進階，預設關閉）")
        val eligible = effectiveAllowedCount(s)
        captureAllowlistWarning(body, s, eligible)
        ui.card(body, padding = 8).apply {
            ui.meta(this, "每一項都是獨立同意。含密碼或輸入欄位的整個視窗、鎖定畫面與安全視窗，都會在擷取前排除。", bottom = 4).setPadding(ui.dp(4), ui.dp(8), ui.dp(4), 0)
            val contextDetail = when { !s.enabled -> "需先開啟 App 使用紀錄"; eligible == 0 -> "沒有可擷取的 app，請調整允許與排除清單"; else -> "可擷取 $eligible 個 app；切換時最多每 5 分鐘讀取一次，仍需系統權限" }
            ui.row(this, "畫面文字", contextDetail, (if (!s.contextEnabled) "關閉" else if (eligible == 0) "待設定" else "已同意") to (s.contextEnabled && eligible > 0)) { toggleContext(s) }
            ui.row(this, "畫面縮圖", if (s.contextEnabled) "最長邊 480 px，可能含無法從文字判斷的私人資訊" else "需先開啟畫面文字", (if (s.imagesEnabled) "開啟" else "關閉") to s.imagesEnabled) { toggleImages(s) }
            ui.row(this, "短期縮圖回放", if (s.imagesEnabled) "另行同意保存，最多 24 小時、256 張" else "需先開啟畫面縮圖", (if (s.replayConsent) "已同意" else "關閉") to s.replayConsent) { toggleReplay(s) }
            ui.row(this, "文字隱私防護", "疑似金鑰、token 或長數字時跳過整個視窗", (if (s.privacyShield) "開啟" else "關閉") to s.privacyShield) { privacyShieldDialog(s) }
            ui.row(this, "最近一次擷取檢查", CaptureDiagnostics.snapshot().outcome.title) { captureDiagnostics { settings() } }
            ui.row(this, "系統輔助使用設定", "畫面文字需要 PocketLog 的輔助使用服務") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        ui.groupLabel(body, "AI")
        ui.card(body, padding = 8).apply {
            ui.row(this, "Bring your own AI", "端點、模型與金鑰保存在你的伺服器") { aiSettings { settings() } }
            val consent = ui.column(this)
            load(consent, "核對未來情境的模型同意…", { CaptureAiConsent.refresh(this@MainActivity) { api().request("/v1/ai") } }) { state ->
                val label = when (state) {
                    CaptureAiConsent.State.CURRENT -> if (s.contextEnabled) "已同意" else "已保存"
                    CaptureAiConsent.State.UNVERIFIED -> "尚未核對"
                    CaptureAiConsent.State.RENEWAL -> "需重新同意"
                    CaptureAiConsent.State.OFF -> "未同意"
                }
                val detail = when (state) {
                    CaptureAiConsent.State.CURRENT -> "最近核對的模型與同意相符；點此撤回"
                    CaptureAiConsent.State.UNVERIFIED -> "尚未確認伺服器目前的模型。舊同意不會授權新模型；點此重新核對。"
                    CaptureAiConsent.State.RENEWAL -> "模型設定已變更或停用。只有重新同意後，未來情境才可使用目前模型。"
                    CaptureAiConsent.State.OFF -> if (s.contextEnabled) "每次更換模型都要重新同意" else "需先開啟畫面文字"
                }
                ui.row(consent, "模型處理未來情境", null, label to (state == CaptureAiConsent.State.CURRENT)) {
                    if (state == CaptureAiConsent.State.CURRENT) task("撤回同意…", { synchronized(SyncEngine.lock) { s.externalAiConsent = false; s.aiProviderId = null; JournalQueue(this@MainActivity).clearContexts() } }) { settings(); notice("已撤回模型處理同意；待傳情境已清除。") }
                    else approveAi { settings() }
                }
                // Give recovery guidance the full width, especially at 2× font size.
                ui.meta(consent, detail, top = 4, bottom = 8)
                if (state == CaptureAiConsent.State.UNVERIFIED && s.hasExternalAiGrant) ui.button(consent, "撤回已保存的模型同意", Tone.TEXT) {
                    task("撤回同意…", { synchronized(SyncEngine.lock) { s.externalAiConsent = false; s.aiProviderId = null; JournalQueue(this@MainActivity).clearContexts() } }) { settings() }
                }
            }
        }

        ui.groupLabel(body, "共用設定")
        ui.card(body, padding = 8).apply {
            ui.row(this, "目標與分類", "網站與手機共用的專注目標、分類與自動規則") { sharedSettings() }
            ui.row(this, "重新閱讀 onboarding", null) { onboarding(0) { settings() } }
        }

        ui.groupLabel(body, "同步")
        ui.card(body, padding = 8).apply {
            ui.row(this, "立即同步", s.status()) { task("同步中…", { SyncEngine.sync(this@MainActivity) }) { settings(); notice(RecordingSettings(this@MainActivity).status(), Kind.INFO) } }
            val queue = ui.column(this)
            load(queue, "讀取離線佇列…", { synchronized(SyncEngine.lock) { JournalQueue(this@MainActivity).counts() } }) { (pending, rejected) ->
                ui.row(queue, "離線佇列", "待傳 $pending · 被拒絕 $rejected · 過期／損毀／超限丟棄 ${s.dropped()}", onClick = null)
                if (rejected > 0) {
                    ui.row(queue, "重試被拒絕的資料", "先確認連線與金鑰正確") {
                        task("重試中…", { synchronized(SyncEngine.lock) { JournalQueue(this@MainActivity).retryRejected(requireNotNull(credentials)); s.nextAttempt = 0 }; SyncEngine.sync(this@MainActivity) }) { settings(); notice(RecordingSettings(this@MainActivity).status(), Kind.INFO) }
                    }
                    ui.row(queue, "刪除被拒絕的資料", "無法復原") {
                        dialog().setTitle("刪除被拒絕的資料？").setMessage("這些資料不會再上傳。").setNegativeButton("取消", null).setPositiveButton("刪除") { _, _ ->
                            task("刪除中…", { synchronized(SyncEngine.lock) { JournalQueue(this@MainActivity).clearRejected() } }) { settings(); notice("已刪除被拒絕的資料") }
                        }.showSecure()
                    }
                }
            }
        }

        ui.groupLabel(body, "資料")
        ui.card(body).apply {
            ui.meta(this, "單日匯出在時間軸頁面的「這一天」。刪除前請確認不再需要。", bottom = 4)
            ui.button(this, "清除這支手機的資料與連線", Tone.DANGER) {
                confirmReset("清除本機資料？", "會停止紀錄並刪除尚未傳送的資料與快取。伺服器資料仍保留。")
            }
            ui.button(this, "永久刪除伺服器上的全部日誌", Tone.DANGER) {
                dialog().setTitle("永久刪除所有日誌？").setMessage("刪除活動、感受、反思與原始文字，同時停止紀錄並清除本機佇列與快取。無法復原。")
                    .setNegativeButton("取消", null).setPositiveButton("刪除全部") { _, _ ->
                        task("刪除伺服器日誌…", { synchronized(SyncEngine.lock) { SyncEngine.clearLocal(this@MainActivity); api().request("/v1/journal", method = "DELETE") } }) { clearDrafts(); settings(); notice("已刪除伺服器上的全部日誌，紀錄已停止。", Kind.INFO) }
                    }.showSecure()
            }
        }
        ui.groupLabel(body, "關於")
        ui.card(body, padding = 8).apply { ui.row(this, "PocketLog ${version()}", "MIT 授權 · 自架私人日誌 · 無分析", onClick = null) }
    }

    private fun toggleUsage(s: RecordingSettings) {
        if (s.remotePaused) { notice("伺服器已暫停所有裝置的收集，請先恢復。", Kind.WARN); return }
        if (s.enabled) {
            task("暫停紀錄…", { synchronized(SyncEngine.lock) { UsageCollector(this@MainActivity).reset(); s.enabled = false; JournalQueue(this@MainActivity).clearContexts() } }) {
                SyncEngine.schedule(this@MainActivity); settings(); notice("已暫停活動與畫面文字紀錄；待傳畫面文字已刪除。")
            }
            return
        }
        dialog().setTitle("開啟 app 使用紀錄？")
            .setMessage("只記錄同意之後的 app 套件名稱及前景時段，不讀取文字或截圖。背景整理最短每 15 分鐘，由 Android 決定實際時間；多視窗與系統遺漏事件可能影響精準度。\n\n下一步會開啟 Android 設定，請授予 PocketLog「使用情況存取權」。")
            .setNegativeButton("取消", null).setPositiveButton("同意並開啟") { _, _ ->
                task("開啟紀錄…", { synchronized(SyncEngine.lock) { UsageCollector(this@MainActivity).reset(); s.enabled = true; JournalQueue(this@MainActivity).clearContexts() } }) {
                    SyncEngine.schedule(this@MainActivity)
                    if (!s.hasUsagePermission()) startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    settings(); notice("已開啟 app 使用紀錄", Kind.INFO)
                }
            }.showSecure()
    }

    private fun manageRemotePause(s: RecordingSettings) {
        task("讀取收集狀態…", { api().request("/v1/collection") }) { control ->
            val paused = !control.getBoolean("paused")
            dialog().setTitle(if (paused) "暫停所有裝置收集？" else "恢復伺服器收集？")
                .setMessage("暫停會拒絕新的活動與情境並刪除待分析原始資料。離線手機須等待同步；恢復不取代各裝置的本機同意。")
                .setNegativeButton("取消", null).setPositiveButton(if (paused) "暫停" else "恢復") { _, _ ->
                    task("更新收集狀態…", {
                        synchronized(SyncEngine.lock) {
                            val result = api().request("/v1/collection", JSONObject(control.toString()).put("paused", paused), "PUT")
                            s.remotePaused = paused; UsageCollector(this@MainActivity).reset(); if (paused) JournalQueue(this@MainActivity).clearCollected(); result
                        }
                    }) { settings(); notice(if (paused) "已暫停所有裝置的收集" else "已恢復伺服器收集") }
                }.showSecure()
        }
    }

    private fun toggleContext(s: RecordingSettings) {
        if (s.contextEnabled) {
            task("關閉畫面文字…", { synchronized(SyncEngine.lock) { s.contextEnabled = false; s.analysisConsent = false; JournalQueue(this@MainActivity).clearContexts() } }) { settings(); notice("已關閉畫面文字並清除待傳文字") }
            return
        }
        if (!s.enabled) { notice("請先開啟 App 使用紀錄", Kind.WARN); return }
        if (s.allowed.isEmpty()) { notice("請先在「排除與允許清單」加入允許的 app", Kind.WARN); appListsPage(); return }
        dialog().setTitle("允許選定 app 的畫面文字？")
            .setMessage("切換 app 時最多每五分鐘一次；Android 安全視窗、鎖定畫面、密碼與輸入欄位會跳過。圖片另有獨立選項，預設關閉。文字透過 HTTPS 傳送到你設定的私人伺服器，形成可見文字摘錄；原始佇列最長 24 小時，摘錄可保留 30 天。預設不使用外部 AI，另有獨立同意選項。\n\n下一步會開啟 Android 輔助使用設定，請開啟 PocketLog 服務；可隨時暫停。")
            .setNegativeButton("取消", null).setPositiveButton("同意私人文字處理") { _, _ ->
                task("開啟畫面文字…", { synchronized(SyncEngine.lock) { require(s.enabled && s.allowed.isNotEmpty()); s.contextEnabled = true; s.analysisConsent = true } }) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); settings(); notice("已同意畫面文字；請在系統設定開啟服務", Kind.INFO)
                }
            }.showSecure()
    }

    private fun toggleImages(s: RecordingSettings) {
        if (s.imagesEnabled) {
            task("關閉縮圖…", { synchronized(SyncEngine.lock) { s.imagesEnabled = false; JournalQueue(this@MainActivity).clearContexts() } }) { settings(); notice("已關閉縮圖並清除待傳情境") }
            return
        }
        if (!s.contextEnabled) { notice("請先開啟畫面文字", Kind.WARN); return }
        dialog().setTitle("允許選定 app 的畫面縮圖？")
            .setMessage("只在 app 切換時最多每五分鐘一次，縮圖最長邊 480 像素、JPEG 最多 24 KiB。整個允許視窗中可能有無法從文字判斷的私人資訊；請只允許你願意記錄的 app。圖片會加密暫存並傳到私人伺服器，原始資料最長 24 小時。只有另外同意模型處理時才可能送給模型；安全視窗、密碼與輸入欄位會跳過。尚未完成實機全天耗電量測。")
            .setNegativeButton("取消", null).setPositiveButton("同意選定 app 的縮圖") { _, _ ->
                task("開啟縮圖…", { synchronized(SyncEngine.lock) { require(s.contextEnabled); s.imagesEnabled = true } }) { settings(); notice("已開啟畫面縮圖") }
            }.showSecure()
    }

    private fun toggleReplay(s: RecordingSettings) {
        if (s.replayConsent) {
            task("關閉回放…", { synchronized(SyncEngine.lock) { s.replayConsent = false; JournalQueue(this@MainActivity).clearContexts() } }) { settings(); notice("已關閉回放保存並清除待傳情境") }
            return
        }
        if (!s.imagesEnabled) { notice("請先開啟畫面縮圖", Kind.WARN); return }
        dialog().setTitle("保存畫面供私人回放？")
            .setMessage("這是與分析分開的同意：未來縮圖可從網站與手機讀回，最多 24 小時、256 張，最長邊 480 px。可能包含私人資訊；沒有 OCR 自動遮罩。只允許你願意保存的 app。關閉不刪除已傳送的縮圖，可另行刪除或等待到期；不代表同意傳給模型。")
            .setNegativeButton("取消", null).setPositiveButton("同意短期回放") { _, _ ->
                task("更新同意…", { synchronized(SyncEngine.lock) { require(s.imagesEnabled); s.replayConsent = true; JournalQueue(this@MainActivity).clearContexts() } }) { settings(); notice("已同意短期回放") }
            }.showSecure()
    }

    private fun privacyShieldDialog(s: RecordingSettings) {
        dialog().setTitle("文字隱私防護")
            .setMessage("啟用後，畫面文字疑似 API 金鑰、Bearer token、密碼欄位值或 13–19 位長數字時，在截圖前跳過整個視窗。密碼／可編輯欄位與安全視窗仍一律排除。這是保守文字檢查，可能誤判，也無法辨識只有像素的秘密；不提供 OCR 自動遮罩。請繼續使用 app 排除與允許清單。")
            .setNegativeButton("取消", null).setPositiveButton(if (s.privacyShield) "關閉疑似秘密檢查" else "啟用") { _, _ ->
                task("更新防護…", { synchronized(SyncEngine.lock) { s.privacyShield = !s.privacyShield; JournalQueue(this@MainActivity).clearContexts() } }) { settings(); notice(if (s.privacyShield) "已啟用文字隱私防護" else "已關閉文字隱私防護", Kind.INFO) }
            }.showSecure()
    }

    private fun appListsPage() {
        val s = RecordingSettings(this@MainActivity)
        val body = screen("排除與允許清單", "完整套件名稱，以空白、逗號或換行分隔", tab = Tab.SETTINGS, back = { settings() })
        ui.banner(body, "PocketLog 本身、已知敏感 app 與套件名稱符合內建規則的 app 會自動排除，但無法辨識所有密碼管理器、驗證器或付款／銀行 app。請主動把敏感 app 加入排除清單。視窗含密碼或輸入欄位時仍會跳過。")
        val excluded: EditText; val allowed: EditText
        ui.card(body).apply {
            ui.heading(this, "排除的 app")
            ui.meta(this, "不記錄這些 app 的使用時段，也不讀取畫面。排除優先於允許清單。")
            excluded = ui.field(this, "排除清單", "例如 com.example.private", Field.MULTILINE, s.exclusions.sorted().joinToString("\n"))
            ui.button(this, "從已安裝 app 選擇", Tone.TONAL) { chooseApps(excluded) }
        }
        ui.card(body).apply {
            ui.heading(this, "畫面文字允許清單")
            ui.meta(this, "只有這些 app 可能被讀取畫面文字，且仍需另外同意。允許清單可能含私人內容，請謹慎選擇。")
            allowed = ui.field(this, "允許清單", "例如 org.example.reader", Field.MULTILINE, s.allowed.sorted().joinToString("\n"))
            ui.button(this, "從已安裝 app 選擇", Tone.TONAL) { chooseApps(allowed) }
        }
        ui.button(body, "儲存清單") {
            val lists = runCatching { RecordingRules.packages(excluded.text.toString()) to RecordingRules.packages(allowed.text.toString()) }.getOrNull()
            if (lists == null) { notice("請輸入完整套件名稱，例如 com.example.app", Kind.WARN); return@button }
            task("儲存清單…", { synchronized(SyncEngine.lock) { s.exclusions = lists.first; s.allowed = lists.second; JournalQueue(this@MainActivity).clearContexts(); UsageCollector(this@MainActivity).reset() } }) { settings(); notice("已儲存；舊的待傳畫面文字已刪除。") }
        }
    }

    private fun chooseApps(field: EditText) {
        // Views belong to the main thread, including validation of a manually edited list.
        val initial = try {
            RecordingRules.packages(field.text.toString())
        } catch (_: IllegalArgumentException) {
            val message = "請輸入完整套件名稱，例如 com.example.app；以空白、逗號或換行分隔"
            field.error = message
            field.requestFocus()
            notice(message, Kind.WARN)
            return
        }
        field.error = null
        task("讀取已安裝 app…", {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), android.content.pm.PackageManager.ResolveInfoFlags.of(0))
                .take(AppPicker.MAX_APPS).map { AppPicker.App(it.activityInfo.packageName, RecordingRules.cap(it.loadLabel(packageManager).toString(), 80)) }
                .distinctBy { it.packageName }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        }) { apps ->
            AppPicker.show(this@MainActivity, apps, initial) { chosen ->
                field.setText(chosen.sorted().joinToString("\n"))
            }
            notice("選好後記得按「儲存清單」", Kind.INFO)
        }
    }

    // ---------------------------------------------------------------- AI settings

    private fun approveAi(after: () -> Unit) {
        if (!RecordingSettings(this@MainActivity).contextEnabled) { notice("模型處理只適用於畫面情境，請先開啟畫面文字", Kind.WARN); return }
        task("讀取模型設定…", { readCaptureAiConfig() }) { config ->
            if (!config.optBoolean("enabled")) { notice("請先設定你的 AI 端點與模型", Kind.WARN); return@task }
            val id = config.getString("provider_id")
            dialog().setTitle("同意這個模型處理未來情境？")
                .setMessage("${config.getString("endpoint")}\n模型：${config.getString("model")}\n\n未來擷取的允許清單文字，以及另有同意且模型支援的縮圖，將經過你的私人 PocketLog 伺服器送到此端點。請確認供應商與保存政策；API 使用費由你負擔。模型可能犯錯，沒有操作工具。更換設定後需重新同意；歷史未授權內容不會補送。已送出的請求無法撤回。")
                .setNegativeButton("取消", null).setPositiveButton("同意目前模型") { _, _ ->
                    task("記錄同意…", {
                        synchronized(SyncEngine.lock) {
                            require(RecordingSettings(this@MainActivity).contextEnabled)
                            val current = readCaptureAiConfig()
                            require(current.optBoolean("enabled") && current.getString("provider_id") == id)
                            RecordingSettings(this@MainActivity).apply { aiProviderId = id; externalAiConsent = true; recordCaptureAiState(CaptureAiConsent.State.CURRENT) }
                        }
                    }) { after(); notice("已同意目前模型處理未來情境") }
                }.showSecure()
        }
    }

    private fun aiSettings(back: () -> Unit) {
        val body = screen("Bring your own AI", "模型設定保存在私人伺服器", tab = null, back = back)
        ui.card(body).apply {
            ui.body(this, "使用自己的 OpenAI 相容、Anthropic Messages 或 Gemini generateContent API。可連接 OpenAI、OpenRouter，或經 HTTPS 提供的 Ollama／LM Studio。")
            ui.meta(this, "沒有預設供應商，也不會自動使用 infovore 的 AI。本機模型同樣由 PocketLog 伺服器連線，請填伺服器連得到的網址。", top = 6)
        }
        val slot = ui.column(body)
        load(slot, "讀取私人伺服器的 AI 設定…", { readCaptureAiConfig() }) { config -> renderAiForm(slot, config, back) }
    }

    private fun readCaptureAiConfig(): JSONObject = synchronized(SyncEngine.lock) {
        val config = try { api().request("/v1/ai") } catch (failure: Exception) {
            RecordingSettings(this@MainActivity).apply { if (hasExternalAiGrant) recordCaptureAiState(CaptureAiConsent.State.UNVERIFIED) }
            throw failure
        }
        CaptureAiConsent.refresh(this@MainActivity) { config }
        config
    }

    private fun renderAiForm(slot: LinearLayout, config: JSONObject, back: () -> Unit) {
        if (!config.optBoolean("available")) { ui.empty(slot, "伺服器尚未開放 app 內 AI 設定", "請先更新 PocketLog 伺服器。活動卡仍可正常使用。"); return }
        val enabled = config.optBoolean("enabled")
        ui.banner(slot, if (enabled) "已設定。${if (config.optBoolean("has_key")) "伺服器已保存金鑰，不會讀回。" else "目前沒有 API key。"} 是否可送日誌取決於每台裝置的同意。" else "尚未啟用 AI，活動卡仍可使用。", if (enabled) Kind.SUCCESS else Kind.INFO)
        val protocols = listOf("openai", "anthropic", "gemini")
        val form = ui.card(slot)
        ui.heading(form, "模型連線")
        val protocol = ui.spinner(form, "協定", listOf("OpenAI 相容", "Anthropic Messages", "Gemini generateContent"), protocols.indexOf(config.optString("protocol", "openai")).coerceAtLeast(0))
        val endpoint = ui.field(form, "端點", "https://your-provider.example/v1", Field.URL, config.optString("endpoint"), "OpenAI 相容填 base URL；原生協定填完整 API URL（Gemini 路徑需包含模型 ID）。網址不能含金鑰、帳密或查詢參數。")
        ui.actions(form, "填入 OpenAI" to Tone.TONAL, "填入 OpenRouter" to Tone.TONAL) { index ->
            protocol.setSelection(0); endpoint.setText(if (index == 0) "https://api.openai.com/v1" else "https://openrouter.ai/api/v1")
        }
        val model = ui.field(form, "模型 ID", "輸入供應商提供的完整模型 ID", Field.TEXT, config.optString("model"))
        val key = ui.field(form, "API key（可留白）", "相同端點及協定留白會保留金鑰", Field.SECRET, help = "變更端點或協定時不會沿用舊金鑰。不需要金鑰的本機服務也支援。")
        val forget = ui.checkbox(form, "這次儲存時清除現有 API key")
        val format = ui.spinner(form, "結構化輸出格式", listOf("JSON Schema（建議）", "JSON mode（相容模式）"), if (config.optString("response_format") == "json_object") 1 else 0)
        val images = ui.checkbox(form, "模型支援圖片輸入（擷取縮圖仍需另外同意）", config.optBoolean("image_support"))
        ui.meta(form, "金鑰只透過 HTTPS 傳到你的 PocketLog 伺服器，保存在受限制的設定檔，不會讀回手機或放入日誌。伺服器管理者可讀取金鑰。儲存只設定模型，不代表同意傳送日誌。", top = 8)
        ui.button(form, "儲存 AI 設定") {
            val request = runCatching {
                JSONObject().put("protocol", protocols[protocol.selectedItemPosition]).put("endpoint", AiEndpoint.normalize(endpoint.text.toString(), protocol.selectedItemPosition == 0))
                    .put("model", model.text.toString().trim().also { require(it.isNotBlank() && it.length <= 200) })
                    .put("response_format", if (format.selectedItemPosition == 0) "json_schema" else "json_object").put("image_support", images.isChecked)
                    .also { if (forget.isChecked || key.text.isNotBlank()) it.put("api_key", if (forget.isChecked) "" else key.text.toString().trim()) }
            }.getOrNull()
            if (request == null) { notice("請檢查端點與模型。網址需為 HTTPS，不能含金鑰、帳密或查詢參數。", Kind.WARN); return@button }
            dialog().setTitle("儲存到私人伺服器？")
                .setMessage("會更新所有連接此伺服器的裝置所使用的模型；各裝置必須重新同意，舊佇列不會轉送到新設定。金鑰將經 HTTPS 傳送並存於伺服器。")
                .setNegativeButton("取消", null).setPositiveButton("儲存") { _, _ ->
                    // Do not retain the editor contents across a network operation or Activity destruction.
                    key.text.clear()
                    task("儲存 AI 設定…", {
                        synchronized(SyncEngine.lock) {
                            try { api().request("/v1/ai", request, "PUT") } finally { request.remove("api_key") }
                            RecordingSettings(this@MainActivity).apply { externalAiConsent = false; aiProviderId = null }
                            JournalQueue(this@MainActivity).clearContexts()
                        }
                    }) { aiSettings(back); notice("已儲存。每台裝置需重新同意模型處理。") }
                }.showSecure()
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12) }
        ui.card(slot, padding = 8).apply {
            ui.row(this, "測試已儲存模型", "只送固定測試文字，不讀取或傳送日誌") {
                if (!enabled) { notice("請先儲存 AI 設定", Kind.WARN); return@row }
                if (!config.optBoolean("probe_provider_binding")) { notice("請先更新伺服器，才能確認測試目的地。尚未傳送測試文字。", Kind.WARN); return@row }
                val providerId = config.getString("provider_id")
                dialog().setTitle("測試已儲存模型？").setMessage("目的地：${config.getString("endpoint")}\n協定：${config.optString("protocol")}\n模型：${config.getString("model")}\n\n使用上次儲存的設定，不包含表單中尚未儲存的修改。僅發送固定測試文字，不會讀取或傳送日誌。供應商可能收取少量 API 費用。")
                    .setNegativeButton("取消", null).setPositiveButton("測試") { _, _ ->
                        task("測試模型…", { api().request("/v1/ai/test", JSONObject().put("provider_id", providerId)) }, failed = { error ->
                            if (error is ApiFailure && error.status == 409) dialog().setTitle("已儲存的模型已變更")
                                .setMessage("沒有改向新模型重試。重新載入設定後，請核對目的地並再次確認；重新載入會捨棄表單中尚未儲存的修改。")
                                .setNegativeButton("保留編輯", null).setPositiveButton("重新載入設定") { _, _ -> aiSettings(back) }.showSecure()
                        }) { notice("連線與摘要格式驗證成功；沒有傳送日誌。") }
                    }.showSecure()
            }
            ui.row(this, "同意這個模型處理未來情境", "需先開啟畫面文字；更換設定後要重新同意") { approveAi { aiSettings(back) } }
        }
        if (enabled) ui.button(slot, "停用並刪除伺服器 AI 金鑰", Tone.DANGER) {
            dialog().setTitle("停用所有裝置的 AI？").setMessage("刪除伺服器現有設定與金鑰，活動卡仍可使用。已送出的模型請求無法撤回；供應商副本、主機備份與已產生的摘要不會因此刪除。")
                .setNegativeButton("取消", null).setPositiveButton("停用並刪除") { _, _ ->
                    task("停用 AI…", {
                        synchronized(SyncEngine.lock) {
                            api().request("/v1/ai", method = "DELETE")
                            RecordingSettings(this@MainActivity).apply { externalAiConsent = false; aiProviderId = null }
                            JournalQueue(this@MainActivity).clearContexts()
                        }
                    }) { aiSettings(back); notice("已停用 AI 並刪除伺服器上的金鑰") }
                }.showSecure()
        }
    }

    // ---------------------------------------------------------------- shared goals, categories and rules

    private fun sharedSettings(retainedDraft: JSONObject? = null, retainedBaseline: JSONObject? = null) {
        val body = screen("目標與分類", "網站與手機共用", tab = Tab.SETTINGS, back = { settings() })
        val slot = ui.column(body)
        val render: (JSONObject) -> Unit = { response ->
            var current = response
            var baseline = JSONObject((retainedBaseline ?: response).toString())
            if (retainedDraft == null) workspace = current
            val card = ui.card(slot)
            ui.heading(card, "每日目標")
            val focus = ui.field(card, "每日專注目標（分鐘）", "270", Field.NUMBER, current.getInt("focus_minutes").toString())
            val budget = ui.field(card, "分心預算（分鐘）", "60", Field.NUMBER, current.getInt("distraction_minutes").toString(), "0–1440。用於時間軸與每日回顧的進度。")
            ui.button(card, "儲存目標") {
                val a = focus.text.toString().toIntOrNull(); val b = budget.text.toString().toIntOrNull()
                if (a == null || b == null || a !in 0..1440 || b !in 0..1440) { notice("分鐘必須介於 0–1440", Kind.WARN); return@button }
                val next = JSONObject(current.toString()).put("focus_minutes", a).put("distraction_minutes", b)
                saveGoals(next, baseline) { workspace = it; current = it; baseline = JSONObject(it.toString()); notice("網站與手機的共用目標已更新") }
            }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12) }
            val styles = current.getJSONArray("categories")
            ui.card(slot).apply {
                ui.heading(this, "分類")
                for (i in 0 until styles.length()) {
                    val c = styles.getJSONObject(i)
                    val row = ui.horizontal(this, 10).apply { minimumHeight = ui.dp(32) }
                    row.addView(ui.swatch(Palette.parse(c.optString("color")), 12))
                    ui.text(row, c.getString("name"), 15f).layoutParams = ui.lp(0, weight = 1f)
                    ui.text(row, when (c.optString("role")) { "focus" -> "專注"; "distraction" -> "分心"; else -> "一般" }, 13f, Palette.MUTED).layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                ui.button(this, "編輯分類與自動規則", Tone.TONAL) { categoryRulesEditor(JSONObject(current.toString())) }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12) }
            }
        }
        if (retainedDraft != null) render(retainedDraft)
        else load(slot, "讀取共用設定…", { api().request("/v1/workspace") }, render)
    }

    private fun saveGoals(draft: JSONObject, baseline: JSONObject, saved: (JSONObject) -> Unit = {
        workspace = it; sharedSettings(); notice("網站與手機的共用目標已更新")
    }) {
        val payload = JSONObject(draft.toString())
        task("儲存共用設定…", { api().request("/v1/workspace", payload, "PUT") }, failed = { error ->
            if (error is ApiFailure && error.status == 409) goalRecovery(draft, baseline)
        }, done = saved)
    }

    private fun goalRecovery(draft: JSONObject, baseline: JSONObject) {
        val body = screen("目標草稿已保留", "尚未重試儲存", tab = Tab.SETTINGS, back = { sharedSettings(draft, baseline) })
        ui.banner(body, "專注目標與分心預算仍保留在手機的這個編輯流程中。核對最新設定後再決定；不用放棄草稿或重新登入。離開這個流程或關閉 app 前，請先完成儲存。")
        ui.button(body, "返回編輯目標", Tone.OUTLINE) { sharedSettings(draft, baseline) }
        val slot = ui.column(body)
        load(slot, "核對最新設定…", { api().request("/v1/workspace") }) { latest ->
            val card = ui.card(slot)
            ui.body(card, "你的草稿：專注目標 ${draft.getInt("focus_minutes")} 分鐘、分心預算 ${draft.getInt("distraction_minutes")} 分鐘。")
            if (!GoalRecovery.goalsChanged(baseline, latest)) {
                ui.heading(card, "分類設定已更新，目標草稿仍在")
                ui.body(card, "遠端目標未變。重試時保留剛讀取的最新分類、規則及其他設定，只儲存你的目標草稿。")
                ui.button(card, "保留最新分類，重試儲存目標") { saveGoals(GoalRecovery.candidate(draft, latest), baseline) }
            } else {
                ui.heading(card, "遠端目標也已變更")
                ui.body(card, "剛讀取的遠端目標：專注 ${latest.getInt("focus_minutes")} 分鐘、分心預算 ${latest.getInt("distraction_minutes")} 分鐘。你的草稿尚未覆蓋遠端版本。")
                ui.button(card, "確認以我的草稿取代遠端目標", Tone.DANGER) {
                    dialog().setTitle("取代剛讀取的遠端目標？")
                        .setMessage("會以你的專注目標 ${draft.getInt("focus_minutes")} 分鐘、分心預算 ${draft.getInt("distraction_minutes")} 分鐘取代剛讀取的遠端目標。保留最新分類、規則及其他設定。如果遠端再更新，會重新核對。")
                        .setNegativeButton("保留草稿", null)
                        .setPositiveButton("取代目標") { _, _ -> saveGoals(GoalRecovery.candidate(draft, latest), baseline) }.showSecure()
                }
            }
            ui.button(slot, "重新核對最新設定", Tone.OUTLINE) { goalRecovery(draft, baseline) }
        }
    }

    private fun categoryRulesEditor(draft: JSONObject) = categoryRulesEditor(draft, JSONObject(draft.toString()))

    private fun categoryRulesEditor(draft: JSONObject, baseline: JSONObject) {
        val body = screen("分類與自動規則", "儲存後同步到網站", tab = Tab.SETTINGS, back = {
            dialog().setTitle("放棄未儲存的修改？").setNegativeButton("繼續編輯", null).setPositiveButton("放棄") { _, _ -> sharedSettings() }.show()
        })
        ui.banner(body, "由上到下套用第一個符合的規則；app 與關鍵字同時指定時必須都符合。手動修改永遠優先。分類被活動使用時不能移除。")
        val styles = draft.getJSONArray("categories")
        val rules = draft.optJSONArray("rules") ?: JSONArray().also { draft.put("rules", it) }
        val capture = mutableListOf<() -> Unit>()
        fun remember() = capture.forEach { it() }
        ui.button(body, "試算規則（不儲存）", Tone.TONAL) {
            remember()
            rulePreview(JSONObject(draft.toString())) { categoryRulesEditor(draft, baseline) }
        }
        val roles = listOf("focus", "neutral", "distraction")
        ui.groupLabel(body, "分類")
        for (i in 0 until styles.length()) {
            val c = styles.getJSONObject(i)
            val card = ui.card(body)
            val top = ui.horizontal(card, 10)
            val swatch = ui.swatch(Palette.parse(c.optString("color")), 16).also { top.addView(it) }
            ui.meta(top, c.getString("id")).layoutParams = ui.lp(0, weight = 1f)
            val name = ui.field(card, "名稱", "最多 40 字", Field.TEXT, c.getString("name"))
            val color = ui.field(card, "顏色", "#RRGGBB", Field.TEXT, c.getString("color"))
            color.addTextChangedListener(watcher { value -> swatch.background = ui.shape(Palette.parse(value), 8f) })
            val role = ui.spinner(card, "角色", listOf("專注", "一般", "分心"), roles.indexOf(c.getString("role")).coerceAtLeast(0))
            capture.add { c.put("name", name.text.toString().trim()).put("color", color.text.toString().trim()).put("role", roles[role.selectedItemPosition]) }
            if (c.getString("id").startsWith("custom_")) ui.button(card, "移除此分類", Tone.TEXT) {
                remember(); styles.remove(i)
                for (j in rules.length() - 1 downTo 0) if (rules.getJSONObject(j).getString("category") == c.getString("id")) rules.remove(j)
                categoryRulesEditor(draft, baseline)
            }
        }
        ui.button(body, "新增分類", Tone.TONAL) {
            if (styles.length() >= 40) { notice("最多 40 個分類", Kind.WARN); return@button }
            remember(); styles.put(JSONObject().put("id", "custom_" + UUID.randomUUID().toString().replace("-", "")).put("name", "新分類").put("color", "#9bbce1").put("role", "neutral")); categoryRulesEditor(draft, baseline)
        }
        ui.groupLabel(body, "自動分類規則")
        val ids = (0 until styles.length()).map { styles.getJSONObject(it).getString("id") }
        if (rules.length() == 0) ui.meta(body, "還沒有規則。沒有符合規則的活動會使用預設分類。", bottom = 8)
        for (i in 0 until rules.length()) {
            val r = rules.getJSONObject(i)
            val card = ui.card(body)
            val enabled = ui.checkbox(card, "規則 ${i + 1} 啟用", r.optBoolean("enabled", true))
            val app = ui.field(card, "完整 app 套件名稱", "留白不限", Field.TEXT, r.optString("app").takeUnless { it == "null" } ?: "")
            val keyword = ui.field(card, "標題包含", "留白不限", Field.TEXT, r.optString("title_contains").takeUnless { it == "null" } ?: "")
            val category = ui.spinner(card, "套用分類", (0 until styles.length()).map { styles.getJSONObject(it).getString("name") }, ids.indexOf(r.getString("category")).coerceAtLeast(0))
            capture.add { r.put("enabled", enabled.isChecked).put("app", app.text.toString().trim().ifBlank { null } ?: JSONObject.NULL).put("title_contains", keyword.text.toString().trim().ifBlank { null } ?: JSONObject.NULL).put("category", ids[category.selectedItemPosition]) }
            val labels = if (i > 0) arrayOf("上移" to Tone.OUTLINE, "刪除規則" to Tone.DANGER) else arrayOf("刪除規則" to Tone.DANGER)
            ui.actions(card, *labels) { index ->
                remember()
                if (i > 0 && index == 0) { val previous = rules.getJSONObject(i - 1); rules.put(i - 1, r); rules.put(i, previous) } else rules.remove(i)
                categoryRulesEditor(draft, baseline)
            }
        }
        ui.button(body, "新增規則", Tone.TONAL) {
            if (rules.length() >= 32) { notice("最多 32 條規則", Kind.WARN); return@button }
            remember(); rules.put(JSONObject().put("id", UUID.randomUUID().toString()).put("category", ids.first()).put("app", JSONObject.NULL).put("title_contains", JSONObject.NULL).put("enabled", true)); categoryRulesEditor(draft, baseline)
        }
        ui.actions(body, "放棄修改" to Tone.OUTLINE, "儲存分類與規則" to Tone.PRIMARY) { index ->
            if (index == 0) { sharedSettings(); return@actions }
            remember()
            saveClassification(draft, baseline)
        }
    }

    private fun saveClassification(draft: JSONObject, baseline: JSONObject) {
        val payload = JSONObject(draft.toString())
        task("儲存分類與規則…", { api().request("/v1/workspace", payload, "PUT") }, failed = { error ->
            if (error is ApiFailure && error.status == 409) classificationRecovery(draft, baseline, payload.getLong("revision"))
        }) { workspace = it; sharedSettings(); notice("已儲存並同步到網站") }
    }

    private fun classificationRecovery(draft: JSONObject, baseline: JSONObject, attemptedRevision: Long) {
        val body = screen("分類草稿已保留", "尚未重試儲存", tab = Tab.SETTINGS, back = { categoryRulesEditor(draft, baseline) })
        ui.banner(body, "分類與規則草稿仍保留在手機的這個編輯流程中。核對最新設定後再決定；不用放棄草稿或重新登入。離開這個流程或關閉 app 前，請先完成儲存。")
        ui.button(body, "返回編輯草稿", Tone.OUTLINE) { categoryRulesEditor(draft, baseline) }
        val slot = ui.column(body)
        load(slot, "核對最新設定…", { api().request("/v1/workspace") }) { latest ->
            val card = ui.card(slot)
            when (ClassificationRecovery.kind(baseline, latest, attemptedRevision)) {
                ClassificationRecovery.Kind.USED -> {
                    ui.heading(card, "分類仍被活動使用")
                    ui.body(card, "這次不是設定版本衝突。移除的分類仍被已儲存活動使用；請還原分類或先重新分類。其他草稿修改已保留。")
                    val categories = draft.getJSONArray("categories")
                    val existing = (0 until categories.length()).map { categories.getJSONObject(it).getString("id") }.toSet()
                    val remote = latest.getJSONArray("categories")
                    val removed = (0 until remote.length()).map { remote.getJSONObject(it) }.filter { it.getString("id") !in existing }
                    if (removed.isNotEmpty()) ui.button(card, "還原移除的分類，保留其他草稿", Tone.TONAL) {
                        if (categories.length() + removed.size > 40) { notice("還原後超過 40 個分類，請先移除未使用的新分類。", Kind.WARN); return@button }
                        removed.forEach { categories.put(JSONObject(it.toString())) }
                        categoryRulesEditor(draft, baseline)
                        notice("已還原分類；其他修改保留，請檢查規則後再儲存。", Kind.INFO)
                    }
                }
                ClassificationRecovery.Kind.OTHER -> {
                    ui.heading(card, "其他設定已更新，分類草稿仍在")
                    ui.body(card, "遠端分類與規則未變。保留最新專注目標 ${latest.getInt("focus_minutes")} 分鐘、分心預算 ${latest.getInt("distraction_minutes")} 分鐘，再儲存你的分類草稿。")
                    ui.button(card, "保留最新目標，重試儲存分類") { saveClassification(ClassificationRecovery.candidate(draft, latest), baseline) }
                }
                ClassificationRecovery.Kind.CLASSIFICATION -> {
                    ui.heading(card, "遠端分類與規則也已變更")
                    ui.body(card, "你的草稿尚未覆蓋遠端版本。比較後可返回編輯，或明確取代剛讀取的遠端分類與規則；其他設定保留最新值。")
                    val remote = latest.getJSONArray("categories")
                    ui.groupLabel(card, "剛讀取的遠端分類")
                    for (i in 0 until remote.length()) {
                        val c = remote.getJSONObject(i)
                        val role = when (c.getString("role")) { "focus" -> "專注"; "distraction" -> "分心"; else -> "一般" }
                        ui.body(card, "${c.getString("name")} · $role · ${c.getString("color")}")
                    }
                    val rules = latest.optJSONArray("rules") ?: JSONArray()
                    ui.groupLabel(card, "剛讀取的遠端規則")
                    if (rules.length() == 0) ui.meta(card, "沒有規則。")
                    for (i in 0 until rules.length()) {
                        val r = rules.getJSONObject(i)
                        val target = (0 until remote.length()).map { remote.getJSONObject(it) }.firstOrNull { it.getString("id") == r.getString("category") }?.getString("name") ?: r.getString("category")
                        ui.body(card, "${i + 1}. ${if (r.getBoolean("enabled")) "啟用" else "停用"} · ${r.optString("app").takeUnless { it == "null" || it.isBlank() } ?: "所有 app"} · ${r.optString("title_contains").takeUnless { it == "null" || it.isBlank() } ?: "所有標題"} → $target")
                    }
                    ui.button(card, "確認以我的草稿取代遠端分類", Tone.DANGER) {
                        dialog().setTitle("取代剛讀取的遠端分類與規則？")
                            .setMessage("會用草稿的全部分類與規則取代剛讀取的遠端版本。保留專注目標 ${latest.getInt("focus_minutes")} 分鐘、分心預算 ${latest.getInt("distraction_minutes")} 分鐘及其他設定。不會刪除活動；使用中的分類仍不能移除。如果遠端再更新，會重新核對。")
                            .setNegativeButton("保留草稿", null)
                            .setPositiveButton("取代分類與規則") { _, _ -> saveClassification(ClassificationRecovery.candidate(draft, latest), baseline) }.showSecure()
                    }
                }
            }
            ui.button(slot, "重新核對最新設定", Tone.OUTLINE) { classificationRecovery(draft, baseline, attemptedRevision) }
        }
    }

    private fun rulePreview(draft: JSONObject, back: () -> Unit) {
        val body = screen("試算分類規則", "先確認結果，再返回儲存", tab = Tab.SETTINGS, back = back)
        val token = generation
        ui.banner(body, "使用目前尚未儲存的規則試算範例。不會加入日誌、儲存設定或呼叫 AI；手動修改的活動永遠保留原分類。")
        val styles = draft.getJSONArray("categories")
        val ids = (0 until styles.length()).map { styles.getJSONObject(it).getString("id") }
        val form = ui.card(body)
        val app = ui.field(form, "範例 app 套件名稱", "例如 org.mozilla.firefox", Field.TEXT, help = "需與規則完全相同，區分大小寫。")
        val title = ui.field(form, "範例活動標題", "例如 閱讀 Kotlin 文件", Field.TEXT, help = "標題關鍵字忽略大小寫；可留白。")
        app.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        app.filters = arrayOf(android.text.InputFilter.LengthFilter(200))
        title.filters = arrayOf(android.text.InputFilter.LengthFilter(200))
        val baseline = ui.spinner(form, "沒有命中規則時的分類", (0 until styles.length()).map { styles.getJSONObject(it).getString("name") }, ids.indexOf("unsorted").coerceAtLeast(0))
        val result = ui.body(form, "", top = 12).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        var epoch = 0
        fun invalidate() {
            epoch++
            if (result.text.isNotEmpty()) result.text = "範例已變更，請重新試算。"
        }
        app.addTextChangedListener(watcher { app.error = null; invalidate() })
        title.addTextChangedListener(watcher { title.error = null; invalidate() })
        baseline.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = invalidate()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = invalidate()
        }
        ui.button(form, "試算規則", Tone.PRIMARY) {
            if (busy) { notice("上一個試算尚在處理中", Kind.INFO); return@button }
            val sampleApp = app.text.toString().trim()
            val sampleTitle = title.text.toString()
            if (!Regex("[A-Za-z0-9._-]{1,200}").matches(sampleApp)) {
                app.error = "請填完整 app 套件名稱，只含英數字、點、底線或連字號。"; app.requestFocus(); return@button
            }
            if (sampleTitle.any { it.isISOControl() }) { title.error = "標題不能包含控制字元。"; title.requestFocus(); return@button }
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(app.windowToken, 0)
            val request = JSONObject().put("workspace", JSONObject(draft.toString())).put("sample", JSONObject()
                .put("app", sampleApp).put("title", sampleTitle).put("category", ids[baseline.selectedItemPosition]).put("edited", false))
            val revision = ++epoch
            result.text = "正在試算目前規則…"
            task("試算規則…", { api().request("/v1/workspace/preview", request, "POST") }, failed = { error ->
                if (token == generation && revision == epoch) result.text = when {
                    error is ApiFailure && error.status == 404 -> "伺服器尚未支援規則試算，請更新伺服器。分類仍可照常儲存。"
                    error is ApiFailure && error.status == 400 -> "規則或範例尚未完整，請檢查 app、關鍵字與分類後重試。"
                    else -> "試算未完成；請檢查連線後重新試算。"
                }
            }) { response ->
                if (token != generation || revision != epoch) return@task
                val category = response.getString("category")
                val name = (0 until styles.length()).map { styles.getJSONObject(it) }.firstOrNull { it.getString("id") == category }?.getString("name") ?: category
                val rules = draft.optJSONArray("rules") ?: JSONArray()
                val matched = response.optString("matched_rule_id")
                val index = (0 until rules.length()).firstOrNull { rules.getJSONObject(it).getString("id") == matched }
                val why = index?.let { "命中第 ${it + 1} 條規則；後面的規則不再套用。" } ?: "沒有命中已啟用的規則，使用範例原分類。"
                result.text = "範例將分類為「$name」\n$why\n這是尚未儲存的試算；請返回編輯頁儲存，才會同步設定。"
                result.post { if (token == generation && revision == epoch) result.requestRectangleOnScreen(android.graphics.Rect(0, 0, result.width, result.height), false) }
            }
        }
    }

    // ---------------------------------------------------------------- replay

    private fun replayPage() {
        val body = screen("短期縮圖回放", dateTitle(day), tab = Tab.TIMELINE, back = { home() })
        ui.banner(body, "只有另行同意保存的縮圖可回放，最長 24 小時、256 張；只供分析的原始畫面不可讀回。沒有 OCR 自動遮罩。")
        val (from, to) = range(day)
        val slot = ui.column(body)
        load(slot, "讀取縮圖清單…", { api().request("/v1/replay?from_ms=$from&to_ms=$to") }) { data ->
            val images = data.getJSONArray("images")
            if (images.length() == 0) {
                val consent = RecordingSettings(this@MainActivity).replayConsent
                ui.empty(slot, "沒有可回放的縮圖", if (consent) "這天沒有仍在 24 小時期限內的已同意縮圖。" else "短期回放需要在設定中另行同意，預設關閉。", if (consent) null else "前往設定") { settings() }
                return@load
            }
            val card = ui.card(slot, padding = 8)
            for (i in 0 until images.length()) {
                val item = images.getJSONObject(i)
                ui.row(card, item.getString("app"), stamp(item.getLong("at_ms"))) {
                    task("解碼縮圖…", work = {
                        val response = api().request("/v1/replay/${item.getString("id")}")
                        val bytes = android.util.Base64.decode(response.getString("jpeg_base64"), android.util.Base64.DEFAULT)
                        try {
                            require(bytes.size <= 24576)
                            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            require(bounds.outWidth in 1..480 && bounds.outHeight in 1..480)
                            requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                        } finally { bytes.fill(0) }
                    }, failed = { replayUnavailable(it) }, discard = { it.recycle() }) { bitmap ->
                        showReplayBitmap(bitmap, item)
                    }
                }
            }
        }
    }

    /** A missing image is normal under expiry or deletion by another client. */
    private fun replayUnavailable(error: Throwable) {
        if (error is ApiFailure && error.status == 404) {
            replayPage()
            notice("這張縮圖已到期或已被刪除；已重新讀取清單。", Kind.WARN)
        }
    }

    private fun showReplayBitmap(bitmap: android.graphics.Bitmap, item: JSONObject) {
        replayDialog?.dismiss()
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            contentDescription = "${item.getString("app")} ${stamp(item.getLong("at_ms"))} 的已核准縮圖"
        }
        var confirmation: AlertDialog? = null
        val preview = dialog().setTitle("已核准的短期縮圖").setView(image)
            .setNegativeButton("關閉", null).setPositiveButton("刪除", null).create()
        preview.setOnDismissListener {
            confirmation?.dismiss(); confirmation = null
            image.setImageDrawable(null)
            if (!bitmap.isRecycled) bitmap.recycle()
            if (replayDialog === preview) replayDialog = null
        }
        try {
            preview.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            replayDialog = preview
            preview.show()
            preview.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (confirmation?.isShowing == true || busy) return@setOnClickListener
                val confirm = dialog().setTitle("永久刪除這張回放縮圖？")
                    .setMessage("${item.getString("app")}\n${stamp(item.getLong("at_ms"))}\n\n只刪除這張已保存的回放縮圖，無法復原。活動紀錄、備份及已送往 AI 供應商的資料不會因此刪除。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("刪除縮圖") { _, _ ->
                        if (replayDialog === preview && preview.isShowing && !isDestroyed && !isFinishing) {
                            preview.dismiss()
                            task("刪除縮圖…", { api().request("/v1/replay/${item.getString("id")}", method = "DELETE") }, failed = { replayUnavailable(it) }) {
                                replayPage(); notice("已刪除縮圖")
                            }
                        }
                    }.create()
                confirmation = confirm
                confirm.setOnDismissListener {
                    if (confirmation === confirm) confirmation = null
                    if (preview.isShowing) preview.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
                }
                confirm.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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

    // ---------------------------------------------------------------- document picker results

    @Deprecated("Legacy result API for the platform Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 22) {
            if (resultCode != RESULT_OK || data?.data == null) return
            val uri = requireNotNull(data.data); val source = agentImportSource
            task("在手機檢查所選檔案…", {
                val bytes = contentResolver.openInputStream(uri).use { requireNotNull(it).readNBytes(524289) }
                try { AgentImport.parse(bytes, source) } finally { bytes.fill(0) }
            }, failed = { notice("無法解析檔案：請選擇 Codex／Claude 原生 JSONL（最多 512 KB、2000 行）。", Kind.ERROR) }) { agentImportPreview(it, source) }
            return
        }
        if (requestCode == 21) {
            if (resultCode != RESULT_OK || data?.data == null) return
            val uri = requireNotNull(data.data)
            task("檢查工作摘要…", {
                val bytes = contentResolver.openInputStream(uri).use { requireNotNull(it).readNBytes(48001) }
                try {
                    require(bytes.size <= 48000)
                    JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("sessions").also { rows ->
                        require(rows.length() <= 20)
                        for (i in 0 until rows.length()) { val r = rows.getJSONObject(i); require(r.getString("source") in listOf("codex", "claude")); require(r.getString("text").length <= 4000); require(r.getString("id").length in 1..80); r.getLong("at_ms") }
                    }
                } finally { bytes.fill(0) }
            }, failed = { notice("檔案格式不符：請參考頁面上的 JSON 格式（最多 20 筆、48 KB）。", Kind.ERROR) }) { rows ->
                dialog().setTitle("匯入 ${rows.length()} 筆私人工作摘要？").setMessage("只會匯入你選取的內容；請確認不含金鑰、私密對話或不想保存的資料。")
                    .setNegativeButton("取消", null).setPositiveButton("匯入") { _, _ ->
                        task("匯入中…", {
                            for (i in 0 until rows.length()) { val r = rows.getJSONObject(i); api().request("/v1/reviews", recordBody(r.getString("id"), r.getLong("at_ms"), "agent", r.getString("text")).put("source", r.getString("source"))) }
                        }) { agentsPage(); notice("已匯入 ${rows.length()} 筆工作摘要") }
                    }.showSecure()
            }
            return
        }
        if (requestCode != 20) return
        val bytes = exportBytes ?: return
        exportBytes = null
        if (resultCode != RESULT_OK || data?.data == null) { bytes.fill(0); return }
        task("匯出中…", { try { contentResolver.openOutputStream(requireNotNull(data.data), "wt").use { requireNotNull(it).write(bytes) } } finally { bytes.fill(0) } }) { notice("已匯出私人 JSON。請妥善保管檔案。") }
    }

    override fun onDestroy() {
        replayDialog?.dismiss(); replayDialog = null
        handler.removeCallbacksAndMessages(null)
        if (backRegistered) onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        executor.shutdown(); exportBytes?.fill(0); if (!isChangingConfigurations) clearDrafts(); pendingAsk = null; credentials = null
        super.onDestroy()
    }
}

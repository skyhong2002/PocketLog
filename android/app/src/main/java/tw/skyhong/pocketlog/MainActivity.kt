package tw.skyhong.pocketlog

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private val executor = Executors.newSingleThreadExecutor()
    private var busy = false
    private var credentials: Credentials? = null
    private var workspace: JSONObject? = null
    private var day = journalDay()
    private var loaded: JSONObject? = null
    private var reflectionInput: EditText? = null
    private var exportBytes: ByteArray? = null
    private val baseCategories = listOf("unsorted", "work", "learning", "communication", "social_media", "entertainment", "news_reading", "life_admin", "rest", "idle")
    private val categories: List<String> get() = workspace?.optJSONArray("categories")?.let { styles -> (0 until styles.length()).map { styles.getJSONObject(it).getString("id") } } ?: baseCategories
    private var agentImportSource = "codex"
    private var pendingAsk: JSONObject? = null
    private var pendingAskWeekly = false
    private var chatWeekly = false
    private var conversationId = UUID.randomUUID().toString()
    private val categoryLabels = listOf("未分類", "工作", "學習", "溝通", "社群", "娛樂", "新聞／閱讀", "生活事務", "休息", "閒置")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 40, 32, 32) }
        setContentView(ScrollView(this).apply { addView(root) })
        val status = text("開啟私人日誌…")
        // Leave a recovery path visible if Keystore/credential loading fails.
        button("重設本機資料與連線") {
            AlertDialog.Builder(this).setTitle("重設本機資料？")
                .setMessage("用於金鑰失效或連線資料損毀。會停止紀錄、刪除未同步資料與快取，然後重新設定連線。伺服器日誌不會被刪除。")
                .setNegativeButton("取消", null).setPositiveButton("清除並重新設定") { _, _ ->
                    task(status, { synchronized(SyncEngine.lock) { SyncEngine.clearLocal(this); CredentialStore(this).clear() } }) { credentials = null; setup() }
                }.show()
        }
        task(status, { synchronized(SyncEngine.lock) { CredentialStore(this).load() } }) {
            credentials = it
            if (it == null) setup() else { SyncEngine.schedule(this); enterWorkspace() }
        }
    }
    private fun journalDay(): LocalDate = java.time.ZonedDateTime.now().let { if (it.hour < 4) it.toLocalDate().minusDays(1) else it.toLocalDate() }
    private fun text(value: String, size: Float = 16f) = TextView(this).apply { text = value; textSize = size; setPadding(0, 12, 0, 12); root.addView(this) }
    private fun button(value: String, action: () -> Unit) = Button(this).apply { text = value; setOnClickListener { if (!busy) action() }; root.addView(this) }
    private fun input(hint: String, secret: Boolean = false) = EditText(this).apply {
        this.hint = hint; isSaveEnabled = false
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        root.addView(this)
    }
    private fun spinner(values: List<String>, selected: Int = 0) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values)
        setSelection(selected); root.addView(this)
    }
    private fun setup() {
        root.removeAllViews(); text("PocketLog", 30f); text("回看一天，理解自己的節奏。")
        text("連接自己的私人伺服器。可先在網站「裝置金鑰與撤銷」建立日誌權限金鑰，每台手機獨立撤銷。活動紀錄與畫面文字需要另外同意，預設關閉。")
        val endpoint = input("https://your-server.example")
        val token = input("裝置金鑰（建議）或 owner token", true)
        val status = text("")
        button("驗證並儲存連線") {
            val candidate = runCatching {
                Credentials(EndpointPolicy.normalize(endpoint.text.toString()), token.text.toString().trim()).also { require(EndpointPolicy.validToken(it.token)) }
            }.getOrNull()
            if (candidate == null) { status.text = "請檢查 HTTPS 網址與 64 位十六進位金鑰"; return@button }
            task(status, {
                synchronized(SyncEngine.lock) {
                    PocketLogApi(candidate).request(dayPath())
                    CredentialStore(this).save(candidate)
                    UsageCollector(this).reset()
                }
            }) { credentials = candidate; token.text.clear(); SyncEngine.schedule(this); enterWorkspace() }
        }
    }
    private fun dayPath(selected: LocalDate = day): String {
        val zone = ZoneId.systemDefault()
        val from = selected.atTime(4, 0).atZone(zone).toInstant().toEpochMilli()
        val to = minOf(selected.plusDays(1).atTime(4, 0).atZone(zone).toInstant().toEpochMilli(), System.currentTimeMillis())
        return "/v1/timeline?from_ms=$from&to_ms=$to"
    }
    private fun home() {
        loaded = null
        root.removeAllViews(); text("PocketLog", 30f); text("$day · 私人時間軸", 23f)
        text("一天從凌晨 4 點開始。伺服器保留 30 天；待傳資料加密保留最多 24 小時。", 13f)
        val navigation = HorizontalScrollView(this)
        val links = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        navigation.addView(links); root.addView(navigation)
        listOf<Pair<String, () -> Unit>>(
            "縮圖回放" to { replayPage() }, "每日" to { dailyReview() }, "每週" to { weeklyTimeline() }, "Chat" to { chatPage() },
            "Flow" to { flowPage() }, "Agents" to { agentsPage() }, "目標" to { sharedSettings() },
            "設定" to { settings() }, "支援" to { supportPage() }
        ).forEach { (name, action) -> links.addView(Button(this).apply { text = name; setOnClickListener { if (!busy) action() } }) }
        val status = text(RecordingSettings(this).status())
        button("選擇日期") { DatePickerDialog(this, { _, y, m, d ->
            val selected = LocalDate.of(y, m + 1, d)
            if (selected <= journalDay() && selected >= journalDay().minusDays(28)) { day = selected; home() }
            else status.text = "請選最近 29 天內的日期"
        }, day.year, day.monthValue - 1, day.dayOfMonth).show() }
        text("活動時間軸", 22f)
        cardContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; root.addView(this) }
        button("同步並重新整理") { task(status, { SyncEngine.sync(this) }) { refreshTimeline(status) } }
        button("讀取時間軸／離線快取") { refreshTimeline(status) }
        button("活動概覽：本週／最近 29 天") {
            AlertDialog.Builder(this).setTitle("活動概覽").setItems(arrayOf("所選日期的本週", "最近 29 天")) { _, choice ->
                val earliest = journalDay().minusDays(28)
                val start = if (choice == 0) maxOf(earliest, day.minusDays((day.dayOfWeek.value - 1).toLong())) else earliest
                val end = if (choice == 0) day.plusDays((7 - day.dayOfWeek.value).toLong()).plusDays(1) else journalDay().plusDays(1)
                val zone = ZoneId.systemDefault()
                val from = start.atTime(4, 0).atZone(zone).toInstant().toEpochMilli()
                val to = minOf(end.atTime(4, 0).atZone(zone).toInstant().toEpochMilli(), System.currentTimeMillis())
                task(status, { SyncEngine.readTimeline(this, "/v1/overview?from_ms=$from&to_ms=$to") }) { data ->
                    val categories = data.getJSONArray("categories")
                    val apps = data.getJSONArray("apps")
                    val keywords = data.getJSONArray("title_keywords")
                    val result = (0 until categories.length()).joinToString("\n") { i -> val item = categories.getJSONObject(i); "${label(item.getString("category"))}：${item.getLong("duration_ms") / 60000} 分鐘" }.ifBlank { "尚無活動" } +
                        "\n\n常用 app\n" + (0 until apps.length()).joinToString("\n") { i -> val item = apps.getJSONObject(i); "${item.getString("app")}：${item.getLong("duration_ms") / 60000} 分鐘" } +
                        "\n\n標題關鍵字（最近 1000 張卡片）\n" + (0 until keywords.length()).joinToString("、") { keywords.getJSONArray(it).getString(0) } +
                        "\n\n感受紀錄：${data.getInt("checkin_count")} 筆（只統計你填寫的感受）" +
                        if (data.optBoolean("offline")) "\n離線快取，尚未包含最新資料。" else ""
                    AlertDialog.Builder(this).setTitle("$start 起的活動概覽").setMessage(result).setPositiveButton("關閉", null).show()
                }
            }.show()
        }
        button("紀錄、排除與同步設定") { settings() }
        button("匯出已載入這一天（私人 JSON）") {
            val data = loaded
            if (data == null) { status.text = "請先載入時間軸"; return@button }
            AlertDialog.Builder(this).setTitle("匯出私人日誌").setMessage("檔案含活動、感受與反思。請選擇你信任的儲存位置；匯出檔不受 app 加密與刪除保護。" )
                .setNegativeButton("取消", null).setPositiveButton("選擇儲存位置") { _, _ ->
                    exportBytes?.fill(0); exportBytes = data.toString(2).toByteArray()
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type = "application/json"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE, "pocketlog-$day.json") }, 20)
                }.show()
        }
        text("現在感覺如何？", 22f); text("1 = 很低，5 = 很高。系統不會猜測你的感受。")
        text("心情"); val mood = spinner(listOf("1", "2", "3", "4", "5"), 2)
        text("精神"); val energy = spinner(listOf("1", "2", "3", "4", "5"), 2)
        button("記下此刻（可離線）") {
            val body = JSONObject().put("id", UUID.randomUUID().toString()).put("at_ms", System.currentTimeMillis()).put("mood", mood.selectedItemPosition + 1).put("energy", energy.selectedItemPosition + 1)
            task(status, { SyncEngine.enqueue(this, "/v1/checkins", body) }) { status.text = "已加密儲存，等待同步。" }
        }
        text("每日反思", 22f)
        text("載入時間軸後可編輯既有反思。留白儲存會刪除這一天的反思。", 13f)
        val reflection = input("今天有什麼值得記下？哪件事讓你恢復精神？")
        reflectionInput = reflection
        button("儲存這一天的反思") {
            val at = minOf(day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), System.currentTimeMillis())
            val body = JSONObject().put("id", "reflection-$day").put("at_ms", at).put("text", RecordingRules.cap(reflection.text.toString(), 2000))
            task(status, { SyncEngine.enqueue(this, "/v1/reflections", body) }) { status.text = "反思已加密加入佇列；留白儲存會刪除這天的反思。" }
        }
        button("清除本機資料與連線") {
            AlertDialog.Builder(this).setTitle("清除本機資料？").setMessage("會停止紀錄並刪除尚未傳送的資料與快取。伺服器資料仍保留。")
                .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ -> task(status, { synchronized(SyncEngine.lock) { SyncEngine.clearLocal(this); CredentialStore(this).clear() } }) { credentials = null; setup() } }.show()
        }
        refreshTimeline(status)
        button("刪除伺服器全部日誌") {
            AlertDialog.Builder(this).setTitle("永久刪除所有日誌？").setMessage("刪除活動、感受、反思與原始文字，同時停止紀錄並清除本機佇列與快取。無法復原。")
                .setNegativeButton("取消", null).setPositiveButton("刪除全部") { _, _ ->
                    task(status, { synchronized(SyncEngine.lock) { SyncEngine.clearLocal(this); PocketLogApi(requireNotNull(credentials)).request("/v1/journal", method = "DELETE") } }) { home() }
                }.show()
        }
    }
    private fun refreshTimeline(status: TextView) {
        task(status, {
            val latest = runCatching { PocketLogApi(requireNotNull(credentials)).request("/v1/workspace") }.getOrNull()
            latest to SyncEngine.readTimeline(this, dayPath())
        }) { (latest, data) -> if (latest != null) workspace = latest; render(data) }
    }
    private lateinit var cardContainer: LinearLayout
    private fun label(category: String): String {
        val styles = workspace?.optJSONArray("categories")
        if (styles != null) for (i in 0 until styles.length()) if (styles.getJSONObject(i).optString("id") == category) return styles.getJSONObject(i).getString("name")
        return categoryLabels.getOrElse(baseCategories.indexOf(category)) { category }
    }
    private fun render(data: JSONObject) {
        loaded = data
        cardContainer.removeAllViews()
        fun line(value: String) { cardContainer.addView(TextView(this).apply { text = value; setPadding(0, 16, 0, 12) }) }
        if (data.optBoolean("offline")) line("離線快取：尚未包含最新修改。")
        if (data.optBoolean("truncated")) line("這天資料已達顯示上限，統計可能不完整。")
        val cards = data.getJSONArray("cards")
        val totals = mutableMapOf<String, Long>()
        val format = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        for (i in 0 until cards.length()) {
            val c = cards.getJSONObject(i)
            val duration = c.getLong("end_ms") - c.getLong("start_ms")
            totals[c.getString("category")] = (totals[c.getString("category")] ?: 0) + duration
            val source = when (c.getString("source")) { "user" -> "你的修改"; "ai" -> "AI 摘要，請核對"; "local_excerpt" -> "可見文字摘錄，非 AI 摘要"; else -> "僅 app 使用紀錄" }
            line("${format.format(java.time.Instant.ofEpochMilli(c.getLong("start_ms")))} · ${c.getString("title")}\n${label(c.getString("category"))} · ${duration / 60000} 分鐘 · $source\n${c.getString("summary")}")
            cardContainer.addView(Button(this).apply { text = "修改卡片"; setOnClickListener { if (!busy) editCard(c) } })
        }
        if (cards.length() > 0) cardContainer.addView(ActivityTimelineView(this, cards, workspace, day) { editCard(it) }, 0)
        if (cards.length() == 0) line("尚無活動。可以在設定中同意 app 使用紀錄，之後授予系統權限。")
        line("分類概覽\n" + totals.entries.sortedByDescending { it.value }.joinToString("\n") { "${label(it.key)}：${it.value / 60000} 分鐘" })
        val checkins = data.getJSONArray("checkins")
        for (i in 0 until checkins.length()) { val c = checkins.getJSONObject(i); line("${format.format(java.time.Instant.ofEpochMilli(c.getLong("at_ms")))} 感受：心情 ${c.getInt("mood")}／精神 ${c.getInt("energy")}") }
        val reflections = data.getJSONArray("reflections")
        for (i in 0 until reflections.length()) {
            val reflection = reflections.getJSONObject(i)
            line("反思：${reflection.getString("text")}")
            if (reflection.getString("id") == "reflection-$day" && reflectionInput?.text.isNullOrBlank()) reflectionInput?.setText(reflection.getString("text"))
        }
    }
    private fun editCard(card: JSONObject) {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 0, 32, 0) }
        val title = EditText(this).apply { setText(card.getString("title")); isSaveEnabled = false; importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS }; container.addView(title)
        val summary = EditText(this).apply { setText(card.getString("summary")); isSaveEnabled = false; importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS }; container.addView(summary)
        val category = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, categories.map { label(it) }); setSelection(categories.indexOf(card.getString("category")).coerceAtLeast(0)) }; container.addView(category)
        AlertDialog.Builder(this).setTitle("修改活動").setView(container).setNegativeButton("取消", null).setPositiveButton("儲存") { _, _ ->
            val body = JSONObject().put("id", card.getString("id")).put("title", RecordingRules.cap(title.text.toString(), 200)).put("summary", RecordingRules.cap(summary.text.toString(), 1000)).put("category", categories[category.selectedItemPosition])
            task(text("儲存中…"), { SyncEngine.enqueue(this, "/v1/cards", body) }) { home() }
        }.show()
    }
    private fun settings() {
        root.removeAllViews(); text("紀錄與隱私", 28f)
        val settings = RecordingSettings(this)
        val status = text(settings.status())
        text(if (settings.enabled) "活動紀錄：啟用" else "活動紀錄：暫停")
        text("只記錄同意之後的 app 套件名稱及前景時段，不讀取文字或截圖。背景整理最短每 15 分鐘，由 Android 決定實際時間。多視窗與系統遺漏事件可能影響精準度。")
        text(if(settings.remotePaused) "遠端收集已暫停；恢復伺服器接收不會開啟未同意的權限。" else "可從網站或此處暫停所有裝置。離線裝置須等同步才會收到狀態。")
        button("管理所有裝置的收集暫停") {
            task(status,{PocketLogApi(requireNotNull(credentials)).request("/v1/collection")}) { control ->
                AlertDialog.Builder(this).setTitle(if(control.getBoolean("paused"))"恢復伺服器收集？" else "暫停所有裝置收集？").setMessage("暫停會拒絕新的活動與情境並刪除待分析原始資料。離線手機須等待同步；恢復不取代本機同意。").setNegativeButton("取消",null).setPositiveButton("確認") { _,_->
                    val paused=!control.getBoolean("paused")
                    task(status,{synchronized(SyncEngine.lock){val result=PocketLogApi(requireNotNull(credentials)).request("/v1/collection",JSONObject(control.toString()).put("paused",paused),"PUT");settings.remotePaused=paused;UsageCollector(this).reset();if(paused)JournalQueue(this).clearCollected();result}}){settings()}
                }.show()
            }
        }
        button(if (settings.enabled) "暫停活動與畫面文字紀錄" else "同意並啟用 app 使用紀錄") {
            task(status, { synchronized(SyncEngine.lock) { UsageCollector(this).reset(); settings.enabled = !settings.enabled; JournalQueue(this).clearContexts() } }) {
                if (settings.enabled && !settings.hasUsagePermission()) startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                SyncEngine.schedule(this); settings()
            }
        }
        button("開啟系統使用情況權限") { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        text("自訂排除（完整套件名稱，以空白／逗號分隔）")
        val excluded = input("例如 com.example.private").apply { setText(settings.exclusions.joinToString("\n")) }
        button("從已安裝 app 選擇排除") { chooseApps(excluded, status) }
        text("畫面文字允許清單（另外同意後才會讀取）")
        val allowed = input("例如 org.example.reader").apply { setText(settings.allowed.joinToString("\n")) }
        button("從已安裝 app 選擇允許") { chooseApps(allowed, status) }
        text("密碼管理器、驗證器、付款／銀行 app、PocketLog 本身一律排除。視窗含密碼或輸入欄位時跳過。允許清單可能含私人內容，請謹慎選擇。")
        button("儲存排除與允許清單") {
            val lists = runCatching { RecordingRules.packages(excluded.text.toString()) to RecordingRules.packages(allowed.text.toString()) }.getOrNull()
            if (lists == null) { status.text = "請輸入完整套件名稱"; return@button }
            task(status, { synchronized(SyncEngine.lock) { settings.exclusions = lists.first; settings.allowed = lists.second; JournalQueue(this).clearContexts(); UsageCollector(this).reset() } }) { status.text = "已儲存；舊的待傳畫面文字已刪除。" }
        }
        text(if (settings.contextEnabled) "選擇性畫面文字：啟用" else "選擇性畫面文字：關閉")
        button(if (settings.contextEnabled) "關閉畫面文字並清除待傳文字" else "閱讀畫面文字同意說明") {
            if (settings.contextEnabled) {
                task(status, { synchronized(SyncEngine.lock) { settings.contextEnabled = false; settings.analysisConsent = false; JournalQueue(this).clearContexts() } }) { settings() }
            } else {
                AlertDialog.Builder(this).setTitle("允許選定 app 的畫面文字？")
                    .setMessage("必須先啟用活動紀錄並設定允許清單。切換 app 時最多每五分鐘一次；Android 安全視窗、鎖定畫面、密碼與輸入欄位會跳過。圖片另有獨立選項，預設關閉。文字透過 HTTPS 傳送到你設定的私人伺服器，形成可見文字摘錄；原始佇列最長 24 小時，摘錄可保留 30 天。預設不使用外部 AI，另有獨立同意選項。啟用後仍須授予輔助使用權限；可隨時暫停。")
                    .setNegativeButton("取消", null).setPositiveButton("同意私人文字處理") { _, _ ->
                        task(status, { synchronized(SyncEngine.lock) { require(settings.enabled && settings.allowed.isNotEmpty()); settings.contextEnabled = true; settings.analysisConsent = true } }) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); settings() }
                    }.show()
            }
        }
        text(if(settings.privacyShield) "隱私防護：可見文字疑似金鑰或長數字時跳過整個視窗" else "文字敏感模式檢查：關閉")
        button(if(settings.privacyShield) "設定文字隱私防護" else "開啟文字隱私防護") {
            AlertDialog.Builder(this).setTitle("文字隱私防護").setMessage("啟用後，畫面文字疑似 API 金鑰、Bearer token、密碼欄位值或 13–19 位長數字時，在截圖前跳過整個視窗。密碼／可編輯欄位與安全視窗仍一律排除。這是保守文字檢查，可能誤判，也無法辨識只有像素的秘密；不提供 OCR 自動遮罩。請繼續使用 app 排除與允許清單。").setNegativeButton("取消",null).setPositiveButton(if(settings.privacyShield)"關閉疑似秘密檢查" else "啟用") {_,_->task(status,{synchronized(SyncEngine.lock){settings.privacyShield=!settings.privacyShield;JournalQueue(this).clearContexts()}}){settings()}}.show()
        }
        text(if(settings.replayConsent) "短期縮圖回放：已另行同意" else "短期縮圖回放：關閉")
        button(if(settings.replayConsent) "關閉回放保存並清除待傳情境" else "另行同意保存縮圖供回放") {
            if(settings.replayConsent){task(status,{synchronized(SyncEngine.lock){settings.replayConsent=false;JournalQueue(this).clearContexts()}}){settings()}}
            else AlertDialog.Builder(this).setTitle("保存畫面供私人回放？").setMessage("需先啟用縮圖。這是與分析分開的同意：未來縮圖可從網站與手機讀回，最多 24 小時、256 張，最長邊 480 px。可能包含私人資訊；沒有 OCR 自動遮罩。只允許你願意保存的 app。關閉不刪除已傳送的縮圖，可另行刪除或等待到期；不代表同意傳給模型。").setNegativeButton("取消",null).setPositiveButton("同意短期回放") {_,_->task(status,{synchronized(SyncEngine.lock){require(settings.imagesEnabled);settings.replayConsent=true;JournalQueue(this).clearContexts()}}){settings()}}.show()
        }
        text(if (settings.imagesEnabled) "選擇性畫面縮圖：啟用" else "選擇性畫面縮圖：關閉")
        button(if (settings.imagesEnabled) "關閉縮圖並清除待傳情境" else "閱讀畫面縮圖同意說明") {
            if (settings.imagesEnabled) {
                task(status, { synchronized(SyncEngine.lock) { settings.imagesEnabled = false; JournalQueue(this).clearContexts() } }) { settings() }
            } else {
                AlertDialog.Builder(this).setTitle("允許選定 app 的畫面縮圖？")
                    .setMessage("需要先同意畫面文字處理並設定允許清單。只在 app 切換時最多每五分鐘一次，縮圖最長邊 480 像素、JPEG 最多 24 KiB。整個允許視窗中可能有無法從文字判斷的私人資訊；請只允許你願意記錄的 app。圖片會加密暫存並傳到私人伺服器，原始資料最長 24 小時。只有另外同意模型處理時才可能送給模型；安全視窗、密碼與輸入欄位會跳過。尚未完成實機全天耗電量測。")
                    .setNegativeButton("取消", null).setPositiveButton("同意選定 app 的縮圖") { _, _ ->
                        task(status, { synchronized(SyncEngine.lock) { require(settings.contextEnabled); settings.imagesEnabled = true } }) { settings() }
                    }.show()
            }
        }
        text(if (settings.externalAiConsent) "AI 處理：已同意目前選定的模型" else "AI 處理：未同意")
        button("Bring your own AI：端點、模型與金鑰") { aiSettings() }
        button(if (settings.externalAiConsent) "撤回模型處理同意" else "閱讀目前模型的同意說明") {
            if (settings.externalAiConsent) {
                task(status, { synchronized(SyncEngine.lock) { settings.externalAiConsent = false; settings.aiProviderId = null; JournalQueue(this).clearContexts() } }) { settings() }
            } else approveAi(status)
        }
        button("開啟系統輔助使用設定") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button(if (settings.wifiOnly) "目前僅不限量網路；切換為允許行動網路" else "目前允許行動網路；切換為僅不限量網路") {
            task(status, { synchronized(SyncEngine.lock) { settings.wifiOnly = !settings.wifiOnly }; SyncEngine.schedule(this) }) { settings() }
        }
        button("檢視佇列狀態") { task(status, { synchronized(SyncEngine.lock) { JournalQueue(this).counts() } }) { status.text = "待傳 ${it.first}；被拒絕 ${it.second}；過期／損毀／超限丟棄 ${settings.dropped()}。" } }
        button("重試被拒絕的資料") { task(status, { synchronized(SyncEngine.lock) { JournalQueue(this).retryRejected(requireNotNull(credentials)); settings.nextAttempt = 0 }; SyncEngine.sync(this) }) { status.text = settings.status() } }
        button("刪除被拒絕的資料") { task(status, { synchronized(SyncEngine.lock) { JournalQueue(this).clearRejected() } }) { status.text = "已刪除" } }
        button("返回時間軸") { home() }
    }
    private fun approveAi(status: TextView) {
        task(status, { PocketLogApi(requireNotNull(credentials)).request("/v1/ai") }) { config ->
            if (!config.optBoolean("enabled")) { status.text = "請先設定你的 AI 端點與模型。"; return@task }
            val id = config.getString("provider_id")
            val target = "${config.getString("endpoint")}\n模型：${config.getString("model")}"
            AlertDialog.Builder(this).setTitle("同意這個模型處理未來情境？")
                .setMessage("$target\n\n未來擷取的允許清單文字，以及另有同意且模型支援的縮圖，將經過你的私人 PocketLog 伺服器送到此端點。請確認供應商與保存政策；API 使用費由你負擔。模型可能犯錯，沒有操作工具。更換設定後需重新同意；歷史未授權內容不會補送。已送出的請求無法撤回。")
                .setNegativeButton("取消", null).setPositiveButton("同意目前模型") { _, _ ->
                    task(status, { synchronized(SyncEngine.lock) {
                        require(RecordingSettings(this).contextEnabled)
                        val current = PocketLogApi(requireNotNull(credentials)).request("/v1/ai")
                        require(current.optBoolean("enabled") && current.getString("provider_id") == id)
                        RecordingSettings(this).apply { aiProviderId = id; externalAiConsent = true }
                    } }) { settings() }
                }.show()
        }
    }
    private fun aiSettings() {
        root.removeAllViews(); text("Bring your own AI", 28f)
        text("使用自己的 OpenAI 相容、Anthropic Messages 或 Gemini generateContent API。可連接 OpenAI、OpenRouter，或經 HTTPS 提供的 Ollama／LM Studio。沒有預設供應商，也不會自動使用 infovore 的 AI。本機模型也由 PocketLog 伺服器連線，請填該伺服器可連到的網址。")
        val status = text("載入私人伺服器的 AI 設定…")
        val protocol = spinner(listOf("OpenAI 相容", "Anthropic Messages", "Gemini generateContent"))
        text("OpenAI base URL；原生協定請填完整 API URL（Gemini 路徑需包含模型 ID）")
        val endpoint = input("https://your-provider.example/v1")
        button("填入 OpenAI 端點") { protocol.setSelection(0); endpoint.setText("https://api.openai.com/v1") }
        button("填入 OpenRouter 端點") { protocol.setSelection(0); endpoint.setText("https://openrouter.ai/api/v1") }
        text("模型 ID"); val model = input("輸入供應商提供的完整模型 ID")
        text("API key（可留白；不需要金鑰的本機服務也支援）")
        val key = input("相同端點及協定留白保留金鑰；變更則不沿用", true)
        val forget = CheckBox(this).apply { text = "這次儲存時清除現有 API key"; root.addView(this) }
        text("結構化輸出格式")
        val format = spinner(listOf("JSON Schema（建議）", "JSON mode（相容模式）"))
        val images = CheckBox(this).apply { text = "模型支援圖片輸入（擷取縮圖仍需另外同意）"; root.addView(this) }
        text("金鑰只透過 HTTPS 傳到你的 PocketLog 伺服器，保存在受限制的設定檔，不會讀回手機或放入日誌。伺服器管理者可讀取金鑰；請使用可信任且有加密磁碟的主機。儲存只設定模型，不代表同意傳送日誌。", 13f)
        button("儲存 AI 設定") {
            val body = runCatching {
                JSONObject().put("protocol", listOf("openai", "anthropic", "gemini")[protocol.selectedItemPosition]).put("endpoint", AiEndpoint.normalize(endpoint.text.toString(), protocol.selectedItemPosition == 0)).put("model", model.text.toString().trim().also { require(it.isNotBlank() && it.length <= 200) })
                    .put("response_format", if (format.selectedItemPosition == 0) "json_schema" else "json_object").put("image_support", images.isChecked)
                    .also { if (forget.isChecked || key.text.isNotBlank()) it.put("api_key", if (forget.isChecked) "" else key.text.toString().trim()) }
            }.getOrNull()
            if (body == null) { status.text = "請檢查端點與模型。網址不能含金鑰、帳密或查詢參數。"; return@button }
            AlertDialog.Builder(this).setTitle("儲存到私人伺服器？")
                .setMessage("會更新所有連接此伺服器的裝置所使用的模型；各裝置必須重新同意，舊佇列不會轉送到新設定。金鑰將經 HTTPS 傳送並存於伺服器。")
                .setNegativeButton("取消", null).setPositiveButton("儲存") { _, _ ->
                    // Do not retain the editor contents across a network operation or Activity destruction.
                    key.text.clear()
                    task(status, { synchronized(SyncEngine.lock) {
                        try { PocketLogApi(requireNotNull(credentials)).request("/v1/ai", body, "PUT") }
                        finally { body.remove("api_key") }
                        RecordingSettings(this).apply { externalAiConsent = false; aiProviderId = null }
                        JournalQueue(this).clearContexts()
                    } }) { aiSettings() }
                }.show()
        }
        button("測試已儲存模型（只送測試文字）") {
            AlertDialog.Builder(this).setTitle("測試模型連線？").setMessage("僅發送固定的測試文字，不會讀取或傳送日誌。供應商可能收取少量 API 費用。")
                .setNegativeButton("取消", null).setPositiveButton("測試") { _, _ ->
                    task(status, { PocketLogApi(requireNotNull(credentials)).request("/v1/ai/test", JSONObject()) }) { status.text = "連線與摘要格式驗證成功；沒有傳送日誌。" }
                }.show()
        }
        button("同意這個模型處理未來情境") { approveAi(status) }
        button("停用並刪除伺服器 AI 金鑰") {
            AlertDialog.Builder(this).setTitle("停用所有裝置的 AI？").setMessage("刪除伺服器現有設定與金鑰，活動卡片仍可使用。已送出的模型請求無法撤回；供應商副本、主機備份與已產生的摘要不會因此刪除。")
                .setNegativeButton("取消", null).setPositiveButton("停用並刪除") { _, _ ->
                    task(status, { synchronized(SyncEngine.lock) {
                        PocketLogApi(requireNotNull(credentials)).request("/v1/ai", method = "DELETE")
                        RecordingSettings(this).apply { externalAiConsent = false; aiProviderId = null }
                        JournalQueue(this).clearContexts()
                    } }) { aiSettings() }
                }.show()
        }
        button("返回紀錄設定") { settings() }
        task(status, { PocketLogApi(requireNotNull(credentials)).request("/v1/ai") }) { config ->
            if (!config.optBoolean("available")) { status.text = "伺服器尚未開放 app 內 AI 設定，請先更新伺服器。"; return@task }
            endpoint.setText(config.optString("endpoint"))
            model.setText(config.optString("model"))
            protocol.setSelection(listOf("openai", "anthropic", "gemini").indexOf(config.optString("protocol", "openai")).coerceAtLeast(0))
            images.isChecked = config.optBoolean("image_support")
            format.setSelection(if (config.optString("response_format") == "json_object") 1 else 0)
            status.text = if (config.optBoolean("enabled")) "已設定。${if (config.optBoolean("has_key")) "伺服器已保存金鑰，不會讀回。" else "目前沒有 API key。"} 是否可送日誌取決於這台裝置的同意。" else "尚未啟用 AI，活動卡片仍可使用。"
        }
    }
    private fun enterWorkspace() {
        root.removeAllViews(); val status = text("讀取共用設定…")
        button("離線開啟時間軸") { home() }
        button("重新載入 onboarding") { enterWorkspace() }
        task(status, { PocketLogApi(requireNotNull(credentials)).request("/v1/workspace") }) {
            workspace = it
            if (it.optInt("onboarding_version") >= 1) home() else onboarding(0)
        }
    }
    private fun onboarding(step: Int) {
        root.removeAllViews(); text("PocketLog · ${step + 1} / 4", 28f)
        when (step) {
            0 -> { text("把一天，收進口袋。", 25f); text("活動卡、每日回顧與你的感受，幫助你理解生活節奏。這份設定在網站與手機共用；完成一次即可。") }
            1 -> { text("先選擇，才開始記錄。", 25f); text("使用紀錄、畫面文字、圖片與 AI 各別同意，預設關閉。排除清單優先於允許清單；密碼、輸入欄位、鎖定畫面與安全視窗會在擷取前排除。原始情境最多 24 小時，活動與反思最多 30 天。伺服器管理者可以讀取日誌。") }
            2 -> { text("為自己留一點專注。", 25f); text("目標與分類角色會同步到網站；它們不是對你的一天評分。") }
            3 -> { text("帶上你自己的 AI。", 25f); text("可設定 OpenAI 相容、Anthropic 或 Gemini API 與模型，也可先使用無 AI 的活動卡。設定模型不代表同意傳送畫面；手機還需明確授予權限。") }
        }
        val focus = if (step == 2) input("每日專注分鐘").apply { inputType = InputType.TYPE_CLASS_NUMBER; setText((workspace?.optInt("focus_minutes") ?: 270).toString()) } else null
        val budget = if (step == 2) input("分心預算分鐘").apply { inputType = InputType.TYPE_CLASS_NUMBER; setText((workspace?.optInt("distraction_minutes") ?: 60).toString()) } else null
        val status = text("")
        if (step > 0) button("上一步") { onboarding(step - 1) }
        button(if (step == 3) "儲存並開始" else "繼續") {
            if (step == 2) {
                val a = focus?.text.toString().toIntOrNull(); val b = budget?.text.toString().toIntOrNull()
                if (a == null || b == null || a !in 0..1440 || b !in 0..1440) { status.text = "請填寫 0–1440 的整數分鐘"; return@button }
                workspace?.put("focus_minutes", a)?.put("distraction_minutes", b)
            }
            if (step < 3) onboarding(step + 1) else {
                val next = JSONObject(requireNotNull(workspace).toString()).put("onboarding_version", 1)
                task(status, { PocketLogApi(requireNotNull(credentials)).request("/v1/workspace", next, "PUT") }) { workspace = it; home() }
            }
        }
        if (step == 3) button("先設定我的 AI") { aiSettings() }
    }
    private fun sharedSettings() {
        root.removeAllViews(); text("共用目標與分類", 27f); val status = text("載入…")
        button("返回時間軸") { home() }
        task(status, { PocketLogApi(requireNotNull(credentials)).request("/v1/workspace") }) { current ->
            workspace = current
            val focus = input("每日專注分鐘").apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(current.getInt("focus_minutes").toString()) }
            val budget = input("分心預算分鐘").apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(current.getInt("distraction_minutes").toString()) }
            button("編輯分類與自動規則") { categoryRulesEditor(JSONObject(current.toString())) }
            button("儲存共用目標") {
                val a = focus.text.toString().toIntOrNull(); val b = budget.text.toString().toIntOrNull()
                if (a == null || b == null || a !in 0..1440 || b !in 0..1440) { status.text = "分鐘必須介於 0–1440"; return@button }
                val next = JSONObject(current.toString()).put("focus_minutes", a).put("distraction_minutes", b)
                task(status, { PocketLogApi(requireNotNull(credentials)).request("/v1/workspace", next, "PUT") }) { workspace = it; current.put("revision", it.getLong("revision")); status.text = "網站與手機的共用設定已更新" }
            }
            button("重新閱讀 onboarding") { onboarding(0) }
        }
    }
    private fun categoryRulesEditor(draft: JSONObject) {
        root.removeAllViews(); text("分類與自動規則", 27f)
        text("由上到下套用第一個符合的規則；app 與關鍵字同時指定時必須都符合。手動修改永遠優先。分類被活動使用時不能移除。")
        val status = text("")
        val styles = draft.getJSONArray("categories")
        val rules = draft.optJSONArray("rules") ?: JSONArray().also { draft.put("rules", it) }
        val capture = mutableListOf<() -> Unit>()
        fun remember() { capture.forEach { it() } }
        for (i in 0 until styles.length()) {
            val c = styles.getJSONObject(i); text(c.getString("id"), 13f)
            val name = input("分類名稱（最多 40 字）").apply { setText(c.getString("name")) }
            val color = input("#RRGGBB").apply { setText(c.getString("color")) }
            val role = spinner(listOf("專注", "一般", "分心"), listOf("focus", "neutral", "distraction").indexOf(c.getString("role")))
            capture.add { c.put("name", name.text.toString().trim()).put("color", color.text.toString().trim()).put("role", listOf("focus", "neutral", "distraction")[role.selectedItemPosition]); Unit }
            if (c.getString("id").startsWith("custom_")) button("移除此分類") {
                remember(); styles.remove(i)
                for (j in rules.length() - 1 downTo 0) if (rules.getJSONObject(j).getString("category") == c.getString("id")) rules.remove(j)
                categoryRulesEditor(draft)
            }
        }
        button("新增分類") {
            if (styles.length() >= 40) { status.text = "最多 40 個分類"; return@button }
            remember(); styles.put(JSONObject().put("id", "custom_" + UUID.randomUUID().toString().replace("-", "")).put("name", "新分類").put("color", "#9bbce1").put("role", "neutral")); categoryRulesEditor(draft)
        }
        text("自動分類規則", 23f)
        val ids = (0 until styles.length()).map { styles.getJSONObject(it).getString("id") }
        for (i in 0 until rules.length()) {
            val r = rules.getJSONObject(i)
            val enabled = CheckBox(this).apply { text = "規則 ${i + 1} 啟用"; isChecked = r.optBoolean("enabled", true); root.addView(this) }
            val app = input("完整 app 套件名稱（留白不限）").apply { setText(r.optString("app").takeUnless { it == "null" } ?: "") }
            val keyword = input("標題包含（留白不限）").apply { setText(r.optString("title_contains").takeUnless { it == "null" } ?: "") }
            val category = spinner((0 until styles.length()).map { styles.getJSONObject(it).getString("name") }, ids.indexOf(r.getString("category")).coerceAtLeast(0))
            capture.add { r.put("enabled", enabled.isChecked).put("app", app.text.toString().trim().ifBlank { null } ?: JSONObject.NULL).put("title_contains", keyword.text.toString().trim().ifBlank { null } ?: JSONObject.NULL).put("category", ids[category.selectedItemPosition]); Unit }
            if (i > 0) button("規則上移") { remember(); val previous = rules.getJSONObject(i - 1); rules.put(i - 1, r); rules.put(i, previous); categoryRulesEditor(draft) }
            button("刪除規則") { remember(); rules.remove(i); categoryRulesEditor(draft) }
        }
        button("新增規則") {
            if (rules.length() >= 32) { status.text = "最多 32 條規則"; return@button }
            remember(); rules.put(JSONObject().put("id", UUID.randomUUID().toString()).put("category", ids.first()).put("app", JSONObject.NULL).put("title_contains", JSONObject.NULL).put("enabled", true)); categoryRulesEditor(draft)
        }
        button("儲存分類與規則") {
            remember()
            task(status, { PocketLogApi(requireNotNull(credentials)).request("/v1/workspace", draft, "PUT") }) { workspace = it; sharedSettings() }
        }
        button("放棄修改並返回") { sharedSettings() }
    }
    private fun weeklyTimeline() {
        root.removeAllViews(); text("每週時間軸", 27f); button("每週 AI 回顧與排程") { briefsPage(true) }; button("返回每日時間軸") { home() }; val status = text("載入本週…")
        val first = day.minusDays((day.dayOfWeek.value - 1).toLong())
        val dates = (0L..6L).map { first.plusDays(it) }.filter { it <= journalDay() && it >= journalDay().minusDays(28) }
        task(status, { dates.map { it to SyncEngine.readTimeline(this, dayPath(it)) } }) { days ->
            val scroll = HorizontalScrollView(this); val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; scroll.addView(columns); root.addView(scroll)
            days.forEach { (date, data) ->
                val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 10, 12, 10); layoutParams = LinearLayout.LayoutParams((240 * resources.displayMetrics.density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT) }
                column.addView(TextView(this).apply { text = "$date${if (data.optBoolean("offline")) " · 離線" else ""}"; textSize = 18f })
                column.addView(ActivityTimelineView(this, data.getJSONArray("cards"), workspace, date) { editCard(it) }); columns.addView(column)
            }
        }
    }
    private fun dailyReview() {
        root.removeAllViews(); text("$day · 每日回顧", 27f); button("每日 AI 回顧與排程") { briefsPage(false) }; val status = text("載入…"); button("返回時間軸與反思") { home() }
        task(status, { SyncEngine.readTimeline(this, dayPath()) }) { data ->
            val cards = data.getJSONArray("cards"); val styles = workspace?.optJSONArray("categories")
            var focus = 0L; var distraction = 0L; val totals = mutableMapOf<String, Long>(); val intervals = mutableListOf<Pair<Long, Long>>()
            for (i in 0 until cards.length()) {
                val c = cards.getJSONObject(i); val id = c.getString("category"); val ms = c.getLong("end_ms") - c.getLong("start_ms"); totals[id] = (totals[id] ?: 0L) + ms
                if (styles != null) for (j in 0 until styles.length()) { val style = styles.getJSONObject(j); if (style.getString("id") == id) when (style.getString("role")) { "focus" -> { focus += ms; intervals += c.getLong("start_ms") to c.getLong("end_ms") }; "distraction" -> distraction += ms } }
            }
            var start = 0L; var end = 0L; var longest = 0L
            intervals.sortedBy { it.first }.forEach { (a, b) -> if (a > end) { start = a; end = b } else end = maxOf(end, b); longest = maxOf(longest, end - start) }
            text("專注 ${focus / 60000} / ${workspace?.optInt("focus_minutes") ?: 270} 分鐘\n最長連續專注 ${longest / 60000} 分鐘\n分心 ${distraction / 60000} / ${workspace?.optInt("distraction_minutes") ?: 60} 分鐘", 21f)
            text(totals.entries.sortedByDescending { it.value }.joinToString("\n") { "${label(it.key)}：${it.value / 60000} 分鐘" })
            if (data.optBoolean("truncated")) text("資料達顯示上限，統計可能不完整。")
            text("我的回顧", 22f)
            listOf("distracted" to "分心", "neutral" to "平衡", "focused" to "專注").forEach { (rating, name) -> button(name) {
                val at = day.atTime(4, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val body = recordBody("review-$day", at, "review", "").put("rating", rating)
                task(status, { PocketLogApi(requireNotNull(credentials)).request("/v1/reviews", body) }) { status.text = "已記下你的回顧：$name" }
            } }
            val checkins = data.getJSONArray("checkins")
            for (i in 0 until checkins.length()) { val c = checkins.getJSONObject(i); text("心情 ${c.getInt("mood")}/5 · 精神 ${c.getInt("energy")}/5") }
            val reflections = data.getJSONArray("reflections"); for (i in 0 until reflections.length()) text(reflections.getJSONObject(i).getString("text"))
            button("複製私人時間軸") {
                val value = (0 until cards.length()).joinToString("\n\n") { i -> val c = cards.getJSONObject(i); "${c.getString("title")}\n${c.getString("summary")}" }
                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("PocketLog", value)); status.text = "已複製私人資料；請只貼到信任的位置。"
            }
        }
    }
    private fun briefsPage(weekly: Boolean) {
        root.removeAllViews(); text(if (weekly) "每週 AI 回顧" else "每日 AI 回顧", 27f)
        text("AI 回顧可能有誤，請核對活動。只傳送最多 100 張活動卡（24 KB），不含畫面、原始文字、感受與反思。摘要保留 30 天。")
        val status=text("載入…"); button("返回時間軸") { home() }
        val first=if(weekly) day.minusDays((day.dayOfWeek.value-1).toLong()) else day
        val zone=ZoneId.systemDefault(); val from=first.atTime(4,0).atZone(zone).toInstant().toEpochMilli()
        val to=minOf(first.plusDays(if(weekly)7 else 1).atTime(4,0).atZone(zone).toInstant().toEpochMilli(),System.currentTimeMillis())
        task(status, { PocketLogApi(requireNotNull(credentials)).request("/v1/briefs?from_ms=$from&to_ms=$to") }) { data ->
            val rows=data.getJSONArray("briefs")
            if(rows.length()==0)text("尚無摘要，可自行產生或明確開啟排程。")
            for(i in 0 until rows.length()) {
                val b=rows.getJSONObject(i);text(b.getString("kind"),18f);text(b.getString("text"))
                button("刪除這份摘要") { AlertDialog.Builder(this).setTitle("永久刪除這份摘要？").setNegativeButton("取消",null).setPositiveButton("刪除") { _,_->task(status,{PocketLogApi(requireNotNull(credentials)).request("/v1/briefs/${b.getString("id")}",method="DELETE")}){briefsPage(weekly)} }.show() }
            }
        }
        button("產生並保存回顧") {
            task(status,{PocketLogApi(requireNotNull(credentials)).request("/v1/ai")}) { config ->
                if(!config.optBoolean("enabled")){status.text="請先設定 AI";return@task}
                AlertDialog.Builder(this).setTitle("這次傳送活動卡？").setMessage("會將上述範圍送到 ${config.getString("model")}，可能產生 API 費用，並保存摘要 30 天。").setNegativeButton("取消",null).setPositiveButton("同意並產生") { _,_->
                    val body=JSONObject().put("id",UUID.randomUUID().toString()).put("from_ms",from).put("to_ms",to).put("kind",if(weekly)"weekly" else "daily").put("provider_id",config.getString("provider_id")).put("consent",true)
                    task(status,{PocketLogApi(requireNotNull(credentials)).request("/v1/briefs",body)}){briefsPage(weekly)}
                }.show()
            }
        }
        button("摘要排程設定") { briefScheduleSettings() }
    }
    private fun briefScheduleSettings() {
        root.removeAllViews();text("AI 摘要排程",27f)
        text("獨立同意：自動傳送已完成日／週的最多 100 張活動卡（24 KB），不含原始畫面、感受與反思；可能產生模型費用，每期最多三次嘗試，不補跑歷史。更換模型後需重新同意，刪除日誌會停用排程。")
        val status=text("載入…");button("返回時間軸"){home()}
        task(status,{val api=PocketLogApi(requireNotNull(credentials));api.request("/v1/briefs/schedule") to api.request("/v1/ai")}){(schedule,config)->
            val enabled=CheckBox(this).apply {text="同意持續傳送給 ${config.optString("model","尚未設定模型")}，並保存摘要 30 天";isChecked=schedule.getBoolean("enabled") && schedule.optString("provider_id")==config.optString("provider_id");root.addView(this)}
            val timezone=input("IANA 時區").apply{setText(schedule.getString("timezone"))}
            val minutes=input("每天幾點幾分（04:00–23:59）").apply{setText("%02d:%02d".format(schedule.getInt("minute_of_day")/60,schedule.getInt("minute_of_day")%60))}
            val daily=CheckBox(this).apply{text="每日摘要";isChecked=schedule.getBoolean("daily");root.addView(this)}
            val weekly=CheckBox(this).apply{text="每週摘要（上一個完整的週一到週一）";isChecked=schedule.getBoolean("weekly");root.addView(this)}
            button("儲存排程") {
                val time=runCatching{java.time.LocalTime.parse(minutes.text.toString())}.getOrNull()
                if(time==null || time.hour<4){status.text="請填寫 04:00–23:59";return@button}
                val body=JSONObject(schedule.toString()).put("enabled",enabled.isChecked).put("provider_id",config.optString("provider_id","")).put("timezone",timezone.text.toString().trim()).put("minute_of_day",time.hour*60+time.minute).put("daily",daily.isChecked).put("weekly",weekly.isChecked)
                task(status,{PocketLogApi(requireNotNull(credentials)).request("/v1/briefs/schedule",body,"PUT")}){status.text="排程已更新";schedule.put("revision",it.getLong("revision"))}
            }
        }
    }
    private fun chatPage() {
        root.removeAllViews(); text("Chat · 詢問自己的 AI", 26f); text("每次只送所選日期最多 100 張活動卡的 app、時間、標題與摘要；不含原始文字、圖片、感受或反思。可接續私人對話，保留 30 天；每次最多送最近 6 輪、6,000 字上下文。")
        val status = text("載入提供者…"); button("返回時間軸") { home() }
        task(status, {
            val api=PocketLogApi(requireNotNull(credentials));val config=api.request("/v1/ai");val list=api.request("/v1/conversations").getJSONArray("conversations")
            val thread=if((0 until list.length()).any{list.getJSONObject(it).getString("id")==conversationId})api.request("/v1/conversations/$conversationId") else null
            config to thread
        }) { (config,savedThread) ->
            val historyPanel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;root.addView(this)}
            val historyTurns=mutableListOf<JSONObject>();var cursor:JSONObject?=null
            fun renderHistory(){historyPanel.removeAllViews();historyTurns.forEach{t->historyPanel.addView(TextView(this).apply{text="你：${t.getString("question")}\nAI：${t.getString("answer")}";setPadding(0,12,0,12)})}}
            fun acceptHistory(thread:JSONObject,prepend:Boolean){val rows=thread.getJSONArray("turns");val turns=(0 until rows.length()).map{rows.getJSONObject(it)};if(prepend)historyTurns.addAll(0,turns)else{historyTurns.clear();historyTurns.addAll(turns)};cursor=thread.optJSONObject("next_cursor");renderHistory()}
            if(savedThread!=null)acceptHistory(savedThread,false)
            button("載入更早的問答") {
                val before=cursor
                if(before==null){status.text="已顯示目前載入對話的所有問答";return@button}
                task(status,{PocketLogApi(requireNotNull(credentials)).request("/v1/conversations/$conversationId?before_ms=${before.getLong("before_ms")}&before_id=${before.getString("before_id")}")}){acceptHistory(it,true)}
            }
            button("已保存的對話") {
                task(status, { PocketLogApi(requireNotNull(credentials)).request("/v1/conversations") }) { list ->
                    val rows=list.getJSONArray("conversations")
                    AlertDialog.Builder(this).setTitle("選擇私人對話").setItems((0 until rows.length()).map { rows.getJSONObject(it).getString("title") }.toTypedArray()) { _, index ->
                        conversationId=rows.getJSONObject(index).getString("id")
                        chatPage()
                    }.setNegativeButton("取消", null).show()
                }
            }
            button("開啟新對話") { conversationId=UUID.randomUUID().toString();chatPage() }
            button("刪除此對話") {
                AlertDialog.Builder(this).setTitle("永久刪除此對話？").setNegativeButton("取消", null).setPositiveButton("刪除") { _, _ ->
                    task(status, { PocketLogApi(requireNotNull(credentials)).request("/v1/conversations/$conversationId", method="DELETE") }) { conversationId=UUID.randomUUID().toString();chatPage() }
                }.show()
            }
            if (!config.optBoolean("enabled")) { status.text = "請先設定 AI"; button("Bring your own AI") { aiSettings() }; return@task }
            text("${config.getString("model")}\n${config.getString("endpoint")}")
            button(if(chatWeekly)"目前本週：切換為所選日" else "目前所選日：切換為本週"){chatWeekly=!chatWeekly;chatPage()}
            val first=if(chatWeekly)day.minusDays((day.dayOfWeek.value-1).toLong()).coerceAtLeast(journalDay().minusDays(28)) else day
            val last=if(chatWeekly)day.minusDays((day.dayOfWeek.value-1).toLong()).plusDays(7) else day.plusDays(1)
            button("查看提問範圍的分類圖表") {
                val from=first.atTime(4,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();val to=minOf(last.atTime(4,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),System.currentTimeMillis())
                task(status,{PocketLogApi(requireNotNull(credentials)).request("/v1/overview?from_ms=$from&to_ms=$to")}) {overview->
                    val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24)};val rows=overview.getJSONArray("categories");val max=(0 until rows.length()).maxOfOrNull{rows.getJSONObject(it).getLong("duration_ms")}?.coerceAtLeast(1) ?: 1
                    for(i in 0 until rows.length()){val c=rows.getJSONObject(i);panel.addView(TextView(this).apply{text="${label(c.getString("category"))} · ${c.getLong("duration_ms")/60000} 分鐘"});panel.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{this.max=1000;progress=(c.getLong("duration_ms")*1000/max).toInt()})}
                    AlertDialog.Builder(this).setTitle("所選範圍的實際活動分類").setView(ScrollView(this).apply{addView(panel)}).setPositiveButton("關閉",null).show()
                }
            }
            val question = input("你想問 $day 的什麼？")
            val consent = CheckBox(this).apply { text = "同意這次將活動卡、問題與最近 6 輪對話送到這個提供者，並在私人伺服器保存問答 30 天，承擔 API 費用。"; root.addView(this) }
            button("傳送這次提問") {
                if (!consent.isChecked || question.text.isBlank()) { status.text = "請輸入問題並同意傳送"; return@button }
                val zone = ZoneId.systemDefault(); val from = first.atTime(4, 0).atZone(zone).toInstant().toEpochMilli(); val to = minOf(last.atTime(4, 0).atZone(zone).toInstant().toEpochMilli(), System.currentTimeMillis())
                val fresh = JSONObject().put("conversation_id", conversationId).put("request_id", UUID.randomUUID().toString()).put("question", RecordingRules.cap(question.text.toString(), 1000)).put("from_ms", from).put("to_ms", to).put("provider_id", config.getString("provider_id")).put("consent", true)
                val body=pendingAsk?.takeIf{pendingAskWeekly==chatWeekly && it.optString("conversation_id")==conversationId && it.optString("question")==fresh.getString("question") && it.optLong("from_ms")==from && it.optString("provider_id")==config.getString("provider_id")} ?: fresh.also{pendingAsk=it;pendingAskWeekly=chatWeekly}
                consent.isChecked = false
                task(status, { PocketLogApi(requireNotNull(credentials)).request("/v1/ask", body) }) { pendingAsk=null;text("AI 回答，請核對：\n${it.getString("answer")}") }
            }
        }
    }
    private fun recordBody(id: String, at: Long, kind: String, text: String) = JSONObject().put("id", id).put("at_ms", at).put("kind", kind).put("text", text).put("end_ms", JSONObject.NULL).put("rating", JSONObject.NULL).put("source", JSONObject.NULL)
    private fun flowPage() {
        root.removeAllViews(); text("Flow · 私人專注", 28f)
        text("計時保存在私人伺服器，可從網站或手機接續；完成時才存為手動專注紀錄。這不會開啟畫面擷取或 AI，也不代表實際使用活動。")
        val status = text("讀取計時…"); button("返回時間軸") { home() }
        task(status, { PocketLogApi(requireNotNull(credentials)).request("/v1/focus") }) { data ->
            val session = data.optJSONObject("session")
            if (session == null) {
                val topic = input("這次想專注什麼？（最多 300 字）")
                val minutes = input("預計分鐘（1–180）").apply { inputType = InputType.TYPE_CLASS_NUMBER; setText("25") }
                button("開始專注") {
                    val duration = minutes.text.toString().toIntOrNull()
                    if (duration == null || duration !in 1..180 || topic.text.isBlank() || topic.text.toString().codePointCount(0, topic.text.length) > 300) { status.text = "請填寫主題與 1–180 分鐘"; return@button }
                    val body = JSONObject().put("id", UUID.randomUUID().toString()).put("at_ms", System.currentTimeMillis()).put("minutes", duration).put("text", topic.text.toString().trim())
                    task(status, { PocketLogApi(requireNotNull(credentials)).request("/v1/focus", body) }) { flowPage() }
                }
            } else {
                text(session.getString("text"), 22f)
                val remaining = ((session.getLong("at_ms") + session.getLong("minutes") * 60_000 - System.currentTimeMillis()).coerceAtLeast(0) + 59_999) / 60_000
                text(if (remaining == 0L) "計時已結束，可以儲存成果。" else "剩餘約 $remaining 分鐘")
                val identity = JSONObject().put("id", session.getString("id"))
                button("儲存並結束") { task(status, { PocketLogApi(requireNotNull(credentials)).request("/v1/focus/finish", identity) }) { flowPage() } }
                button("捨棄這次紀錄") { task(status, { PocketLogApi(requireNotNull(credentials)).request("/v1/focus", identity, "DELETE") }) { flowPage() } }
            }
            button("重新整理計時") { flowPage() }
            loadRecords("flow", status)
        }
    }
    private fun agentsPage() {
        root.removeAllViews(); text("Agents · 工作紀錄", 27f); text("記下或明確選取 Codex／Claude 工作摘要。尚未自動掃描桌面 session、執行工具或回放錄影。請移除金鑰、私密對話後才匯入。")
        button("匯入原生 JSONL 並預覽") {
            AlertDialog.Builder(this).setTitle("紀錄來源").setItems(arrayOf("Codex", "Claude")) { _, index ->
                agentImportSource=if(index==0)"codex" else "claude"
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="*/*";addCategory(Intent.CATEGORY_OPENABLE)},22)
            }.setNegativeButton("取消",null).show()
        }
        button("專案工作線與對話回放") { agentSessionsPage() }
        val source = spinner(listOf("Codex", "Claude")); val note = input("專案、成果與待辦"); val status = text("")
        button("儲存工作摘要") {
            if (note.text.isBlank()) { status.text = "請填寫摘要"; return@button }
            val body = recordBody(UUID.randomUUID().toString(), System.currentTimeMillis(), "agent", RecordingRules.cap(note.text.toString(), 4000)).put("source", if (source.selectedItemPosition == 0) "codex" else "claude")
            task(status, { PocketLogApi(requireNotNull(credentials)).request("/v1/reviews", body) }) { agentsPage() }
        }
        button("匯入已選取的工作摘要 JSON") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "application/json"; addCategory(Intent.CATEGORY_OPENABLE) }, 21)
        }
        text("格式：{\"sessions\":[{\"id\":\"session-id\",\"at_ms\":毫秒時間戳,\"source\":\"codex\",\"text\":\"成果與待辦\"}]}。最多 20 筆、48 KB。", 13f)
        button("返回時間軸") { home() }; loadRecords("agent", status)
    }
    private fun loadRecords(kind: String, status: TextView) {
        val zone = ZoneId.systemDefault(); val from = day.atTime(4, 0).atZone(zone).toInstant().toEpochMilli(); val to = minOf(day.plusDays(1).atTime(4, 0).atZone(zone).toInstant().toEpochMilli(), System.currentTimeMillis())
        task(status, { PocketLogApi(requireNotNull(credentials)).request("/v1/reviews?from_ms=$from&to_ms=$to") }) { data ->
            val rows = data.getJSONArray("records"); var found = false
            for (i in 0 until rows.length()) { val r = rows.getJSONObject(i); if (r.getString("kind") == kind) { found = true; text(r.getString("text")); if (kind == "flow") text("${(r.getLong("end_ms") - r.getLong("at_ms")) / 60000} 分鐘", 13f) } }
            if (!found) text("$day 還沒有這類紀錄。")
            if (data.optBoolean("truncated")) text("紀錄達顯示上限。")
        }
    }
    private fun supportPage() {
        root.removeAllViews(); text("支援與隱私", 27f); text("沒有活動時，請檢查同意、Usage Access、暫停狀態、排除清單與同步網路。文字與圖片還需明確允許名單。AI 設定改變後，請重新閱讀並同意提供者。")
        text("尚未移植官方 Dayflow 雲端 Flow、桌面 CLI 自動追蹤、錄影回放與 OCR 遮罩。手機採擷取前排除，請不要允許敏感 app。實機權限與全天電池量測仍需驗證。")
        button("使用文件與原始碼") { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/skyhong2002/PocketLog"))) }
        button("返回時間軸") { home() }
    }
    private fun chooseApps(field: EditText, status: TextView) {
        task(status, {
            val selected = RecordingRules.packages(field.text.toString())
            val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), android.content.pm.PackageManager.ResolveInfoFlags.of(0))
                .take(2000).map { it.activityInfo.packageName to RecordingRules.cap(it.loadLabel(packageManager).toString(), 80) }
                .distinctBy { it.first }.sortedBy { it.second }
            apps to selected
        }) { (apps, initial) ->
            val chosen = initial.toMutableSet()
            AlertDialog.Builder(this).setTitle("選擇 app，完成後仍須儲存清單")
                .setMultiChoiceItems(apps.map { "${it.second}\n${it.first}" }.toTypedArray(), apps.map { it.first in chosen }.toBooleanArray()) { _, index, checked ->
                    if (checked) chosen += apps[index].first else chosen -= apps[index].first
                }.setNegativeButton("取消", null).setPositiveButton("填入清單") { _, _ -> field.setText(chosen.sorted().joinToString("\n")) }.show()
        }
    }
    private fun replayPage() {
        root.removeAllViews();text("短期畫面回放",27f);text("只有另行同意保存的縮圖可回放，最長 24 小時、256 張；只供分析的原始畫面仍不可讀回。沒有 OCR 自動遮罩，請先設定排除與允許清單。")
        val status=text("載入…");button("返回時間軸"){home()}
        val from=day.atTime(4,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();val to=minOf(day.plusDays(1).atTime(4,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),System.currentTimeMillis())
        task(status,{PocketLogApi(requireNotNull(credentials)).request("/v1/replay?from_ms=$from&to_ms=$to")}) {data->
            val images=data.getJSONArray("images")
            if(images.length()==0)text("沒有已同意且仍在期限內的縮圖。")
            for(i in 0 until images.length()) {val item=images.getJSONObject(i);button("${InstantFormat(item.getLong("at_ms"))} · ${item.getString("app")}") {
                task(status,{
                    val response=PocketLogApi(requireNotNull(credentials)).request("/v1/replay/${item.getString("id")}")
                    val bytes=android.util.Base64.decode(response.getString("jpeg_base64"),android.util.Base64.DEFAULT)
                    try{require(bytes.size<=24576);val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds);require(bounds.outWidth in 1..480 && bounds.outHeight in 1..480);requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size))}finally{bytes.fill(0)}
                }) {bitmap->
                    val image=ImageView(this).apply{setImageBitmap(bitmap);adjustViewBounds=true}
                    AlertDialog.Builder(this).setTitle("已核准的短期縮圖").setView(image).setNegativeButton("關閉",null).setPositiveButton("刪除") {_,_->task(status,{PocketLogApi(requireNotNull(credentials)).request("/v1/replay/${item.getString("id")}",method="DELETE")}){replayPage()}}.create().apply{setOnDismissListener{image.setImageDrawable(null);bitmap.recycle()};show();window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)}
                }
            }}
        }
    }
    private fun agentImportPreview(rows: JSONArray, source: String) {
        root.removeAllViews();text("核准要匯入的對話",27f)
        text("只在手機解析所選檔案。顯示近 30 天最後 40 則文字，每則最多 1000 字；工具輸出與附件不會匯入。請移除金鑰與私密內容。只有勾選的文字會傳到私人伺服器，不會傳到 AI。")
        val project=input("專案／工作線名稱（最多 80 字，不要填完整路徑）");val title=input("工作標題（最多 200 字）");val status=text("")
        val selections=(0 until rows.length()).map { i ->
            val turn=rows.getJSONObject(i)
            val selected=CheckBox(this).apply{text="${turn.getString("role")} · ${InstantFormat(turn.getLong("at_ms"))}";root.addView(this)}
            val value=input("核准後保存的文字").apply{setText(turn.getString("text"))}
            Triple(turn,selected,value)
        }
        if(rows.length()==0)text("沒有近 30 天可匯入的文字。")
        button("只匯入勾選的文字") {
            val turns=JSONArray();selections.filter{it.second.isChecked}.forEach{(turn,_,value)->turns.put(JSONObject(turn.toString()).put("text",value.text.toString().trim()))}
            if(turns.length()==0 || project.text.isBlank() || title.text.isBlank()){status.text="請填寫專案、標題並勾選要保存的文字";return@button}
            val body=JSONObject().put("id",UUID.randomUUID().toString()).put("revision",0).put("at_ms",turns.getJSONObject(turns.length()-1).getLong("at_ms")).put("source",source).put("project",project.text.toString().trim()).put("title",title.text.toString().trim()).put("status","completed").put("turns",turns)
            if(body.toString().toByteArray(Charsets.UTF_8).size>64000){status.text="超過 64 KB，請減少選取文字";return@button}
            task(status,{PocketLogApi(requireNotNull(credentials)).request("/v1/agent-sessions",body,"PUT")}){agentSessionsPage()}
        }
        button("放棄並返回"){agentsPage()}
    }
    private fun InstantFormat(at: Long): String = java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    private fun agentSessionsPage() {
        root.removeAllViews();text("專案工作線",27f);val status=text("載入…");button("返回 Agents"){agentsPage()}
        task(status,{PocketLogApi(requireNotNull(credentials)).request("/v1/agent-sessions")}) { data ->
            val rows=data.getJSONArray("sessions")
            (0 until rows.length()).map{rows.getJSONObject(it)}.groupBy{it.getString("project")}.forEach{(project,sessions)->
                text(project,23f);sessions.forEach{s->button("${s.getString("title")} · ${s.getString("source")} · ${s.getString("status")} · ${s.getInt("turn_count")} 則"){agentSessionReplay(s.getString("id"))}}
            }
            if(rows.length()==0)text("尚無已核准的工作紀錄。")
        }
    }
    private fun agentSessionReplay(id: String) {
        root.removeAllViews();text("核准對話回放",27f);val status=text("載入…");button("返回工作線"){agentSessionsPage()}
        task(status,{PocketLogApi(requireNotNull(credentials)).request("/v1/agent-sessions/$id")}){session->
            text(session.getString("title"),23f);text("${session.getString("project")} · ${session.getString("source")}。只顯示核准文字，不執行工具或連結。")
            val state=spinner(listOf("進行中","待接續","已完成"),listOf("active","waiting","completed").indexOf(session.getString("status")))
            button("儲存狀態") {val body=JSONObject(session.toString()).put("status",listOf("active","waiting","completed")[state.selectedItemPosition]);task(status,{PocketLogApi(requireNotNull(credentials)).request("/v1/agent-sessions",body,"PUT")}){session.put("revision",it.getLong("revision"));status.text="已儲存"}}
            val turns=session.getJSONArray("turns");var index=0;val position=text("");val content=text("")
            fun render(){val t=turns.getJSONObject(index);position.text="${index+1} / ${turns.length()} · ${t.getString("role")} · ${InstantFormat(t.getLong("at_ms"))}";content.text=t.getString("text")}
            button("上一則"){index=(index-1).coerceAtLeast(0);render()};button("下一則"){index=(index+1).coerceAtMost(turns.length()-1);render()};render()
            button("刪除工作與核准對話") {AlertDialog.Builder(this).setTitle("永久刪除這個工作紀錄？").setNegativeButton("取消",null).setPositiveButton("刪除"){_,_->task(status,{PocketLogApi(requireNotNull(credentials)).request("/v1/agent-sessions/$id",method="DELETE")}){agentSessionsPage()}}.show()}
        }
    }
    @Deprecated("Legacy result API for the platform Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 22) {
            if(resultCode!=RESULT_OK || data?.data==null)return
            val uri=requireNotNull(data.data);val source=agentImportSource
            task(text("在手機檢查所選檔案…"),{
                val bytes=contentResolver.openInputStream(uri).use{requireNotNull(it).readNBytes(524289)}
                try{AgentImport.parse(bytes,source)}finally{bytes.fill(0)}
            }){agentImportPreview(it,source)}
            return
        }
        if (requestCode == 21) {
            if (resultCode != RESULT_OK || data?.data == null) return
            val uri = requireNotNull(data.data)
            val status = text("檢查工作摘要…")
            task(status, {
                val bytes = contentResolver.openInputStream(uri).use { requireNotNull(it).readNBytes(48001) }
                try { require(bytes.size <= 48000); JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("sessions").also { rows ->
                    require(rows.length() <= 20)
                    for (i in 0 until rows.length()) { val r = rows.getJSONObject(i); require(r.getString("source") in listOf("codex", "claude")); require(r.getString("text").length <= 4000); require(r.getString("id").length in 1..80); r.getLong("at_ms") }
                } } finally { bytes.fill(0) }
            }) { rows ->
                AlertDialog.Builder(this).setTitle("匯入 ${rows.length()} 筆私人工作摘要？").setMessage("只會匯入你選取的內容；請確認不含金鑰、私密對話或不想保存的資料。")
                    .setNegativeButton("取消", null).setPositiveButton("匯入") { _, _ -> task(status, {
                        for (i in 0 until rows.length()) { val r = rows.getJSONObject(i); PocketLogApi(requireNotNull(credentials)).request("/v1/reviews", recordBody(r.getString("id"), r.getLong("at_ms"), "agent", r.getString("text")).put("source", r.getString("source"))) }
                    }) { agentsPage() } }.show()
            }
            return
        }
        if (requestCode != 20) return
        val bytes = exportBytes ?: return
        exportBytes = null
        if (resultCode != RESULT_OK || data?.data == null) { bytes.fill(0); return }
        task(text("匯出中…"), { try { contentResolver.openOutputStream(requireNotNull(data.data), "wt").use { requireNotNull(it).write(bytes) } } finally { bytes.fill(0) } }) { text("已匯出私人 JSON。") }
    }
    private fun <T> task(status: TextView, work: () -> T, done: (T) -> Unit) {
        if (busy) { status.text = "上一個操作尚在處理中"; return }
        busy = true; status.text = "處理中…"
        executor.execute {
            val result = runCatching(work)
            runOnUiThread {
                busy = false
                if (!isDestroyed && !isFinishing) result.fold({ status.text = "已完成"; done(it) }, { status.text = "無法完成。請檢查設定、網路、伺服器與金鑰。" })
            }
        }
    }
    override fun onDestroy() { executor.shutdown(); exportBytes?.fill(0); pendingAsk = null; credentials = null; super.onDestroy() }
}

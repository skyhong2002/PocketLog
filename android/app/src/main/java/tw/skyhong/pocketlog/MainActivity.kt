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
    private var day = journalDay()
    private var loaded: JSONObject? = null
    private var reflectionInput: EditText? = null
    private var exportBytes: ByteArray? = null
    private val categories = listOf("unsorted", "work", "learning", "communication", "social_media", "entertainment", "news_reading", "life_admin", "rest", "idle")
    private val categoryLabels = listOf("未分類", "工作", "學習", "溝通", "社群", "娛樂", "新聞／閱讀", "生活事務", "休息", "閒置")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 40, 32, 32) }
        setContentView(ScrollView(this).apply { addView(root) })
        val status = text("開啟私人日誌…")
        task(status, { synchronized(SyncEngine.lock) { CredentialStore(this).load() } }) {
            credentials = it
            if (it == null) setup() else { SyncEngine.schedule(this); home() }
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
        text("連接自己的私人伺服器。活動紀錄與畫面文字需要另外同意，預設關閉。")
        val endpoint = input("https://your-server.example")
        val token = input("Owner token", true)
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
            }) { credentials = candidate; token.text.clear(); SyncEngine.schedule(this); home() }
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
        val status = text(RecordingSettings(this).status())
        button("選擇日期") { DatePickerDialog(this, { _, y, m, d ->
            val selected = LocalDate.of(y, m + 1, d)
            if (selected <= journalDay() && selected >= journalDay().minusDays(28)) { day = selected; home() }
            else status.text = "請選最近 29 天內的日期"
        }, day.year, day.monthValue - 1, day.dayOfMonth).show() }
        button("同步並重新整理") { task(status, { SyncEngine.sync(this); SyncEngine.readTimeline(this, dayPath()) }) { render(it) } }
        button("讀取時間軸／離線快取") { task(status, { SyncEngine.readTimeline(this, dayPath()) }) { render(it) } }
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
        text("活動卡片", 22f)
        cardContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; root.addView(this) }
        button("清除本機資料與連線") {
            AlertDialog.Builder(this).setTitle("清除本機資料？").setMessage("會停止紀錄並刪除尚未傳送的資料與快取。伺服器資料仍保留。")
                .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ -> task(status, { synchronized(SyncEngine.lock) { SyncEngine.clearLocal(this); CredentialStore(this).clear() } }) { credentials = null; setup() } }.show()
        }
        button("刪除伺服器全部日誌") {
            AlertDialog.Builder(this).setTitle("永久刪除所有日誌？").setMessage("刪除活動、感受、反思與原始文字，同時停止紀錄並清除本機佇列與快取。無法復原。")
                .setNegativeButton("取消", null).setPositiveButton("刪除全部") { _, _ ->
                    task(status, { synchronized(SyncEngine.lock) { SyncEngine.clearLocal(this); PocketLogApi(requireNotNull(credentials)).request("/v1/journal", method = "DELETE") } }) { home() }
                }.show()
        }
    }
    private lateinit var cardContainer: LinearLayout
    private fun label(category: String) = categoryLabels.getOrElse(categories.indexOf(category)) { category }
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
        val category = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, categoryLabels); setSelection(categories.indexOf(card.getString("category")).coerceAtLeast(0)) }; container.addView(category)
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
        text(if (settings.externalAiConsent) "外部／指定模型處理：已同意" else "外部／指定模型處理：未同意")
        button(if (settings.externalAiConsent) "撤回模型處理同意" else "閱讀模型處理同意說明") {
            if (settings.externalAiConsent) {
                task(status, { synchronized(SyncEngine.lock) { settings.externalAiConsent = false; JournalQueue(this).clearContexts() } }) { settings() }
            } else {
                AlertDialog.Builder(this).setTitle("允許指定模型讀取畫面文字？")
                    .setMessage("只有伺服器管理者另外設定 AI 後才有效。未來擷取的允許清單文字及另外同意的縮圖，可能傳送給該伺服器設定的外部供應商；請先向管理者確認供應商、模型與保存政策。模型可能犯錯，不會獲得操作工具。撤回會停止未來擷取內容的模型授權，並清除本機待傳文字；已送出的內容與供應商副本無法從手機撤回。")
                    .setNegativeButton("取消", null).setPositiveButton("同意未來文字供模型處理") { _, _ ->
                        task(status, { synchronized(SyncEngine.lock) { require(settings.contextEnabled); settings.externalAiConsent = true } }) { settings() }
                    }.show()
            }
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
    @Deprecated("Legacy result API for the platform Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 20) return
        val bytes = exportBytes ?: return
        exportBytes = null
        if (resultCode != RESULT_OK || data?.data == null) { bytes.fill(0); return }
        task(text("匯出中…"), { try { contentResolver.openOutputStream(requireNotNull(data.data), "wt").use { requireNotNull(it).write(bytes) } } finally { bytes.fill(0) } }) { text("已匯出私人 JSON。") }
    }
    private fun <T> task(status: TextView, work: () -> T, done: (T) -> Unit) {
        busy = true; status.text = "處理中…"
        executor.execute {
            val result = runCatching(work)
            runOnUiThread {
                busy = false
                if (!isDestroyed && !isFinishing) result.fold({ status.text = "已完成"; done(it) }, { status.text = "無法完成。請檢查設定、網路、伺服器與金鑰；尚未成功送出的資料會保留在加密佇列。" })
            }
        }
    }
    override fun onDestroy() { executor.shutdown(); exportBytes?.fill(0); credentials = null; super.onDestroy() }
}

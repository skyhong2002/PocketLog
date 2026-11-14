package tw.skyhong.pocketlog

import android.app.Activity
import android.os.Bundle
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
    private lateinit var store: CredentialStore
    private val executor = Executors.newSingleThreadExecutor()
    private var busy = false
    private var credentials: Credentials? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        store = CredentialStore(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 40, 32, 32) }
        setContentView(ScrollView(this).apply { addView(root) })
        try { credentials = store.load() } catch (_: Exception) { credentials = null }
        if (credentials == null) setup() else home()
    }
    private fun text(value: String, size: Float = 16f) = TextView(this).apply { text = value; textSize = size; setPadding(0, 12, 0, 12); root.addView(this) }
    private fun button(value: String, action: () -> Unit) = Button(this).apply { text = value; setOnClickListener { if (!busy) action() }; root.addView(this) }
    private fun input(hint: String, secret: Boolean = false) = EditText(this).apply {
        this.hint = hint; isSaveEnabled = false
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        if (secret) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        root.addView(this)
    }
    private fun setup() {
        root.removeAllViews(); text("PocketLog", 30f); text("回看一天，理解自己的節奏。")
        text("連接自己的 PocketLog 伺服器。此開發版不會自動擷取畫面或上傳活動。")
        val endpoint = input("https://your-server.example")
        val token = input("Owner token", true)
        val status = text("")
        button("驗證並儲存連線") {
            try {
                val candidate = Credentials(EndpointPolicy.normalize(endpoint.text.toString()), token.text.toString().trim())
                require(EndpointPolicy.validToken(candidate.token)) { "金鑰必須為 64 位十六進位字元" }
                task(status, { PocketLogApi(candidate).request(todayPath()); store.save(candidate); candidate }) {
                    credentials = it; token.text.clear(); home()
                }
            } catch (e: Exception) { status.text = e.message ?: "設定無效" }
        }
    }
    private fun todayPath(): String {
        val zone = ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone)
        val day = if (now.hour < 4) LocalDate.now(zone).minusDays(1) else LocalDate.now(zone)
        val from = day.atTime(4,0).atZone(zone).toInstant().toEpochMilli()
        val to = minOf(day.plusDays(1).atTime(4,0).atZone(zone).toInstant().toEpochMilli(), System.currentTimeMillis())
        return "/v1/timeline?from_ms=$from&to_ms=$to"
    }
    private fun home() {
        root.removeAllViews(); text("PocketLog", 30f); text("今天的足跡", 24f)
        text("${LocalDate.now()} · 一天從凌晨 4 點開始")
        val status = text("按重新整理讀取私人時間軸。")
        val timeline = text("")
        button("重新整理") {
            task(status, { PocketLogApi(requireNotNull(credentials)).request(todayPath()) }) { data ->
                val activities = data.getJSONArray("activities")
                val checkins = data.getJSONArray("checkins")
                val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
                timeline.text = (0 until activities.length()).joinToString("\n\n") { i ->
                    val a = activities.getJSONObject(i)
                    val start = formatter.format(java.time.Instant.ofEpochMilli(a.getLong("start_ms")))
                    "$start · ${a.getString("app")}\n${a.getString("category")} · ${(a.getLong("end_ms")-a.getLong("start_ms"))/60000} 分鐘"
                }.ifBlank { "還沒有活動紀錄。自動活動擷取尚在開發中。" } + "\n\n今日感受紀錄：${checkins.length()} 筆" + if (data.optBoolean("truncated")) "\n此區間紀錄較多，僅顯示部分。" else ""
            }
        }
        text("現在感覺如何？", 22f)
        text("1 = 很低，5 = 很高。感受由你記錄，系統不會從使用時間猜測。")
        text("心情"); val mood = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("1", "2", "3", "4", "5")); setSelection(2); root.addView(this) }
        text("精神"); val energy = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("1", "2", "3", "4", "5")); setSelection(2); root.addView(this) }
        button("記下此刻") {
            // One request per tap. Future offline queue will persist the same ID across retries.
            val body = JSONObject().put("id", UUID.randomUUID().toString()).put("at_ms", System.currentTimeMillis()).put("mood", mood.selectedItemPosition+1).put("energy", energy.selectedItemPosition+1)
            task(status, { PocketLogApi(requireNotNull(credentials)).request("/v1/checkins", body) }) { status.text = "已儲存這一刻的感受。" }
        }
        button("清除本機連線") { store.clear(); credentials = null; setup() }
        text("開發版 · 無背景擷取、截圖、雲端 AI 或公開分享。伺服器保留最近 30 天紀錄。", 13f)
    }
    private fun <T> task(status: TextView, work: () -> T, done: (T) -> Unit) {
        busy = true; status.text = "連線中…"
        executor.execute {
            val result = runCatching(work)
            runOnUiThread {
                busy = false
                if (!isDestroyed && !isFinishing) result.fold({ status.text = "已連線"; done(it) }, { status.text = "無法完成連線。請檢查網路、伺服器憑證與金鑰後重試。" })
            }
        }
    }
    override fun onDestroy() { executor.shutdownNow(); credentials = null; super.onDestroy() }
}

package tw.skyhong.pocketlog

/** One content-free result in process memory. No app names, payloads or exception strings. */
object CaptureDiagnostics {
    enum class Outcome(val title: String, val detail: String) {
        NONE("尚無結果", "這次開啟 app 之後還沒有讀取過允許的 app。"),
        CHECKING("正在檢查畫面", "先檢查隱私規則，再擷取。"),
        WINDOW_REJECTED("這個畫面依隱私規則略過", "畫面有密碼或輸入欄位（包含搜尋列、訊息輸入框）時會略過整個畫面；疑似秘密文字、無法完整檢查或畫面已切換時也會略過。"),
        SYSTEM_FAILED("系統截圖沒有成功", "請確認輔助使用服務仍開啟，稍後會自動再試。"),
        CANCELED("這次已取消", "畫面、設定或允許的 app 在處理時改變，這次沒有保存。"),
        EMPTY("畫面沒有可記錄的內容", "沒有可見文字，也沒有可用的截圖；不會產生虛構內容。"),
        UNCHANGED("畫面沒有變化，這次略過", "和上一次相同的 app、文字與畫面不會重複保存或上傳，以節省電量。"),
        IMAGE_FAILED("截圖無法轉換", "截圖是空白或無法在大小限制內轉換，而且沒有可用文字。"),
        CONNECTION_MISSING("尚未連線", "請先設定伺服器網址與裝置金鑰。"),
        PROCESSING_FAILED("處理沒有完成", "沒有確認這次已保存。請檢查連線設定。"),
        QUEUED_TEXT("已保存畫面文字", "已加密保存在手機，等待上傳；還不代表伺服器已收到。"),
        QUEUED_IMAGE("已保存畫面文字與截圖", "已加密保存在手機，會依上傳設定補傳。"),
        QUEUED_PROTECTED("受保護畫面，只保存文字", "這個 app 的畫面受系統保護（例如影片或銀行 app），Android 不提供截圖，PocketLog 也不會繞過。只保存 app 名稱與可見文字。"),
        QUEUED_SCHEDULE_FAILED("已保存，但無法安排上傳", "內容已加密保存。可到設定按「立即同步」。"),
        QUEUED_TEXT_IMAGE_FAILED("已保存文字，這次沒有截圖", "截圖是空白或無法轉換，只保存了文字。"),
    }
    data class Snapshot(val atMs: Long?, val outcome: Outcome, val lastQueuedMs: Long?)
    private var serial = 0L
    private var state = Snapshot(null, Outcome.NONE, null)
    @Synchronized fun begin(now: Long): Long {
        serial++
        state = Snapshot(now, Outcome.CHECKING, state.lastQueuedMs)
        return serial
    }
    @Synchronized fun complete(attempt: Long, outcome: Outcome, now: Long) {
        if (attempt != serial) return
        val queued = outcome in setOf(Outcome.QUEUED_TEXT, Outcome.QUEUED_IMAGE, Outcome.QUEUED_PROTECTED, Outcome.QUEUED_TEXT_IMAGE_FAILED, Outcome.QUEUED_SCHEDULE_FAILED)
        state = Snapshot(now, outcome, if (queued) now else state.lastQueuedMs)
    }
    @Synchronized fun snapshot(): Snapshot = state
    @Synchronized fun clear() { serial++; state = Snapshot(null, Outcome.NONE, null) }
}

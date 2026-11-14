package tw.skyhong.pocketlog

/** One content-free result in process memory. No app names, payloads or exception strings. */
object CaptureDiagnostics {
    enum class Outcome(val title: String, val detail: String) {
        NONE("尚無擷取結果", "本次 app 執行期間尚未嘗試讀取允許清單中的視窗。"),
        CHECKING("正在檢查視窗", "先檢查隱私規則，再請求系統擷取。"),
        WINDOW_REJECTED("視窗已依隱私規則排除", "只要視窗含密碼或可編輯欄位（包含搜尋列、訊息輸入框），就會跳過整個視窗。疑似秘密、無法完整檢查或視窗已切換時也會排除。"),
        SYSTEM_SECURE("Android 安全視窗已排除", "系統禁止擷取這個視窗；PocketLog 不會繞過限制。"),
        SYSTEM_FAILED("系統擷取未成功", "請確認輔助使用服務仍開啟。切換到允許清單中的一般唯讀畫面後再試；每次系統擷取嘗試仍受五分鐘間隔限制。"),
        CANCELED("這次擷取已取消", "視窗、同意或允許清單在處理期間改變，這次情境未加入佇列。"),
        EMPTY("沒有可用的畫面內容", "沒有可見文字，也沒有取得已同意的縮圖；不會建立虛構內容。"),
        IMAGE_FAILED("縮圖轉換未成功", "圖片無法在大小限制內轉換，且沒有可用文字。請切換其他允許的唯讀畫面。"),
        CONNECTION_MISSING("尚未設定私人連線", "請先設定伺服器網址與日誌裝置金鑰。"),
        PROCESSING_FAILED("本機情境處理未完成", "未確認情境成功加入佇列。請檢查連線設定與離線佇列；不要把開啟狀態當成擷取成功。"),
        QUEUED_TEXT("畫面文字已排入本機佇列", "文字已加密保存等待同步；這不代表伺服器已收到或活動卡已完成。"),
        QUEUED_IMAGE("文字與縮圖已排入本機佇列", "已同意的情境已加密保存等待同步；這不代表伺服器已收到或活動卡已完成。"),
        QUEUED_SCHEDULE_FAILED("情境已排入，同步排程未成功", "情境已加密保存。請到時間軸手動同步；沒有自動重複擷取或額外上傳。"),
        QUEUED_TEXT_IMAGE_FAILED("文字已排入，縮圖轉換未成功", "可用文字已加密保存；這次沒有縮圖。可在同步後查看文字活動卡。"),
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
        val queued = outcome in setOf(Outcome.QUEUED_TEXT, Outcome.QUEUED_IMAGE, Outcome.QUEUED_TEXT_IMAGE_FAILED, Outcome.QUEUED_SCHEDULE_FAILED)
        state = Snapshot(now, outcome, if (queued) now else state.lastQueuedMs)
    }
    @Synchronized fun snapshot(): Snapshot = state
    @Synchronized fun clear() { serial++; state = Snapshot(null, Outcome.NONE, null) }
}

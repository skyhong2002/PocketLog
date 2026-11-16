package tw.skyhong.pocketlog

/** The timeline's single status line: the one state that matters most right now. */
object StatusLine {
    enum class State { KEY_REJECTED, PAUSED, NOT_STARTED, NEEDS_USAGE_ACCESS, NEEDS_ACCESSIBILITY, AI_MISSING, WAITING_FOR_CHARGER, RECORDING, ALL_GOOD }

    data class Inputs(
        val keyRejected: Boolean,
        val paused: Boolean,
        val recordingOn: Boolean,
        val usageAccess: Boolean,
        val screenTextOn: Boolean,
        val accessibilityOn: Boolean,
        /** True when the server summarises new captures automatically; null when unknown (offline). */
        val aiAutomatic: Boolean?,
        val waitingCaptures: Int,
    )

    fun select(i: Inputs): State = when {
        i.keyRejected -> State.KEY_REJECTED
        i.paused -> State.PAUSED
        !i.recordingOn -> State.NOT_STARTED
        !i.usageAccess -> State.NEEDS_USAGE_ACCESS
        i.screenTextOn && !i.accessibilityOn -> State.NEEDS_ACCESSIBILITY
        i.aiAutomatic == false -> State.AI_MISSING
        i.waitingCaptures > 0 -> State.WAITING_FOR_CHARGER
        i.aiAutomatic == null -> State.RECORDING
        else -> State.ALL_GOOD
    }

    fun text(state: State, waiting: Int): String = when (state) {
        State.KEY_REJECTED -> "金鑰失效，請重新連線"
        State.PAUSED -> "已暫停"
        State.NOT_STARTED -> "尚未開始記錄"
        State.NEEDS_USAGE_ACCESS -> "需要權限：使用情況存取"
        State.NEEDS_ACCESSIBILITY -> "需要權限：輔助使用"
        State.AI_MISSING -> "記錄中 · AI 未設定，只顯示 app 名稱"
        State.WAITING_FOR_CHARGER -> "等待充電時上傳截圖（$waiting 張）"
        State.RECORDING -> "記錄中"
        State.ALL_GOOD -> "記錄中 · AI 自動整理"
    }

    /** States that need the user's action are shown as warnings; the rest are calm. */
    fun needsAction(state: State): Boolean = state in setOf(State.KEY_REJECTED, State.NOT_STARTED, State.NEEDS_USAGE_ACCESS, State.NEEDS_ACCESSIBILITY, State.AI_MISSING)
}

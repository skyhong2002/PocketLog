package tw.skyhong.pocketlog

/**
 * The timeline's single status line: the most important real problem, in the shared
 * cross-platform vocabulary (docs/ux/SIMPLIFIED_IA.md, "Cross-platform consistency").
 */
object StatusLine {
    enum class State { PAUSED, KEY_REJECTED, NOT_STARTED, NEEDS_USAGE_ACCESS, NEEDS_ACCESSIBILITY, AI_MISSING, AI_OFF, WAITING_FOR_CHARGER, AI_PENDING, RECORDING }

    data class Inputs(
        val keyRejected: Boolean,
        val paused: Boolean,
        val recordingOn: Boolean,
        val usageAccess: Boolean,
        /** Screen text + screenshots consent; recording without it is only app sessions. */
        val screenTextOn: Boolean,
        val accessibilityOn: Boolean,
        /** Null when unknown (offline): AI states are then not claimed either way. */
        val aiConfigured: Boolean?,
        val aiAutomatic: Boolean?,
        val waitingCaptures: Int,
        val pendingCards: Int,
    )

    fun select(i: Inputs): State = when {
        i.paused -> State.PAUSED
        i.keyRejected -> State.KEY_REJECTED
        !i.recordingOn -> State.NOT_STARTED
        !i.usageAccess -> State.NEEDS_USAGE_ACCESS
        !i.screenTextOn || !i.accessibilityOn -> State.NEEDS_ACCESSIBILITY
        i.aiConfigured == false -> State.AI_MISSING
        i.aiConfigured == true && i.aiAutomatic == false -> State.AI_OFF
        i.waitingCaptures > 0 -> State.WAITING_FOR_CHARGER
        i.pendingCards > 0 -> State.AI_PENDING
        else -> State.RECORDING
    }

    fun text(state: State, inputs: Inputs): String = when (state) {
        State.PAUSED -> "已暫停"
        State.KEY_REJECTED -> "金鑰失效，請重新連線"
        State.NOT_STARTED -> "尚未開始記錄"
        State.NEEDS_USAGE_ACCESS -> "需要權限：使用情況存取"
        State.NEEDS_ACCESSIBILITY -> "需要權限：輔助使用"
        State.AI_MISSING -> "AI 未設定，只顯示應用程式名稱"
        State.AI_OFF -> "AI 自動整理已關閉"
        State.WAITING_FOR_CHARGER -> "等待充電時上傳截圖（${inputs.waitingCaptures} 張）"
        State.AI_PENDING -> "AI 整理中（${inputs.pendingCards} 張）"
        State.RECORDING -> "記錄中"
    }

    /** A not-yet-started recorder is actionable too; healthy recording stays green. */
    fun needsAction(state: State): Boolean = state in setOf(State.PAUSED, State.KEY_REJECTED, State.NOT_STARTED, State.NEEDS_USAGE_ACCESS, State.NEEDS_ACCESSIBILITY)
}

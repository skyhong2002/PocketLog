package tw.skyhong.pocketlog

import org.junit.Assert.*
import org.junit.Test

class StatusLineTest {
    private val good = StatusLine.Inputs(keyRejected = false, paused = false, recordingOn = true, usageAccess = true, screenTextOn = true,
        accessibilityOn = true, aiConfigured = true, aiAutomatic = true, waitingCaptures = 0, pendingCards = 0)
    private fun text(i: StatusLine.Inputs) = StatusLine.text(StatusLine.select(i), i)

    @Test fun recordingIsTheCalmDefaultAndUnknownAiIsNotClaimed() {
        assertEquals("記錄中", text(good))
        assertEquals("記錄中", text(good.copy(aiConfigured = null, aiAutomatic = null)))
    }
    @Test fun firstMatchingStateWinsInTheSharedOrder() {
        var i = good.copy(paused = true, keyRejected = true, recordingOn = false, usageAccess = false, accessibilityOn = false,
            aiConfigured = false, waitingCaptures = 3, pendingCards = 2)
        val expected = listOf("已暫停", "金鑰失效，請重新連線", "尚未開始記錄", "需要權限：使用情況存取", "需要權限：輔助使用", "AI 未設定，只顯示 app 名稱")
        val fixes = listOf<(StatusLine.Inputs) -> StatusLine.Inputs>({ it.copy(paused = false) }, { it.copy(keyRejected = false) }, { it.copy(recordingOn = true) },
            { it.copy(usageAccess = true) }, { it.copy(accessibilityOn = true) }, { it.copy(aiConfigured = true, aiAutomatic = false) })
        expected.forEachIndexed { n, want -> assertEquals(want, text(i)); i = fixes[n](i) }
        assertEquals("AI 自動整理已關閉", text(i))
        i = i.copy(aiAutomatic = true)
        assertEquals("等待充電時上傳截圖（3 張）", text(i))
        i = i.copy(waitingCaptures = 0)
        assertEquals("AI 整理中（2 張）", text(i))
    }
    @Test fun missingScreenTextOrAccessibilityIsARealProblemEvenWhileRecording() {
        // Regression: 記錄中 was shown while 輔助使用 was off and 畫面文字 was off.
        assertEquals("需要權限：輔助使用", text(good.copy(screenTextOn = false, accessibilityOn = false)))
        assertEquals("需要權限：輔助使用", text(good.copy(accessibilityOn = false)))
        assertEquals("需要權限：輔助使用", text(good.copy(screenTextOn = false)))
    }
    @Test fun onlyActionableStatesWarn() {
        assertFalse(StatusLine.needsAction(StatusLine.State.WAITING_FOR_CHARGER))
        assertFalse(StatusLine.needsAction(StatusLine.State.RECORDING))
        assertFalse(StatusLine.needsAction(StatusLine.State.AI_PENDING))
        assertTrue(StatusLine.needsAction(StatusLine.State.NEEDS_USAGE_ACCESS))
    }
}

package tw.skyhong.pocketlog

import org.junit.Assert.*
import org.junit.Test

class StatusLineTest {
    private val good = StatusLine.Inputs(keyRejected = false, paused = false, recordingOn = true, usageAccess = true, screenTextOn = true, accessibilityOn = true, aiAutomatic = true, waitingCaptures = 0)

    @Test fun allGoodAndUnknownAi() {
        assertEquals(StatusLine.State.ALL_GOOD, StatusLine.select(good))
        assertEquals("記錄中 · AI 自動整理", StatusLine.text(StatusLine.State.ALL_GOOD, 0))
        assertEquals(StatusLine.State.RECORDING, StatusLine.select(good.copy(aiAutomatic = null)))
    }
    @Test fun mostImportantStateWins() {
        val worst = good.copy(keyRejected = true, paused = true, recordingOn = false, usageAccess = false, accessibilityOn = false, aiAutomatic = false, waitingCaptures = 3)
        assertEquals(StatusLine.State.KEY_REJECTED, StatusLine.select(worst))
        assertEquals(StatusLine.State.PAUSED, StatusLine.select(worst.copy(keyRejected = false)))
        assertEquals(StatusLine.State.NOT_STARTED, StatusLine.select(worst.copy(keyRejected = false, paused = false)))
        assertEquals(StatusLine.State.NEEDS_USAGE_ACCESS, StatusLine.select(worst.copy(keyRejected = false, paused = false, recordingOn = true)))
        assertEquals(StatusLine.State.NEEDS_ACCESSIBILITY, StatusLine.select(worst.copy(keyRejected = false, paused = false, recordingOn = true, usageAccess = true)))
        assertEquals(StatusLine.State.AI_MISSING, StatusLine.select(good.copy(aiAutomatic = false, waitingCaptures = 3)))
        assertEquals(StatusLine.State.WAITING_FOR_CHARGER, StatusLine.select(good.copy(waitingCaptures = 3)))
    }
    @Test fun accessibilityOnlyMattersWhenScreenTextIsOn() {
        assertEquals(StatusLine.State.ALL_GOOD, StatusLine.select(good.copy(screenTextOn = false, accessibilityOn = false)))
    }
    @Test fun copyMatchesTheSpec() {
        assertEquals("等待充電時上傳截圖（12 張）", StatusLine.text(StatusLine.State.WAITING_FOR_CHARGER, 12))
        assertEquals("需要權限：使用情況存取", StatusLine.text(StatusLine.State.NEEDS_USAGE_ACCESS, 0))
        assertEquals("需要權限：輔助使用", StatusLine.text(StatusLine.State.NEEDS_ACCESSIBILITY, 0))
        assertEquals("記錄中 · AI 未設定，只顯示 app 名稱", StatusLine.text(StatusLine.State.AI_MISSING, 0))
        assertEquals("金鑰失效，請重新連線", StatusLine.text(StatusLine.State.KEY_REJECTED, 0))
        assertEquals("尚未開始記錄", StatusLine.text(StatusLine.State.NOT_STARTED, 0))
        assertEquals("已暫停", StatusLine.text(StatusLine.State.PAUSED, 0))
        assertFalse(StatusLine.needsAction(StatusLine.State.WAITING_FOR_CHARGER))
        assertTrue(StatusLine.needsAction(StatusLine.State.NEEDS_USAGE_ACCESS))
    }
}

package tw.skyhong.pocketlog

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CaptureDiagnosticsTest {
    @Before fun reset() { CaptureDiagnostics.clear() }
    @Test fun failuresNeverBecomeQueueSuccess() {
        for (outcome in listOf(CaptureDiagnostics.Outcome.WINDOW_REJECTED, CaptureDiagnostics.Outcome.SYSTEM_SECURE,
            CaptureDiagnostics.Outcome.SYSTEM_FAILED, CaptureDiagnostics.Outcome.IMAGE_FAILED,
            CaptureDiagnostics.Outcome.PROCESSING_FAILED, CaptureDiagnostics.Outcome.CANCELED)) {
            CaptureDiagnostics.complete(CaptureDiagnostics.begin(10), outcome, 20)
            assertEquals(outcome, CaptureDiagnostics.snapshot().outcome)
            assertNull(CaptureDiagnostics.snapshot().lastQueuedMs)
        }
    }
    @Test fun laterFailureKeepsLastQueueSuccessWithoutCallingItSyncSuccess() {
        CaptureDiagnostics.complete(CaptureDiagnostics.begin(10), CaptureDiagnostics.Outcome.QUEUED_TEXT, 15)
        CaptureDiagnostics.complete(CaptureDiagnostics.begin(20), CaptureDiagnostics.Outcome.SYSTEM_FAILED, 25)
        val state = CaptureDiagnostics.snapshot()
        assertEquals(CaptureDiagnostics.Outcome.SYSTEM_FAILED, state.outcome)
        assertEquals(25L, state.atMs)
        assertEquals(15L, state.lastQueuedMs)
    }
    @Test fun staleCallbacksAndConnectionResetCannotRestoreOldResult() {
        val old = CaptureDiagnostics.begin(10)
        val current = CaptureDiagnostics.begin(20)
        CaptureDiagnostics.complete(old, CaptureDiagnostics.Outcome.QUEUED_IMAGE, 30)
        assertEquals(CaptureDiagnostics.Outcome.CHECKING, CaptureDiagnostics.snapshot().outcome)
        assertNull(CaptureDiagnostics.snapshot().lastQueuedMs)
        CaptureDiagnostics.clear()
        CaptureDiagnostics.complete(current, CaptureDiagnostics.Outcome.QUEUED_IMAGE, 40)
        assertEquals(CaptureDiagnostics.Outcome.NONE, CaptureDiagnostics.snapshot().outcome)
        assertNull(CaptureDiagnostics.snapshot().atMs)
    }
    @Test fun partialImageAndScheduleFailuresStillRecordSuccessfulQueueWrite() {
        for (outcome in listOf(CaptureDiagnostics.Outcome.QUEUED_IMAGE, CaptureDiagnostics.Outcome.QUEUED_TEXT_IMAGE_FAILED,
            CaptureDiagnostics.Outcome.QUEUED_SCHEDULE_FAILED)) {
            CaptureDiagnostics.complete(CaptureDiagnostics.begin(10), outcome, 20)
            assertEquals(20L, CaptureDiagnostics.snapshot().lastQueuedMs)
        }
    }
}

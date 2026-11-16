package tw.skyhong.pocketlog

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.util.UUID

/**
 * Allowlisted screen text, captured once a minute while an allowed app stays in front and on app
 * switches (at least five seconds apart). Pixels are a secure-window gate; thumbnails need consent.
 */
class ContextService : AccessibilityService() {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val handler by lazy { android.os.Handler(mainLooper) }
    private val tick = Runnable { attempt() }
    private var lastCapture = 0L
    private var lastShot = 0L
    private var pending = false
    @Volatile private var foreground = ""
    @Volatile private var generation = 0L
    private val screen = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            // Screen off/locked stops the minute timer; unlocking restarts it.
            if (intent.action == android.content.Intent.ACTION_SCREEN_OFF) { generation++; handler.removeCallbacks(tick) }
            else schedule(0)
        }
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching {
            registerReceiver(screen, android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_OFF); addAction(android.content.Intent.ACTION_SCREEN_ON)
                addAction(android.content.Intent.ACTION_USER_PRESENT)
            }, RECEIVER_NOT_EXPORTED)
        }
        schedule(0)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        foreground = pkg
        generation++
        schedule(RecordingRules.nextCaptureDelay(System.currentTimeMillis(), lastCapture, appSwitch = true))
    }
    private fun schedule(delay: Long) {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, delay)
    }
    /** Timer and app-switch entry point. The timer stops while capture is off, locked or the screen is off. */
    private fun attempt() {
        val settings = RecordingSettings(this)
        if (!settings.contextEnabled || !settings.analysisConsent || !screenUsable()) return
        val now = System.currentTimeMillis()
        // Respect the platform screenshot rate limit and never overlap requests.
        if (pending || now - lastShot < RecordingRules.SCREENSHOT_GAP || now - lastCapture < RecordingRules.CONTEXT_MIN_GAP) {
            schedule(maxOf(RecordingRules.SCREENSHOT_GAP, RecordingRules.nextCaptureDelay(now, lastCapture, appSwitch = true))); return
        }
        schedule(RecordingRules.CONTEXT_INTERVAL)
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg != foreground) { foreground = pkg; generation++ }
        if (allowed(pkg, settings)) capture(pkg, root.windowId, settings, now)
    }
    private fun screenUsable(): Boolean =
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked && getSystemService(PowerManager::class.java).isInteractive
    private fun capture(pkg: String, window: Int, settings: RecordingSettings, now: Long) {
        val currentGeneration = generation
        val revision = settings.revision
        val attempt = CaptureDiagnostics.begin(now)
        fun report(outcome: CaptureDiagnostics.Outcome) = CaptureDiagnostics.complete(attempt, outcome, System.currentTimeMillis())
        // Structural exclusions and optional local text-secret checks run before pixels.
        if (safeNodes(pkg, window) == null) { report(CaptureDiagnostics.Outcome.WINDOW_REJECTED); return }
        pending = true
        lastCapture = now
        lastShot = now
        runCatching { takeScreenshotOfWindow(window, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                var imageFailed = false
                val jpeg = try {
                    if (currentGeneration == generation && revision == settings.revision && allowed(pkg, settings) && settings.imagesEnabled && safeNodes(pkg, window) != null)
                        runCatching { ContextImage.jpeg(result) }.getOrNull().also { imageFailed = it == null } else null
                } finally { result.hardwareBuffer.close() }
                pending = false
                runCatching { executor.execute { synchronized(SyncEngine.lock) {
                    if (revision != settings.revision || currentGeneration != generation || foreground != pkg || !allowed(pkg, settings)) { report(CaptureDiagnostics.Outcome.CANCELED); return@synchronized }
                    runCatching {
                        val nodes = safeNodes(pkg, window) ?: run { report(CaptureDiagnostics.Outcome.WINDOW_REJECTED); return@runCatching }
                        val lines = nodes.flatMap { node -> listOfNotNull(node.text?.let { RecordingRules.cap(it.toString(), 300) }, node.contentDescription?.let { RecordingRules.cap(it.toString(), 300) }) }
                        if (revision != settings.revision || currentGeneration != generation || !allowed(pkg, settings)) { report(CaptureDiagnostics.Outcome.CANCELED); return@runCatching }
                        val text = RecordingRules.normalize(lines)
                        if (text.isBlank() && jpeg == null) { report(if (imageFailed) CaptureDiagnostics.Outcome.IMAGE_FAILED else CaptureDiagnostics.Outcome.EMPTY); return@runCatching }
                        val credentials = CredentialStore(this@ContextService).load() ?: run { report(CaptureDiagnostics.Outcome.CONNECTION_MISSING); return@runCatching }
                        // Model processing is decided once on the server (automatic summaries); no per-capture AI grant.
                        val body = JSONObject()
                            .put("id", UUID.randomUUID().toString()).put("at_ms", now).put("app", pkg)
                            .put("text", text).put("analysis_consent", true)
                        if (jpeg != null && settings.imagesEnabled) { body.put("jpeg_base64", jpeg).put("image_consent", true); if (settings.replayConsent) body.put("replay_consent", true) }
                        JournalQueue(this@ContextService).enqueue(credentials, "/v1/observations", body)
                        report(when { jpeg != null -> CaptureDiagnostics.Outcome.QUEUED_IMAGE; imageFailed -> CaptureDiagnostics.Outcome.QUEUED_TEXT_IMAGE_FAILED; else -> CaptureDiagnostics.Outcome.QUEUED_TEXT })
                        runCatching { SyncEngine.schedule(this@ContextService) }.onFailure { report(CaptureDiagnostics.Outcome.QUEUED_SCHEDULE_FAILED) }
                    }.onFailure { report(CaptureDiagnostics.Outcome.PROCESSING_FAILED) }
                } } }.onFailure { report(CaptureDiagnostics.Outcome.PROCESSING_FAILED) }
            }
            override fun onFailure(errorCode: Int) {
                pending = false
                report(if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) CaptureDiagnostics.Outcome.SYSTEM_SECURE else CaptureDiagnostics.Outcome.SYSTEM_FAILED)
            }
        }) }.onFailure { pending = false; report(CaptureDiagnostics.Outcome.SYSTEM_FAILED) }
    }
    /** Reject structural risks before collecting text; fail closed on partial traversal. */
    private fun safeNodes(pkg: String, window: Int): List<AccessibilityNodeInfo>? {
        val root = rootInActiveWindow ?: return null
        if (root.windowId != window || root.packageName?.toString() != pkg) return null
        val shield = RecordingSettings(this).privacyShield
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (++visited > 300 || depth > 20 || node.childCount > 100 || node.isPassword || node.isEditable || node.packageName?.toString() != pkg) return false
            if (node.isVisibleToUser) {
                if (shield && (PrivacyShield.rejects(node.text) || PrivacyShield.rejects(node.contentDescription))) return false
                nodes += node
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: return false
                if (!visit(child, depth + 1)) return false
            }
            return true
        }
        return if (visit(root, 0)) nodes else null
    }
    private fun allowed(pkg: String, settings: RecordingSettings): Boolean =
        settings.contextEnabled && settings.analysisConsent && settings.hasUsagePermission() &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            getSystemService(PowerManager::class.java).isInteractive &&
            RecordingRules.contextAllowed(pkg, settings.allowed, settings.exclusions, packageName)
    override fun onDestroy() { onInterrupt(); runCatching { unregisterReceiver(screen) }; executor.shutdown(); super.onDestroy() }
    override fun onInterrupt() { generation++; foreground = ""; handler.removeCallbacks(tick) }
    override fun onUnbind(intent: android.content.Intent?): Boolean { onInterrupt(); return super.onUnbind(intent) }
}

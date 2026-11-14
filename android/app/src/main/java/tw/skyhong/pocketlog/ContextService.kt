package tw.skyhong.pocketlog

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.util.UUID

/** Allowlisted event-driven text. Pixels are a secure-window gate; optional thumbnails require separate consent. */
class ContextService : AccessibilityService() {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var lastCapture = 0L
    private var pending = false
    @Volatile private var foreground = ""
    @Volatile private var generation = 0L
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        foreground = pkg
        generation++
        val window = event.windowId
        val currentGeneration = generation
        val settings = RecordingSettings(this)
        val revision = settings.revision
        val now = System.currentTimeMillis()
        if (pending || now - lastCapture < RecordingRules.CONTEXT_GAP || !allowed(pkg, settings)) return
        // Inspect only structural flags before asking the OS for pixels. No text is read yet.
        if (safeNodes(pkg, window) == null) return
        pending = true
        lastCapture = now
        runCatching { takeScreenshotOfWindow(window, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val jpeg = try {
                    if (currentGeneration == generation && revision == settings.revision && allowed(pkg, settings) && settings.imagesEnabled && safeNodes(pkg, window) != null)
                        runCatching { ContextImage.jpeg(result) }.getOrNull() else null
                } finally { result.hardwareBuffer.close() }
                pending = false
                runCatching { executor.execute { synchronized(SyncEngine.lock) {
                    if (revision != settings.revision || currentGeneration != generation || foreground != pkg || !allowed(pkg, settings)) return@synchronized
                    runCatching {
                        val nodes = safeNodes(pkg, window) ?: return@runCatching
                        val lines = nodes.flatMap { node -> listOfNotNull(node.text?.let { RecordingRules.cap(it.toString(), 300) }, node.contentDescription?.let { RecordingRules.cap(it.toString(), 300) }) }
                        if (revision != settings.revision || currentGeneration != generation || !allowed(pkg, settings)) return@runCatching
                        val text = RecordingRules.normalize(lines)
                        if (text.isBlank() && jpeg == null) return@runCatching
                        val credentials = CredentialStore(this@ContextService).load() ?: return@runCatching
                        val body = JSONObject()
                            .put("id", UUID.randomUUID().toString()).put("at_ms", now).put("app", pkg)
                            .put("text", text).put("analysis_consent", true).put("external_ai_consent", settings.externalAiConsent)
                        if (jpeg != null && settings.imagesEnabled) body.put("jpeg_base64", jpeg).put("image_consent", true)
                        JournalQueue(this@ContextService).enqueue(credentials, "/v1/observations", body)
                        SyncEngine.schedule(this@ContextService)
                    }
                } } }
            }
            override fun onFailure(errorCode: Int) { pending = false }
        }) }.onFailure { pending = false }
    }
    /** Two passes: reject the whole window before reading any visible text. Fail on partial traversal. */
    private fun safeNodes(pkg: String, window: Int): List<AccessibilityNodeInfo>? {
        val root = rootInActiveWindow ?: return null
        if (root.windowId != window || root.packageName?.toString() != pkg) return null
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (++visited > 300 || depth > 20 || node.childCount > 100 || node.isPassword || node.isEditable || node.packageName?.toString() != pkg) return false
            if (node.isVisibleToUser) nodes += node
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
    override fun onDestroy() { onInterrupt(); executor.shutdown(); super.onDestroy() }
    override fun onInterrupt() { generation++; foreground = "" }
    override fun onUnbind(intent: android.content.Intent?): Boolean { onInterrupt(); return super.onUnbind(intent) }
}

package tw.skyhong.pocketlog;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic emulator OS accessibility actions; never enables capture or disables secure windows. */
final class AccessibilityChecks {
    private final Instrumentation test;
    private final Bundle args;
    private Activity activity;
    private PrintWriter log;
    private File folder;
    private AccessibilityServiceInfo originalServiceInfo;
    private final AtomicInteger clicks = new AtomicInteger();
    AccessibilityChecks(Instrumentation test, Bundle args) { this.test = test; this.args = args; }
    void run() throws Exception {
        if (!"yes".equals(args.getString("synthetic")) || !Set.of("ranchu", "goldfish").contains(android.os.Build.HARDWARE)
                || new CredentialStore(test.getTargetContext()).load() != null) throw new IllegalStateException("Disconnected synthetic emulator required");
        RecordingSettings settings = new RecordingSettings(test.getTargetContext());
        if (settings.getEnabled() || settings.getContextEnabled() || settings.getImagesEnabled()) throw new IllegalStateException("Capture consent must remain off");
        String services = android.provider.Settings.Secure.getString(test.getTargetContext().getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (settings.hasUsagePermission() || (services != null && services.contains(test.getTargetContext().getPackageName() + "/"))) throw new IllegalStateException("Capture-related system grants must remain off");
        String variant = args.getString("variant", "phone");
        if (!variant.matches("[a-z0-9-]{1,40}")) throw new IllegalArgumentException("Variant");
        folder = new File(test.getTargetContext().getExternalFilesDir(null), "ux-accessibility");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException("Evidence folder unavailable");
        try (PrintWriter writer = new PrintWriter(new File(folder, variant + "-os-actions.txt"))) {
            log = writer;
            try {
                originalServiceInfo = test.getUiAutomation().getServiceInfo();
                AccessibilityServiceInfo serviceInfo = test.getUiAutomation().getServiceInfo();
                serviceInfo.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
                test.getUiAutomation().setServiceInfo(serviceInfo);
                Thread.sleep(300);
                activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                settle(); Thread.sleep(400); settle();
                log.println("Synthetic only; OS UiAutomation nodes/actions, no TalkBack speech/gestures. fontScale=" + activity.getResources().getConfiguration().fontScale);
                if (!field("listMode").getBoolean(activity)) throw new IllegalStateException("Touch exploration did not default to list mode");
                log.println("Touch exploration default list mode=true (automation service, not TalkBack).");
                main(() -> render(false)); settle(); snapshot(variant + "-timeline"); dump("timeline");
                AccessibilityNodeInfo list = seek("清單", true);
                action(list, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, "list selector accessibility focus");
                action(list, AccessibilityNodeInfo.ACTION_CLICK, "switch production timeline to list"); settle();
                if (!field("listMode").getBoolean(activity)) throw new IllegalStateException("OS click did not select list mode");
                // Only the synthetic card callback is intercepted: production edit would require a server.
                main(() -> intercept(activity.getWindow().getDecorView()));
                AccessibilityNodeInfo card = seek("OS 合成活動 0", true);
                action(card, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, "card accessibility focus");
                action(card, AccessibilityNodeInfo.ACTION_CLICK, "synthetic card callback"); settle();
                if (clicks.get() != 1) throw new IllegalStateException("Card callback count=" + clicks.get());
                snapshot(variant + "-list"); dump("list");
                for (String label : new String[]{"時間軸", "每日", "每週", "設定"}) {
                    AccessibilityNodeInfo node = seek(label, true, true);
                    log.println("Labelled navigation/control: " + label + " class=" + node.getClassName());
                }
                action(seek("設定", true, true), AccessibilityNodeInfo.ACTION_CLICK, "navigate Settings"); settle();
                seek("更換伺服器或金鑰", true); snapshot(variant + "-settings"); dump("settings");
                action(seek("更換伺服器或金鑰", true), AccessibilityNodeInfo.ACTION_CLICK, "open connection recovery confirmation"); settle();
                seek("更換連線？", false);
                action(seek("取消", true), AccessibilityNodeInfo.ACTION_CLICK, "cancel recovery without clearing data"); settle();
                seek("更換伺服器或金鑰", true);
                log.println("PASS: list selection, card focus/callback, labelled destinations, Settings navigation and recovery cancel. FLAG_SECURE retained.");
            } catch (Exception error) { log.println("FAIL: " + error); throw error; }
            finally { if (activity != null) test.runOnMainSync(activity::finish); if (originalServiceInfo != null) test.getUiAutomation().setServiceInfo(originalServiceInfo); }
        }
    }
    private interface Work { void run() throws Exception; }
    private void main(Work work) throws Exception { final Exception[] error = {null}; test.runOnMainSync(() -> { try { work.run(); } catch (Exception e) { error[0] = e; } }); if (error[0] != null) throw error[0]; }
    private Field field(String name) throws Exception { Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private void invoke(String name) throws Exception { Method m = MainActivity.class.getDeclaredMethod(name); m.setAccessible(true); m.invoke(activity); }
    private void settle() throws Exception { test.waitForIdleSync(); Thread.sleep(250); test.waitForIdleSync(); }
    private void render(boolean list) throws Exception {
        LocalDate day = LocalDate.now().minusDays(1); field("day").set(activity, day); field("listMode").setBoolean(activity, list); invoke("home");
        Field generation = field("generation"); generation.setInt(activity, generation.getInt(activity) + 1);
        LinearLayout page = (LinearLayout) field("page").get(activity); LinearLayout slot = (LinearLayout) page.getChildAt(2); slot.removeAllViews();
        long start = day.atTime(8, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); JSONArray cards = new JSONArray();
        for (int i = 0; i < 12; i++) cards.put(new JSONObject().put("id", "os-synthetic-" + i).put("start_ms", start + i * 1800000L).put("end_ms", start + (i + 1) * 1800000L).put("title", "OS 合成活動 " + i).put("category", "work").put("source", "app_only").put("summary", "僅用於無障礙介面驗證的合成資料。"));
        JSONObject data = new JSONObject().put("cards", cards).put("checkins", new JSONArray()).put("reflections", new JSONArray());
        Method m = MainActivity.class.getDeclaredMethod("renderTimeline", LinearLayout.class, JSONObject.class); m.setAccessible(true); m.invoke(activity, slot, data);
    }
    private void intercept(View view) {
        if (view.isClickable() && view.getContentDescription() != null && view.getContentDescription().toString().startsWith("OS 合成活動")) view.setOnClickListener(v -> clicks.incrementAndGet());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) intercept(((ViewGroup) view).getChildAt(i));
    }
    private List<AccessibilityNodeInfo> nodes() {
        List<AccessibilityNodeInfo> result = new ArrayList<>(); AccessibilityNodeInfo root = null;
        for (int retry = 0; retry < 10 && root == null; retry++) { root = test.getUiAutomation().getRootInActiveWindow(); if (root == null) android.os.SystemClock.sleep(100); }
        if (root == null) throw new IllegalStateException("No OS active window"); collect(root, result); return result;
    }
    private void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) { out.add(node); for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) collect(child, out); } }
    private String label(AccessibilityNodeInfo node) { return String.valueOf(node.getText()) + " | " + String.valueOf(node.getContentDescription()); }
    private AccessibilityNodeInfo seek(String text, boolean clickable) throws Exception { return seek(text, clickable, false); }
    private AccessibilityNodeInfo seek(String text, boolean clickable, boolean exactDescription) throws Exception {
        for (int attempt = 0; attempt < 16; attempt++) {
            List<AccessibilityNodeInfo> all = nodes();
            for (AccessibilityNodeInfo node : all) if (node.isVisibleToUser() && (exactDescription ? (text.equals(String.valueOf(node.getContentDescription())) || (text + "，目前頁面").equals(String.valueOf(node.getContentDescription()))) : label(node).contains(text)) && (!clickable || node.isClickable())) return node;
            boolean scrolled = false;
            for (AccessibilityNodeInfo node : all) if (node.isVisibleToUser() && node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) { log.println("ACTION_SCROLL_FORWARD seeking " + text); scrolled = true; break; }
            if (!scrolled) break; settle();
        }
        dump("missing-" + text); throw new IllegalStateException("OS node unavailable: " + text + " clickable=" + clickable);
    }
    private void action(AccessibilityNodeInfo node, int action, String reason) throws Exception { boolean ok = node.performAction(action); log.println("action=" + action + " result=" + ok + " " + reason + " node=" + label(node)); log.flush(); if (!ok) throw new IllegalStateException("OS action rejected: " + reason); }
    private void dump(String phase) { log.println("TREE " + phase); for (AccessibilityNodeInfo node : nodes()) if (node.isVisibleToUser()) log.println(node.getClassName() + " clickable=" + node.isClickable() + " focusable=" + node.isFocusable() + " scrollable=" + node.isScrollable() + " " + label(node)); log.flush(); }
    private void snapshot(String name) throws Exception {
        main(() -> { if ((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) == 0) throw new IllegalStateException("FLAG_SECURE missing"); View view = activity.getWindow().getDecorView(); Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888); try (FileOutputStream out = new FileOutputStream(new File(folder, name + ".png"))) { view.draw(new Canvas(bitmap)); if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IllegalStateException("PNG failure"); } finally { bitmap.recycle(); } });
    }
}

package tw.skyhong.pocketlog;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneId;

/** Explicit synthetic-only emulator view snapshots; never uses a server or stored credentials. */
public final class UiSnapshotInstrumentation extends Instrumentation {
    private Bundle args;
    private Activity activity;
    private File folder;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); args = arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (args == null || !"yes".equals(args.getString("synthetic"))) throw new IllegalStateException("Explicit synthetic=yes required");
            if ("yes".equals(args.getString("rejectedConnection"))) {
                new RejectedConnectionChecks(this,args).run();
                result.putString("stream", "yes".equals(args.getString("expectLegacy")) ? "Legacy exact post401 fixture shows generic capture-off and offers re-enable; support retains reason.\n" : "Synthetic credential-bound known rejection, consent stop, retained journal/cache and actionable secure recovery checks passed.\n");
                finish(Activity.RESULT_OK,result); return;
            }
            if ("yes".equals(args.getString("classificationFocus"))) {
                new ClassificationFocusChecks(this,args).run();
                result.putString("stream", "yes".equals(args.getString("expectLegacy")) ? "Legacy native classification action scroll reset reproduced.\n" : "Synthetic native classification action focus, visible continuation, draft preservation and rule order passed.\n");
                finish(Activity.RESULT_OK,result); return;
            }
            if ("yes".equals(args.getString("exportLifecycle"))) {
                new ExportLifecycleChecks(this,args).run();
                result.putString("stream", "yes".equals(args.getString("expectLegacy")) ? "Legacy pending export loss on Activity recreation reproduced.\n" : "Synthetic export recreation, exact Unicode write, cancel/reset/missing-state and buffer disposal passed.\n");
                finish(Activity.RESULT_OK,result); return;
            }
            if ("yes".equals(args.getString("replayOwnership"))) {
                new ReplayOwnershipChecks(this,args).run();
                result.putString("stream", "Synthetic replay secure dialog, dismissal/recreation and undelivered-bitmap disposal passed.\n");
                finish(Activity.RESULT_OK,result); return;
            }
            if ("yes".equals(args.getString("ruleAppPicker"))) {
                new RuleAppPickerChecks(this,args).run();
                result.putString("stream", "Synthetic secure single rule app picker search/select/cancel/manual/draft/stale checks passed; settings and consent unchanged.\n");
                finish(Activity.RESULT_OK,result); return;
            }
            if ("yes".equals(args.getString("appPicker"))) {
                new AppPickerChecks(this,args).run();
                result.putString("stream", "Synthetic secure native app picker search/select/cancel checks passed; settings and consent unchanged.\n");
                finish(Activity.RESULT_OK,result); return;
            }
            if ("yes".equals(args.getString("capture"))) {
                new CapturePipelineChecks(this,args).run();
                result.putString("stream", "Synthetic real-window capture, input exclusion, encrypted queue and journal-device API upload passed.\n");
                finish(Activity.RESULT_OK,result); return;
            }
            if (new CredentialStore(getTargetContext()).load() != null) throw new IllegalStateException("Refusing snapshots on a connected installation");
            folder = new File(getTargetContext().getExternalFilesDir(null), "ux-snapshots");
            if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException("Snapshot directory unavailable");
            activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            // Let the initial Keystore read deliver its result, without network or stored credentials.
            Thread.sleep(400); waitForIdleSync();
            capture("first-run");
            if ("yes".equals(args.getString("setupHelp"))) {
                runOnMainSync(() -> {
                    var found = new java.util.ArrayList<View>();
                    activity.getWindow().getDecorView().findViewsWithText(found, "不知道網址或金鑰在哪裡", View.FIND_VIEWS_WITH_TEXT);
                    if (found.isEmpty() || !found.get(0).performClick()) throw new IllegalStateException("Setup help unavailable");
                });
                settle();
                runOnMainSync(() -> {
                    var found = new java.util.ArrayList<View>();
                    activity.getWindow().getDecorView().findViewsWithText(found, "設定 → 連線與裝置 → 管理裝置金鑰", View.FIND_VIEWS_WITH_TEXT);
                    if (found.isEmpty()) throw new IllegalStateException("Current website key path missing");
                    View target = found.get(0);
                    target.requestRectangleOnScreen(new android.graphics.Rect(0, 0, target.getWidth(), target.getHeight()), true);
                });
                settle(); capture("first-run-help");
                result.putString("stream", "Synthetic expanded setup help captured with current web device-key path.\n");
                finish(Activity.RESULT_OK, result); return;
            }
            runOnMainSync(() -> { try { invoke("settings"); } catch (Exception e) { throw new RuntimeException(e); } });
            settle(); capture("settings");
            runOnMainSync(() -> { try { renderFixture(); } catch (Exception e) { throw new RuntimeException(e); } });
            settle(); capture("timeline");
            runOnMainSync(() -> { try { renderReviewFixture(); } catch (Exception e) { throw new RuntimeException(e); } });
            settle(); capture("review");
            result.putString("stream", "Synthetic emulator view snapshots of the three tabs saved.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "Snapshot failure: " + error.getClass().getSimpleName() + (("yes".equals(args.getString("rejectedConnection")) || "yes".equals(args.getString("ruleAppPicker")) || "yes".equals(args.getString("classificationFocus")) || "yes".equals(args.getString("capture")) || "yes".equals(args.getString("appPicker")) || "yes".equals(args.getString("replayOwnership")) || "yes".equals(args.getString("exportLifecycle"))) ? " " + error.getMessage() : "") + "\n");
            finish(Activity.RESULT_CANCELED, result);
        } finally { if (activity != null) runOnMainSync(() -> activity.finish()); }
    }
    private Field field(String name) throws Exception { Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private void invoke(String name) throws Exception { Method m = MainActivity.class.getDeclaredMethod(name); m.setAccessible(true); m.invoke(activity); }
    private void settle() throws Exception { waitForIdleSync(); Thread.sleep(250); waitForIdleSync(); }
    private void renderFixture() throws Exception {
        LocalDate day = TimelineMath.INSTANCE.journalDay(java.time.ZonedDateTime.now()).minusDays(1);
        field("day").set(activity, day);
        invoke("home");
        // Reject the intentionally unconnected async read; use the actual production render callback.
        Field generation = field("generation"); generation.setInt(activity, generation.getInt(activity) + 1);
        LinearLayout page = (LinearLayout) field("page").get(activity);
        LinearLayout slot = (LinearLayout) page.getChildAt(2); slot.removeAllViews();
        long start = day.atTime(8, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        JSONArray cards = new JSONArray();
        String[] titles = {"整理專案與今天的計畫", "研究資料與閱讀筆記：較長的繁體中文活動標題", "午間散步與休息"};
        String[] categories = {"work", "learning", "rest"};
        long[] offsets = {0, 15 * 60000L, 90 * 60000L};
        long[] durations = {55 * 60000L, 45 * 60000L, 30 * 60000L};
        for (int i = 0; i < titles.length; i++) cards.put(new JSONObject().put("id", "synthetic-" + i).put("start_ms", start + offsets[i]).put("end_ms", start + offsets[i] + durations[i]).put("title", titles[i]).put("category", categories[i]).put("source", i == 0 ? "ai" : "app_only").put("summary", "僅用於介面驗證的合成資料。").put("pending_context", i == 1).put("protected_screen", i == 2));
        JSONObject fixture = new JSONObject().put("cards", cards).put("checkins", new JSONArray()).put("reflections", new JSONArray());
        Method render = MainActivity.class.getDeclaredMethod("renderTimeline", LinearLayout.class, JSONObject.class); render.setAccessible(true); render.invoke(activity, slot, fixture);
    }
    /** 回顧 rendered from synthetic overview data through the production render path; no server. */
    private void renderReviewFixture() throws Exception {
        invoke("review");
        Field generation = field("generation"); generation.setInt(activity, generation.getInt(activity) + 1);
        LinearLayout page = (LinearLayout) field("page").get(activity);
        LinearLayout slot = (LinearLayout) page.getChildAt(3); slot.removeAllViews();
        JSONObject overview = new JSONObject()
            .put("categories", new JSONArray().put(new JSONObject().put("category", "work").put("duration_ms", 3 * 3600000L)).put(new JSONObject().put("category", "learning").put("duration_ms", 5400000L)).put(new JSONObject().put("category", "rest").put("duration_ms", 1800000L)))
            .put("apps", new JSONArray().put(new JSONObject().put("app", "org.example.reader").put("duration_ms", 5400000L)).put(new JSONObject().put("app", "org.example.notes").put("duration_ms", 3600000L)))
            .put("title_keywords", new JSONArray().put(new JSONArray().put("Kotlin").put(4)).put(new JSONArray().put("專案計畫").put(3)));
        JSONObject ai = new JSONObject().put("available", true).put("enabled", true).put("endpoint", "https://ai.example/v1/chat/completions").put("model", "synthetic").put("provider_id", "0".repeat(64)).put("auto_summarize", true);
        JSONObject briefs = new JSONObject().put("briefs", new JSONArray().put(new JSONObject().put("id", "synthetic").put("kind", "daily").put("text", "合成回顧：上午整理專案，下午閱讀資料。")));
        Class<?> data = Class.forName("tw.skyhong.pocketlog.MainActivity$ReviewData");
        var constructor = data.getDeclaredConstructor(JSONObject.class, JSONObject.class, JSONObject.class, java.util.List.class, JSONObject.class); constructor.setAccessible(true);
        Object fixture = constructor.newInstance(overview, ai, briefs, null, null);
        Method render = MainActivity.class.getDeclaredMethod("renderReview", LinearLayout.class, data, boolean.class, long.class, long.class, String.class); render.setAccessible(true);
        render.invoke(activity, slot, fixture, false, 0L, 1L, "d:synthetic");
    }
    private void capture(String name) throws Exception {
        String variant = args.getString("variant", "phone");
        if (!variant.matches("[a-z0-9-]{1,40}")) throw new IllegalArgumentException("Bad variant");
        final Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            Bitmap bitmap = null;
            try {
                View view = activity.getWindow().getDecorView();
                bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
                view.draw(new Canvas(bitmap));
                try (FileOutputStream out = new FileOutputStream(new File(folder, variant + "-" + name + ".png"))) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IllegalStateException("PNG failed");
                }
            } catch (Throwable e) { failure[0] = e; } finally { if (bitmap != null) bitmap.recycle(); }
        });
        if (failure[0] != null) throw new RuntimeException(failure[0]);
    }
}

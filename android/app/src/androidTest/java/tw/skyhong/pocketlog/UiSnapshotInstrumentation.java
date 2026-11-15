package tw.skyhong.pocketlog;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
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

/** Explicit synthetic-only emulator view snapshots; never disables FLAG_SECURE or uses a server. */
public final class UiSnapshotInstrumentation extends Instrumentation {
    private Bundle args;
    private Activity activity;
    private File folder;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); args = arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (args == null || !"yes".equals(args.getString("synthetic"))) throw new IllegalStateException("Explicit synthetic=yes required");
            if ("yes".equals(args.getString("shortTimeline"))) {
                new ShortTimelineChecks(this,args).run();
                result.putString("stream", "Synthetic native short-card geometry/bounds/original hit targets passed at 320/360/800dp and 100-card density.\n");
                finish(Activity.RESULT_OK,result); return;
            }
            if ("yes".equals(args.getString("cardEdit"))) {
                new CardEditChecks(this,args).run();
                result.putString("stream", "Synthetic activity-edit validation, failure/busy draft retention and exact encrypted Unicode payload checks passed.\n");
                finish(Activity.RESULT_OK,result); return;
            }
            if ("yes".equals(args.getString("readiness"))) {
                new CaptureReadinessChecks(this,args).run();
                result.putString("stream", "Synthetic effective-allowlist readiness and historical diagnostics checks passed; original settings restored.\n");
                finish(Activity.RESULT_OK,result); return;
            }
            if ("yes".equals(args.getString("replayOwnership"))) {
                new ReplayOwnershipChecks(this,args).run();
                result.putString("stream", "Synthetic replay secure dialog, dismissal/recreation and undelivered-bitmap disposal passed.\n");
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
            if ("yes".equals(args.getString("connected"))) {
                new ConnectedUiChecks(this, args).run();
                result.putString("stream", "Connected synthetic HTTPS view/API checks passed; capture consent remained off.\n");
                finish(Activity.RESULT_OK, result); return;
            }
            if (new CredentialStore(getTargetContext()).load() != null) throw new IllegalStateException("Refusing snapshots on a connected installation");
            folder = new File(getTargetContext().getExternalFilesDir(null), "ux-snapshots");
            if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException("Snapshot directory unavailable");
            activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            // Let the initial Keystore read deliver its result, without network or stored credentials.
            Thread.sleep(400); waitForIdleSync();
            capture("first-run");
            runOnMainSync(() -> { try { invoke("settings"); } catch (Exception e) { throw new RuntimeException(e); } });
            settle(); capture("settings");
            runOnMainSync(() -> { try { renderFixture(false); } catch (Exception e) { throw new RuntimeException(e); } });
            settle(); capture("timeline");
            runOnMainSync(() -> { try { renderFixture(true); } catch (Exception e) { throw new RuntimeException(e); } });
            settle(); capture("activity-list");
            for (String page : new String[]{"dailyReview", "weeklyTimeline", "chatPage", "flowPage", "agentsPage", "supportPage"}) {
                runOnMainSync(() -> { try { invoke(page); } catch (Exception e) { throw new RuntimeException(e); } });
                settle(); capture(page + "-unconnected");
            }
            result.putString("stream", "Synthetic emulator view snapshots saved. FLAG_SECURE remained enabled.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "Snapshot failure: " + error.getClass().getSimpleName() + (("yes".equals(args.getString("shortTimeline")) || "yes".equals(args.getString("cardEdit")) || "yes".equals(args.getString("connected")) || "yes".equals(args.getString("capture")) || "yes".equals(args.getString("appPicker")) || "yes".equals(args.getString("replayOwnership")) || "yes".equals(args.getString("readiness"))) ? " " + error.getMessage() : "") + "\n");
            finish(Activity.RESULT_CANCELED, result);
        } finally { if (activity != null) runOnMainSync(() -> activity.finish()); }
    }
    private Field field(String name) throws Exception { Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private void invoke(String name) throws Exception { Method m = MainActivity.class.getDeclaredMethod(name); m.setAccessible(true); m.invoke(activity); }
    private void settle() throws Exception { waitForIdleSync(); Thread.sleep(250); waitForIdleSync(); }
    private void renderFixture(boolean listMode) throws Exception {
        LocalDate day = TimelineMath.INSTANCE.journalDay(java.time.ZonedDateTime.now()).minusDays(1);
        field("day").set(activity, day); field("listMode").setBoolean(activity, listMode);
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
        for (int i = 0; i < titles.length; i++) cards.put(new JSONObject().put("id", "synthetic-" + i).put("start_ms", start + offsets[i]).put("end_ms", start + offsets[i] + durations[i]).put("title", titles[i]).put("category", categories[i]).put("source", "app_only").put("summary", "僅用於介面驗證的合成資料。"));
        JSONObject fixture = new JSONObject().put("cards", cards).put("checkins", new JSONArray()).put("reflections", new JSONArray());
        Method render = MainActivity.class.getDeclaredMethod("renderTimeline", LinearLayout.class, JSONObject.class); render.setAccessible(true); render.invoke(activity, slot, fixture);
    }
    private void capture(String name) throws Exception {
        String variant = args.getString("variant", "phone");
        if (!variant.matches("[a-z0-9-]{1,40}")) throw new IllegalArgumentException("Bad variant");
        final Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            Bitmap bitmap = null;
            try {
                if ((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) == 0) throw new IllegalStateException("Secure flag missing");
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

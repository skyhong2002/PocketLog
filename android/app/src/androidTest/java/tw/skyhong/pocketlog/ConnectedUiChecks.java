package tw.skyhong.pocketlog;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.ProgressBar;
import java.lang.reflect.Field;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Real HTTPS/API checks on a disposable synthetic fixture; never accepts a real owner key. */
final class ConnectedUiChecks {
    private final Instrumentation test;
    private final Bundle args;
    private Activity activity;
    private String stage = "fixture setup";
    ConnectedUiChecks(Instrumentation test, Bundle args) { this.test = test; this.args = args; }
    void run() throws Exception {
        if (!java.util.Set.of("ranchu","goldfish").contains(android.os.Build.HARDWARE))
            throw new IllegalStateException("Disposable emulator required");
        String endpoint = args.getString("endpoint", "");
        if (!endpoint.matches("https://[a-z0-9.-]+\\.ts\\.net:18447"))
            throw new IllegalArgumentException("Dedicated tailnet fixture origin on18447 required");
        if (new CredentialStore(test.getTargetContext()).load() != null)
            throw new IllegalStateException("Refusing a connected installation");
        String connectionToken="a".repeat(64);
        String pairingId=null;
        boolean pairingPassed=false;
        PocketLogApi fixtureOwner=new PocketLogApi(new Credentials(endpoint,connectionToken));
        try {
            if ("yes".equals(args.getString("pairingOnly"))) {
                JSONObject issued=fixtureOwner.request("/v1/devices",new JSONObject().put("label","Synthetic native journal pairing").put("scope","journal").put("days",1),"POST");
                pairingId=issued.getString("id");connectionToken=issued.getString("token");
                assertAuthentication(fixtureOwner,pairingId,false);
            }
            activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await("驗證並連線");
            stage = "short key validation";
            fillConnection(endpoint, "abc"); click("驗證並連線"); await("金鑰應為 64 個字元");
            stage = "rejected key recovery";
            fillConnection(endpoint, "b".repeat(64)); click("驗證並連線"); await("伺服器拒絕這把金鑰");
            stage = "valid synthetic connection";
            fillConnection(endpoint, connectionToken); click("驗證並連線"); await("手機紀錄未開啟"); await("活動"); awaitSettled();
            assertConsentOff(); capture("timeline");
            if (pairingId!=null) {
                assertAuthentication(fixtureOwner,pairingId,true);
                if(!has("尚無活動")) throw new IllegalStateException("Expected empty connected synthetic journal");
                File evidence=new File(test.getTargetContext().getExternalFilesDir(null),"ux-connected/pairing-id.txt");
                try(FileOutputStream out=new FileOutputStream(evidence)) { out.write(pairingId.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                pairingPassed=true;return;
            }
            if ("yes".equals(args.getString("previewOnly"))) { rulePreviewCheck(fixtureOwner); return; }
            if ("yes".equals(args.getString("replayOnly"))) { replayUnavailableCheck(fixtureOwner); return; }
            if ("yes".equals(args.getString("pendingOnly"))) { pendingContextCheck(); return; }
            for (String[] page : new String[][] {{"每日", "時間分配"}, {"每週", "本週已記錄"}, {"Chat", "你想問什麼？"}, {"Flow", "開始一段專注"}, {"Agents", "匯入"}, {"支援", "這支手機的狀態"}, {"設定", "手機紀錄"}}) {
                stage = "navigation " + page[0]; click(page[0]); await(page[1]); awaitSettled();
                if (has("無法載入")) throw new IllegalStateException("Connected page failed");
                capture(page[0].equals("每日") ? "daily" : page[0].equals("設定") ? "settings" : "page-" + page[0]);
            }
            stage = "content-free capture status and whole-window guidance";
            captureStatusCheck();
            stage = "category visibility, list parity, reset and recreation";
            categoryVisibilityCheck();
            if ("yes".equals(args.getString("pending"))) pendingContextCheck();
            stage = "reflection draft navigation and empty edit";
            click("時間軸"); await("活動"); awaitSettled();
            setEditor("哪件事讓你恢復精神？", "Synthetic unsaved reflection");
            click("每日"); await("時間分配"); awaitSettled(); click("時間軸"); await("活動"); awaitSettled();
            assertEditor("哪件事讓你恢復精神？", "Synthetic unsaved reflection");
            setEditor("哪件事讓你恢復精神？", "");
            click("每日"); await("時間分配"); awaitSettled(); click("時間軸"); await("活動"); awaitSettled();
            assertEditor("哪件事讓你恢復精神？", "");
            stage = "draft survives Activity recreation";
            setEditor("哪件事讓你恢復精神？", "Synthetic retained reflection");
            Instrumentation.ActivityMonitor monitor = test.addMonitor(MainActivity.class.getName(), null, false);
            test.runOnMainSync(() -> activity.recreate());
            Activity recreated = test.waitForMonitorWithTimeout(monitor, 10_000); test.removeMonitor(monitor);
            if (recreated == null) throw new IllegalStateException("Activity did not recreate");
            activity = recreated; await("活動"); awaitSettled();
            assertEditor("哪件事讓你恢復精神？", "Synthetic retained reflection");
            stage = "Daily editor shares timeline draft and saves without leaving";
            click("每日"); await("時間分配"); awaitSettled();
            assertEditor("哪件事讓你恢復精神？", "Synthetic retained reflection");
            setEditor("哪件事讓你恢復精神？", "Synthetic Daily editor draft");
            click("時間軸"); await("活動"); awaitSettled();
            assertEditor("哪件事讓你恢復精神？", "Synthetic Daily editor draft");
            click("每日"); await("時間分配"); awaitSettled();
            setEditor("哪件事讓你恢復精神？", "Synthetic Daily saved reflection");
            click("儲存這一天的反思"); await("反思已加密加入佇列"); awaitSettled();
            click("時間軸"); await("活動"); awaitSettled();
            assertEditor("哪件事讓你恢復精神？", "Synthetic Daily saved reflection");
            click("每日"); await("時間分配"); awaitSettled();
            revealEditor("哪件事讓你恢復精神？"); capture("daily-editor");
            revealAction("儲存這一天的反思"); capture("daily-editor-actions");
            stage = "manual mood entry removed on current and historical days";
            if(has("現在感覺如何？") || has("記下此刻（可離線）")) throw new IllegalStateException("Mood entry remains");
            click("前一天"); await("時間分配"); awaitSettled();
            if(has("記下此刻（可離線）")) throw new IllegalStateException("Historical check-in form was shown");
            setEditor("哪件事讓你恢復精神？", "Synthetic historical reflection draft");
            revealEditor("哪件事讓你恢復精神？"); capture("daily-historical-editor");
            click("後一天"); await("時間分配"); awaitSettled();
            assertEditor("哪件事讓你恢復精神？", "Synthetic Daily saved reflection");
            stage = "brief consent destination, scope, cancel and explicit generate";
            click("前一天"); await("時間分配"); awaitSettled();
            click("每日 AI 回顧與排程"); await("產生並保存回顧"); awaitSettled();
            int beforeBriefs=briefCount();
            click("產生並保存回顧"); await("提供者端點"); awaitSettled();
            if(!has("127.0.0.1:18752") || !has("synthetic-ui") || !has("24,000 位元組") || !has("時區：") || !has("不含原始畫面") || !has("尚未傳送"))
                throw new IllegalStateException("Consent destination or scope missing");
            capture("brief-consent"); revealAction("取消，返回回顧"); capture("brief-consent-actions");
            click("取消，返回回顧"); await("產生並保存回顧"); awaitSettled();
            if(briefCount()!=beforeBriefs) throw new IllegalStateException("Cancel created a brief");
            click("產生並保存回顧"); await("提供者端點"); awaitSettled();
            click("同意並產生"); await("已保存新的回顧"); awaitSettled();
            if(briefCount()!=beforeBriefs+1) throw new IllegalStateException("Explicit brief was not stored once");
            click("摘要排程設定"); await("提供者端點："); awaitSettled();
            if(!has("127.0.0.1:18752") || !has("synthetic-ui")) throw new IllegalStateException("Schedule destination missing");
            capture("brief-schedule");
            click("返回"); await("產生並保存回顧"); awaitSettled();
            click("返回"); await("時間分配"); awaitSettled();
            click("後一天"); await("時間分配"); awaitSettled();
            stage = "question draft navigation and scope isolation";
            click("Chat"); await("你想問什麼？"); awaitSettled();
            setEditor("例如：今天哪段時間最專注？", "Synthetic day question");
            click("每日"); await("時間分配"); awaitSettled(); click("Chat"); await("你想問什麼？"); awaitSettled();
            assertEditor("例如：今天哪段時間最專注？", "Synthetic day question");
            click("本週"); await("你想問什麼？"); awaitSettled();
            assertEditor("例如：今天哪段時間最專注？", "");
            stage = "persisted daily rating and failed replacement";
            click("每日"); await("我的回顧"); awaitSettled(); click("專注"); await("已記下你的回顧"); awaitSettled();
            assertSelected("專注，已選擇");
            click("Flow"); await("開始一段專注"); awaitSettled(); click("每日"); await("我的回顧"); awaitSettled();
            assertSelected("專注，已選擇");
            reveal("專注，已選擇"); capture("daily-rating-selected");
            Field credentials = MainActivity.class.getDeclaredField("credentials"); credentials.setAccessible(true);
            Object valid = credentials.get(activity);
            test.runOnMainSync(() -> { try { credentials.set(activity, new Credentials(endpoint, "b".repeat(64))); } catch (Exception e) { throw new RuntimeException(e); } });
            try { click("分心"); await("伺服器拒絕這把金鑰"); assertSelected("專注，已選擇"); }
            finally { test.runOnMainSync(() -> { try { credentials.set(activity, valid); } catch (Exception e) { throw new RuntimeException(e); } }); }
            capture("daily-rating");
            stage = "durable focus mutation";
            click("Flow"); await("開始一段專注");
            test.runOnMainSync(() -> edits().get(0).setText("Synthetic native focus"));
            click("開始專注"); await("儲存並結束"); capture("flow-running");
            click("每日"); await("時間分配"); click("Flow"); await("Synthetic native focus");
            click("儲存並結束"); await("開始一段專注");
            stage = "reflection encrypted queue sync without mood entry";
            click("時間軸"); await("活動"); awaitSettled();
            if(has("記下此刻（可離線）")) throw new IllegalStateException("Mood entry remains on timeline");
            setEditor("哪件事讓你恢復精神？", "Synthetic queue sync reflection");
            click("儲存這一天的反思"); await("反思已加密加入佇列");
            click("同步並重新整理"); await("已同步");
            assertConsentOff();
            if ("yes".equals(args.getString("offline"))) offlineCacheCheck();
        } catch (Exception error) {
            throw new IllegalStateException("Connected synthetic check failed at " + stage, error);
        } finally {
            if (activity != null) test.runOnMainSync(() -> activity.finish());
            synchronized (SyncEngine.INSTANCE.getLock()) {
                SyncEngine.INSTANCE.clearLocal(test.getTargetContext());
                new CredentialStore(test.getTargetContext()).clear();
            }
            // Keep only the successful synthetic key's metadata for the web evidence;
            // root revokes it after capture. No token is written to artifacts.
            if(pairingId!=null && !pairingPassed) fixtureOwner.request("/v1/devices/"+pairingId,null,"DELETE");
        }
    }
    private void rulePreviewCheck(PocketLogApi owner) throws Exception {
        stage="unsaved rule preview";
        JSONObject before=owner.request("/v1/workspace",null,"GET"), draft=new JSONObject(before.toString());
        org.json.JSONArray rules=new org.json.JSONArray()
            .put(new JSONObject().put("id","synthetic-disabled").put("category","work").put("app","org.example.reader").put("title_contains",JSONObject.NULL).put("enabled",false))
            .put(new JSONObject().put("id","synthetic-title").put("category","learning").put("app",JSONObject.NULL).put("title_contains","kotlin").put("enabled",true))
            .put(new JSONObject().put("id","synthetic-app").put("category","work").put("app","org.example.reader").put("title_contains",JSONObject.NULL).put("enabled",true));
        draft.put("rules",rules);
        java.lang.reflect.Method editor=MainActivity.class.getDeclaredMethod("categoryRulesEditor",JSONObject.class);editor.setAccessible(true);
        test.runOnMainSync(()->{try{editor.invoke(activity,draft);}catch(Exception e){throw new RuntimeException(e);}});
        await("分類與自動規則");click("試算規則（不儲存）");await("試算分類規則");
        setEditor("例如 org.mozilla.firefox","org.example.reader");setEditor("例如 閱讀 Kotlin 文件","Learn KOTLIN");
        click("試算規則");await("範例將分類為「學習」");await("命中第 2 條規則");awaitSettled();capture("rule-preview-match");
        setEditor("例如 閱讀 Kotlin 文件","Planning");await("範例已變更，請重新試算");
        click("試算規則");await("範例將分類為「工作」");await("命中第 3 條規則");awaitSettled();
        setEditor("例如 org.mozilla.firefox","ORG.EXAMPLE.READER");click("試算規則");await("範例將分類為「未分類」");await("沒有命中已啟用的規則");awaitSettled();capture("rule-preview-no-match");
        setEditor("例如 org.mozilla.firefox","");click("試算規則");
        test.runOnMainSync(()->{if(editor("例如 org.mozilla.firefox").getError()==null)throw new IllegalStateException("Missing sample validation");});
        if(!before.toString().equals(owner.request("/v1/workspace",null,"GET").toString()))throw new IllegalStateException("Preview saved draft preferences");
        assertConsentOff();
    }
    private void replayUnavailableCheck(PocketLogApi owner) throws Exception {
        stage="replay stale resource recovery";
        long now=System.currentTimeMillis();
        String path="/v1/replay?from_ms="+(now-86400000)+"&to_ms="+now;
        org.json.JSONArray images=owner.request(path,null,"GET").getJSONArray("images");
        if(images.length()!=2) throw new IllegalStateException("Exactly two synthetic thumbnails required");
        for(int i=0;i<2;i++) if(!images.getJSONObject(i).getString("id").startsWith("synthetic-replay-") || !images.getJSONObject(i).getString("app").equals("org.example.replayproof")) throw new IllegalStateException("Non-synthetic archive rejected");
        java.lang.reflect.Method replay=MainActivity.class.getDeclaredMethod("replayPage");replay.setAccessible(true);
        test.runOnMainSync(()->{try{replay.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});
        await("短期縮圖回放");await("org.example.replayproof");awaitSettled();
        owner.request("/v1/replay/"+images.getJSONObject(0).getString("id"),null,"DELETE");
        click("org.example.replayproof");await("這張縮圖已到期或已被刪除");awaitSettled();
        if(has("伺服器沒有這項功能")) throw new IllegalStateException("Missing image still suggests server upgrade");
        final int[] rows={0};test.runOnMainSync(()->{for(View v:views())if(v instanceof TextView && ((TextView)v).getText().toString().equals("org.example.replayproof"))rows[0]++;});
        if(rows[0]!=1)throw new IllegalStateException("Stale replay row retained");
        capture("replay-unavailable");
        click("org.example.replayproof");awaitSettled();
        Field owned=MainActivity.class.getDeclaredField("replayDialog");owned.setAccessible(true);
        android.app.AlertDialog preview=(android.app.AlertDialog)owned.get(activity);
        if(preview==null || !preview.isShowing())throw new IllegalStateException("Surviving thumbnail did not open");
        final Bitmap[] shown={null};
        test.runOnMainSync(()->{
            if((preview.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)==0)throw new IllegalStateException("Insecure replay dialog");
            List<View> tree=new ArrayList<>();collect(preview.getWindow().getDecorView(),tree);
            for(View v:tree)if(v instanceof android.widget.ImageView && ((android.widget.ImageView)v).getDrawable() instanceof android.graphics.drawable.BitmapDrawable)shown[0]=((android.graphics.drawable.BitmapDrawable)((android.widget.ImageView)v).getDrawable()).getBitmap();
            preview.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        });
        await("沒有可回放的縮圖");awaitSettled();
        if(shown[0]==null || !shown[0].isRecycled() || owned.get(activity)!=null)throw new IllegalStateException("Delete did not release displayed media");
        if(owner.request(path,null,"GET").getJSONArray("images").length()!=0)throw new IllegalStateException("Surviving image not deleted");
        assertConsentOff();capture("replay-empty");
    }
    private void assertAuthentication(PocketLogApi owner,String id,boolean expected) throws Exception {
        org.json.JSONArray devices=owner.request("/v1/devices",null,"GET").getJSONArray("devices");
        for(int i=0;i<devices.length();i++) {
            JSONObject device=devices.getJSONObject(i);
            if(id.equals(device.getString("id"))) {
                boolean present=!device.isNull("last_authenticated_ms");
                if(present!=expected || present && device.getLong("last_authenticated_ms")%60_000!=0) throw new IllegalStateException("Authentication metadata mismatch");
                return;
            }
        }
        throw new IllegalStateException("Synthetic pairing device absent");
    }
    private void captureStatusCheck() throws Exception {
        CaptureDiagnostics.INSTANCE.clear();
        long now=System.currentTimeMillis();
        CaptureDiagnostics.INSTANCE.complete(CaptureDiagnostics.INSTANCE.begin(now),CaptureDiagnostics.Outcome.WINDOW_REJECTED,now);
        click("設定"); await("畫面情境"); awaitSettled();
        click("擷取狀態與排除原因"); await("視窗已依隱私規則排除");
        if(!has("整個視窗") || !has("尚無成功排入的情境") || !has("此頁不會啟用任何紀錄"))
            throw new IllegalStateException("Capture rejection guidance missing");
        capture("capture-status-exclusion");
        CaptureDiagnostics.INSTANCE.complete(CaptureDiagnostics.INSTANCE.begin(now),CaptureDiagnostics.Outcome.QUEUED_TEXT,now);
        CaptureDiagnostics.INSTANCE.complete(CaptureDiagnostics.INSTANCE.begin(now),CaptureDiagnostics.Outcome.SYSTEM_FAILED,now);
        click("重新檢查擷取狀態"); await("系統擷取未成功");
        if(has("尚無成功排入的情境")) throw new IllegalStateException("Failure erased previous queue success");
        capture("capture-status-failure");
        click("返回"); await("畫面情境"); awaitSettled();
        click("支援"); await("沒有活動時"); awaitSettled();
        click("擷取狀態與排除原因"); await("系統擷取未成功");
        reveal("重新檢查擷取狀態");
        click("返回"); await("沒有活動時"); awaitSettled();
        CaptureDiagnostics.INSTANCE.clear(); assertConsentOff();
    }
    private void pendingContextCheck() throws Exception {
        stage = "server-pending context list and secure inspector";
        click("時間軸"); await("活動"); awaitSettled(); click("清單");
        Field loaded=MainActivity.class.getDeclaredField("loaded"); loaded.setAccessible(true);
        org.json.JSONArray cards=((JSONObject)loaded.get(activity)).getJSONArray("cards");
        JSONObject pending=null;
        for(int i=0;i<cards.length();i++) if(cards.getJSONObject(i).optBoolean("pending_context")) {pending=cards.getJSONObject(i);break;}
        if(pending==null) throw new IllegalStateException("Pending synthetic API fixture absent");
        String title=pending.getString("title");
        await("有待處理內容");
        test.runOnMainSync(() -> {
            for(View v:views()) if(v instanceof TextView && title.contentEquals(((TextView)v).getText())) {
                View row=v;
                while(!row.isClickable() && row.getParent() instanceof View) row=(View)row.getParent();
                row.requestRectangleOnScreen(new android.graphics.Rect(0,0,row.getWidth(),row.getHeight()),true); return;
            }
            throw new IllegalStateException("Pending activity row absent");
        }); test.waitForIdleSync(); capture("pending-context-list");
        click(title);
        long until=System.currentTimeMillis()+5000;
        boolean explained=false;
        while(System.currentTimeMillis()<until) {
            android.view.accessibility.AccessibilityNodeInfo root=test.getUiAutomation().getRootInActiveWindow();
            if(accessibilityContains(root,"12 分鐘",0) && accessibilityContains(root,"不代表 AI 正在生成",0)) {explained=true;break;}
            Thread.sleep(100);
        }
        test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        if(!explained) throw new IllegalStateException("Pending inspector explanation absent");
        assertConsentOff();
        click("活動檢視：時間軸");
    }
    private boolean accessibilityContains(android.view.accessibility.AccessibilityNodeInfo node,String value,int depth) {
        if(node==null || depth>32) return false;
        if(node.getText()!=null && node.getText().toString().contains(value)) return true;
        for(int i=0;i<node.getChildCount();i++) if(accessibilityContains(node.getChild(i),value,depth+1)) return true;
        return false;
    }
    private void categoryVisibilityCheck() throws Exception {
        click("時間軸"); await("活動"); awaitSettled();
        click("前一天"); await("顯示全部"); awaitSettled();
        Field loaded=MainActivity.class.getDeclaredField("loaded"); loaded.setAccessible(true);
        JSONObject data=(JSONObject)loaded.get(activity);
        org.json.JSONArray cards=data.getJSONArray("cards");
        if(cards.length()<2) throw new IllegalStateException("Synthetic category fixture absent");
        String hiddenCategory=cards.getJSONObject(0).getString("category");
        int hiddenCount=0;
        for(int i=0;i<cards.length();i++) if(hiddenCategory.equals(cards.getJSONObject(i).getString("category"))) hiddenCount++;
        final String[] categoryAction={null};
        test.runOnMainSync(() -> {
            for(View v:views()) if(v.getContentDescription()!=null && v.getContentDescription().toString().endsWith("顯示中，點按隱藏")) {
                categoryAction[0]=v.getContentDescription().toString(); break;
            }
        });
        // Categories follow first appearance in the synthetic response.
        click(categoryAction[0]);
        await("顯示 "+(cards.length()-hiddenCount)+" / "+cards.length());
        click("清單");
        for(int i=0;i<cards.length();i++) if(hiddenCategory.equals(cards.getJSONObject(i).getString("category")) && has(cards.getJSONObject(i).getString("title")))
            throw new IllegalStateException("Hidden category remains in list");
        capture("category-filter-list");
        Instrumentation.ActivityMonitor monitor=test.addMonitor(MainActivity.class.getName(),null,false);
        test.runOnMainSync(() -> activity.recreate());
        Activity recreated=test.waitForMonitorWithTimeout(monitor,10_000); test.removeMonitor(monitor);
        if(recreated==null) throw new IllegalStateException("Filter recreation failed");
        activity=recreated; await("顯示 "+(cards.length()-hiddenCount)+" / "+cards.length()); awaitSettled();
        final boolean[] selectedList={false};
        test.runOnMainSync(() -> {
            for(View v:views()) if("活動檢視：清單，已選取".contentEquals(v.getContentDescription()==null?"":v.getContentDescription())) selectedList[0]=v.isSelected();
        });
        if(!selectedList[0]) throw new IllegalStateException("List mode lost on recreation");
        // Hide every remaining visible category through its accessible action.
        for(int attempt=0;attempt<100;attempt++) {
            final String[] action={null};
            test.runOnMainSync(() -> {
                for(View v:views()) if(v.getContentDescription()!=null && v.getContentDescription().toString().endsWith("顯示中，點按隱藏")) {
                    action[0]=v.getContentDescription().toString();break;
                }
            });
            if(action[0]==null) break;
            click(action[0]);
        }
        await("這些分類已隱藏"); await("顯示 0 / "+cards.length());
        capture("category-filter-empty"); click("重設分類篩選"); await("顯示 "+cards.length()+" / "+cards.length());
        if(has("這些分類已隱藏")) throw new IllegalStateException("Reset failed");
        click("活動檢視：時間軸"); reveal("活動檢視：時間軸，已選取"); capture("category-filter-timeline");
        click("後一天"); await("活動"); awaitSettled();
    }
    private void reveal(String label) {
        test.runOnMainSync(() -> {
            for(View v:views()) if(v.getContentDescription()!=null && label.contentEquals(v.getContentDescription())) {
                v.requestRectangleOnScreen(new android.graphics.Rect(0,0,v.getWidth(),v.getHeight()), true); return;
            }
        }); test.waitForIdleSync();
    }
    private void revealAction(String label) {
        test.runOnMainSync(() -> {
            for(View v:views()) if(v instanceof TextView && label.contentEquals(((TextView)v).getText())) {
                v.requestRectangleOnScreen(new android.graphics.Rect(0,0,v.getWidth(),v.getHeight()),true); return;
            }
            throw new IllegalStateException("Action to reveal absent");
        }); test.waitForIdleSync();
    }
    private void revealEditor(String hint) {
        test.runOnMainSync(() -> {
            EditText e=editor(hint);
            e.requestRectangleOnScreen(new android.graphics.Rect(0,0,e.getWidth(),e.getHeight()),true);
        }); test.waitForIdleSync();
    }
    private void shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor pipe=test.getUiAutomation().executeShellCommand(command);
             java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe)) {
            while(in.read()!=-1) { /* commands contain no private data */ }
        }
    }
    private void offlineCacheCheck() throws Exception {
        stage="advancing current-day offline cache and queued reflection";
        android.content.Context context=test.getTargetContext();
        Credentials c=new CredentialStore(context).load();
        java.time.ZoneId zone=java.time.ZoneId.systemDefault();
        java.time.LocalDate day=TimelineMath.INSTANCE.journalDay(java.time.ZonedDateTime.now());
        long from=TimelineMath.INSTANCE.dayStart(day,zone);
        String prefix="/v1/timeline?from_ms="+from+"&to_ms=";
        String id="reflection-"+day;
        org.json.JSONObject body=new org.json.JSONObject().put("id",id).put("at_ms",from+1000).put("text","Synthetic cached original");
        new PocketLogApi(c).request("/v1/reflections",body,"POST");
        SyncEngine.INSTANCE.readTimeline(context,prefix+System.currentTimeMillis());
        androidx.work.WorkManager.getInstance(context).cancelAllWork().getResult().get();
        try {
            shell("cmd connectivity airplane-mode enable"); shell("svc wifi disable");
            android.net.ConnectivityManager manager=context.getSystemService(android.net.ConnectivityManager.class);
            long deadline=System.currentTimeMillis()+10_000;
            while(manager.getActiveNetwork()!=null && System.currentTimeMillis()<deadline) Thread.sleep(100);
            if(manager.getActiveNetwork()!=null) throw new IllegalStateException("Emulator stayed online");
            synchronized(SyncEngine.INSTANCE.getLock()) {
                JournalQueue queue=new JournalQueue(context);
                body.put("text","Synthetic pending replacement");
                queue.enqueue(c,"/v1/reflections",body,System.currentTimeMillis());
                org.json.JSONObject offline=SyncEngine.INSTANCE.readTimeline(context,prefix+System.currentTimeMillis());
                if(!offline.optBoolean("offline") || !offline.optBoolean("pending_reflections") ||
                   !offline.getJSONArray("reflections").toString().contains("Synthetic pending replacement"))
                    throw new IllegalStateException("Queued update or advancing cache missing");
                Thread.sleep(2); body.put("text","");
                queue.enqueue(c,"/v1/reflections",body,System.currentTimeMillis());
                org.json.JSONObject deleted=SyncEngine.INSTANCE.readTimeline(context,prefix+System.currentTimeMillis());
                for(int i=0;i<deleted.getJSONArray("reflections").length();i++)
                    if(id.equals(deleted.getJSONArray("reflections").getJSONObject(i).getString("id")))
                        throw new IllegalStateException("Queued deletion resurrected cached reflection");
            }
        } finally { shell("cmd connectivity airplane-mode disable"); shell("svc wifi enable"); }
    }
    private int briefCount() throws Exception {
        long now=System.currentTimeMillis();
        Credentials c=new CredentialStore(test.getTargetContext()).load();
        return new PocketLogApi(c).request("/v1/briefs?from_ms="+(now-7L*86_400_000)+"&to_ms="+now,null,"GET").getJSONArray("briefs").length();
    }
    private void assertConsentOff() {
        RecordingSettings s = new RecordingSettings(test.getTargetContext());
        if (s.getEnabled() || s.getContextEnabled() || s.getImagesEnabled() || s.getReplayConsent() || s.getAnalysisConsent() || s.getExternalAiConsent())
            throw new IllegalStateException("Connection enabled capture consent");
    }
    private List<View> views() {
        List<View> out = new ArrayList<>(); collect(activity.getWindow().getDecorView(), out); return out;
    }
    private void collect(View view, List<View> out) {
        if (view.getVisibility() != View.VISIBLE) return;
        out.add(view); if (view instanceof ViewGroup) { ViewGroup g = (ViewGroup)view; for (int i=0;i<g.getChildCount();i++) collect(g.getChildAt(i), out); }
    }
    private List<EditText> edits() {
        List<EditText> out = new ArrayList<>(); for (View v : views()) if (v instanceof EditText) out.add((EditText)v); return out;
    }
    private void fillConnection(String endpoint, String key) {
        test.runOnMainSync(() -> { List<EditText> e = edits(); e.get(0).setText(endpoint); e.get(1).setText(key); });
    }
    private boolean has(String text) {
        final boolean[] found = {false}; test.runOnMainSync(() -> {
            for (View v : views()) if (v instanceof TextView && ((TextView)v).getText().toString().contains(text)) { found[0]=true; break; }
        }); return found[0];
    }
    private void await(String text) throws Exception {
        long until = System.currentTimeMillis()+30_000;
        while (System.currentTimeMillis()<until) { test.waitForIdleSync(); if (has(text)) return; Thread.sleep(100); }
        throw new IllegalStateException("Expected state absent: " + text);
    }
    private void click(String label) {
        test.runOnMainSync(() -> {
            for (View v : views()) {
                boolean matches = v instanceof TextView && ((TextView)v).getText().toString().equals(label);
                matches |= v.getContentDescription()!=null && (v.getContentDescription().toString().equals(label) || v.getContentDescription().toString().equals(label+"，目前頁面") || v.getContentDescription().toString().equals(label+"，未選擇") || v.getContentDescription().toString().equals(label+"，已選擇"));
                if (!matches) continue;
                View target=v;
                while (!target.isClickable() && target.getParent() instanceof View) target=(View)target.getParent();
                if (target.isClickable() && target.isEnabled()) { target.performClick(); return; }
            }
            throw new IllegalStateException("Action absent: " + label);
        });
    }
    private void awaitSettled() throws Exception {
        long until=System.currentTimeMillis()+30_000;
        while (System.currentTimeMillis()<until) {
            test.waitForIdleSync(); final boolean[] loading={false};
            test.runOnMainSync(() -> { for(View v:views()) if(v instanceof ProgressBar && ((ProgressBar)v).isIndeterminate()) loading[0]=true; });
            if (!loading[0]) return; Thread.sleep(100);
        }
        throw new IllegalStateException("Page did not settle");
    }
    private EditText editor(String hint) {
        for(EditText e:edits()) if(hint.contentEquals(e.getHint())) return e;
        throw new IllegalStateException("Editor absent");
    }
    private void setEditor(String hint,String value) { test.runOnMainSync(() -> editor(hint).setText(value)); }
    private void assertEditor(String hint,String value) {
        test.runOnMainSync(() -> { if(!editor(hint).getText().toString().equals(value)) throw new IllegalStateException("Draft changed"); });
    }
    private void assertSelected(String label) {
        test.runOnMainSync(() -> {
            for(View v:views()) if(v.getContentDescription()!=null && label.contentEquals(v.getContentDescription()) && v.isSelected()) return;
            throw new IllegalStateException("Saved rating not selected");
        });
    }
    private void capture(String name) throws Exception {
        String variant = args.getString("variant", "phone");
        if (!variant.matches("[a-z0-9-]{1,40}")) throw new IllegalArgumentException("Bad variant");
        File folder = new File(test.getTargetContext().getExternalFilesDir(null), "ux-connected");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException("Snapshot folder unavailable");
        final Exception[] failure = {null};
        test.runOnMainSync(() -> {
            Bitmap bitmap=null;
            try {
                if ((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE)==0) throw new IllegalStateException("Secure flag missing");
                View view=activity.getWindow().getDecorView();
                bitmap=Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888); view.draw(new Canvas(bitmap));
                try (FileOutputStream out=new FileOutputStream(new File(folder,variant+"-"+name+".png"))) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG,100,out)) throw new IllegalStateException("PNG failed");
                }
            } catch (Exception e) { failure[0]=e; } finally { if (bitmap!=null) bitmap.recycle(); }
        });
        if (failure[0]!=null) throw failure[0];
    }
}

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
    private String pendingCardTitle;
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
            if ("yes".equals(args.getString("appNamesOnly"))) { appNamesCheck(fixtureOwner); return; }
            if ("yes".equals(args.getString("weeklyLimitOnly"))) { weeklyLimitCheck(fixtureOwner); return; }
            if ("yes".equals(args.getString("goalsConflictOnly"))) { goalsConflictCheck(fixtureOwner); return; }
            if ("yes".equals(args.getString("classificationConflictOnly"))) { classificationConflictCheck(fixtureOwner); return; }
            if ("yes".equals(args.getString("goalsThenCategoriesOnly"))) { goalsThenCategoriesCheck(fixtureOwner); return; }
            if ("yes".equals(args.getString("controlFailureOnly"))) { controlFailureCheck(fixtureOwner, endpoint); return; }
            if ("yes".equals(args.getString("captureAiOnly"))) { captureAiConsentCheck(fixtureOwner); return; }
            if ("yes".equals(args.getString("probeOnly"))) { providerProbeCheck(fixtureOwner); return; }
            if ("yes".equals(args.getString("previewOnly"))) { rulePreviewCheck(fixtureOwner); return; }
            if ("yes".equals(args.getString("replayOnly"))) { replayUnavailableCheck(fixtureOwner); return; }
            if ("yes".equals(args.getString("cardPendingOnly"))) { pendingCardCheck(fixtureOwner); return; }
            if ("yes".equals(args.getString("pendingOnly"))) { pendingContextCheck(); return; }
            for (String[] page : new String[][] {{"每日", "時間分配"}, {"每週", "一週節奏"}, {"Chat", "你想問什麼？"}, {"Flow", "開始一段專注"}, {"Agents", "匯入"}, {"支援", "這支手機的狀態"}, {"設定", "手機紀錄"}}) {
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
            throw new IllegalStateException("Connected synthetic check failed at " + stage + ": " + error.getClass().getSimpleName() + " " + error.getMessage(), error);
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
    private void weeklyLimitCheck(PocketLogApi owner) throws Exception {
        stage="weekly API truncation transparency";
        test.setInTouchMode(true);
        java.time.ZoneId zone=java.time.ZoneId.systemDefault();java.time.LocalDate date=java.time.LocalDate.now(zone).minusDays(1);
        long start=date.atTime(12,0).atZone(zone).toInstant().toEpochMilli();
        for(int batch=0;batch<1001;batch+=100){org.json.JSONArray rows=new org.json.JSONArray();for(int i=batch;i<Math.min(1001,batch+100);i++)rows.put(new JSONObject().put("id","synthetic-week-limit-"+date+"-"+i).put("app","org.example.weeklimit").put("category","learning").put("start_ms",start+i*1000).put("end_ms",start+(i+1)*1000));owner.request("/v1/activities",new JSONObject().put("activities",rows),"POST");}
        long from=date.atTime(4,0).atZone(zone).toInstant().toEpochMilli(),to=date.plusDays(1).atTime(4,0).atZone(zone).toInstant().toEpochMilli();
        JSONObject raw=owner.request("/v1/timeline?from_ms="+from+"&to_ms="+to,null,"GET");
        if(!raw.getBoolean("truncated")||raw.getJSONArray("cards").length()!=1000)throw new IllegalStateException("Real API display limit not reached");
        Field selected=MainActivity.class.getDeclaredField("day");selected.setAccessible(true);test.runOnMainSync(()->{try{selected.set(activity,date);}catch(Exception e){throw new RuntimeException(e);}});
        click("每週");await("一週節奏");awaitSettled();
        if("yes".equals(args.getString("expectLegacy"))){if(has("部分資料")||has("顯示上限"))throw new IllegalStateException("Legacy unexpectedly warns");capture("weekly-limit-legacy");return;}
        await("本週有 1 天資料達顯示上限");if(!has("部分資料")||!has("已載入的記錄"))throw new IllegalStateException("Partial day or totals not identified");
        revealTextContaining("本週有 1 天資料達顯示上限");capture("weekly-limit-warning");
        revealTextContaining("部分資料");capture("weekly-limit-day");
        try{
            shell("svc wifi disable");shell("svc data disable");shell("cmd connectivity airplane-mode enable");
            click("時間軸");await("離線：顯示加密快取");awaitSettled();click("每週");await("部分日期為離線快取");awaitSettled();
            if(!has("本週有 1 天資料達顯示上限")||!has("部分資料")||!has("已載入的記錄"))throw new IllegalStateException("Cached week lost partial-data warning");
        }finally{shell("cmd connectivity airplane-mode disable");shell("svc wifi enable");awaitUnmetered();}
        test.runOnMainSync(()->{try{selected.set(activity,date.minusWeeks(1));}catch(Exception e){throw new RuntimeException(e);}});
        click("每週");await("一週節奏");awaitSettled();
        if(has("本週有 1 天資料達顯示上限")||has("已載入的記錄")||!has("本週已記錄"))throw new IllegalStateException("Warning remained on a complete different week");
        assertConsentOff();
    }
    private void appNamesCheck(PocketLogApi owner) throws Exception {
        stage="local readable app names without journal mutation";
        String known="com.android.settings",unknown="org.example.uninstalled_for_ui";
        String expected=test.getTargetContext().getPackageManager().getApplicationLabel(test.getTargetContext().getPackageManager().getApplicationInfo(known,0)).toString();
        if(expected.equals(known)||expected.isBlank())throw new IllegalStateException("Visible installed system-app fixture required");
        String id="local-app-label-"+java.util.UUID.randomUUID(),editedId=id+"-edit",missingId=id+"-missing";
        long now=System.currentTimeMillis(),from=now-3600000;
        owner.request("/v1/activities",new JSONObject().put("activities",new org.json.JSONArray()
            .put(new JSONObject().put("id",id).put("app",known).put("category","work").put("start_ms",now-900000).put("end_ms",now-840000))
            .put(new JSONObject().put("id",editedId).put("app",known).put("category","work").put("start_ms",now-830000).put("end_ms",now-770000))
            .put(new JSONObject().put("id",missingId).put("app",unknown).put("category","rest").put("start_ms",now-760000).put("end_ms",now-700000))),"POST");
        String chosen="合成：自訂設定活動";
        owner.request("/v1/cards",new JSONObject().put("id",editedId).put("title",chosen).put("summary","Synthetic custom title stays canonical").put("category","work"),"POST");
        click("每日");await("時間分配");awaitSettled();click("時間軸");await("活動");awaitSettled();
        click("活動檢視：清單");awaitSettled();
        String displayed="yes".equals(args.getString("expectLegacy"))?known:expected;
        final View[] row={null};test.runOnMainSync(()->{for(View v:views())if(v.getContentDescription()!=null&&v.getContentDescription().toString().startsWith(displayed+"，")&&v.getContentDescription().toString().contains("僅 app 使用紀錄")){row[0]=v;break;}if(row[0]==null)throw new IllegalStateException("Usage card label absent");row[0].requestRectangleOnScreen(new android.graphics.Rect(0,0,row[0].getWidth(),row[0].getHeight()),true);});test.waitForIdleSync();capture("local-app-name-list");
        if(!has(chosen)||!has(unknown))throw new IllegalStateException("Edited or unknown-package title changed");
        Field loadedField=MainActivity.class.getDeclaredField("loaded");loadedField.setAccessible(true);JSONObject data=(JSONObject)loadedField.get(activity);
        org.json.JSONArray cards=data.getJSONArray("cards");JSONObject card=null;
        for(int j=0;j<cards.length();j++)if(id.equals(cards.getJSONObject(j).getString("id")))card=cards.getJSONObject(j);
        if(card==null||!known.equals(card.getString("title"))||!"app_only".equals(card.getString("source"))||card.has("app_label")||card.has("display_title"))throw new IllegalStateException("Canonical loaded journal mutated by labels");
        org.json.JSONArray raw=owner.request("/v1/timeline?from_ms="+from+"&to_ms="+now,null,"GET").getJSONArray("cards");boolean found=false;
        for(int j=0;j<raw.length();j++)if(id.equals(raw.getJSONObject(j).getString("id"))){found=true;if(!known.equals(raw.getJSONObject(j).getString("title")))throw new IllegalStateException("Server title rewritten");}if(!found)throw new IllegalStateException("Synthetic server activity missing");
        if("yes".equals(args.getString("expectLegacy")))return;
        test.runOnMainSync(()->row[0].performClick());awaitProbeDialog("App："+expected);if(!probeDialogText().contains("套件："+known))throw new IllegalStateException("Raw app identity hidden");
        test.runOnMainSync(()->{List<View> all=new ArrayList<>();collect(probeDialog(),all);for(View v:all)if(v instanceof EditText e){if(!known.equals(e.getText().toString()))throw new IllegalStateException("Editor silently used derived local label");return;}throw new IllegalStateException("Title editor missing");});captureProbeDialog("local-app-name-identity");probeDialogClick("取消");
        java.lang.reflect.Method itemMethod=MainActivity.class.getDeclaredMethod("item",JSONObject.class);itemMethod.setAccessible(true);final JSONObject sourceCard=card;
        test.runOnMainSync(()->{try{TimelineItem item=(TimelineItem)itemMethod.invoke(activity,sourceCard);if(!expected.equals(item.getTitle()))throw new IllegalStateException("Day canvas label differs from list");}catch(Exception e){throw new RuntimeException(e);}});
        click("活動概覽");awaitProbeDialog("活動概覽");test.runOnMainSync(()->{List<View> all=new ArrayList<>();collect(probeDialog(),all);for(View v:all)if(v instanceof android.widget.ListView l){l.performItemClick(l.getChildAt(0),0,l.getAdapter().getItemId(0));return;}throw new IllegalStateException("Overview range chooser absent");});awaitProbeDialog("常用 app");if(!probeDialogText().contains(expected+" · "))throw new IllegalStateException("Overview package label absent");probeDialogClick("關閉");
        try {
            shell("svc wifi disable");shell("svc data disable");shell("cmd connectivity airplane-mode enable");
            click("每日");await("時間分配");awaitSettled();click("時間軸");await("離線：顯示加密快取");awaitSettled();
            if(!has(expected)||!has(chosen)||!has(unknown))throw new IllegalStateException("Offline labels/custom/unknown titles changed");
        } finally {shell("cmd connectivity airplane-mode disable");shell("svc wifi enable");awaitUnmetered();}
        assertConsentOff();
    }
    private void goalsConflictCheck(PocketLogApi owner) throws Exception {
        stage="native goal draft conflict recovery";
        JSONObject original=owner.request("/v1/workspace",null,"GET");
        int focus=(original.getInt("focus_minutes")+37)%1441,budget=(original.getInt("distraction_minutes")+19)%1441;
        String remoteName="合成：網站分類已更新";
        try {
            click("設定");await("目標與分類");click("目標與分類");await("儲存目標");awaitSettled();
            setEditor("270",String.valueOf(focus));setEditor("60",String.valueOf(budget));
            JSONObject r=owner.request("/v1/workspace",null,"GET");r.getJSONArray("categories").getJSONObject(0).put("name",remoteName);owner.request("/v1/workspace",r,"PUT");
            revealAction("儲存目標");click("儲存目標");
            if("yes".equals(args.getString("expectLegacy"))) {
                await("資料已在其他裝置更新，請重新載入後再試。");awaitSettled();assertEditor("270",String.valueOf(focus));assertEditor("60",String.valueOf(budget));
                click("儲存目標");await("資料已在其他裝置更新，請重新載入後再試。");awaitSettled();
                if(has("保留最新分類，重試儲存目標"))throw new IllegalStateException("Legacy unexpectedly recovers goals");
                r=owner.request("/v1/workspace",null,"GET");if(r.getInt("focus_minutes")==focus||!remoteName.equals(r.getJSONArray("categories").getJSONObject(0).getString("name")))throw new IllegalStateException("Legacy modified conflict");
                revealEditor("270");capture("goals-legacy-conflict");return;
            }
            await("分類設定已更新，目標草稿仍在");awaitSettled();revealFullRow("保留最新分類，重試儲存目標");capture("goals-classification-conflict");
            revealAction("返回編輯目標");click("返回編輯目標");await("儲存目標");awaitSettled();assertEditor("270",String.valueOf(focus));assertEditor("60",String.valueOf(budget));
            revealAction("儲存目標");click("儲存目標");await("分類設定已更新，目標草稿仍在");awaitSettled();
            revealAction("保留最新分類，重試儲存目標");click("保留最新分類，重試儲存目標");await("網站與手機的共用目標已更新");awaitSettled();
            r=owner.request("/v1/workspace",null,"GET");if(r.getInt("focus_minutes")!=focus||r.getInt("distraction_minutes")!=budget||!remoteName.equals(r.getJSONArray("categories").getJSONObject(0).getString("name")))throw new IllegalStateException("Goals retry reverted remote classification");
            setEditor("270",String.valueOf((focus+1)%1441));setEditor("60",String.valueOf((budget+1)%1441));
            r=owner.request("/v1/workspace",null,"GET");r.put("focus_minutes",(focus+2)%1441);owner.request("/v1/workspace",r,"PUT");
            revealAction("儲存目標");click("儲存目標");await("遠端目標也已變更");awaitSettled();
            revealAction("確認以我的草稿取代遠端目標");click("確認以我的草稿取代遠端目標");awaitProbeDialog("取代剛讀取的遠端目標？");captureProbeDialog("goals-replace-confirm");probeDialogClick("保留草稿");
            if(owner.request("/v1/workspace",null,"GET").getInt("focus_minutes")!=(focus+2)%1441)throw new IllegalStateException("Goal cancel wrote server");
            click("確認以我的草稿取代遠端目標");awaitProbeDialog("取代剛讀取的遠端目標？");
            r=owner.request("/v1/workspace",null,"GET");r.getJSONArray("categories").getJSONObject(0).put("name",remoteName+"二");owner.request("/v1/workspace",r,"PUT");
            probeDialogClick("取代目標");await("遠端目標也已變更");awaitSettled();
            r=owner.request("/v1/workspace",null,"GET");if(r.getInt("focus_minutes")!=(focus+2)%1441||!(remoteName+"二").equals(r.getJSONArray("categories").getJSONObject(0).getString("name")))throw new IllegalStateException("Goal race overwrote remote");
            revealAction("確認以我的草稿取代遠端目標");click("確認以我的草稿取代遠端目標");awaitProbeDialog("取代剛讀取的遠端目標？");probeDialogClick("取代目標");await("網站與手機的共用目標已更新");awaitSettled();
            r=owner.request("/v1/workspace",null,"GET");if(r.getInt("focus_minutes")!=(focus+1)%1441||r.getInt("distraction_minutes")!=(budget+1)%1441||!(remoteName+"二").equals(r.getJSONArray("categories").getJSONObject(0).getString("name")))throw new IllegalStateException("Explicit goal replacement lost unrelated fields");
            setEditor("270",String.valueOf((focus+3)%1441));setEditor("60",String.valueOf((budget+3)%1441));
            r=owner.request("/v1/workspace",null,"GET");r.getJSONArray("categories").getJSONObject(0).put("name",remoteName+"離線");owner.request("/v1/workspace",r,"PUT");
            revealAction("儲存目標");click("儲存目標");await("分類設定已更新，目標草稿仍在");awaitSettled();
            shell("svc wifi disable");shell("svc data disable");shell("cmd connectivity airplane-mode enable");
            revealAction("重新核對最新設定");click("重新核對最新設定");await("無法載入");awaitSettled();
            click("返回編輯目標");await("儲存目標");awaitSettled();assertEditor("270",String.valueOf((focus+3)%1441));assertEditor("60",String.valueOf((budget+3)%1441));
            shell("cmd connectivity airplane-mode disable");shell("svc wifi enable");awaitUnmetered();
            revealAction("儲存目標");click("儲存目標");await("分類設定已更新，目標草稿仍在");awaitSettled();revealAction("保留最新分類，重試儲存目標");click("保留最新分類，重試儲存目標");await("網站與手機的共用目標已更新");awaitSettled();
            r=owner.request("/v1/workspace",null,"GET");if(r.getInt("focus_minutes")!=(focus+3)%1441||r.getInt("distraction_minutes")!=(budget+3)%1441||!(remoteName+"離線").equals(r.getJSONArray("categories").getJSONObject(0).getString("name")))throw new IllegalStateException("Offline goal recovery lost values or classification");assertConsentOff();
        } finally {
            shell("cmd connectivity airplane-mode disable");shell("svc wifi enable");awaitUnmetered();
            JSONObject current=owner.request("/v1/workspace",null,"GET");original.put("revision",current.getLong("revision"));owner.request("/v1/workspace",original,"PUT");
        }
    }
    private void classificationConflictCheck(PocketLogApi owner) throws Exception {
        stage="native classification draft conflict recovery";
        JSONObject original=owner.request("/v1/workspace",null,"GET");
        int focus=(original.getInt("focus_minutes")+31)%1441;
        String local="合成：分類草稿保留", remoteName="合成：遠端分類版本";
        try {
            click("設定");await("目標與分類");click("目標與分類");await("儲存目標");awaitSettled();
            click("編輯分類與自動規則");await("儲存分類與規則");awaitSettled();setEditor("最多 40 字",local);
            // Recursive editor construction must retain the original comparison baseline.
            revealAction("新增分類");click("新增分類");await("儲存分類與規則");awaitSettled();assertEditor("最多 40 字",local);
            JSONObject r=owner.request("/v1/workspace",null,"GET");r.put("focus_minutes",focus);owner.request("/v1/workspace",r,"PUT");
            revealAction("儲存分類與規則");click("儲存分類與規則");
            if("yes".equals(args.getString("expectLegacy"))) {
                await("資料已在其他裝置更新，請重新載入後再試。");awaitSettled();assertEditor("最多 40 字",local);
                click("儲存分類與規則");await("資料已在其他裝置更新，請重新載入後再試。");awaitSettled();
                if(has("保留最新目標，重試儲存分類"))throw new IllegalStateException("Legacy unexpectedly recovers");
                JSONObject saved=owner.request("/v1/workspace",null,"GET");if(local.equals(saved.getJSONArray("categories").getJSONObject(0).getString("name")))throw new IllegalStateException("Legacy overwrote conflict");
                capture("classification-legacy-conflict");return;
            }
            await("其他設定已更新，分類草稿仍在");awaitSettled();revealAction("其他設定已更新，分類草稿仍在");capture("classification-goals-conflict");revealFullRow("保留最新目標，重試儲存分類");capture("classification-goals-retry");
            revealAction("返回編輯草稿");click("返回編輯草稿");await("儲存分類與規則");awaitSettled();assertEditor("最多 40 字",local);
            revealAction("試算規則（不儲存）");click("試算規則（不儲存）");await("試算分類規則");click("返回");await("儲存分類與規則");awaitSettled();
            revealAction("儲存分類與規則");click("儲存分類與規則");await("其他設定已更新，分類草稿仍在");awaitSettled();
            revealAction("保留最新目標，重試儲存分類");click("保留最新目標，重試儲存分類");await("已儲存並同步到網站");awaitSettled();
            r=owner.request("/v1/workspace",null,"GET");if(r.getInt("focus_minutes")!=focus||!local.equals(r.getJSONArray("categories").getJSONObject(0).getString("name"))||r.getJSONArray("categories").length()!=original.getJSONArray("categories").length()+1)throw new IllegalStateException("Goal rebase lost draft/baseline/goals");
            click("編輯分類與自動規則");await("儲存分類與規則");awaitSettled();setEditor("最多 40 字",local+"二");
            r=owner.request("/v1/workspace",null,"GET");r.getJSONArray("categories").getJSONObject(0).put("name",remoteName);owner.request("/v1/workspace",r,"PUT");
            revealAction("儲存分類與規則");click("儲存分類與規則");await("遠端分類與規則也已變更");awaitSettled();
            revealAction("確認以我的草稿取代遠端分類");click("確認以我的草稿取代遠端分類");awaitProbeDialog("取代剛讀取的遠端分類與規則？");captureProbeDialog("classification-replace-confirm");probeDialogClick("保留草稿");
            if(!remoteName.equals(owner.request("/v1/workspace",null,"GET").getJSONArray("categories").getJSONObject(0).getString("name")))throw new IllegalStateException("Cancel wrote server");
            click("確認以我的草稿取代遠端分類");awaitProbeDialog("取代剛讀取的遠端分類與規則？");
            r=owner.request("/v1/workspace",null,"GET");r.put("focus_minutes",(focus+1)%1441);owner.request("/v1/workspace",r,"PUT");
            probeDialogClick("取代分類與規則");await("遠端分類與規則也已變更");awaitSettled();
            if(!remoteName.equals(owner.request("/v1/workspace",null,"GET").getJSONArray("categories").getJSONObject(0).getString("name")))throw new IllegalStateException("Race overwrote remote");
            revealAction("確認以我的草稿取代遠端分類");click("確認以我的草稿取代遠端分類");awaitProbeDialog("取代剛讀取的遠端分類與規則？");probeDialogClick("取代分類與規則");await("已儲存並同步到網站");awaitSettled();
            r=owner.request("/v1/workspace",null,"GET");if(r.getInt("focus_minutes")!=(focus+1)%1441||!(local+"二").equals(r.getJSONArray("categories").getJSONObject(0).getString("name")))throw new IllegalStateException("Explicit replacement failed");
            String usedId=null;for(int i=0;i<original.getJSONArray("categories").length();i++){String id=original.getJSONArray("categories").getJSONObject(i).getString("id");if(id.startsWith("custom_")){usedId=id;break;}}
            if(usedId==null)throw new IllegalStateException("Fixture requires pre-existing custom category");
            long now=System.currentTimeMillis();owner.request("/v1/activities",new JSONObject().put("activities",new org.json.JSONArray().put(new JSONObject().put("id","native-conflict-"+java.util.UUID.randomUUID()).put("start_ms",now-120000).put("end_ms",now-60000).put("app","org.example.classificationrecovery").put("category",usedId))),"POST");
            click("編輯分類與自動規則");await("儲存分類與規則");awaitSettled();setEditor("最多 40 字",local+"三");
            final String removeId=usedId;test.runOnMainSync(()->{for(View v:views())if(v instanceof TextView t&&removeId.equals(t.getText().toString())){List<View> all=new ArrayList<>();collect((View)v.getParent().getParent(),all);for(View button:all)if(button instanceof TextView b&&"移除此分類".equals(b.getText().toString())){button.performClick();return;}}throw new IllegalStateException("Category remove action missing");});
            await("儲存分類與規則");awaitSettled();revealAction("儲存分類與規則");click("儲存分類與規則");await("分類仍被活動使用");awaitSettled();revealAction("分類仍被活動使用");capture("classification-used-conflict");revealFullRow("還原移除的分類，保留其他草稿");capture("classification-used-restore");
            revealAction("還原移除的分類，保留其他草稿");click("還原移除的分類，保留其他草稿");await("儲存分類與規則");awaitSettled();assertEditor("最多 40 字",local+"三");
            revealAction("儲存分類與規則");click("儲存分類與規則");await("已儲存並同步到網站");awaitSettled();
            click("編輯分類與自動規則");await("儲存分類與規則");awaitSettled();setEditor("最多 40 字",local+"離線");
            r=owner.request("/v1/workspace",null,"GET");r.put("focus_minutes",(focus+2)%1441);owner.request("/v1/workspace",r,"PUT");
            revealAction("儲存分類與規則");click("儲存分類與規則");await("其他設定已更新，分類草稿仍在");awaitSettled();
            shell("svc wifi disable");shell("svc data disable");shell("cmd connectivity airplane-mode enable");
            revealAction("重新核對最新設定");click("重新核對最新設定");await("無法載入");awaitSettled();capture("classification-refresh-offline");
            click("返回編輯草稿");await("儲存分類與規則");awaitSettled();assertEditor("最多 40 字",local+"離線");
            shell("cmd connectivity airplane-mode disable");shell("svc wifi enable");awaitUnmetered();
            revealAction("儲存分類與規則");click("儲存分類與規則");await("其他設定已更新，分類草稿仍在");awaitSettled();revealAction("保留最新目標，重試儲存分類");click("保留最新目標，重試儲存分類");await("已儲存並同步到網站");awaitSettled();
            if(!(local+"離線").equals(owner.request("/v1/workspace",null,"GET").getJSONArray("categories").getJSONObject(0).getString("name")))throw new IllegalStateException("Offline recovery lost draft");assertConsentOff();
        } finally {
            shell("cmd connectivity airplane-mode disable");shell("svc wifi enable");awaitUnmetered();
            JSONObject current=owner.request("/v1/workspace",null,"GET");original.put("revision",current.getLong("revision"));owner.request("/v1/workspace",original,"PUT");
        }
    }
    private void goalsThenCategoriesCheck(PocketLogApi owner) throws Exception {
        stage="new goals followed by classification save";
        JSONObject original=owner.request("/v1/workspace",null,"GET");
        int focus=(original.getInt("focus_minutes")+37)%1441,budget=(original.getInt("distraction_minutes")+19)%1441;
        String name="待分類 UX 測試";
        try {
            click("設定");await("目標與分類");click("目標與分類");await("儲存目標");awaitSettled();
            setEditor("270",String.valueOf(focus));setEditor("60",String.valueOf(budget));revealAction("儲存目標");click("儲存目標");await("網站與手機的共用目標已更新");awaitSettled();
            JSONObject saved=owner.request("/v1/workspace",null,"GET");if(saved.getInt("focus_minutes")!=focus||saved.getInt("distraction_minutes")!=budget)throw new IllegalStateException("Goal save failed");
            click("編輯分類與自動規則");await("儲存分類與規則");awaitSettled();setEditor("最多 40 字",name);revealAction("儲存分類與規則");click("儲存分類與規則");await("已儲存並同步到網站");await("儲存目標");awaitSettled();
            JSONObject result=owner.request("/v1/workspace",null,"GET");if(result.getInt("focus_minutes")!=focus||result.getInt("distraction_minutes")!=budget||!name.equals(result.getJSONArray("categories").getJSONObject(0).getString("name")))throw new IllegalStateException("Classification save reverted new goals or lost category");
            assertEditor("270",String.valueOf(focus));assertEditor("60",String.valueOf(budget));revealEditor("270");capture("goals-after-classification");revealAction(name);capture("classification-after-goals");
        } finally {
            JSONObject current=owner.request("/v1/workspace",null,"GET");original.put("revision",current.getLong("revision"));owner.request("/v1/workspace",original,"PUT");
        }
    }
    private void controlFailureCheck(PocketLogApi owner,String endpoint) throws Exception {
        stage="private server outage and local usage checkpoint";
        Credentials c=new Credentials(endpoint.replace(":18447",":18448"),"a".repeat(64));PocketLogApi proxy=new PocketLogApi(c);
        RecordingSettings s=new RecordingSettings(test.getTargetContext());PrivateFiles files=new PrivateFiles(test.getTargetContext());JournalQueue queue=new JournalQueue(test.getTargetContext());
        JSONObject original=owner.request("/v1/collection",null,"GET");
        try {
            androidx.work.WorkManager.getInstance(test.getTargetContext()).cancelAllWork().getResult().get();
            shell("appops set tw.skyhong.pocketlog GET_USAGE_STATS allow");if(!s.hasUsagePermission())throw new IllegalStateException("Synthetic usage permission missing");awaitUnmetered();
            synchronized(SyncEngine.INSTANCE.getLock()){new CredentialStore(test.getTargetContext()).save(c);s.setEnabled(true);s.setContextEnabled(false);s.setRemotePaused(false);s.setNextAttempt(0);}
            long now=System.currentTimeMillis();String activityId="synthetic-outage-"+java.util.UUID.randomUUID();
            JSONObject batch=new JSONObject().put("activities",new org.json.JSONArray().put(new JSONObject().put("id",activityId).put("app","org.example.outage").put("category","unsorted").put("start_ms",now-60000).put("end_ms",now-1000)));
            synchronized(SyncEngine.INSTANCE.getLock()){queue.enqueue(c,"/v1/activities",batch,now);}
            int dropped=s.dropped();
            for(String mode:new String[]{"busy","transport"}) {
                stage="local collection while control "+mode;proxy.request("/v1/__synthetic_control",new JSONObject().put("mode",mode),"POST");s.setNextAttempt(0);ageUsageCheckpoint(files);
                long before=usageCursor(files);if(SyncEngine.INSTANCE.sync(test.getTargetContext()))throw new IllegalStateException("Outage did not request retry");
                if(usageCursor(files)<=before||s.dropped()!=dropped||!s.getEnabled())throw new IllegalStateException("Local usage scan did not advance safely");
                if(proxy.request("/v1/__synthetic_control",null,"GET").getInt("uploads")!=0||queue.entries(c).isEmpty())throw new IllegalStateException("Outage uploaded or lost queued data");
                if(!s.status().contains("伺服器暫時無法連線"))throw new IllegalStateException("Stale outage status");
                if(mode.equals("busy")){click("設定");await(s.status());awaitSettled();revealFullRow(s.status());capture("control-outage-status");}
                int reads=proxy.request("/v1/__synthetic_control",null,"GET").getInt("control_reads");ageUsageCheckpoint(files);before=usageCursor(files);
                if(SyncEngine.INSTANCE.sync(test.getTargetContext())||usageCursor(files)<=before||proxy.request("/v1/__synthetic_control",null,"GET").getInt("control_reads")!=reads)throw new IllegalStateException("Backoff skipped local scan or made control request");
                s.setRemotePaused(true);s.setNextAttempt(0);ageUsageCheckpoint(files);before=usageCursor(files);SyncEngine.INSTANCE.sync(test.getTargetContext());if(usageCursor(files)!=before)throw new IllegalStateException("Outage bypassed last-known pause");s.setRemotePaused(false);
            }
            stage="permanent control rejection is not treated as offline capture";proxy.request("/v1/__synthetic_control",new JSONObject().put("mode","forbidden"),"POST");s.setNextAttempt(0);ageUsageCheckpoint(files);long blocked=usageCursor(files);SyncEngine.INSTANCE.sync(test.getTargetContext());if(usageCursor(files)!=blocked||s.getNextAttempt()!=0||proxy.request("/v1/__synthetic_control",null,"GET").getInt("uploads")!=0)throw new IllegalStateException("Permanent rejection bypassed control");
            stage="restored endpoint uploads retained queue";proxy.request("/v1/__synthetic_control",new JSONObject().put("mode","up"),"POST");s.setNextAttempt(0);
            for(int i=0;i<8&&!queue.entries(c).isEmpty();i++){s.setNextAttempt(0);SyncEngine.INSTANCE.sync(test.getTargetContext());}
            if(!queue.entries(c).isEmpty()||proxy.request("/v1/__synthetic_control",null,"GET").getInt("uploads")==0)throw new IllegalStateException("Restored upload did not drain bounded queue");
            JSONObject timeline=owner.request("/v1/timeline?from_ms="+(now-120000)+"&to_ms="+System.currentTimeMillis(),null,"GET");boolean found=false;org.json.JSONArray activities=timeline.getJSONArray("activities");for(int i=0;i<activities.length();i++)if(activityId.equals(activities.getJSONObject(i).getString("id")))found=true;if(!found)throw new IllegalStateException("Retained activity absent after recovery");
            stage="confirmed shared pause clears captured queue";
            JSONObject paused=owner.request("/v1/collection",null,"GET");paused.put("paused",true);owner.request("/v1/collection",paused,"PUT");queue.enqueue(c,"/v1/activities",batch,System.currentTimeMillis());s.setNextAttempt(0);SyncEngine.INSTANCE.sync(test.getTargetContext());if(!s.getRemotePaused()||!queue.entries(c).isEmpty())throw new IllegalStateException("Confirmed pause did not clear capture");
            stage="confirmed unauthorized stops local capture";s.setRemotePaused(false);s.setEnabled(true);queue.enqueue(c,"/v1/activities",batch,System.currentTimeMillis());proxy.request("/v1/__synthetic_control",new JSONObject().put("mode","unauthorized"),"POST");s.setNextAttempt(0);if(!SyncEngine.INSTANCE.sync(test.getTargetContext())||s.getEnabled()||!queue.entries(c).isEmpty())throw new IllegalStateException("Unauthorized did not stop and clear capture");
        } finally {
            proxy.request("/v1/__synthetic_control",new JSONObject().put("mode","up"),"POST");JSONObject restore=owner.request("/v1/collection",null,"GET");restore.put("paused",original.getBoolean("paused"));owner.request("/v1/collection",restore,"PUT");shell("appops set tw.skyhong.pocketlog GET_USAGE_STATS default");
        }
    }
    private void ageUsageCheckpoint(PrivateFiles files)throws Exception {long old=System.currentTimeMillis()-21L*60000;byte[] clear=new JSONObject().put("cursor",old).put("app","org.example.outage").put("start",old).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);try{files.write("usage",clear);}finally{java.util.Arrays.fill(clear,(byte)0);}}
    private long usageCursor(PrivateFiles files)throws Exception {byte[] clear=files.read("usage");if(clear==null)throw new IllegalStateException("Usage checkpoint missing");try{return new JSONObject(new String(clear,java.nio.charset.StandardCharsets.UTF_8)).getLong("cursor");}finally{java.util.Arrays.fill(clear,(byte)0);}}
    private void captureAiConsentCheck(PocketLogApi owner) throws Exception {
        stage="future-capture AI consent reconciliation";
        RecordingSettings s=new RecordingSettings(test.getTargetContext());
        JSONObject original=owner.request("/v1/ai",null,"GET");
        if(!original.getBoolean("enabled")||!original.getString("endpoint").contains("127.0.0.1:18752"))throw new IllegalStateException("Synthetic provider fixture required");
        JSONObject draft=new JSONObject();for(String key:new String[]{"protocol","endpoint","model","response_format","image_support"})draft.put(key,original.get(key));
        try {
            String a=original.getString("provider_id");
            synchronized(SyncEngine.INSTANCE.getLock()){s.setEnabled(true);s.setContextEnabled(true);s.setAnalysisConsent(true);s.setAllowed(java.util.Set.of());s.setImagesEnabled(false);s.setAiProviderId(a);s.setExternalAiConsent(true);}
            if(CaptureAiConsent.INSTANCE.refresh(test.getTargetContext(),()->original)!=CaptureAiConsent.State.CURRENT)throw new IllegalStateException("Unchanged identity not current");
            long revision=s.getRevision();CaptureAiConsent.INSTANCE.refresh(test.getTargetContext(),()->original);if(s.getRevision()!=revision)throw new IllegalStateException("Unchanged check rewrote capture revision");
            // Real encrypted queue, never scheduled or uploaded: old consent identity must stay intact.
            Credentials c=new CredentialStore(test.getTargetContext()).load();JournalQueue queue=new JournalQueue(test.getTargetContext());
            synchronized(SyncEngine.INSTANCE.getLock()){queue.enqueue(c,"/v1/observations",new JSONObject().put("id","synthetic-old-provider").put("provider_id",a).put("external_ai_consent",true).put("analysis_consent",true),System.currentTimeMillis());}
            String queued=queue.entries(c).get(0).getData().toString();
            owner.request("/v1/ai",new JSONObject(draft.toString()).put("model","synthetic-rotated"),"PUT");
            JSONObject b=owner.request("/v1/ai",null,"GET");
            CaptureAiConsent.INSTANCE.refresh(test.getTargetContext(),()->b);
            if(s.getHasExternalAiGrant()||s.getAiProviderId()!=null||s.getCaptureAiState()!=CaptureAiConsent.State.RENEWAL)throw new IllegalStateException("Remote rotation did not invalidate future grant");
            if(!queued.equals(queue.entries(c).get(0).getData().toString()))throw new IllegalStateException("Old queued provider changed");
            // Remove this intentionally non-uploadable probe, then verify the real existing sync path.
            synchronized(SyncEngine.INSTANCE.getLock()){queue.clearContexts();s.setAiProviderId(a);s.setExternalAiConsent(true);}
            SyncEngine.INSTANCE.sync(test.getTargetContext());
            if(s.getHasExternalAiGrant()||s.getCaptureAiState()!=CaptureAiConsent.State.RENEWAL)throw new IllegalStateException("Sync missed remote rotation");
            stage="remote rotation settings and renewal";
            click("設定");await("需重新同意");awaitSettled();revealCaptureAiRow();capture("capture-ai-renewal");
            click("模型處理未來情境");awaitProbeDialog("同意這個模型處理未來情境？");if(!probeDialogText().contains("synthetic-rotated"))throw new IllegalStateException("Renewal destination wrong");captureProbeDialog("capture-ai-consent");probeDialogClick("取消");
            if(s.getHasExternalAiGrant())throw new IllegalStateException("Cancel granted consent");
            click("模型處理未來情境");awaitProbeDialog("同意這個模型處理未來情境？");probeDialogClick("同意目前模型");await("已同意目前模型處理未來情境");awaitSettled();
            if(!s.getExternalAiConsent()||!b.getString("provider_id").equals(s.getAiProviderId())||s.getCaptureAiState()!=CaptureAiConsent.State.CURRENT)throw new IllegalStateException("Explicit renewal did not bind B");
            CaptureAiConsent.INSTANCE.refresh(test.getTargetContext(),()->{throw new IllegalStateException("Synthetic read failure");});
            if(s.getCaptureAiState()!=CaptureAiConsent.State.UNVERIFIED||!b.getString("provider_id").equals(s.getAiProviderId()))throw new IllegalStateException("Failed read claimed readiness or rebound consent");
            // Render the real offline status without making a production/network request.
            java.lang.reflect.Method settings=MainActivity.class.getDeclaredMethod("settings");settings.setAccessible(true);
            stage="offline consent readiness";shell("cmd connectivity airplane-mode enable");shell("svc wifi disable");
            test.runOnMainSync(()->{try{settings.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});await("尚未核對");awaitSettled();revealCaptureAiRow();capture("capture-ai-unverified");
            shell("cmd connectivity airplane-mode disable");shell("svc wifi enable");awaitUnmetered();
            JSONObject disabled=new JSONObject().put("enabled",false);CaptureAiConsent.INSTANCE.refresh(test.getTargetContext(),()->disabled);
            if(s.getHasExternalAiGrant()||s.getCaptureAiState()!=CaptureAiConsent.State.RENEWAL)throw new IllegalStateException("Disabled provider left consent current");
        } finally {
            shell("cmd connectivity airplane-mode disable");shell("svc wifi enable");
            awaitUnmetered();
            owner.request("/v1/ai",draft,"PUT");
        }
    }
    private void awaitUnmetered() throws Exception {
        android.net.ConnectivityManager manager=test.getTargetContext().getSystemService(android.net.ConnectivityManager.class);
        long until=System.currentTimeMillis()+20000;
        while(System.currentTimeMillis()<until){android.net.NetworkCapabilities caps=manager.getNetworkCapabilities(manager.getActiveNetwork());if(caps!=null&&caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)&&caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)&&caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))return;Thread.sleep(200);}
        throw new IllegalStateException("Unmetered network not restored");
    }
    private void revealCaptureAiRow() {
        test.runOnMainSync(()->{for(View v:views())if(v instanceof TextView t&&"模型處理未來情境".contentEquals(t.getText())){View row=v;while(!row.isClickable()&&row.getParent() instanceof View)row=(View)row.getParent();row=(View)row.getParent();row.requestRectangleOnScreen(new android.graphics.Rect(0,0,row.getWidth(),row.getHeight()),true);return;}throw new IllegalStateException("Capture AI row absent");});test.waitForIdleSync();
        test.runOnMainSync(()->{for(View v:views())if(v instanceof TextView t&&"模型處理未來情境".contentEquals(t.getText())){View row=v;while(!row.isClickable()&&row.getParent() instanceof View)row=(View)row.getParent();row=(View)row.getParent();android.graphics.Rect visible=new android.graphics.Rect();if(!row.getGlobalVisibleRect(visible)||visible.height()<row.getHeight()-2)throw new IllegalStateException("Capture AI row clipped");return;}});
    }
    private void revealFullRow(String label) {
        test.runOnMainSync(()->{for(View v:views())if(v instanceof TextView t&&label.contentEquals(t.getText())){View row=v;while(!row.isClickable()&&row.getParent() instanceof View)row=(View)row.getParent();row.requestRectangleOnScreen(new android.graphics.Rect(0,0,row.getWidth(),row.getHeight()),true);return;}throw new IllegalStateException("Row absent");});test.waitForIdleSync();
        test.runOnMainSync(()->{for(View v:views())if(v instanceof TextView t&&label.contentEquals(t.getText())){View row=v;while(!row.isClickable()&&row.getParent() instanceof View)row=(View)row.getParent();android.graphics.Rect visible=new android.graphics.Rect();if(!row.getGlobalVisibleRect(visible)||visible.height()<row.getHeight()-2)throw new IllegalStateException("Row clipped");return;}});
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
    private JSONObject probeConfig(String model)throws Exception {
        return new JSONObject().put("protocol","openai").put("endpoint","http://127.0.0.1:18753/v1/chat/completions").put("model",model).put("api_key","synthetic-probe-key").put("image_support",false).put("response_format","json_schema");
    }
    private void providerProbeCheck(PocketLogApi owner)throws Exception {
        stage="saved provider test consent";
        owner.request("/v1/ai",probeConfig("synthetic-A"),"PUT");
        java.lang.reflect.Method ai=MainActivity.class.getDeclaredMethod("aiSettings",kotlin.jvm.functions.Function0.class);ai.setAccessible(true);
        test.runOnMainSync(()->{try{ai.invoke(activity,(kotlin.jvm.functions.Function0<kotlin.Unit>)()->kotlin.Unit.INSTANCE);}catch(Exception e){throw new RuntimeException(e);}});
        await("模型連線");awaitSettled();
        setEditor("https://your-provider.example/v1","https://unsaved.example/v1");setEditor("輸入供應商提供的完整模型 ID","unsaved-B");
        click("測試已儲存模型");awaitProbeDialog("測試已儲存模型？");
        String consent=probeDialogText();
        if(!consent.contains("127.0.0.1:18753")||!consent.contains("synthetic-A")||!consent.contains("openai")||!consent.contains("尚未儲存的修改")||consent.contains("unsaved-B")||consent.contains("unsaved.example"))throw new IllegalStateException("Saved destination consent mismatch");
        captureProbeDialog("probe-consent");probeDialogClick("取消");awaitSettled();assertEditor("輸入供應商提供的完整模型 ID","unsaved-B");
        owner.request("/v1/ai",probeConfig("synthetic-B"),"PUT");
        click("測試已儲存模型");awaitProbeDialog("測試已儲存模型？");probeDialogClick("測試");awaitProbeDialog("已儲存的模型已變更");captureProbeDialog("probe-changed");
        probeDialogClick("保留編輯");awaitSettled();assertEditor("輸入供應商提供的完整模型 ID","unsaved-B");
        click("測試已儲存模型");awaitProbeDialog("測試已儲存模型？");probeDialogClick("測試");awaitProbeDialog("已儲存的模型已變更");probeDialogClick("重新載入設定");
        await("模型連線");awaitSettled();assertEditor("輸入供應商提供的完整模型 ID","synthetic-B");
        click("測試已儲存模型");awaitProbeDialog("測試已儲存模型？");if(!probeDialogText().contains("synthetic-B"))throw new IllegalStateException("New saved provider absent");probeDialogClick("測試");await("連線與摘要格式驗證成功");assertConsentOff();
        JSONObject oldServer=owner.request("/v1/ai",null,"GET");oldServer.remove("probe_provider_binding");
        Field page=MainActivity.class.getDeclaredField("page");page.setAccessible(true);
        java.lang.reflect.Method render=MainActivity.class.getDeclaredMethod("renderAiForm",android.widget.LinearLayout.class,JSONObject.class,kotlin.jvm.functions.Function0.class);render.setAccessible(true);
        test.runOnMainSync(()->{try{android.widget.LinearLayout target=(android.widget.LinearLayout)page.get(activity);target.removeAllViews();render.invoke(activity,target,oldServer,(kotlin.jvm.functions.Function0<kotlin.Unit>)()->kotlin.Unit.INSTANCE);}catch(Exception e){throw new RuntimeException(e);}});
        click("測試已儲存模型");await("請先更新伺服器，才能確認測試目的地");if(!probeDialogText().isEmpty())throw new IllegalStateException("Old-server probe consent opened");assertConsentOff();
    }
    private View probeDialog(){View top=null;for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews())if(root!=activity.getWindow().getDecorView()&&root.isShown())top=root;if(top!=null)return top;throw new IllegalStateException("Probe dialog absent");}
    private String probeDialogText(){StringBuilder out=new StringBuilder();test.runOnMainSync(()->{List<View> all=new ArrayList<>();try{collect(probeDialog(),all);}catch(IllegalStateException absent){return;}for(View v:all)if(v instanceof TextView t)out.append(t.getText()).append('\n');});return out.toString();}
    private void awaitProbeDialog(String label)throws Exception {long end=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<end){try{if(probeDialogText().contains(label)){Thread.sleep(200);return;}}catch(RuntimeException ignored){}Thread.sleep(100);}throw new IllegalStateException("Probe dialog missing: "+label);}
    private void probeDialogClick(String label){test.runOnMainSync(()->{List<View> all=new ArrayList<>();collect(probeDialog(),all);for(View v:all)if(v instanceof TextView t&&label.equals(t.getText().toString())&&v.isClickable()){v.performClick();return;}throw new IllegalStateException("Probe action absent");});}
    private void captureProbeDialog(String name)throws Exception {
        String variant=args.getString("variant","phone");if(!variant.matches("[a-z0-9-]{1,40}"))throw new IllegalArgumentException("Bad variant");
        File folder=new File(test.getTargetContext().getExternalFilesDir(null),"ux-connected");if(!folder.isDirectory()&&!folder.mkdirs())throw new IllegalStateException("Evidence folder");
        final Exception[] failure={null};test.runOnMainSync(()->{Bitmap bitmap=null;try{View view=probeDialog();if(!(view.getLayoutParams() instanceof WindowManager.LayoutParams p)||(p.flags&WindowManager.LayoutParams.FLAG_SECURE)==0)throw new IllegalStateException("Insecure probe dialog");bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(folder,variant+"-"+name+".png"))){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IllegalStateException("Snapshot failed");}}catch(Exception e){failure[0]=e;}finally{if(bitmap!=null)bitmap.recycle();}});if(failure[0]!=null)throw failure[0];
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
            preview.getButton(android.app.AlertDialog.BUTTON_POSITIVE).requestFocusFromTouch();
            preview.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        });
        awaitProbeDialog("永久刪除這張回放縮圖？");
        if(!preview.isShowing()||shown[0]==null||shown[0].isRecycled())throw new IllegalStateException("Confirmation released the preview before consent");
        if(!probeDialogText().contains("org.example.replayproof")||!probeDialogText().contains("活動紀錄"))throw new IllegalStateException("Deletion scope or identity absent");
        captureProbeDialog("replay-delete-confirm");
        if(owner.request(path,null,"GET").getJSONArray("images").length()!=1)throw new IllegalStateException("Opening confirmation deleted image");
        probeDialogClick("取消");test.waitForIdleSync();
        if(!preview.isShowing()||shown[0].isRecycled())throw new IllegalStateException("Cancel lost preview");
        test.runOnMainSync(()->{if(!preview.getButton(android.app.AlertDialog.BUTTON_POSITIVE).hasFocus())throw new IllegalStateException("Cancel did not return focus");});
        if(owner.request(path,null,"GET").getJSONArray("images").length()!=1)throw new IllegalStateException("Cancel deleted image");
        test.runOnMainSync(()->preview.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick());
        awaitProbeDialog("永久刪除這張回放縮圖？");probeDialogClick("刪除縮圖");
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
        click("最近一次擷取檢查"); await("視窗已依隱私規則排除");
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
        click("最近一次擷取檢查"); await("系統擷取未成功");
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
    private void revealTextContaining(String label) {
        final View[] found={null};test.runOnMainSync(()->{for(View v:views())if(v instanceof TextView t&&t.getText().toString().contains(label)){found[0]=v;v.requestRectangleOnScreen(new android.graphics.Rect(0,0,v.getWidth(),v.getHeight()),true);return;}});test.waitForIdleSync();
        if(found[0]==null)throw new IllegalStateException("Text to reveal absent");
        final boolean[] visible={false};test.runOnMainSync(()->{android.graphics.Rect bounds=new android.graphics.Rect();visible[0]=found[0].getGlobalVisibleRect(bounds)&&bounds.height()>=found[0].getHeight()-2;});if(!visible[0])throw new IllegalStateException("Text clipped after scrolling");
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
    private void revealPendingCard() {
        test.runOnMainSync(()->{for(View view:views())if(view instanceof TextView t&&t.getText().toString().equals(pendingCardTitle)){View row=(View)((View)view.getParent()).getParent();row.requestRectangleOnScreen(new android.graphics.Rect(0,0,row.getWidth(),row.getHeight()),true);return;}throw new IllegalStateException("Pending card absent");});
        test.waitForIdleSync();
        test.runOnMainSync(()->{for(View view:views())if(view instanceof TextView t&&t.getText().toString().equals(pendingCardTitle)){View row=(View)((View)view.getParent()).getParent();if(row.getContentDescription()==null||!row.getContentDescription().toString().contains("手機修改，待同步"))throw new IllegalStateException("Wrong pending card captured");android.graphics.Rect bounds=new android.graphics.Rect();if(!row.getGlobalVisibleRect(bounds)||bounds.height()<row.getHeight())throw new IllegalStateException("Pending card clipped");return;}});
    }
    private void pendingCardCheck(PocketLogApi owner) throws Exception {
        stage="offline activity correction and acknowledgement";
        android.content.Context context=test.getTargetContext();
        Credentials credentials=new CredentialStore(context).load();
        String id="pending-card-"+java.util.UUID.randomUUID();pendingCardTitle="合成：離線修正 "+id.substring(id.length()-5);long now=System.currentTimeMillis();
        owner.request("/v1/activities",new JSONObject().put("activities",new org.json.JSONArray().put(new JSONObject().put("id",id).put("app","org.example.pendingcorrection").put("start_ms",now-120000).put("end_ms",now-60000).put("category","work"))),"POST");
        java.time.LocalDate day=TimelineMath.INSTANCE.journalDay(java.time.ZonedDateTime.now());
        String path="/v1/timeline?from_ms="+TimelineMath.INSTANCE.dayStart(day,java.time.ZoneId.systemDefault())+"&to_ms="+now;
        JSONObject original=SyncEngine.INSTANCE.readTimeline(context,path);
        JSONObject selected=null;for(int i=0;i<original.getJSONArray("cards").length();i++)if(original.getJSONArray("cards").getJSONObject(i).getString("id").equals(id))selected=original.getJSONArray("cards").getJSONObject(i);
        if(selected==null)throw new IllegalStateException("Synthetic card not returned");
        JSONObject card=selected;
        androidx.work.WorkManager.getInstance(context).cancelAllWork().getResult().get();
        try {
            shell("cmd connectivity airplane-mode enable");shell("svc wifi disable");
            android.net.ConnectivityManager manager=context.getSystemService(android.net.ConnectivityManager.class);
            long until=System.currentTimeMillis()+10000;while(manager.getActiveNetwork()!=null&&System.currentTimeMillis()<until)Thread.sleep(100);
            if(manager.getActiveNetwork()!=null)throw new IllegalStateException("Still online");
            java.lang.reflect.Method edit=MainActivity.class.getDeclaredMethod("editCard",JSONObject.class);edit.setAccessible(true);
            test.runOnMainSync(()->{try{edit.invoke(activity,card);List<View> all=new ArrayList<>();collect(probeDialog(),all);int field=0;for(View view:all){if(view instanceof EditText e)e.setText(field++==0?pendingCardTitle:"Synthetic offline activity correction");if(view instanceof android.widget.Spinner spinner)for(int j=0;j<spinner.getCount();j++)if(spinner.getItemAtPosition(j).toString().equals("休息"))spinner.setSelection(j);}}catch(Exception e){throw new RuntimeException(e);}});
            probeDialogClick("儲存");await("活動卡先顯示手機上的修改");awaitSettled();
            JSONObject offline=SyncEngine.INSTANCE.readTimeline(context,path);
            JSONObject pending=null;for(int i=0;i<offline.getJSONArray("cards").length();i++)if(offline.getJSONArray("cards").getJSONObject(i).getString("id").equals(id))pending=offline.getJSONArray("cards").getJSONObject(i);
            if(pending==null||!offline.optBoolean("offline")||!pending.optBoolean("pending_edit")||!pending.getString("title").equals(pendingCardTitle)||!pending.getString("category").equals("rest")||!pending.getString("source").equals("user"))throw new IllegalStateException("Local edit absent");
            if(!card.getString("title").equals("org.example.pendingcorrection")||card.has("pending_edit"))throw new IllegalStateException("Server snapshot mutated");
            revealAction("活動卡先顯示手機上的修改，仍待伺服器同步確認。");capture("card-pending-timeline");
            click("清單");await(pendingCardTitle);revealPendingCard();capture("card-pending-list");
            click("每日");await("時間分配");await("活動卡先顯示手機上的修改");awaitSettled();revealAction("活動卡先顯示手機上的修改，仍待伺服器同步確認。");capture("card-pending-daily");
            assertConsentOff();
        } finally {shell("cmd connectivity airplane-mode disable");shell("svc wifi enable");}
        android.net.ConnectivityManager manager=context.getSystemService(android.net.ConnectivityManager.class);
        long until=System.currentTimeMillis()+15000;
        boolean ready=false;
        while(System.currentTimeMillis()<until){android.net.NetworkCapabilities caps=manager.getNetworkCapabilities(manager.getActiveNetwork());if(caps!=null&&caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)&&caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)){ready=true;break;}Thread.sleep(200);}
        if(!ready)throw new IllegalStateException("Unmetered network not restored");
        synchronized(SyncEngine.INSTANCE.getLock()){if(!SyncEngine.INSTANCE.sync(context))throw new IllegalStateException("Sync failed");}
        JSONObject synced=SyncEngine.INSTANCE.readTimeline(context,path);
        if(synced.optBoolean("pending_card_edits"))throw new IllegalStateException("Pending flag remained after acknowledgement");
        boolean found=false;for(int i=0;i<synced.getJSONArray("cards").length();i++){JSONObject c=synced.getJSONArray("cards").getJSONObject(i);if(c.getString("id").equals(id)){found=true;if(!c.getString("title").equals(pendingCardTitle)||!c.getString("category").equals("rest")||!c.getString("source").equals("user")||c.optBoolean("pending_edit"))throw new IllegalStateException("Synced correction mismatch");}}
        if(!found)throw new IllegalStateException("Synced card absent");
        click("時間軸");await("活動");awaitSettled();if(has("活動卡先顯示手機上的修改"))throw new IllegalStateException("Pending banner remained after sync");assertConsentOff();capture("card-synced");
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

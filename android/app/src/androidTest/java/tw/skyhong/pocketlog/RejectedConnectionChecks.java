package tw.skyhong.pocketlog;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import android.widget.TextView;
import android.widget.ScrollView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

/** Exact synthetic post-collection401 local state, no credentials stored or network/OS grants. */
final class RejectedConnectionChecks {
    private final Instrumentation test;
    private final Bundle args;
    private Activity activity;
    private SharedPreferences prefs;
    private RecordingSettings settings;
    private final Credentials fixture=new Credentials("https://example.invalid","c".repeat(64));
    RejectedConnectionChecks(Instrumentation test,Bundle args){this.test=test;this.args=args;}
    void run()throws Exception {
        if(!Set.of("ranchu","goldfish").contains(android.os.Build.HARDWARE))throw new IllegalStateException("Disposable emulator required");
        if(new CredentialStore(test.getTargetContext()).load()!=null)throw new IllegalStateException("Connected installation refused");
        prefs=test.getTargetContext().getSharedPreferences("recording",0);
        if(!prefs.getAll().isEmpty()||!new PrivateFiles(test.getTargetContext()).names("").isEmpty())throw new IllegalStateException("Clean disposable fixture required");
        settings=new RecordingSettings(test.getTargetContext());
        String services=android.provider.Settings.Secure.getString(test.getTargetContext().getContentResolver(),android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if(settings.hasUsagePermission()||(services!=null&&services.contains(test.getTargetContext().getPackageName())))throw new IllegalStateException("OS capture grants must remain off");
        try {
            activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));settle();
            test.runOnMainSync(()->{try{var f=MainActivity.class.getDeclaredField("credentials");f.setAccessible(true);f.set(activity,fixture);}catch(Exception e){throw new RuntimeException(e);}});
            if("yes".equals(args.getString("expectLegacy"))) {
                synchronized(SyncEngine.INSTANCE.getLock()) {
                    settings.setEnabled(false);settings.setContextEnabled(false);settings.setImagesEnabled(false);settings.setReplayConsent(false);settings.setExternalAiConsent(false);settings.setAiProviderId(null);
                    new JournalQueue(test.getTargetContext()).clearCollected();new UsageCollector(test.getTargetContext()).reset(System.currentTimeMillis());
                    settings.status("金鑰已過期、撤銷或失效，已停止擷取；請重新設定連線");
                }
                page("home");check(text().contains("手機紀錄未開啟，現有日誌仍可閱讀"),"Generic off callout not reproduced");capture("legacy-timeline",false,null);
                page("settings");toggle("toggleUsage");check(windowText().contains("同意並開啟"),"Legacy re-enable loop not reproduced");capture("legacy-enable",true,null);clickWindow("取消");
                page("supportPage");check(text().contains("金鑰已過期、撤銷或失效"),"Existing support status missing");return;
            }
            verifyFixed();
        }finally {
            synchronized(SyncEngine.INSTANCE.getLock()){prefs.edit().clear().commit();new PrivateFiles(test.getTargetContext()).clear();}
            if(activity!=null)test.runOnMainSync(()->activity.finish());
        }
    }
    private void verifyFixed()throws Exception {
        var queue=new JournalQueue(test.getTargetContext());var files=new PrivateFiles(test.getTargetContext());
        synchronized(SyncEngine.INSTANCE.getLock()){prefs.edit().putBoolean("enabled",true).putBoolean("context",true).putBoolean("analysis",true).putBoolean("images",true).putBoolean("replay",true).putBoolean("external_ai",true).putString("ai_provider_id","synthetic-prior-provider").commit();}
        settings.setAllowed(Set.of("org.example.reader"));settings.setExclusions(Set.of("org.example.private"));
        // Representative encrypted cache + explicit reflection survive; collected content is removed.
        byte[] cache="synthetic-private-cache".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        files.write("cache_synthetic",cache);
        queue.enqueue(fixture,"/v1/reflections",new JSONObject().put("text","synthetic pending reflection"),System.currentTimeMillis());
        queue.enqueue(fixture,"/v1/activities",new JSONObject().put("id","synthetic activity"),System.currentTimeMillis());
        queue.enqueue(fixture,"/v1/observations",new JSONObject().put("id","synthetic context"),System.currentTimeMillis());
        for(Throwable failure:new Throwable[]{new ApiFailure(403,60000),new ApiFailure(503,60000),new java.io.IOException("synthetic outage")}) {
            check(!stop(failure)&&!settings.isAuthRejected(fixture),"Non401 inferred rejected credentials");
            check(queue.entries(fixture).size()==3&&settings.getEnabled()&&settings.getContextEnabled()&&settings.getImagesEnabled()&&settings.getReplayConsent()&&settings.getHasExternalAiGrant(),"Non401 discarded data or revoked consent");
        }
        check(stop(new ApiFailure(401,60000)),"401 not handled");
        check(settings.isAuthRejected(fixture),"Known rejection not persisted");assertOff();
        check(queue.entries(fixture).size()==1&&queue.entries(fixture).get(0).getData().getString("path").equals("/v1/reflections"),"Explicit pending journal lost");
        byte[] preserved=files.read("cache_synthetic");check(java.util.Arrays.equals(cache,preserved),"Encrypted cache erased");java.util.Arrays.fill(preserved,(byte)0);java.util.Arrays.fill(cache,(byte)0);
        check(settings.getAllowed().equals(Set.of("org.example.reader"))&&settings.getExclusions().equals(Set.of("org.example.private")),"App lists changed");
        check(!settings.isAuthRejected(new Credentials(fixture.getEndpoint(),"d".repeat(64))),"New key inherited rejection");
        settings.status("synthetic later offline status");check(settings.isAuthRejected(fixture),"Transient status erased known rejection");
        page("home");check(text().contains("伺服器曾拒絕這把金鑰")&&!text().contains("手機紀錄未開啟，現有日誌仍可閱讀"),"Timeline shows ordinary capture-off");capture("timeline",false,null);
        page("settings");check(rowDescription("App 使用紀錄").contains("需連線"),"Settings row hides rejection");capture("settings",false,null);clickWindow("處理連線");check(windowText().contains("更換失效的連線"),"Recovery banner has no action");clickWindow("取消");
        for(String action:new String[]{"toggleUsage","toggleContext","toggleImages","toggleReplay"}) {
            toggle(action);check(windowText().contains("更換失效的連線")&&windowText().contains("刪除這支手機尚未同步的資料與快取")&&!windowText().contains("同意並開啟"),"Re-enable loop instead of explicit recovery: "+action);
            if(action.equals("toggleUsage")){capture("recovery",true,null);scrollDialogEnd();capture("recovery-end",true,null);}
            clickWindow("取消");assertOff();check(settings.isAuthRejected(fixture)&&queue.entries(fixture).size()==1&&files.names("cache_").size()==1,"Cancel destroyed state or journal");
        }
        page("supportPage");check(text().contains("伺服器曾拒絕這把金鑰"),"Support recovery missing");capture("support",false,null);
        test.runOnMainSync(()->{try{Method m=MainActivity.class.getDeclaredMethod("captureDiagnostics",kotlin.jvm.functions.Function0.class);m.setAccessible(true);m.invoke(activity,(kotlin.jvm.functions.Function0<kotlin.Unit>)()->kotlin.Unit.INSTANCE);}catch(Exception e){throw new RuntimeException(e);}});settle();
        check(text().contains("伺服器曾拒絕這把金鑰")&&!text().contains("系統權限未齊全"),"Capture diagnostics gives wrong recovery");capture("capture",false,null);
        settings.confirmCollectionCredentials(new Credentials(fixture.getEndpoint(),"d".repeat(64)));check(settings.isAuthRejected(fixture),"Unrelated handshake cleared rejection");
        settings.confirmCollectionCredentials(fixture);check(!settings.isAuthRejected(fixture),"Validated same connection did not recover");assertOff();
        page("settings");toggle("toggleUsage");check(windowText().contains("同意並開啟"),"Explicit renewed consent not available after validation");
        // A new401 arriving after the consent dialog opens must invalidate confirmation.
        check(stop(new ApiFailure(401,60000)),"Late401 not handled");clickWindow("同意並開啟");awaitIdle();assertOff();check(settings.isAuthRejected(fixture),"Stale dialog bypassed known rejection");
    }
    private boolean stop(Throwable failure)throws Exception {for(Method m:SyncEngine.class.getDeclaredMethods())if(m.getName().startsWith("stopForRejectedCredentials")){m.setAccessible(true);return (Boolean)m.invoke(SyncEngine.INSTANCE,test.getTargetContext(),fixture,failure);}throw new IllegalStateException("Rejection handler missing");}
    private void assertOff(){check(!settings.getEnabled()&&!settings.getContextEnabled()&&!settings.getImagesEnabled()&&!settings.getReplayConsent()&&!settings.getExternalAiConsent()&&!settings.getHasExternalAiGrant()&&settings.getAiProviderId()==null,"Capture/model consent restored");}
    private void awaitIdle()throws Exception {long until=android.os.SystemClock.elapsedRealtime()+5000;while(android.os.SystemClock.elapsedRealtime()<until){settle();boolean[] busy={true};test.runOnMainSync(()->{try{var f=MainActivity.class.getDeclaredField("busy");f.setAccessible(true);busy[0]=f.getBoolean(activity);}catch(Exception e){throw new RuntimeException(e);}});if(!busy[0])return;}throw new IllegalStateException("Recovery operation timeout");}
    private void page(String name)throws Exception { invoke(name,null);settle(); }
    private void toggle(String name)throws Exception {invoke(name,settings);settle();}
    private void invoke(String name,RecordingSettings value)throws Exception {test.runOnMainSync(()->{try{Method m=value==null?MainActivity.class.getDeclaredMethod(name):MainActivity.class.getDeclaredMethod(name,RecordingSettings.class);m.setAccessible(true);if(value==null)m.invoke(activity);else m.invoke(activity,value);}catch(Exception e){throw new RuntimeException(e);}});}
    private void settle()throws Exception{test.waitForIdleSync();Thread.sleep(300);test.waitForIdleSync();}
    private List<View> views(View root){List<View> out=new ArrayList<>();out.add(root);if(root instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)out.addAll(views(g.getChildAt(i)));return out;}
    private String rowDescription(String title){String[] result={null};test.runOnMainSync(()->{for(View v:views(activity.getWindow().getDecorView()))if(v.getContentDescription()!=null&&v.getContentDescription().toString().startsWith(title+"，")){result[0]=v.getContentDescription().toString();return;}});return result[0]==null?"":result[0];}
    private String text(){return collect(false);}
    private String windowText(){return collect(true);}
    private String collect(boolean windows){StringBuilder out=new StringBuilder();test.runOnMainSync(()->{for(View root:windows?WindowInspector.getGlobalWindowViews():List.of(activity.getWindow().getDecorView()))for(View v:views(root))if(v instanceof TextView t)out.append(t.getText()).append('\n');});return out.toString();}
    private void clickWindow(String label)throws Exception {test.runOnMainSync(()->{for(View root:WindowInspector.getGlobalWindowViews())for(View v:views(root))if(v instanceof TextView t&&t.getText().toString().equals(label)){check(v.performClick(),"Click "+label);return;}throw new IllegalStateException("Missing button "+label);});settle();}
    private void scrollDialogEnd()throws Exception {test.runOnMainSync(()->{List<View> roots=WindowInspector.getGlobalWindowViews();for(View v:views(roots.get(roots.size()-1)))if(v instanceof ScrollView s)s.fullScroll(View.FOCUS_DOWN);});settle();}
    private void capture(String name,boolean dialog,String anchor)throws Exception {
        if(anchor!=null){test.runOnMainSync(()->{for(View v:views(activity.getWindow().getDecorView()))if(v instanceof TextView t&&t.getText().toString().equals(anchor)){try{var field=MainActivity.class.getDeclaredField("scroll");field.setAccessible(true);ScrollView scroll=(ScrollView)field.get(activity);int[] at=new int[2],top=new int[2];v.getLocationInWindow(at);scroll.getLocationInWindow(top);scroll.scrollTo(0,scroll.getScrollY()+at[1]-top[1]-24);return;}catch(Exception e){throw new RuntimeException(e);}}throw new IllegalStateException("Capture anchor missing: "+anchor);});settle();}
        String variant=args.getString("variant","phone");check(variant.matches("[a-z0-9-]{1,40}"),"Variant");
        File folder=new File(test.getTargetContext().getExternalFilesDir(null),"ux-rejected-connection");check(folder.isDirectory()||folder.mkdirs(),"Evidence folder");
        final Exception[] failure={null};test.runOnMainSync(()->{Bitmap bitmap=null;try{
            check((activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0,"Activity secure flag");
            List<View> roots=WindowInspector.getGlobalWindowViews();View view=dialog?roots.get(roots.size()-1):activity.getWindow().getDecorView();
            if(dialog)check((((WindowManager.LayoutParams)view.getLayoutParams()).flags&WindowManager.LayoutParams.FLAG_SECURE)!=0,"Dialog secure flag");
            bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));
            try(FileOutputStream out=new FileOutputStream(new File(folder,variant+"-"+name+".png"))){check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out),"Snapshot");}
        }catch(Exception e){failure[0]=e;}finally{if(bitmap!=null)bitmap.recycle();}});if(failure[0]!=null)throw failure[0];
    }
    private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}

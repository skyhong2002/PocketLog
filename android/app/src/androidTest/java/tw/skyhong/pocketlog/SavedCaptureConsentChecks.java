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

/** Explicit disposable-emulator saved-grant fixture; no credentials, OS grants or server. */
final class SavedCaptureConsentChecks {
    private final Instrumentation test;
    private final Bundle args;
    private Activity activity;
    private SharedPreferences prefs;
    private RecordingSettings settings;
    SavedCaptureConsentChecks(Instrumentation test, Bundle args) { this.test=test; this.args=args; }
    void run() throws Exception {
        if (!Set.of("ranchu", "goldfish").contains(android.os.Build.HARDWARE)) throw new IllegalStateException("Disposable emulator required");
        if (new CredentialStore(test.getTargetContext()).load()!=null) throw new IllegalStateException("Connected installation refused");
        prefs=test.getTargetContext().getSharedPreferences("recording",0);
        if(!prefs.getAll().isEmpty()||!new PrivateFiles(test.getTargetContext()).names("").isEmpty()) throw new IllegalStateException("Clean disposable fixture required");
        if (prefs.getBoolean("enabled",false)||prefs.getBoolean("context",false)||prefs.getBoolean("images",false)||prefs.getBoolean("replay",false)||prefs.getBoolean("external_ai",false)||prefs.getBoolean("remote_paused",false)) throw new IllegalStateException("Consent-off fixture required");
        settings=new RecordingSettings(test.getTargetContext());
        String services=android.provider.Settings.Secure.getString(test.getTargetContext().getContentResolver(),android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if(settings.hasUsagePermission()||(services!=null&&services.contains(test.getTargetContext().getPackageName())))throw new IllegalStateException("OS capture permissions must remain off");
        try {
            activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); settle();
            fixture(false,false); page("settings");
            check(!settings.getContextEnabled()&&!settings.getImagesEnabled()&&!settings.getReplayConsent(),"upstream gate ineffective");
            if ("yes".equals(args.getString("expectLegacy"))) {
                check(rowDescription("畫面文字").contains("關閉"),"Legacy off presentation missing");
                toggle("toggleContext");toggle("toggleImages");toggle("toggleReplay");
                check(prefs.getBoolean("context",false)&&prefs.getBoolean("images",false)&&prefs.getBoolean("replay",false),"Legacy revocation unexpectedly succeeded");
                fixture(true,true);toggle("toggleUsage");
                check(prefs.getBoolean("enabled",false),"Legacy remote-pause revocation unexpectedly succeeded");
                fixture(false,false);toggle("toggleUsage");
                check(windowText().contains("不讀取文字或截圖"),"Legacy incomplete disclosure not reproduced");
                capture("legacy-resume",true,null);clickWindow("取消");
                fixture(true,false);
                check(settings.getContextEnabled()&&settings.getImagesEnabled()&&settings.getReplayConsent(),"Saved grants did not resume");
                return;
            }
            verifyFixed();
        } finally {
            synchronized(SyncEngine.INSTANCE.getLock()) { prefs.edit().clear().commit(); new PrivateFiles(test.getTargetContext()).clear(); }
            if(activity!=null)test.runOnMainSync(()->activity.finish());
        }
    }
    private void verifyFixed() throws Exception {
        for(boolean remote:new boolean[]{false,true}) {
            fixture(false,remote);page("settings");
            for(String title:new String[]{"畫面文字","畫面縮圖","短期縮圖回放"})check(rowDescription(title).contains("已保存")&&rowDescription(title).contains("撤回"),"Hidden saved grant: "+title);
            capture(remote?"remote-paused":"local-paused",false,"畫面文字");
            if(remote)capture("paused-replay",false,"短期縮圖回放");
            page("supportPage");check(rowDescription("畫面文字").contains("已保存"),"Support hid saved context");
            // Each revoke must work under both pauses, clear queued context and preserve other choices.
            for(String grant:new String[]{"context","images","replay"}) {
                fixture(false,remote);page("settings");queueContext();
                String method=grant.equals("context")?"toggleContext":grant.equals("images")?"toggleImages":"toggleReplay";
                toggle(method);await(()->!prefs.getBoolean(grant,true)&&!busy());
                check(!prefs.getBoolean("enabled",true)&&settings.getRemotePaused()==remote,"Revocation released a pause");
                for(String other:new String[]{"context","images","replay"})if(!other.equals(grant))check(prefs.getBoolean(other,false),"Revoked unrelated grant: "+other);
                check(prefs.getBoolean("external_ai",false)&&"synthetic-provider".equals(settings.getAiProviderId()),"Provider consent rebound or lost");
                check(new JournalQueue(test.getTargetContext()).counts().getFirst()==0,"Pending context survived revocation");
            }
        }
        // An immediate parent can also be off, independently of App usage recording.
        fixture(true,false);prefs.edit().putBoolean("context",false).commit();page("settings");
        check(rowDescription("畫面縮圖").contains("已保存"),"Context pause hid images");
        toggle("toggleImages");await(()->!prefs.getBoolean("images",true)&&!busy());
        check(prefs.getBoolean("replay",false),"Image revoke lost independent replay grant");
        toggle("toggleReplay");await(()->!prefs.getBoolean("replay",true)&&!busy());
        // Remote pause cannot prevent revoking the saved App usage consent.
        fixture(true,true);page("settings");check(rowDescription("App 使用紀錄").contains("已保存"),"Remote pause hid usage grant");capture("paused-usage",false,"App 使用紀錄");queueContext();
        toggle("toggleUsage");await(()->!prefs.getBoolean("enabled",true)&&!busy());
        check(settings.getRemotePaused()&&prefs.getBoolean("context",false)&&prefs.getBoolean("images",false)&&prefs.getBoolean("replay",false),"Usage revoke changed independent choices");
        check(new JournalQueue(test.getTargetContext()).counts().getFirst()==0,"Usage revoke left context");
        // Resume cannot promise app-only collection while retaining downstream consent.
        fixture(false,false);page("settings");toggle("toggleUsage");
        check(windowText().contains("已保存的獨立同意")&&windowText().contains("• 畫面文字")&&windowText().contains("• 畫面縮圖")&&windowText().contains("• 短期縮圖回放")&&windowText().contains("原先同意的供應商")&&windowText().contains("同意並恢復"),"Incomplete usage resume disclosure");
        check(!windowText().contains("不讀取文字或截圖"),"False app-only resume promise");capture("resume-disclosure",true,null);scrollDialogEnd();capture("resume-disclosure-end",true,null);clickWindow("取消");
        check(!prefs.getBoolean("enabled",true)&&prefs.getBoolean("context",false)&&prefs.getBoolean("images",false)&&prefs.getBoolean("replay",false),"Cancel changed saved grants");
        // If a confirmed pause arrives while the consent dialog is open, confirmation fails closed.
        toggle("toggleUsage");settings.setRemotePaused(true);clickWindow("同意並恢復");await(()->!busy());check(!prefs.getBoolean("enabled",true),"Late remote pause bypassed");
        fixture(true,false);prefs.edit().putBoolean("context",false).putBoolean("analysis",false).commit();page("settings");toggle("toggleContext");
        check(windowText().contains("• 畫面縮圖")&&windowText().contains("• 短期縮圖回放")&&windowText().contains("原先同意的供應商"),"Incomplete text resume disclosure");capture("text-resume",true,null);clickWindow("取消");
        check(!prefs.getBoolean("context",true)&&!prefs.getBoolean("analysis",true),"Text cancel granted capture");
        fixture(true,false);prefs.edit().putBoolean("images",false).commit();page("settings");toggle("toggleImages");
        check(windowText().contains("• 短期縮圖回放")&&windowText().contains("原先同意的供應商"),"Incomplete image resume disclosure");clickWindow("取消");
        check(!prefs.getBoolean("images",true),"Image cancel granted capture");
        fixture(true,false);check(settings.getEnabled()&&settings.getContextEnabled()&&settings.getImagesEnabled()&&settings.getReplayConsent(),"Existing effective gates broken");
        settings.setRemotePaused(true);check(!settings.getEnabled()&&!settings.getContextEnabled()&&!settings.getImagesEnabled()&&!settings.getReplayConsent()&&!settings.getExternalAiConsent(),"Remote pause capture gate bypassed");
    }
    private void queueContext() throws Exception { synchronized(SyncEngine.INSTANCE.getLock()){new JournalQueue(test.getTargetContext()).enqueue(new Credentials("https://example.invalid","synthetic-consent-fixture"),"/v1/observations",new JSONObject().put("synthetic",true),System.currentTimeMillis());} }
    private boolean busy(){boolean[] value={true};test.runOnMainSync(()->{try{var field=MainActivity.class.getDeclaredField("busy");field.setAccessible(true);value[0]=field.getBoolean(activity);}catch(Exception e){throw new RuntimeException(e);}});return value[0];}
    private void await(java.util.function.BooleanSupplier condition)throws Exception {long end=android.os.SystemClock.elapsedRealtime()+5000;while(android.os.SystemClock.elapsedRealtime()<end){settle();if(condition.getAsBoolean())return;}throw new IllegalStateException("Consent operation timeout");}
    private void fixture(boolean usage, boolean remote) { synchronized(SyncEngine.INSTANCE.getLock()) {check(prefs.edit().putBoolean("enabled",usage).putBoolean("remote_paused",remote).putBoolean("context",true).putBoolean("analysis",true).putBoolean("images",true).putBoolean("replay",true).putBoolean("external_ai",true).putString("ai_provider_id","synthetic-provider").putStringSet("allowed",Set.of("org.example.reader")).commit(),"fixture persistence");} }
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
        File folder=new File(test.getTargetContext().getExternalFilesDir(null),"ux-saved-consent");check(folder.isDirectory()||folder.mkdirs(),"Evidence folder");
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

package tw.skyhong.pocketlog;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Synthetic settings only, no credentials/network/capture, restored in finally. */
final class CaptureReadinessChecks {
    private final Instrumentation test;
    private final Bundle args;
    private Activity activity;
    CaptureReadinessChecks(Instrumentation test,Bundle args){this.test=test;this.args=args;}
    void run()throws Exception {
        if(!Set.of("ranchu","goldfish").contains(android.os.Build.HARDWARE))throw new IllegalStateException("Disposable emulator required");
        if(new CredentialStore(test.getTargetContext()).load()!=null)throw new IllegalStateException("Connected installation refused");
        RecordingSettings settings=new RecordingSettings(test.getTargetContext());
        if(test.getTargetContext().getSharedPreferences("recording",0).getBoolean("context",false)||settings.getEnabled()||settings.getContextEnabled()||settings.getImagesEnabled()||settings.getExternalAiConsent()||settings.getRemotePaused())throw new IllegalStateException("Unpaused, consent-off fixture required");
        Set<String> allowed=settings.getAllowed(),excluded=settings.getExclusions();
        try {
            activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));settle();
            synchronized(SyncEngine.INSTANCE.getLock()){settings.setEnabled(true);settings.setContextEnabled(true);}
            long attempt=CaptureDiagnostics.INSTANCE.begin(System.currentTimeMillis()-60000);
            CaptureDiagnostics.INSTANCE.complete(attempt,CaptureDiagnostics.Outcome.QUEUED_TEXT,System.currentTimeMillis()-59000);
            lists(settings,Set.of(),Set.of());
            page("settings");requireText("已同意畫面文字，但沒有可擷取的 app");capture("empty", "已同意畫面文字");
            lists(settings,Set.of("org.example.reader"),Set.of("org.example.reader"));
            page("supportPage");requireText("可擷取 0 個");requireText("待設定");requireText("已同意畫面文字，但沒有可擷取的 app");capture("excluded", "已同意畫面文字");
            test.runOnMainSync(()->{for(View v:views(activity.getWindow().getDecorView()))if(v instanceof TextView t&&t.getText().toString().equals("調整允許與排除清單")){v.performClick();return;}throw new IllegalStateException("Recovery action absent");});settle();
            requireText("排除與允許清單");requireText("無法辨識所有");
            test.runOnMainSync(()->{try{Method m=MainActivity.class.getDeclaredMethod("captureDiagnostics",kotlin.jvm.functions.Function0.class);m.setAccessible(true);m.invoke(activity,(kotlin.jvm.functions.Function0<kotlin.Unit>)()->kotlin.Unit.INSTANCE);}catch(Exception e){throw new RuntimeException(e);}});settle();
            requireText("已同意畫面文字，但沒有可擷取的 app");requireText("過去的檢查結果");requireText("畫面文字已排入本機佇列");capture("historical", "已同意畫面文字");
            lists(settings,Set.of("org.example.bankapp",test.getTargetContext().getPackageName()),Set.of());
            page("settings");requireText("已同意畫面文字，但沒有可擷取的 app");
            lists(settings,Set.of("org.example.reader","org.example.bankapp"),Set.of());
            page("supportPage");requireText("可擷取 1 個");if(text().contains("已同意畫面文字，但沒有可擷取的 app"))throw new IllegalStateException("Stale zero-eligible warning");capture("eligible","畫面文字");
            page("appListsPage");requireText("無法辨識所有");capture("privacy","無法辨識所有");
            if(!settings.getEnabled()||!settings.getContextEnabled()||settings.getImagesEnabled()||settings.getExternalAiConsent())throw new IllegalStateException("Rendering changed consent");
        }finally {
            synchronized(SyncEngine.INSTANCE.getLock()){settings.setContextEnabled(false);settings.setEnabled(false);settings.setAllowed(allowed);settings.setExclusions(excluded);}
            CaptureDiagnostics.INSTANCE.clear();
            if(activity!=null)test.runOnMainSync(()->activity.finish());
        }
        if(!allowed.equals(settings.getAllowed())||!excluded.equals(settings.getExclusions())||settings.getEnabled()||settings.getContextEnabled())throw new IllegalStateException("Original settings not restored");
    }
    private void lists(RecordingSettings s,Set<String> allowed,Set<String> excluded){synchronized(SyncEngine.INSTANCE.getLock()){s.setAllowed(allowed);s.setExclusions(excluded);}}
    private void page(String name)throws Exception {test.runOnMainSync(()->{try{Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});settle();}
    private void settle()throws Exception {test.waitForIdleSync();Thread.sleep(250);test.waitForIdleSync();}
    private List<View> views(View root){List<View> out=new ArrayList<>();out.add(root);if(root instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)out.addAll(views(g.getChildAt(i)));return out;}
    private String text(){StringBuilder out=new StringBuilder();test.runOnMainSync(()->{for(View v:views(activity.getWindow().getDecorView()))if(v instanceof TextView t)out.append(t.getText()).append('\n');});return out.toString();}
    private void requireText(String value){if(!text().contains(value))throw new IllegalStateException("Missing readiness guidance: "+value);}
    private void capture(String name,String anchor)throws Exception {
        test.runOnMainSync(()->{for(View v:views(activity.getWindow().getDecorView()))if(v instanceof TextView t&&t.getText().toString().contains(anchor)){View target=anchor.equals("已同意畫面文字")?(View)v.getParent():v;target.requestRectangleOnScreen(new Rect(0,0,target.getWidth(),target.getHeight()),true);break;}});settle();
        if(anchor.equals("已同意畫面文字"))test.runOnMainSync(()->{for(View v:views(activity.getWindow().getDecorView()))if(v instanceof TextView t&&t.getText().toString().equals("調整允許與排除清單")){Rect bounds=new Rect();if(!v.getGlobalVisibleRect(bounds)||bounds.height()<v.getHeight())throw new IllegalStateException("Recovery action clipped");return;}throw new IllegalStateException("Recovery action absent");});
        String variant=args.getString("variant","phone");if(!variant.matches("[a-z0-9-]{1,40}"))throw new IllegalArgumentException("Variant");
        File folder=new File(test.getTargetContext().getExternalFilesDir(null),"ux-readiness");if(!folder.isDirectory()&&!folder.mkdirs())throw new IllegalStateException("Evidence folder");
        final Exception[] failure={null};test.runOnMainSync(()->{Bitmap bitmap=null;try{if((activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)==0)throw new IllegalStateException("Secure flag missing");View view=activity.getWindow().getDecorView();bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(folder,variant+"-"+name+".png"))){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IllegalStateException("Snapshot failed");}}catch(Exception e){failure[0]=e;}finally{if(bitmap!=null)bitmap.recycle();}});if(failure[0]!=null)throw failure[0];
    }
}

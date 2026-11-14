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
import android.view.inspector.WindowInspector;
import android.widget.EditText;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Disposable, consent-off emulator only. No server, real credentials or capture. */
final class CardEditChecks {
    private final Instrumentation test; private final Bundle args; private Activity activity;
    CardEditChecks(Instrumentation test,Bundle args){this.test=test;this.args=args;}
    void run() throws Exception {
        if(!Set.of("ranchu","goldfish").contains(android.os.Build.HARDWARE))throw new IllegalStateException("Emulator required");
        CredentialStore store=new CredentialStore(test.getTargetContext());
        RecordingSettings settings=new RecordingSettings(test.getTargetContext());
        JournalQueue queue=new JournalQueue(test.getTargetContext());
        if(store.load()!=null||settings.getEnabled()||settings.getContextEnabled()||settings.getImagesEnabled()||queue.counts().getFirst()!=0)throw new IllegalStateException("Empty disconnected consent-off fixture required");
        long previousAttempt=settings.getNextAttempt();
        Credentials synthetic=new Credentials("https://127.0.0.1:1","a".repeat(64));
        try {
            activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));settle();
            JSONObject card=new JSONObject().put("id","synthetic-edit").put("start_ms",System.currentTimeMillis()-60000).put("end_ms",System.currentTimeMillis()).put("title","合成活動").put("summary","合成摘要").put("category","work").put("source","app_only");
            Method method=MainActivity.class.getDeclaredMethod("editCard",JSONObject.class);method.setAccessible(true);
            test.runOnMainSync(()->{try{method.invoke(activity,card);}catch(Exception e){throw new RuntimeException(e);}});settle();
            String tooLong="標".repeat(201);
            edit(0,tooLong);click("儲存");settle();requireText("尚未儲存");requireDraft(0,tooLong);empty(queue);capture("title-limit");
            edit(0,"  \n ");click("儲存");settle();requireDraft(0,"  \n ");empty(queue);
            String title="🙂".repeat(200), summary="🙂".repeat(1000);
            edit(0,title);edit(1,summary+"字");click("儲存");settle();requireText("尚未儲存");requireDraft(1,summary+"字");empty(queue);
            edit(1,summary);busy(true);click("儲存");settle();requireText("上一個操作尚在處理中");requireDraft(0,title);empty(queue);busy(false);
            // Missing encrypted credentials makes the real enqueue fail before any queue write.
            click("儲存");settle();requireText("未能完成儲存");requireDraft(0,title);requireDraft(1,summary);empty(queue);capture("save-failure");
            // Real Keystore + production queue; future backoff prevents a worker network attempt.
            settings.setNextAttempt(System.currentTimeMillis()+3600000);store.save(synthetic);
            click("儲存");settle();
            List<JournalQueue.Entry> entries=queue.entries(synthetic);
            if(entries.size()!=1)throw new IllegalStateException("Expected one persisted edit");
            JSONObject entry=entries.get(0).getData();JSONObject body=entry.getJSONObject("body");
            if(!entry.getString("path").equals("/v1/cards")||!title.equals(body.getString("title"))||!summary.equals(body.getString("summary"))||!body.getString("category").equals("work"))throw new IllegalStateException("Unicode edit changed in encrypted queue");
            test.runOnMainSync(()->{for(View v:WindowInspector.getGlobalWindowViews())if(v!=activity.getWindow().getDecorView()&&v.isShown())throw new IllegalStateException("Editor did not dismiss after persistence");});
            queue.acknowledge(entries.get(0));empty(queue);
        }finally {
            store.clear();settings.setNextAttempt(previousAttempt);
            for(JournalQueue.Entry entry:queue.entries(synthetic))queue.acknowledge(entry);
            if(activity!=null)test.runOnMainSync(activity::finish);
        }
    }
    private void empty(JournalQueue queue){if(queue.counts().getFirst()!=0)throw new IllegalStateException("Invalid edit reached queue");}
    private void busy(boolean value)throws Exception {Field f=MainActivity.class.getDeclaredField("busy");f.setAccessible(true);test.runOnMainSync(()->{try{f.setBoolean(activity,value);}catch(Exception e){throw new RuntimeException(e);}});}
    private void settle()throws Exception {test.waitForIdleSync();Thread.sleep(400);test.waitForIdleSync();}
    private View root(){for(View v:WindowInspector.getGlobalWindowViews())if(v!=activity.getWindow().getDecorView()&&v.isShown())return v;throw new IllegalStateException("Editor disappeared");}
    private List<View> views(View root){List<View> result=new ArrayList<>();result.add(root);if(root instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)result.addAll(views(g.getChildAt(i)));return result;}
    private List<EditText> editors(){List<EditText> out=new ArrayList<>();for(View v:views(root()))if(v instanceof EditText e)out.add(e);return out;}
    private void edit(int i,String text){test.runOnMainSync(()->editors().get(i).setText(text));}
    private void requireDraft(int i,String value){test.runOnMainSync(()->{if(!editors().get(i).getText().toString().equals(value))throw new IllegalStateException("Draft lost");});}
    private void click(String value){test.runOnMainSync(()->{for(View v:views(root()))if(v instanceof TextView t&&t.getText().toString().equals(value)){v.performClick();return;}throw new IllegalStateException("Button missing "+value);});}
    private void requireText(String value){test.runOnMainSync(()->{for(View v:views(root()))if(v instanceof TextView t&&t.getText().toString().contains(value))return;throw new IllegalStateException("Missing editor feedback "+value);});}
    private void capture(String name)throws Exception {
        String variant=args.getString("variant","phone");if(!variant.matches("[a-z0-9-]{1,40}"))throw new IllegalArgumentException("Variant");
        File folder=new File(test.getTargetContext().getExternalFilesDir(null),"ux-card-edit");if(!folder.isDirectory()&&!folder.mkdirs())throw new IllegalStateException("Evidence folder");
        test.runOnMainSync(()->{View v=root();for(View child:views(v))if(child instanceof TextView t&&t.getText().toString().startsWith(name.equals("save-failure")?"未能完成儲存":"尚未儲存")){android.graphics.Rect bounds=new android.graphics.Rect();if(!child.getGlobalVisibleRect(bounds)||bounds.height()<child.getHeight())throw new IllegalStateException("Editor feedback clipped");}if(!(v.getLayoutParams() instanceof WindowManager.LayoutParams p)||(p.flags&WindowManager.LayoutParams.FLAG_SECURE)==0)throw new IllegalStateException("Insecure editor");Bitmap b=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);try(FileOutputStream out=new FileOutputStream(new File(folder,variant+"-"+name+".png"))){v.draw(new Canvas(b));if(!b.compress(Bitmap.CompressFormat.PNG,100,out))throw new IllegalStateException("PNG failed");}catch(Exception e){throw new RuntimeException(e);}finally{b.recycle();}});
    }
}

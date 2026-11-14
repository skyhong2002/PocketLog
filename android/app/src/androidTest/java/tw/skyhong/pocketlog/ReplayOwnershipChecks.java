package tw.skyhong.pocketlog;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

/** Synthetic-only emulator lifecycle checks; uses real production dialog/task ownership. */
final class ReplayOwnershipChecks {
    private final Instrumentation test;
    private final Bundle args;
    private Activity activity;
    ReplayOwnershipChecks(Instrumentation test, Bundle args) { this.test=test; this.args=args; }
    void run() throws Exception {
        if (!java.util.Set.of("ranchu","goldfish").contains(android.os.Build.HARDWARE)) throw new IllegalStateException("Disposable emulator required");
        if (new CredentialStore(test.getTargetContext()).load()!=null) throw new IllegalStateException("Refusing a connected installation");
        try {
            activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            settle();
            Bitmap first=syntheticBitmap();
            show(first); settle();
            AlertDialog preview=preview();
            capture(preview,"viewer");
            test.runOnMainSync(preview::dismiss); test.waitForIdleSync();
            if(!first.isRecycled() || preview()!=null) throw new IllegalStateException("Dismiss did not release bitmap/dialog");
            Bitmap second=syntheticBitmap(); show(second); settle();
            AlertDialog oldDialog=preview();
            Instrumentation.ActivityMonitor monitor=test.addMonitor(MainActivity.class.getName(),null,false);
            test.runOnMainSync(activity::recreate);
            Activity next=test.waitForMonitorWithTimeout(monitor,10000);test.removeMonitor(monitor);
            if(next==null) throw new IllegalStateException("Activity not recreated");
            activity=next;settle();
            if(oldDialog.isShowing() || !second.isRecycled() || preview()!=null) throw new IllegalStateException("Recreation leaked owned replay media");

            CountDownLatch started=new CountDownLatch(1), release=new CountDownLatch(1), disposed=new CountDownLatch(1);
            AtomicBoolean delivered=new AtomicBoolean(false);
            Function0<Bitmap> work=()-> { started.countDown();try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Response gate timed out"); } catch(InterruptedException e) { throw new RuntimeException(e); }return syntheticBitmap(); };
            Function1<Bitmap,Unit> discard=bitmap->{ bitmap.recycle(); if(!bitmap.isRecycled()) throw new IllegalStateException("Bitmap retained");disposed.countDown();return Unit.INSTANCE; };
            Function1<Bitmap,Unit> done=bitmap->{delivered.set(true);bitmap.recycle();return Unit.INSTANCE;};
            Method task=MainActivity.class.getDeclaredMethod("task",String.class,Function0.class,Function1.class,Function1.class,Function1.class);task.setAccessible(true);
            Activity finishing=activity;
            test.runOnMainSync(()->{try {task.invoke(finishing,"合成圖片回應等待中…",work,null,discard,done);}catch(Exception e){throw new RuntimeException(e);}});
            if(!started.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Worker not started");
            test.runOnMainSync(finishing::finish);test.waitForIdleSync();
            release.countDown();
            if(!disposed.await(5,TimeUnit.SECONDS) || delivered.get()) throw new IllegalStateException("Undelivered result was not discarded");
        } finally { if(activity!=null) test.runOnMainSync(activity::finish); }
    }
    private Bitmap syntheticBitmap() {
        Bitmap b=Bitmap.createBitmap(320,480,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(Color.rgb(238,243,233));
        Paint p=new Paint();p.setColor(Color.rgb(52,91,67));p.setTextSize(18);c.drawText("Synthetic replay fixture",20,60,p);c.drawText("No personal data",20,100,p);return b;
    }
    private void show(Bitmap bitmap) throws Exception {
        JSONObject item=new JSONObject().put("id","synthetic-owned-media").put("app","org.example.synthetic").put("at_ms",System.currentTimeMillis());
        Method method=MainActivity.class.getDeclaredMethod("showReplayBitmap",Bitmap.class,JSONObject.class);method.setAccessible(true);
        test.runOnMainSync(()->{try {method.invoke(activity,bitmap,item);}catch(Exception e){throw new RuntimeException(e);}});
    }
    private AlertDialog preview() throws Exception { Field f=MainActivity.class.getDeclaredField("replayDialog");f.setAccessible(true);return (AlertDialog)f.get(activity); }
    private void settle() throws Exception { test.waitForIdleSync();Thread.sleep(300);test.waitForIdleSync(); }
    private void capture(AlertDialog preview,String name) throws Exception {
        String variant=args.getString("variant","phone");if(!variant.matches("[a-z0-9-]{1,40}"))throw new IllegalArgumentException("Bad variant");
        File folder=new File(test.getTargetContext().getExternalFilesDir(null),"ux-replay");if(!folder.isDirectory()&&!folder.mkdirs())throw new IllegalStateException("No snapshot directory");
        test.runOnMainSync(()-> {
            if((preview.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)==0)throw new IllegalStateException("Secure dialog flag missing");
            View view=preview.getWindow().getDecorView();Bitmap out=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
            try(FileOutputStream stream=new FileOutputStream(new File(folder,variant+"-"+name+".png"))) { view.draw(new Canvas(out));if(!out.compress(Bitmap.CompressFormat.PNG,100,stream))throw new IllegalStateException("PNG failure"); }catch(Exception e){throw new RuntimeException(e);}finally{out.recycle();}
        });
    }
}

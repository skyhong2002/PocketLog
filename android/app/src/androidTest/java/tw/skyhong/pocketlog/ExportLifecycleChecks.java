package tw.skyhong.pocketlog;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** No server or picker automation: actual recreation and production result/write callbacks. */
final class ExportLifecycleChecks {
    private final Instrumentation test; private final Bundle args;
    private Activity activity; private Field pending; private Method result;
    ExportLifecycleChecks(Instrumentation test,Bundle args){this.test=test;this.args=args;}
    void run() throws Exception {
        if(!java.util.Set.of("ranchu","goldfish").contains(android.os.Build.HARDWARE)||new CredentialStore(test.getTargetContext()).load()!=null)throw new IllegalStateException("Unconnected disposable emulator required");
        File out=new File(test.getTargetContext().getFilesDir(),"synthetic-export-lifecycle.json");
        byte[] original="{\"synthetic\":\"原本核准的日期與內容 🙂\"}".getBytes(StandardCharsets.UTF_8);
        byte[] expected=original.clone();
        pending=MainActivity.class.getDeclaredField("exportBytes");pending.setAccessible(true);
        result=MainActivity.class.getDeclaredMethod("onActivityResult",int.class,int.class,Intent.class);result.setAccessible(true);
        try{
            activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));settle();
            pending.set(activity,original);
            Instrumentation.ActivityMonitor monitor=test.addMonitor(MainActivity.class.getName(),null,false);
            test.runOnMainSync(activity::recreate);Activity next=test.waitForMonitorWithTimeout(monitor,10000);test.removeMonitor(monitor);
            if(next==null)throw new IllegalStateException("Activity did not recreate");activity=next;settle();
            if("yes".equals(args.getString("expectLegacy"))){if(pending.get(activity)!=null||!zero(original))throw new IllegalStateException("Legacy snapshot loss not reproduced");return;}
            if(pending.get(activity)!=original||!Arrays.equals(expected,original))throw new IllegalStateException("Recreation lost or changed confirmed bytes");
            deliver(Activity.RESULT_OK,out);await("已匯出私人 JSON");
            if(!Arrays.equals(expected,Files.readAllBytes(out.toPath()))||!zero(original)||pending.get(activity)!=null)throw new IllegalStateException("Export write changed bytes or retained buffer");
            out.delete();byte[] cancelled=expected.clone();pending.set(activity,cancelled);deliver(Activity.RESULT_CANCELED,out);settle();
            if(!zero(cancelled)||pending.get(activity)!=null||out.exists())throw new IllegalStateException("Cancel did not wipe pending export");
            byte[] failed=expected.clone();pending.set(activity,failed);File invalid=new File(test.getTargetContext().getFilesDir(),"synthetic-missing-directory/export.json");
            deliver(Activity.RESULT_OK,invalid);await("匯出未完成");if(!zero(failed)||pending.get(activity)!=null||invalid.exists())throw new IllegalStateException("Write failure retained bytes or wrote a file");capture("write-failure");
            byte[] busyBytes=expected.clone();pending.set(activity,busyBytes);Field busy=MainActivity.class.getDeclaredField("busy");busy.setAccessible(true);busy.setBoolean(activity,true);deliver(Activity.RESULT_OK,out);busy.setBoolean(activity,false);
            if(!zero(busyBytes)||pending.get(activity)!=null||out.exists())throw new IllegalStateException("Busy result leaked pending bytes");
            byte[] reset=expected.clone();pending.set(activity,reset);Method clear=MainActivity.class.getDeclaredMethod("clearDrafts");clear.setAccessible(true);
            test.runOnMainSync(()->{try{clear.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});
            if(!zero(reset)||pending.get(activity)!=null)throw new IllegalStateException("Data/connection reset retained export");
            deliver(Activity.RESULT_OK,out);await("請返回時間軸重新匯出");if(out.exists())throw new IllegalStateException("Missing snapshot wrote a document");capture("lost-state");
            byte[] destroyed=expected.clone();pending.set(activity,destroyed);test.runOnMainSync(activity::finish);settle();
            long end=System.currentTimeMillis()+10000;while(!activity.isDestroyed()&&System.currentTimeMillis()<end){Thread.sleep(100);test.waitForIdleSync();}
            if(!activity.isDestroyed())throw new IllegalStateException("Activity destruction did not finish");
            if(!zero(destroyed))throw new IllegalStateException("Final destruction retained export");
        }finally{Arrays.fill(expected,(byte)0);Arrays.fill(original,(byte)0);out.delete();if(activity!=null&&!activity.isFinishing())test.runOnMainSync(activity::finish);}
    }
    private void deliver(int code,File out){test.runOnMainSync(()->{try{result.invoke(activity,20,code,new Intent().setData(Uri.fromFile(out)));}catch(Exception e){throw new RuntimeException(e);}});}
    private boolean zero(byte[] bytes){for(byte b:bytes)if(b!=0)return false;return true;}
    private void settle()throws Exception{test.waitForIdleSync();Thread.sleep(300);test.waitForIdleSync();}
    private boolean contains(View v,String text){if(v instanceof TextView t&&t.getText().toString().contains(text))return true;if(v instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)if(contains(g.getChildAt(i),text))return true;return false;}
    private void await(String text)throws Exception{long until=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<until){final boolean[] found={false};test.runOnMainSync(()->found[0]=contains(activity.getWindow().getDecorView(),text));if(found[0])return;Thread.sleep(100);}throw new IllegalStateException("Export feedback absent");}
    private void capture(String name)throws Exception{
        String variant=args.getString("variant","phone");if(!variant.matches("[a-z0-9-]{1,40}"))throw new IllegalArgumentException("Bad variant");
        File folder=new File(test.getTargetContext().getExternalFilesDir(null),"ux-export");if(!folder.isDirectory()&&!folder.mkdirs())throw new IllegalStateException("Evidence folder");
        final Exception[] error={null};test.runOnMainSync(()->{Bitmap bitmap=null;try{if((activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)==0)throw new IllegalStateException("Insecure export state");View view=activity.getWindow().getDecorView();bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));try(FileOutputStream stream=new FileOutputStream(new File(folder,variant+"-"+name+".png"))){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,stream))throw new IllegalStateException("PNG failed");}}catch(Exception e){error[0]=e;}finally{if(bitmap!=null)bitmap.recycle();}});if(error[0]!=null)throw error[0];
    }
}

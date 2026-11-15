package tw.skyhong.pocketlog;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import org.json.JSONArray;
import org.json.JSONObject;

/** Controlled native foreground reads and actual secure Views; no credential/server/model. */
final class TimelineUpdateChecks {
    private final Instrumentation test;
    private final Bundle args;
    private Activity activity;
    private String stage="guard";
    TimelineUpdateChecks(Instrumentation test,Bundle args){this.test=test;this.args=args;}
    void run() throws Exception {
        if(!java.util.Set.of("ranchu","goldfish").contains(android.os.Build.HARDWARE))throw new IllegalStateException("Disposable emulator required");
        if(new CredentialStore(test.getTargetContext()).load()!=null)throw new IllegalStateException("Connected installation refused");
        var settings=new RecordingSettings(test.getTargetContext());
        if(settings.getEnabled()||settings.getContextEnabled()||settings.getImagesEnabled()||settings.getExternalAiConsent())throw new IllegalStateException("Capture consent must be off");
        try {
            activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));settle();
            if("yes".equals(args.getString("expectLegacy"))) {
                try{field("timelineWatch");throw new IllegalStateException("Expected old snapshot-only app");}catch(NoSuchFieldException expected){}
                System.out.println("Legacy native home loads a snapshot with no timeline watcher; manual refresh required.");return;
            }
            if("yes".equals(args.getString("realTimer"))) { realTimerCheck(); return; }
            helperChecks(); uiChecks();
        }catch(Throwable e){throw new IllegalStateException("Timeline update "+stage+": "+e,e);}
        finally{if(activity!=null)test.runOnMainSync(()->activity.finish());}
    }
    private void realTimerCheck()throws Exception{
        stage="real120s foreground timer";
        java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch fired=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        JSONObject baseline=snapshot("原始合成活動"), fresh=snapshot("更新後的合成活動");
        TimelineUpdateWatch watch=new TimelineUpdateWatch(new Handler(Looper.getMainLooper()),worker,LocalDate.now(),baseline,()->true,()->{reads.incrementAndGet();return fresh;},state->{if(state==TimelineUpdateWatch.State.UPDATED)fired.countDown();return Unit.INSTANCE;},LocalDate::now,android.os.SystemClock::elapsedRealtime);
        try{
            test.runOnMainSync(watch::resume);
            if(fired.await(50,java.util.concurrent.TimeUnit.SECONDS)||reads.get()!=0)throw new IllegalStateException("Read before120s");
            if(!fired.await(80,java.util.concurrent.TimeUnit.SECONDS)||reads.get()!=1)throw new IllegalStateException("Real120s timer did not produce one read");
        }finally{test.runOnMainSync(watch::close);worker.shutdown();}
    }
    private void helperChecks()throws Exception{
        stage="controlled cadence/lifecycle";
        long[] time={0};boolean[] allowed={true};LocalDate[] today={LocalDate.now()};AtomicInteger reads=new AtomicInteger();List<Runnable> queue=new ArrayList<>();List<TimelineUpdateWatch.State> results=new ArrayList<>();JSONObject baseline=snapshot("原始合成活動");JSONObject[] response={baseline};boolean[] fail={false};
        TimelineUpdateWatch watch=new TimelineUpdateWatch(new Handler(Looper.getMainLooper()),queue::add,today[0],baseline,()->allowed[0],()->{reads.incrementAndGet();if(fail[0])throw new IllegalStateException("synthetic");return response[0];},s->{results.add(s);return Unit.INSTANCE;},()->today[0],()->time[0]);
        test.runOnMainSync(()->{watch.resume();watch.check();});check(queue.isEmpty(),"immediate read");
        time[0]=120000;allowed[0]=false;test.runOnMainSync(watch::check);check(queue.isEmpty(),"ineligible read");allowed[0]=true;
        test.runOnMainSync(()->{watch.check();watch.check();});check(queue.size()==1,"overlapping reads");queue.remove(0).run();settle();check(reads.get()==1&&results.get(0)==TimelineUpdateWatch.State.CURRENT,"unchanged result");
        test.runOnMainSync(watch::check);check(queue.isEmpty(),"cadence bypass");
        response[0]=snapshot("新的合成活動");time[0]+=120000;test.runOnMainSync(watch::check);queue.remove(0).run();settle();check(results.get(results.size()-1)==TimelineUpdateWatch.State.UPDATED,"change missing");
        fail[0]=true;time[0]+=120000;test.runOnMainSync(watch::check);queue.remove(0).run();settle();check(results.get(results.size()-1)==TimelineUpdateWatch.State.FAILED,"failure missing");
        fail[0]=false;response[0]=baseline;time[0]+=120000;test.runOnMainSync(watch::check);queue.remove(0).run();settle();check(results.get(results.size()-1)==TimelineUpdateWatch.State.CURRENT,"recovery missing");
        int before=results.size();time[0]+=120000;test.runOnMainSync(()->{watch.check();watch.pause();watch.resume();watch.check();});check(queue.size()==1,"restart overlapped in-flight");queue.remove(0).run();settle();check(results.size()==before,"paused epoch published");check(reads.get()==4,"queued stale request started after pause");
        time[0]+=120000;before=results.size();test.runOnMainSync(()->{watch.check();queue.remove(0).run();watch.pause();});settle();check(results.size()==before&&reads.get()==5,"started stale completion published");
        test.runOnMainSync(watch::pause);time[0]+=120000;test.runOnMainSync(watch::check);check(queue.isEmpty(),"background read");
        test.runOnMainSync(watch::resume);today[0]=today[0].plusDays(1);test.runOnMainSync(watch::check);check(queue.isEmpty()&&results.get(results.size()-1)==TimelineUpdateWatch.State.ROLLOVER,"rollover read");
        test.runOnMainSync(watch::close);before=results.size();test.runOnMainSync(()->{watch.resume();watch.check();});check(queue.isEmpty()&&results.size()==before,"closed watcher restarted");
    }
    private void uiChecks()throws Exception{
        stage="native view snapshot and explicit reload";
        JSONObject initial=snapshot("原始合成活動"),fresh=snapshot("更新後的合成活動");AtomicInteger reads=new AtomicInteger(),reloads=new AtomicInteger();boolean[] failure={false};long[] clock={0};LinearLayout[] slot={null};EditText[] draft={null};
        Function0<JSONObject> read=()->{reads.incrementAndGet();if(failure[0])throw new IllegalStateException("synthetic");return fresh;};
        Function0<JSONObject> reload=()->{reloads.incrementAndGet();return fresh;};
        test.runOnMainSync(()->{try{
            field("listMode").setBoolean(activity,true);invoke("home");field("generation").setInt(activity,field("generation").getInt(activity)+1);
            LinearLayout page=(LinearLayout)field("page").get(activity);slot[0]=(LinearLayout)page.getChildAt(2);slot[0].removeAllViews();
            Method render=MainActivity.class.getDeclaredMethod("renderTimeline",LinearLayout.class,JSONObject.class);render.setAccessible(true);render.invoke(activity,slot[0],initial);
            draft[0]=(EditText)field("reflectionInput").get(activity);draft[0].setText("尚未儲存的反思草稿");
            Method attach=MainActivity.class.getDeclaredMethod("startTimelineWatch",LinearLayout.class,JSONObject.class,Function0.class,Function0.class);attach.setAccessible(true);attach.invoke(activity,slot[0],initial,read,reload);
            TimelineUpdateWatch w=(TimelineUpdateWatch)field("timelineWatch").get(activity);Field elapsed=TimelineUpdateWatch.class.getDeclaredField("elapsed");elapsed.setAccessible(true);elapsed.set(w,(Function0<Long>)()->clock[0]);Field last=TimelineUpdateWatch.class.getDeclaredField("lastAttempt");last.setAccessible(true);last.setLong(w,0);
            page.setFocusableInTouchMode(true);page.requestFocus();
        }catch(Exception e){throw new RuntimeException(e);}});settle();
        check(reads.get()==0,"implicit check read");
        // Focused edit, busy operation and a real secure dialog all suppress checks.
        clock[0]=120000;test.runOnMainSync(()->{draft[0].requestFocus();watch().check();});settle();check(reads.get()==0,"editing read");
        test.runOnMainSync(()->{try{((View)field("page").get(activity)).requestFocus();field("busy").setBoolean(activity,true);watch().check();field("busy").setBoolean(activity,false);}catch(Exception e){throw new RuntimeException(e);}});settle();check(reads.get()==0,"busy read");
        android.app.AlertDialog[] dialog={null};test.runOnMainSync(()->{dialog[0]=new android.app.AlertDialog.Builder(activity).setTitle("合成測試對話框").setPositiveButton("關閉",null).create();dialog[0].getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog[0].show();});settle();test.runOnMainSync(()->watch().check());settle();check(reads.get()==0,"dialog read");test.runOnMainSync(()->dialog[0].dismiss());settle();
        test.runOnMainSync(()->{test.callActivityOnStop(activity);watch().check();});settle();check(reads.get()==0,"stopped Activity read");test.runOnMainSync(()->test.callActivityOnStart(activity));settle();
        ScrollView outer=(ScrollView)field("scroll").get(activity);test.runOnMainSync(()->{outer.scrollTo(0,slot[0].getTop()+200);});settle();
        TextView anchor=findText("原始合成活動");int[] beforeAnchor=new int[2];anchor.getLocationInWindow(beforeAnchor);View previousFocus=activity.getCurrentFocus();
        test.runOnMainSync(()->watch().check());await(()->reads.get()==1&&text().contains("有新的活動或分類更新"));
        check(text().contains("原始合成活動")&&!text().contains("更新後的合成活動"),"check replaced snapshot");check(draft[0].getText().toString().equals("尚未儲存的反思草稿"),"check lost draft");settle();int[] afterAnchor=new int[2];anchor.getLocationInWindow(afterAnchor);check(beforeAnchor[1]==afterAnchor[1],"update moved reading anchor "+beforeAnchor[1]+"/"+afterAnchor[1]);check(activity.getCurrentFocus()==previousFocus,"check stole focus");
        test.runOnMainSync(()->outer.scrollTo(0,slot[0].getTop()));settle();capture("updated");
        test.runOnMainSync(()->click("載入最新活動"));await(()->reloads.get()==1&&text().contains("更新後的合成活動"));settle();
        check(draft[0].getText().toString().equals("尚未儲存的反思草稿"),"reload lost draft");check(!text().contains("有新的活動或分類更新"),"reload did not reset baseline");capture("reloaded");
        // The proportional canvas owns a nested vertical viewport as well as the outer page.
        test.runOnMainSync(()->{try{
            field("listMode").setBoolean(activity,false);slot[0].removeAllViews();
            Method render=MainActivity.class.getDeclaredMethod("renderTimeline",LinearLayout.class,JSONObject.class);render.setAccessible(true);render.invoke(activity,slot[0],initial);
            Method attach=MainActivity.class.getDeclaredMethod("startTimelineWatch",LinearLayout.class,JSONObject.class,Function0.class,Function0.class);attach.setAccessible(true);attach.invoke(activity,slot[0],initial,read,reload);
            TimelineUpdateWatch w=watch();Field elapsed=TimelineUpdateWatch.class.getDeclaredField("elapsed");elapsed.setAccessible(true);elapsed.set(w,(Function0<Long>)()->clock[0]);Field last=TimelineUpdateWatch.class.getDeclaredField("lastAttempt");last.setAccessible(true);last.setLong(w,0);((View)field("page").get(activity)).requestFocus();
        }catch(Exception e){throw new RuntimeException(e);}});settle();
        ScrollView inner=null;for(View v:views(slot[0]))if(v instanceof ScrollView s){inner=s;break;}check(inner!=null,"canvas viewport missing");ScrollView originalInner=inner;
        test.runOnMainSync(()->originalInner.scrollTo(0,400));int innerY=originalInner.getScrollY();test.runOnMainSync(()->watch().check());await(()->text().contains("有新的活動或分類更新"));settle();check(originalInner.getScrollY()==innerY,"check moved inner canvas");
        int reloadCount=reloads.get();test.runOnMainSync(()->click("載入最新活動"));await(()->reloads.get()==reloadCount+1);settle();ScrollView refreshedInner=null;for(View v:views(slot[0]))if(v instanceof ScrollView s){refreshedInner=s;break;}check(refreshedInner!=null&&refreshedInner.getScrollY()==innerY,"reload moved inner canvas");
        capture("canvas-reloaded");
        // Reattach with controlled failure to exercise visible error without removing cards.
        test.runOnMainSync(()->{try{watch().close();Method attach=MainActivity.class.getDeclaredMethod("startTimelineWatch",LinearLayout.class,JSONObject.class,Function0.class,Function0.class);attach.setAccessible(true);attach.invoke(activity,slot[0],fresh,read,reload);TimelineUpdateWatch w=watch();Field elapsed=TimelineUpdateWatch.class.getDeclaredField("elapsed");elapsed.setAccessible(true);elapsed.set(w,(Function0<Long>)()->clock[0]);Field last=TimelineUpdateWatch.class.getDeclaredField("lastAttempt");last.setAccessible(true);last.setLong(w,0);((View)field("page").get(activity)).requestFocus();failure[0]=true;clock[0]+=120000;w.check();}catch(Exception e){throw new RuntimeException(e);}});
        await(()->text().contains("暫時無法檢查活動更新"));check(slot[0].getChildCount()>1,"failure lost cards");capture("failed");
        // Navigation closes the watch and a closed reference cannot submit more work.
        TimelineUpdateWatch old=watch();int count=reads.get();test.runOnMainSync(()->{try{invoke("settings");old.check();}catch(Exception e){throw new RuntimeException(e);}});settle();check(reads.get()==count,"navigation did not close watch");
    }
    private JSONObject snapshot(String title)throws Exception {long start=TimelineMath.INSTANCE.dayStart(TimelineMath.INSTANCE.journalDay(java.time.ZonedDateTime.now()),ZoneId.systemDefault())+4*3600000L;return new JSONObject().put("cards",new JSONArray().put(new JSONObject().put("id","synthetic-update").put("start_ms",start).put("end_ms",start+3600000L).put("app","org.example.synthetic").put("title",title).put("category","work").put("source","app_only").put("summary","僅供介面驗證"))).put("reflections",new JSONArray()).put("checkins",new JSONArray());}
    private TimelineUpdateWatch watch(){try{return (TimelineUpdateWatch)field("timelineWatch").get(activity);}catch(Exception e){throw new RuntimeException(e);}}
    private Field field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private void invoke(String name)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(activity);}
    private void settle()throws Exception{test.waitForIdleSync();Thread.sleep(180);test.waitForIdleSync();}
    private void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private List<View> views(View root){List<View> all=new ArrayList<>();all.add(root);if(root instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)all.addAll(views(g.getChildAt(i)));return all;}
    private TextView findText(String value){for(View v:views(activity.getWindow().getDecorView()))if(v instanceof TextView t&&value.equals(t.getText().toString()))return t;throw new IllegalStateException("Text absent "+value);}
    private String text(){StringBuilder all=new StringBuilder();test.runOnMainSync(()->{for(View v:views(activity.getWindow().getDecorView()))if(v instanceof TextView t&&v.getVisibility()==View.VISIBLE)all.append(t.getText()).append('\n');});return all.toString();}
    private void click(String label){for(View v:views(activity.getWindow().getDecorView()))if(v instanceof TextView t&&label.equals(t.getText().toString())&&v.isClickable()){v.performClick();return;}throw new IllegalStateException("Button absent "+label);}
    private void await(java.util.function.BooleanSupplier predicate)throws Exception{long end=System.currentTimeMillis()+7000;while(System.currentTimeMillis()<end){if(predicate.getAsBoolean())return;Thread.sleep(60);}throw new IllegalStateException("Wait timed out");}
    private void capture(String name)throws Exception{String variant=args.getString("variant","phone");if(!variant.matches("[a-z0-9-]{1,40}"))throw new IllegalArgumentException("Variant");File folder=new File(test.getTargetContext().getExternalFilesDir(null),"ux-timeline-updates");if(!folder.isDirectory()&&!folder.mkdirs())throw new IllegalStateException("Evidence folder");test.runOnMainSync(()->{Bitmap bitmap=null;try{View root=activity.getWindow().getDecorView();if((activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)==0)throw new IllegalStateException("Secure flag absent");bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(folder,variant+"-"+name+".png"))){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IllegalStateException("PNG");}}catch(Exception e){throw new RuntimeException(e);}finally{if(bitmap!=null)bitmap.recycle();}});}
}

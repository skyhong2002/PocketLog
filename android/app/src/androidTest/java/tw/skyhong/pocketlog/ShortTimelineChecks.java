package tw.skyhong.pocketlog;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import kotlin.Unit;

/** Actual native View layout/hit tests, synthetic emulator only; no credentials or network. */
final class ShortTimelineChecks {
    private final Instrumentation test; private final Bundle args; private Activity activity;
    ShortTimelineChecks(Instrumentation test,Bundle args){this.test=test;this.args=args;}
    void run() throws Exception {
        if(!Set.of("ranchu","goldfish").contains(android.os.Build.HARDWARE)||new CredentialStore(test.getTargetContext()).load()!=null)throw new IllegalStateException("Disconnected disposable emulator required");
        RecordingSettings settings=new RecordingSettings(test.getTargetContext());
        if(settings.getEnabled()||settings.getContextEnabled()||settings.getImagesEnabled())throw new IllegalStateException("Consent-off fixture required");
        try {
            activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));test.waitForIdleSync();Thread.sleep(400);test.waitForIdleSync();
            final Exception[] failure={null};test.runOnMainSync(()->{try{checks();}catch(Exception e){failure[0]=e;}});if(failure[0]!=null)throw failure[0];
        }finally{if(activity!=null)test.runOnMainSync(activity::finish);}
    }
    private void checks()throws Exception {
        LocalDate day=LocalDate.of(2026,11,14);ZoneId zone=ZoneId.of("Asia/Taipei");long start=TimelineMath.INSTANCE.dayStart(day,zone),end=TimelineMath.INSTANCE.dayStart(day.plusDays(1),zone),at=start+4*3600000L;
        List<TimelineItem> items=List.of(item(at,at+60000,"一分鐘閱讀",0xffa7bfa1),item(at+60000,at+120000,"一分鐘整理",0xffb7a5dc),item(at+120000,at+180000,"一分鐘溝通",0xffe1ba8e),item(at+30000,at+600000,"重疊的研究活動",0xff8ebbb7),item(at+3600000,at+7200000,"較長活動",0xffc2b17f),item(end-60000,end,"日界前一分鐘",0xffb7a5dc),item(end,end+60000,"不在這天",0xffaaaaaa));
        List<TimelineItem> before=new ArrayList<>(items);
        for(int dp:new int[]{320,360,800}) {
            List<Integer> hits=new ArrayList<>();DayTimelineView view=new DayTimelineView(activity,day,zone,items,at,index->{hits.add(index);return Unit.INSTANCE;});layout(view,dp);List<RectF> rects=rects(view);checkGeometry(view,rects,6);
            for(int i=0;i<6;i++){RectF r=rects.get(i);touch(view,MotionEvent.ACTION_DOWN,r.centerX(),r.centerY());touch(view,MotionEvent.ACTION_UP,r.centerX(),r.centerY());if(hits.size()!=i+1||hits.get(i)!=i)throw new IllegalStateException("Wrong original callback index at width "+dp);}
            RectF r=rects.get(0);touch(view,MotionEvent.ACTION_DOWN,r.centerX(),r.centerY());touch(view,MotionEvent.ACTION_MOVE,r.centerX()+200,r.centerY());touch(view,MotionEvent.ACTION_UP,r.centerX(),r.centerY());if(hits.size()!=6)throw new IllegalStateException("Scroll gesture activated card");
            if(dp==320)capture(view,view.yFor(at)-20,"short-cards");
        }
        List<TimelineItem> dense=new ArrayList<>();for(int i=0;i<100;i++)dense.add(item(at+i*1000,at+(i+1)*1000,"密集活動 "+i,0xffa7bfa1));
        DayTimelineView denseView=new DayTimelineView(activity,day,zone,dense,at,index->Unit.INSTANCE);layout(denseView,320);checkGeometry(denseView,rects(denseView),100);
        if(!before.equals(items))throw new IllegalStateException("Original intervals/model changed");
        if((activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)==0)throw new IllegalStateException("Secure flag missing");
    }
    private TimelineItem item(long from,long to,String title,int color){return new TimelineItem(from,to,title,"合成分類",color);}
    private void layout(DayTimelineView view,int dp){int width=Math.round(dp*activity.getResources().getDisplayMetrics().density);view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));view.layout(0,0,width,view.getMeasuredHeight());}
    @SuppressWarnings("unchecked")private List<RectF> rects(DayTimelineView view)throws Exception {Field field=DayTimelineView.class.getDeclaredField("rects");field.setAccessible(true);return (List<RectF>)field.get(view);}
    private void checkGeometry(DayTimelineView view,List<RectF> rects,int count){for(int i=0;i<count;i++){RectF a=rects.get(i);if(a.width()<=0||a.height()<=0||a.left<0||a.right>view.getWidth()+.1||a.top<0||a.bottom>view.getHeight()+.1)throw new IllegalStateException("Card outside positive canvas bounds");for(int j=i+1;j<count;j++)if(RectF.intersects(a,rects.get(j)))throw new IllegalStateException("Rendered cards overlap: "+i+","+j);}if(rects.size()>count&&!rects.get(count).isEmpty())throw new IllegalStateException("Out-of-window card rendered");}
    private void touch(DayTimelineView view,int action,float x,float y){MotionEvent event=MotionEvent.obtain(0,0,action,x,y,0);try{view.onTouchEvent(event);}finally{event.recycle();}}
    private void capture(DayTimelineView view,int y,String name)throws Exception {
        String variant=args.getString("variant","phone");if(!variant.matches("[a-z0-9-]{1,40}"))throw new IllegalArgumentException("Variant");File folder=new File(test.getTargetContext().getExternalFilesDir(null),"ux-short-timeline");if(!folder.isDirectory()&&!folder.mkdirs())throw new IllegalStateException("Evidence folder");
        int height=Math.round(200*activity.getResources().getDisplayMetrics().density);Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),height,Bitmap.Config.ARGB_8888);try(FileOutputStream out=new FileOutputStream(new File(folder,variant+"-"+name+".png"))){Canvas canvas=new Canvas(bitmap);canvas.drawColor(0xfff2f5ee);canvas.translate(0,-y);view.draw(canvas);
            List<RectF> cards=rects(view);for(int i=0;i<4;i++){RectF r=cards.get(i);boolean ink=false;for(int py=Math.max(0,(int)r.top-y+2);py<Math.min(height,(int)r.bottom-y-2)&&!ink;py++)for(int px=(int)r.left+Math.round(5*activity.getResources().getDisplayMetrics().density);px<(int)r.right-2;px++){int c=bitmap.getPixel(px,py);if(android.graphics.Color.red(c)<100&&android.graphics.Color.green(c)<100&&android.graphics.Color.blue(c)<100){ink=true;break;}}if(!ink)throw new IllegalStateException("Narrow card has no visible title cue: "+i);}
            if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IllegalStateException("PNG failed");}finally{bitmap.recycle();}
    }
}

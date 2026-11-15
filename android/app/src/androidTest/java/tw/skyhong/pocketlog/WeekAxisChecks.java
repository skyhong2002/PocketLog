package tw.skyhong.pocketlog;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
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

/** Real native Canvas text/geometry/navigation, fixed synthetic days, no network or credentials. */
final class WeekAxisChecks {
    private static final int INK = 0xff3a7198;
    private final Instrumentation test;
    private final Bundle args;
    private Activity activity;
    WeekAxisChecks(Instrumentation test, Bundle args) { this.test=test; this.args=args; }
    void run() throws Exception {
        if (!Set.of("ranchu","goldfish").contains(android.os.Build.HARDWARE) || new CredentialStore(test.getTargetContext()).load()!=null)
            throw new IllegalStateException("Disconnected disposable emulator required");
        RecordingSettings settings=new RecordingSettings(test.getTargetContext());
        if (settings.getEnabled() || settings.getContextEnabled() || settings.getImagesEnabled()) throw new IllegalStateException("Consent-off fixture required");
        try {
            activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            test.waitForIdleSync();
            final Exception[] failure={null};
            test.runOnMainSync(()->{try { checks(); } catch(Exception e) { failure[0]=e; }});
            if (failure[0]!=null) throw failure[0];
        } finally { if (activity!=null) test.runOnMainSync(activity::finish); }
    }
    private void checks() throws Exception {
        boolean legacy="yes".equals(args.getString("expectLegacy"));
        for (Object[] c : new Object[][] {
            {"ny-fall",LocalDate.of(2026,10,31),ZoneId.of("America/New_York"),true},
            {"ny-spring",LocalDate.of(2026,3,7),ZoneId.of("America/New_York"),true},
            {"lord-howe",LocalDate.of(2026,10,3),ZoneId.of("Australia/Lord_Howe"),true},
            {"taipei",LocalDate.of(2026,10,31),ZoneId.of("Asia/Taipei"),false},
            {"ny-normal",LocalDate.of(2026,11,14),ZoneId.of("America/New_York"),false}
        }) {
            String name=(String)c[0]; LocalDate selected=(LocalDate)c[1]; ZoneId zone=(ZoneId)c[2]; boolean transition=(boolean)c[3];
            LocalDate first=TimelineMath.INSTANCE.weekStart(selected);
            List<WeekDay> days=new ArrayList<>();
            for(int i=0;i<7;i++) {
                LocalDate day=first.plusDays(i); long start=TimelineMath.INSTANCE.dayStart(day,zone),length=TimelineMath.INSTANCE.dayLength(day,zone);
                long middle=start+length/2;
                days.add(new WeekDay(day,true,false,List.of(new TimelineItem(middle,middle+600000,"合成活動","合成分類",INK))));
            }
            for(int dp:new int[]{320,360,800}) {
                List<LocalDate> picked=new ArrayList<>();
                WeekTimelineView view=new WeekTimelineView(activity,days,selected,selected.plusDays(8),zone,0L,d->{picked.add(d);return Unit.INSTANCE;});
                int width=Math.round(dp*activity.getResources().getDisplayMetrics().density);
                view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                view.layout(0,0,width,view.getMeasuredHeight());
                float gutter=field(view,"gutter"),header=field(view,"header"),hour=field(view,"hour"),column=(width-gutter)/7f;
                Bitmap bitmap=Bitmap.createBitmap(width,view.getHeight(),Bitmap.Config.ARGB_8888);
                try {
                    RecordingCanvas canvas=new RecordingCanvas(bitmap);canvas.drawColor(0xfff2f5ee);view.draw(canvas);
                    Set<String> wanted=transition&&!legacy?Set.of("0%","25%","50%","75%","100%"):Set.of("04","08","12","16","20","00");
                    if(!canvas.texts.containsAll(wanted))throw new IllegalStateException(name+" missing axis labels "+wanted);
                    if(transition&&!legacy) {
                        if(canvas.texts.contains("04"))throw new IllegalStateException("Misleading shared clock remains");
                        for(Text t:canvas.entries)if(t.text.endsWith("%")&&(t.x<0||t.x+t.width>gutter||t.baseline+t.descent>view.getHeight()))throw new IllegalStateException("Progress label clipped");
                    }
                    if(!legacy) {
                        for(Text t:canvas.entries)if(t.centered&&t.width>column-.1f)throw new IllegalStateException("Week header text exceeds column");
                        for(RectF r:canvas.circles)if(r.width()>column||r.left<gutter||r.right>width)throw new IllegalStateException("Selected date circle exceeds column");
                    }
                    if(canvas.cards.size()!=7)throw new IllegalStateException("Activity geometry missing");
                    for(RectF r:canvas.cards) {
                        if(Math.abs(r.top-(header+12*hour))>1||r.left<gutter||r.right>width||r.bottom>view.getHeight())throw new IllegalStateException("Elapsed midpoint geometry changed");
                    }
                    for(int i=0;i<7;i++) {
                        float x=gutter+(i+.5f)*column,y=header+12*hour;
                        MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,x,y,0),up=MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,x,y,0);
                        try {view.onTouchEvent(down);view.onTouchEvent(up);}finally {down.recycle();up.recycle();}
                        if(!picked.get(i).equals(first.plusDays(i)))throw new IllegalStateException("Day navigation changed");
                    }
                    if(dp==320)save(bitmap,(legacy?"legacy-":"")+name);
                } finally {bitmap.recycle();}
            }
        }
        if((activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)==0)throw new IllegalStateException("Secure flag missing");
    }
    private float field(Object view,String name)throws Exception {Field f=WeekTimelineView.class.getDeclaredField(name);f.setAccessible(true);return ((Number)f.get(view)).floatValue();}
    private void save(Bitmap bitmap,String name)throws Exception {
        String variant=args.getString("variant","phone");if(!variant.matches("[a-z0-9-]{1,40}"))throw new IllegalArgumentException("Variant");
        File dir=new File(test.getTargetContext().getExternalFilesDir(null),"ux-week-axis");if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalStateException("Evidence folder");
        try(FileOutputStream out=new FileOutputStream(new File(dir,variant+"-"+name+".png"))) {if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IllegalStateException("PNG failed");}
    }
    private record Text(String text,float x,float width,float baseline,float descent,boolean centered) {}
    private static final class RecordingCanvas extends Canvas {
        final List<String> texts=new ArrayList<>();final List<Text> entries=new ArrayList<>();final List<RectF> cards=new ArrayList<>();final List<RectF> circles=new ArrayList<>();
        RecordingCanvas(Bitmap bitmap){super(bitmap);}
        @Override public void drawText(String text,float x,float y,Paint paint){texts.add(text);entries.add(new Text(text,x,paint.measureText(text),y,paint.getFontMetrics().descent,paint.getTextAlign()==Paint.Align.CENTER));super.drawText(text,x,y,paint);}
        @Override public void drawCircle(float x,float y,float r,Paint paint){circles.add(new RectF(x-r,y-r,x+r,y+r));super.drawCircle(x,y,r,paint);}
        @Override public void drawRoundRect(RectF rect,float rx,float ry,Paint paint){if(paint.getColor()==INK)cards.add(new RectF(rect));super.drawRoundRect(rect,rx,ry,paint);}
    }
}

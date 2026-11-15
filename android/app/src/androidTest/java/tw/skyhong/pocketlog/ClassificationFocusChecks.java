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
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Synthetic-only editor actions on actual secure Views; no API or persisted workspace. */
final class ClassificationFocusChecks {
    private final Instrumentation test;
    private final Bundle args;
    private Activity activity;
    private JSONObject draft;
    ClassificationFocusChecks(Instrumentation test, Bundle args) { this.test=test;this.args=args; }
    void run() throws Exception {
        if(!java.util.Set.of("ranchu","goldfish").contains(android.os.Build.HARDWARE)) throw new IllegalStateException("Disposable emulator required");
        if(new CredentialStore(test.getTargetContext()).load()!=null) throw new IllegalStateException("Connected installation refused");
        RecordingSettings settings=new RecordingSettings(test.getTargetContext());
        if(settings.getEnabled()||settings.getContextEnabled()||settings.getImagesEnabled()||settings.getExternalAiConsent()) throw new IllegalStateException("Consent must start off");
        test.setInTouchMode(true);
        try {
            activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));settle();
            JSONArray categories=new JSONArray();
            for(int i=0;i<5;i++)categories.put(new JSONObject().put("id","custom_fixture_"+i).put("name","測試分類 "+i).put("color","#9bbce1").put("role","neutral"));
            JSONArray rules=new JSONArray();
            for(int i=0;i<8;i++)rules.put(new JSONObject().put("id","fixture-rule-"+i).put("category","custom_fixture_0").put("app","org.example.fixture"+i).put("title_contains","測試關鍵字 "+i).put("enabled",true));
            draft=new JSONObject().put("revision",1).put("categories",categories).put("rules",rules);
            render();settle();
            edit("org.example.fixture6","org.example.edited");
            View row=rule("org.example.fixture7");reveal(action(row,"上移"));capture("before-move");click(action(row,"上移"));settle();
            if(!"fixture-rule-7".equals(rules.getJSONObject(6).getString("id"))||!"org.example.edited".equals(rules.getJSONObject(7).getString("app")))throw new IllegalStateException("Move lost order or unsaved draft");
            if("yes".equals(args.getString("expectLegacy"))){
                final int[] y={0};test.runOnMainSync(()->y[0]=scroll().getScrollY());
                if(y[0]>20)throw new IllegalStateException("Expected legacy scroll reset absent");capture("legacy-reset");return;
            }
            assertVisibleFocused("上移");capture("after-move");
            row=rule("org.example.fixture7");reveal(action(row,"刪除規則"));click(action(row,"刪除規則"));settle();
            if(rules.length()!=7||!"fixture-rule-6".equals(rules.getJSONObject(6).getString("id")))throw new IllegalStateException("Delete changed surviving order");
            assertVisibleFocused("刪除規則");capture("after-delete");
            reveal(find("新增規則"));click(find("新增規則"));settle();
            if(rules.length()!=8)throw new IllegalStateException("New rule absent");
            assertVisibleFocused(null);capture("after-add-rule");
            if("yes".equals(args.getString("ime"))) keyboardCheck("new-rule-keyboard");
            final String[] focused={null};test.runOnMainSync(()->{View focus=activity.getCurrentFocus();if(!(focus instanceof EditText e)||!e.getText().toString().isEmpty())throw new IllegalStateException("New rule app field not focused");e.setText("org.example.newdraft");focused[0]=e.getText().toString();});
            reveal(find("新增分類"));click(find("新增分類"));settle();
            if(categories.length()!=6||!focused[0].equals(rules.getJSONObject(7).getString("app")))throw new IllegalStateException("Category add lost rule draft");
            assertVisibleFocused(null);capture("after-add-category");
            if("yes".equals(args.getString("ime"))) keyboardCheck("new-category-keyboard");
            test.runOnMainSync(()->{if(!(activity.getCurrentFocus() instanceof EditText e)||!"新分類".equals(e.getText().toString()))throw new IllegalStateException("New category name not focused");e.setText("保留分類草稿");});
            row=category("custom_fixture_4");reveal(action(row,"移除此分類"));click(action(row,"移除此分類"));settle();
            if(categories.length()!=5||!"保留分類草稿".equals(categories.getJSONObject(4).getString("name")))throw new IllegalStateException("Category remove lost draft");
            assertVisibleFocused("移除此分類");capture("after-delete-category");
            // Edge positions: moved-to-first, final rule, and final custom category.
            categories=new JSONArray().put(new JSONObject().put("id","focus").put("name","專注測試").put("color","#9bbce1").put("role","focus"))
                .put(new JSONObject().put("id","custom_edge").put("name","邊界分類").put("color","#9bbce1").put("role","neutral"));
            rules=new JSONArray();
            for(int i=0;i<2;i++)rules.put(new JSONObject().put("id","edge-rule-"+i).put("category","custom_edge").put("app","org.example.edge"+i).put("title_contains",JSONObject.NULL).put("enabled",true));
            draft=new JSONObject().put("revision",1).put("categories",categories).put("rules",rules);render();settle();
            row=rule("org.example.edge1");reveal(action(row,"上移"));click(action(row,"上移"));settle();assertVisibleFocused("刪除規則");
            if(!"edge-rule-1".equals(rules.getJSONObject(0).getString("id")))throw new IllegalStateException("Move to first changed order");
            click(action(rule("org.example.edge1"),"刪除規則"));settle();assertVisibleFocused("刪除規則");
            click(action(rule("org.example.edge0"),"刪除規則"));settle();assertVisibleFocused("新增規則");
            if(rules.length()!=0)throw new IllegalStateException("Final rule remains");capture("empty-rules");
            row=category("custom_edge");reveal(action(row,"移除此分類"));click(action(row,"移除此分類"));settle();assertVisibleFocused(null);
            test.runOnMainSync(()->{if(!(activity.getCurrentFocus() instanceof EditText e)||!"專注測試".equals(e.getText().toString()))throw new IllegalStateException("Final custom category fallback missing");});
            capture("builtin-category-fallback");
            if(settings.getEnabled()||settings.getContextEnabled()||settings.getImagesEnabled()||settings.getExternalAiConsent())throw new IllegalStateException("Consent changed");
        } catch(Exception e) { throw new IllegalStateException("Classification focus: "+e.getMessage(),e); } finally {if(activity!=null)test.runOnMainSync(()->{activity.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(),0);activity.finish();});}
    }
    private void render(){test.runOnMainSync(()->{try{Method m=MainActivity.class.getDeclaredMethod("categoryRulesEditor",JSONObject.class);m.setAccessible(true);m.invoke(activity,draft);}catch(Exception e){throw new RuntimeException(e);}});}
    private void settle()throws Exception{test.waitForIdleSync();Thread.sleep(350);test.waitForIdleSync();}
    private List<View> views(View root){List<View> all=new ArrayList<>();all.add(root);if(root instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)all.addAll(views(g.getChildAt(i)));return all;}
    private View find(String text){for(View v:views(activity.getWindow().getDecorView()))if(v instanceof TextView t&&text.equals(t.getText().toString()))return v;throw new IllegalStateException("Text absent: "+text);}
    private View rule(String app){return (View)find(app).getParent();}
    private View category(String id)throws Exception{JSONArray rows=draft.getJSONArray("categories");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(id.equals(row.getString("id")))return (View)find(row.getString("name")).getParent();}throw new IllegalStateException("Fixture category absent");}
    private View action(View row,String text){for(View v:views(row))if(v instanceof TextView t&&text.equals(t.getText().toString())&&v.isClickable())return v;throw new IllegalStateException("Action absent: "+text);}
    private ScrollView scroll(){for(View v:views(activity.getWindow().getDecorView()))if(v instanceof ScrollView s)return s;throw new IllegalStateException("Scroll absent");}
    private void edit(String before,String after){test.runOnMainSync(()->((EditText)find(before)).setText(after));}
    private void reveal(View view)throws Exception{test.runOnMainSync(()->{Rect r=new Rect(0,0,view.getWidth(),view.getHeight());view.requestRectangleOnScreen(r,true);});settle();}
    private void click(View view){test.runOnMainSync(()->{if(!view.performClick())throw new IllegalStateException("Click failed");});}
    private void assertVisibleFocused(String label){test.runOnMainSync(()->{View v=activity.getCurrentFocus();Rect r=new Rect();if(v==null||!v.getGlobalVisibleRect(r)||r.height()<v.getHeight())throw new IllegalStateException("Continuation focus absent or clipped");if(label!=null&&(!(v instanceof TextView t)||!label.equals(t.getText().toString())))throw new IllegalStateException("Wrong continuation focus");});}
    private void keyboardCheck(String name)throws Exception{
        test.runOnMainSync(()->{View field=activity.getCurrentFocus();if(!(field instanceof EditText))throw new IllegalStateException("Input focus absent");activity.getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(field,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});
        boolean[] visible={false};
        for(int i=0;i<20;i++){settle();test.runOnMainSync(()->{var insets=activity.getWindow().getDecorView().getRootWindowInsets();visible[0]=insets!=null&&insets.isVisible(android.view.WindowInsets.Type.ime());});if(visible[0])break;}
        if(!visible[0])throw new IllegalStateException("Keyboard did not open");
        settle();assertVisibleFocused(null);
        test.runOnMainSync(()->{View field=activity.getCurrentFocus();Rect frame=new Rect(),bounds=new Rect();activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);field.getGlobalVisibleRect(bounds);if(bounds.bottom>frame.bottom)throw new IllegalStateException("Keyboard obscures focused input");});
        capture(name);
        test.runOnMainSync(()->activity.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(),0));settle();
    }
    private void capture(String name)throws Exception{
        String variant=args.getString("variant","phone");if(!variant.matches("[a-z0-9-]{1,40}"))throw new IllegalArgumentException("Variant");
        File folder=new File(test.getTargetContext().getExternalFilesDir(null),"ux-classification-focus");if(!folder.isDirectory()&&!folder.mkdirs())throw new IllegalStateException("Evidence folder");
        final Exception[] failure={null};test.runOnMainSync(()->{Bitmap bitmap=null;try{if((activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)==0)throw new IllegalStateException("Secure flag absent");View v=activity.getWindow().getDecorView();bitmap=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);v.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(folder,variant+"-"+name+".png"))){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IllegalStateException("Capture failed");}}catch(Exception e){failure[0]=e;}finally{if(bitmap!=null)bitmap.recycle();}});if(failure[0]!=null)throw failure[0];
    }
}

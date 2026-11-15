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
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Actual dialog Views on an unconnected disposable emulator; never saves capture settings. */
final class RuleAppPickerChecks {
    private final Instrumentation test;
    private final Bundle args;
    private Activity activity;
    private EditText origin, categoryField, keywordField, otherRuleField;
    private String stage="start";
    RuleAppPickerChecks(Instrumentation test, Bundle args) { this.test=test;this.args=args; }
    void run() throws Exception {
        if(!java.util.Set.of("ranchu","goldfish").contains(android.os.Build.HARDWARE)) throw new IllegalStateException("Disposable emulator required");
        if(new CredentialStore(test.getTargetContext()).load()!=null) throw new IllegalStateException("Connected installation refused");
        RecordingSettings settings=new RecordingSettings(test.getTargetContext());
        var beforeAllowed=settings.getAllowed(); var beforeExcluded=settings.getExclusions();
        if(settings.getEnabled()||settings.getContextEnabled()||settings.getImagesEnabled()||settings.getExternalAiConsent()) throw new IllegalStateException("Consent must start off");
        try {
            activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            settle();
            test.runOnMainSync(()->{try {
                org.json.JSONObject category=new org.json.JSONObject().put("id","custom_fixture").put("name","分類草稿").put("color","#9bbce1").put("role","neutral");
                org.json.JSONObject rule=new org.json.JSONObject().put("id","fixture-rule").put("category","custom_fixture").put("app","org.example.manual").put("title_contains","保留關鍵字").put("enabled",true);
                org.json.JSONObject draft=new org.json.JSONObject().put("revision",1).put("categories",new org.json.JSONArray().put(category)).put("rules",new org.json.JSONArray().put(rule).put(new org.json.JSONObject(rule.toString()).put("id","other-rule").put("app","org.example.other")));
                Method editor=MainActivity.class.getDeclaredMethod("categoryRulesEditor",org.json.JSONObject.class);editor.setAccessible(true);editor.invoke(activity,draft);
                List<EditText> fields=new ArrayList<>();for(View v:views(activity.getWindow().getDecorView()))if(v instanceof EditText)fields.add((EditText)v);
                categoryField=fields.get(0);categoryField.setText("未儲存分類草稿");origin=fields.get(2);keywordField=fields.get(3);keywordField.setText("未儲存規則草稿");otherRuleField=fields.get(4);otherRuleField.setText("org.example.otherdraft");
                origin.setText("unknown manual package!");
                choose();
            }catch(Exception e){throw new RuntimeException(e);}});
            awaitDialog();capture("initial");
            if("yes".equals(args.getString("ime"))) keyboardCheck();
            stage="no match and cancel";query("zzzz.synthetic.no.match");settle();capture("no-match");
            if(!dialogText().contains("找不到符合的 app"))throw new IllegalStateException("No-match explanation absent");
            click("取消");settle();
            test.runOnMainSync(()->{if(!"unknown manual package!".equals(origin.getText().toString()))throw new IllegalStateException("Cancel changed manual draft");});
            test.runOnMainSync(()->{try{choose();}catch(Exception e){throw new RuntimeException(e);}});awaitDialog();
            query("  SETTINGS  ");settle();capture("search-name");
            query("com.android.settings");settle();capture("search-package");selectSettings();settle();
            test.runOnMainSync(()->{if(!"com.android.settings".equals(origin.getText().toString()))throw new IllegalStateException("Canonical single app not filled");if(!"未儲存分類草稿".equals(categoryField.getText().toString())||!"未儲存規則草稿".equals(keywordField.getText().toString()))throw new IllegalStateException("Other drafts changed");if(!"org.example.otherdraft".equals(otherRuleField.getText().toString()))throw new IllegalStateException("Another rule changed");if(activity.getCurrentFocus()!=origin)throw new IllegalStateException("Origin focus not restored");});
            capture("filled");
            stage="empty installed list";test.runOnMainSync(()->RuleAppPicker.INSTANCE.show(activity,java.util.List.of(),chosen->kotlin.Unit.INSTANCE));awaitDialog();
            if(!dialogText().contains("沒有可顯示的 app"))throw new IllegalStateException("Empty list guidance absent");capture("no-apps");click("取消");settle();
            stage="stale page callback";test.runOnMainSync(()->{try{choose();invoke("appListsPage");}catch(Exception e){throw new RuntimeException(e);}});settle();
            test.runOnMainSync(()->{for(View root:WindowInspector.getGlobalWindowViews())if(root!=activity.getWindow().getDecorView()&&root.isShown())throw new IllegalStateException("Stale page opened picker");});
            if(!beforeAllowed.equals(settings.getAllowed())||!beforeExcluded.equals(settings.getExclusions())||settings.getEnabled()||settings.getContextEnabled()||settings.getImagesEnabled()||settings.getExternalAiConsent())throw new IllegalStateException("Picker persisted settings/consent");
        }catch(Throwable e){throw new IllegalStateException("App picker "+stage+": "+e.getClass().getSimpleName(),e);}
        finally {if(activity!=null)test.runOnMainSync(()->activity.finish());}
    }
    private void invoke(String name)throws Exception {Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(activity);}
    private void choose()throws Exception {Method m=MainActivity.class.getDeclaredMethod("chooseRuleApp",EditText.class);m.setAccessible(true);m.invoke(activity,origin);}
    private void settle()throws Exception {test.waitForIdleSync();Thread.sleep(300);test.waitForIdleSync();}
    private List<View> views(View root){List<View> all=new ArrayList<>();all.add(root);if(root instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)all.addAll(views(g.getChildAt(i)));return all;}
    private View dialogRoot(){for(View root:WindowInspector.getGlobalWindowViews())if(root!=activity.getWindow().getDecorView()&&root.isShown())return root;throw new IllegalStateException("Picker dialog absent");}
    private void awaitDialog()throws Exception {long end=System.currentTimeMillis()+7000;while(System.currentTimeMillis()<end){try{final boolean[] found={false};test.runOnMainSync(()->{try{dialogRoot();found[0]=true;}catch(IllegalStateException ignored){}});if(found[0]){settle();return;}}catch(Exception ignored){}Thread.sleep(100);}throw new IllegalStateException("Picker timeout");}
    private void keyboardCheck()throws Exception {
        stage="keyboard and visible actions";
        test.runOnMainSync(()->{for(View v:views(dialogRoot()))if(v instanceof EditText e){e.requestFocus();e.post(()->activity.getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(e,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT));return;}});
        long end=System.currentTimeMillis()+6000;boolean[] visible={false};
        while(System.currentTimeMillis()<end){test.runOnMainSync(()->visible[0]=dialogRoot().getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime()));if(visible[0])break;Thread.sleep(100);}
        if(!visible[0])throw new IllegalStateException("Keyboard did not open");settle();
        test.runOnMainSync(()->{android.graphics.Rect frame=new android.graphics.Rect();dialogRoot().getWindowVisibleDisplayFrame(frame);int actions=0;for(View v:views(dialogRoot()))if(v instanceof TextView t&&java.util.Set.of("取消").contains(t.getText().toString())){android.graphics.Rect bounds=new android.graphics.Rect();if(!v.getGlobalVisibleRect(bounds)||bounds.height()<v.getHeight()||bounds.bottom>frame.bottom)throw new IllegalStateException("Action hidden by keyboard");actions++;}if(actions!=1)throw new IllegalStateException("Actions absent");boolean resultVisible=false;for(View v:views(dialogRoot()))if(v instanceof android.widget.CheckedTextView){android.graphics.Rect bounds=new android.graphics.Rect();if(v.getGlobalVisibleRect(bounds)&&bounds.height()>=Math.min(v.getHeight(),(int)(48*activity.getResources().getDisplayMetrics().density)))resultVisible=true;}if(!resultVisible)throw new IllegalStateException("Keyboard left no usable result row");});
        capture("keyboard");test.runOnMainSync(()->activity.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(dialogRoot().getWindowToken(),0));settle();
    }
    private void query(String value)throws Exception {test.runOnMainSync(()->{for(View v:views(dialogRoot()))if(v instanceof EditText e){e.setText(value);return;}throw new IllegalStateException("Search absent");});}
    private String dialogText(){StringBuilder all=new StringBuilder();test.runOnMainSync(()->{for(View v:views(dialogRoot()))if(v instanceof TextView t)all.append(t.getText()).append('\n');});return all.toString();}
    private void selectSettings()throws Exception {test.runOnMainSync(()->{for(View v:views(dialogRoot()))if(v instanceof android.widget.CheckedTextView t&&t.getText().toString().contains("com.android.settings")){View parent=v;while(parent.getParent() instanceof View p){parent=p;if(parent instanceof android.widget.ListView list){int index=list.getPositionForView(v);list.performItemClick(v,index,list.getAdapter().getItemId(index));return;}}}throw new IllegalStateException("Settings result absent");});}
    private void click(String label)throws Exception {test.runOnMainSync(()->{for(View v:views(dialogRoot()))if(v instanceof TextView t&&label.equals(t.getText().toString())&&v.isClickable()){v.performClick();return;}throw new IllegalStateException("Action absent");});}
    private void capture(String name)throws Exception {
        String variant=args.getString("variant","phone");if(!variant.matches("[a-z0-9-]{1,40}"))throw new IllegalArgumentException("Variant");
        File folder=new File(test.getTargetContext().getExternalFilesDir(null),"ux-rule-app-picker");if(!folder.isDirectory()&&!folder.mkdirs())throw new IllegalStateException("Evidence folder");
        final Exception[] failure={null};test.runOnMainSync(()->{Bitmap bitmap=null;try{View view=name.equals("filled")?activity.getWindow().getDecorView():dialogRoot();if(!(view.getLayoutParams() instanceof WindowManager.LayoutParams p)||(p.flags&WindowManager.LayoutParams.FLAG_SECURE)==0)throw new IllegalStateException("Dialog secure flag absent");bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(folder,variant+"-"+name+".png"))){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IllegalStateException("Capture failed");}}catch(Exception e){failure[0]=e;}finally{if(bitmap!=null)bitmap.recycle();}});if(failure[0]!=null)throw failure[0];
    }
}

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
final class AppPickerChecks {
    private final Instrumentation test;
    private final Bundle args;
    private Activity activity;
    private EditText origin;
    private String stage="start";
    AppPickerChecks(Instrumentation test, Bundle args) { this.test=test;this.args=args; }
    void run() throws Exception {
        if(!java.util.Set.of("ranchu","goldfish").contains(android.os.Build.HARDWARE)) throw new IllegalStateException("Disposable emulator required");
        if(new CredentialStore(test.getTargetContext()).load()!=null) throw new IllegalStateException("Connected installation refused");
        RecordingSettings settings=new RecordingSettings(test.getTargetContext());
        var beforeAllowed=settings.getAllowed(); var beforeExcluded=settings.getExclusions();
        if(settings.getEnabled()||settings.getContextEnabled()||settings.getImagesEnabled()) throw new IllegalStateException("Consent must start off");
        try {
            activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            settle();
            test.runOnMainSync(()->{try {
                invoke("appListsPage");
                List<EditText> fields=new ArrayList<>();for(View v:views(activity.getWindow().getDecorView()))if(v instanceof EditText)fields.add((EditText)v);
                origin=fields.get(1);origin.setText("org.example.manual");
                choose();
            }catch(Exception e){throw new RuntimeException(e);}});
            awaitDialog();capture("initial");
            if("yes".equals(args.getString("ime"))) keyboardCheck();
            stage="search and select";query("com.android.settings");settle();
            selectSettings();query("zzzz.synthetic.no.match");settle();capture("no-match");
            if(!dialogText().contains("找不到符合的 app"))throw new IllegalStateException("No-match explanation absent");
            query("  SETTINGS  ");settle();assertSettingsSelected();capture("selected");
            stage="fill selection";click("填入清單");settle();
            String[] filled={null};test.runOnMainSync(()->filled[0]=origin.getText().toString());
            if(!filled[0].contains("org.example.manual")||!filled[0].contains("com.android.settings"))throw new IllegalStateException("Selection or unknown manual package lost");
            stage="cancel preserves field";test.runOnMainSync(()->{try{choose();}catch(Exception e){throw new RuntimeException(e);}});awaitDialog();
            query("com.android.settings");settle();selectSettings();click("取消");settle();
            test.runOnMainSync(()->{if(!filled[0].equals(origin.getText().toString()))throw new IllegalStateException("Cancel changed field");});
            stage="empty installed list guidance";test.runOnMainSync(()->AppPicker.INSTANCE.show(activity,java.util.List.of(),java.util.Set.of("org.example.manual"),chosen->kotlin.Unit.INSTANCE));awaitDialog();
            if(!dialogText().contains("沒有可顯示的 app"))throw new IllegalStateException("Empty installed list guidance absent");capture("no-apps");click("取消");settle();
            stage="invalid manual input";test.runOnMainSync(()->{try{origin.setText("invalid package!");choose();if(origin.getError()==null)throw new IllegalStateException("Field error absent");}catch(Exception e){throw new RuntimeException(e);}});settle();
            if(!beforeAllowed.equals(settings.getAllowed())||!beforeExcluded.equals(settings.getExclusions())||settings.getEnabled()||settings.getContextEnabled()||settings.getImagesEnabled())throw new IllegalStateException("Picker persisted settings/consent");
        }catch(Throwable e){throw new IllegalStateException("App picker "+stage+": "+e.getClass().getSimpleName(),e);}
        finally {if(activity!=null)test.runOnMainSync(()->activity.finish());}
    }
    private void invoke(String name)throws Exception {Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(activity);}
    private void choose()throws Exception {Method m=MainActivity.class.getDeclaredMethod("chooseApps",EditText.class,boolean.class);m.setAccessible(true);m.invoke(activity,origin,false);}
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
        test.runOnMainSync(()->{android.graphics.Rect frame=new android.graphics.Rect();dialogRoot().getWindowVisibleDisplayFrame(frame);int actions=0;for(View v:views(dialogRoot()))if(v instanceof TextView t&&java.util.Set.of("取消","填入清單").contains(t.getText().toString())){android.graphics.Rect bounds=new android.graphics.Rect();if(!v.getGlobalVisibleRect(bounds)||bounds.height()<v.getHeight()||bounds.bottom>frame.bottom)throw new IllegalStateException("Action hidden by keyboard");actions++;}if(actions!=2)throw new IllegalStateException("Actions absent");});
        capture("keyboard");test.runOnMainSync(()->activity.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(dialogRoot().getWindowToken(),0));settle();
    }
    private void query(String value)throws Exception {test.runOnMainSync(()->{for(View v:views(dialogRoot()))if(v instanceof EditText e){e.setText(value);return;}throw new IllegalStateException("Search absent");});}
    private String dialogText(){StringBuilder all=new StringBuilder();test.runOnMainSync(()->{for(View v:views(dialogRoot()))if(v instanceof TextView t)all.append(t.getText()).append('\n');});return all.toString();}
    private void selectSettings()throws Exception {test.runOnMainSync(()->{for(View v:views(dialogRoot()))if(v instanceof android.widget.CheckedTextView t&&t.getText().toString().contains("com.android.settings")){View parent=v;while(parent.getParent() instanceof View p){parent=p;if(parent instanceof android.widget.ListView list){int index=list.getPositionForView(v);list.performItemClick(v,index,list.getAdapter().getItemId(index));return;}}}throw new IllegalStateException("Settings result absent");});}
    private void assertSettingsSelected(){test.runOnMainSync(()->{for(View v:views(dialogRoot()))if(v instanceof android.widget.CheckedTextView t&&t.getText().toString().contains("com.android.settings")&&t.isChecked())return;throw new IllegalStateException("Selection lost across queries");});}
    private void click(String label)throws Exception {test.runOnMainSync(()->{for(View v:views(dialogRoot()))if(v instanceof TextView t&&label.equals(t.getText().toString())&&v.isClickable()){v.performClick();return;}throw new IllegalStateException("Action absent");});}
    private void capture(String name)throws Exception {
        String variant=args.getString("variant","phone");if(!variant.matches("[a-z0-9-]{1,40}"))throw new IllegalArgumentException("Variant");
        File folder=new File(test.getTargetContext().getExternalFilesDir(null),"ux-app-picker");if(!folder.isDirectory()&&!folder.mkdirs())throw new IllegalStateException("Evidence folder");
        final Exception[] failure={null};test.runOnMainSync(()->{Bitmap bitmap=null;try{View view=dialogRoot();if(!(view.getLayoutParams() instanceof WindowManager.LayoutParams p)||(p.flags&WindowManager.LayoutParams.FLAG_SECURE)==0)throw new IllegalStateException("Dialog secure flag absent");bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(folder,variant+"-"+name+".png"))){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IllegalStateException("Capture failed");}}catch(Exception e){failure[0]=e;}finally{if(bitmap!=null)bitmap.recycle();}});if(failure[0]!=null)throw failure[0];
    }
}

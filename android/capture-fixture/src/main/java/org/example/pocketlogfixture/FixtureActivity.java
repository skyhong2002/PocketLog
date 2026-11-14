package org.example.pocketlogfixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.EditText;

/** Separate no-network app: only fixed synthetic content, never user input or account data. */
public final class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(40,100,40,40); content.setBackgroundColor(0xfff2f5ee);
        TextView title=new TextView(this); title.setText("PocketLog synthetic capture fixture"); title.setTextSize(24); content.addView(title);
        TextView body=new TextView(this); body.setText("SYNTHETIC_READ_ONLY_CONTEXT\nReading a public Kotlin example.\nNo account, network, private content or real journal."); body.setTextSize(20); content.addView(body);
        if("editable".equals(getIntent().getStringExtra("variant"))) {
            EditText input=new EditText(this); input.setText("SYNTHETIC_INPUT_EXCLUSION"); content.addView(input);
            input.clearFocus(); content.setFocusableInTouchMode(true); content.requestFocus();
        }
        setContentView(content);
    }
}

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
        if ("secure".equals(getIntent().getStringExtra("variant"))) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
        LinearLayout content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(40,100,40,40); content.setBackgroundColor(0xfff2f5ee);
        TextView title=new TextView(this); title.setText("PocketLog synthetic capture fixture"); title.setTextSize(24); content.addView(title);
        TextView body=new TextView(this); body.setText("SYNTHETIC_READ_ONLY_CONTEXT\nReading a public Kotlin example.\nNo account, network, private content or real journal."); body.setTextSize(20); content.addView(body);
        if("editable".equals(getIntent().getStringExtra("variant"))) {
            EditText input=new EditText(this); input.setText("SYNTHETIC_INPUT_EXCLUSION"); content.addView(input);
            input.clearFocus(); content.setFocusableInTouchMode(true); content.requestFocus();
        }
        setContentView(content);
        // UI Automation temporarily suppresses accessibility services. Give the
        // physical-device driver time to release its own helper before a fresh
        // fixed-content window is opened. Existing emulator variants stay unchanged.
        if (getIntent().getBooleanExtra("deferred", false)) {
            final String variant = getIntent().getStringExtra("variant");
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                startActivity(new android.content.Intent(this, FixtureActivity.class)
                    .putExtra("variant", variant));
                finish();
            }, 10_000);
        }
    }
}

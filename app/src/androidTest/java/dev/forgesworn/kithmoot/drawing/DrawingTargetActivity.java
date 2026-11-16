package dev.forgesworn.kithmoot.drawing;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;

/** A separate UID/native window, with no dependency on the target APK's Kotlin runtime. */
public final class DrawingTargetActivity extends Activity {
    private int clicks;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.WHITE);
        Button button = new Button(this);
        button.setText("Target clicks: 0");
        button.setOnClickListener(view -> button.setText("Target clicks: " + (++clicks)));
        frame.addView(button, new FrameLayout.LayoutParams(500, 150, Gravity.CENTER));
        setContentView(frame);
    }
    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("stop", false)) finish();
    }
}

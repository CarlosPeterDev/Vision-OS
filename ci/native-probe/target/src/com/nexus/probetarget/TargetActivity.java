package com.nexus.probetarget;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

/** A harmless full-colour screen: red at first, green after a tap. */
public class TargetActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        View view = new View(this);
        view.setBackgroundColor(Color.RED);
        view.setOnClickListener(v -> { v.setBackgroundColor(Color.GREEN); Log.i("NexusProbe", "target tapped"); });
        setContentView(view);
        Log.i("NexusProbe", "target created on display " + getWindowManager().getDefaultDisplay().getDisplayId());
    }
    /** Android 12 and newer keeps a root launcher screen open on Back, so this one closes itself. */
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { Log.i("NexusProbe", "target got back"); finish(); }

    @Override protected void onDestroy() { Log.i("NexusProbe", "target destroyed"); super.onDestroy(); }
}

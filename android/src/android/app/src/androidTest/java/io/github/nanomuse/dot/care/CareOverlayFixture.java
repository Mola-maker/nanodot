package io.github.nanomuse.dot.care;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** External test-APK process: framework-only Java, independent of the target Kotlin runtime.
 * Disposable host; no accounts, production sessions, preferences or Room rows. */
public final class CareOverlayFixture extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 100, 24, 24);
        layout.setBackgroundColor(Color.WHITE);
        final TextView result = new TextView(this);
        result.setText("CARE_TOUCH_PENDING");
        Button target = new Button(this);
        target.setText("CARE_TOUCH_TARGET");
        target.setContentDescription("CARE_TOUCH_TARGET");
        target.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                result.setText("CARE_TOUCH_RECEIVED");
            }
        });
        layout.addView(target);
        layout.addView(result);
        setContentView(layout);
    }
}

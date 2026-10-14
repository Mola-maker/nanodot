package io.github.nanomuse.dot.care;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.graphics.Color;
import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** External test-APK process: framework-only Java, independent of the target Kotlin runtime.
 * Disposable host; no accounts, production sessions, preferences or Room rows. */
public final class CareOverlayFixture extends Activity {
    public static final String EXTRA_SAFE_TARGET = "care_safe_target";
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 100, 24, 24);
        final boolean safeTarget = getIntent().getBooleanExtra(EXTRA_SAFE_TARGET, false);
        if (safeTarget) {
            final int margin = Math.round(24 * getResources().getDisplayMetrics().density);
            layout.setOnApplyWindowInsetsListener((view, insets) -> {
                int left = insets.getSystemWindowInsetLeft();
                int top = insets.getSystemWindowInsetTop();
                int right = insets.getSystemWindowInsetRight();
                int bottom = insets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= 30) {
                    Insets safe = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    left = safe.left; top = safe.top; right = safe.right; bottom = safe.bottom;
                }
                if (view.getPaddingLeft() != left + margin || view.getPaddingTop() != top + margin ||
                    view.getPaddingRight() != right + margin || view.getPaddingBottom() != bottom + margin) {
                    view.setPadding(left + margin, top + margin, right + margin, bottom + margin);
                }
                return insets;
            });
        }
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
        if (safeTarget) layout.requestApplyInsets();
    }
}

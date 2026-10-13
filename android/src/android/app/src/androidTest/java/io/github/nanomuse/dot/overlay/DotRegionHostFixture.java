package io.github.nanomuse.dot.overlay;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Public labels in the independent test APK; no dependency on the product Kotlin runtime. */
public final class DotRegionHostFixture extends Activity {
    private FrameLayout root;
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        label("REGION_INSIDE_LEFT", Gravity.LEFT | Gravity.CENTER_VERTICAL, 60, Color.WHITE);
        label("REGION_INSIDE_RIGHT", Gravity.RIGHT | Gravity.CENTER_VERTICAL, 60, Color.WHITE);
        label("REGION_OUTSIDE_RED", Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, Color.RED);
        setContentView(root);
    }

    private void label(String text, int gravity, int margin, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14f);
        view.setTextColor(Color.BLACK);
        view.setBackgroundColor(color);
        view.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(96), dp(48), gravity);
        params.leftMargin = dp(margin);
        params.rightMargin = dp(margin);
        root.addView(view, params);
    }
}

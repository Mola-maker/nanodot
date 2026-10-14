package io.github.nanomuse.apiproof;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Framework-only public host: focus changes come from normal native touch dispatch. */
public final class ForegroundApiHostFixture extends Activity {
    public static final String ONE = "FOREGROUND_FOCUS_ONE";
    public static final String TWO = "FOREGROUND_FOCUS_TWO";
    public static final String MARKER = "FOREGROUND_PUBLIC_HOST";
    private FrameLayout root;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xfff2f5fa);
        root.setFocusable(true); root.setFocusableInTouchMode(true);
        TextView marker = new TextView(this); marker.setText(MARKER); marker.setContentDescription(MARKER);
        marker.setTextColor(0xff213047); marker.setTextSize(16); marker.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams label = new FrameLayout.LayoutParams(-1, dp(48), Gravity.CENTER);
        root.addView(marker,label);
        root.addView(field(ONE), position(Gravity.BOTTOM,40));
        root.addView(field(TWO), position(Gravity.TOP,48));
        root.setOnApplyWindowInsetsListener((view,insets) -> {
            android.graphics.Insets edges=insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(edges.left,edges.top,edges.right,edges.bottom); return insets;
        });
        setContentView(root); root.requestFocus();
    }
    private EditText field(String name) {
        EditText field=new EditText(this);
        field.setId(name.equals(ONE) ? 0x00f01301 : 0x00f01302);
        field.setContentDescription(name); field.setHint(name); field.setTextSize(14);
        field.setSingleLine(true); field.setShowSoftInputOnFocus(false);
        field.setTextColor(0xff213047); field.setBackgroundColor(0xffd7e5fb);
        return field;
    }
    private FrameLayout.LayoutParams position(int gravity,int margin) {
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(220),dp(56),gravity|Gravity.CENTER_HORIZONTAL);
        if(gravity==Gravity.BOTTOM) p.bottomMargin=dp(margin); else p.topMargin=dp(margin);
        return p;
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
}

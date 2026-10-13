package io.github.nanomuse.dot.overlay;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Disposable external host. No production configuration, accounts, session or provider. */
public final class DotOrbMenuHostFixture extends Activity {
    private int touches;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.WHITE);
        layout.setPadding(24, 100, 24, 24);
        TextView result = new TextView(this);
        result.setText("ORB_HOST_TOUCHES_0");
        Button target = new Button(this);
        target.setText("ORB_HOST_TARGET");
        target.setOnClickListener(view -> result.setText("ORB_HOST_TOUCHES_" + (++touches)));
        layout.addView(target);
        layout.addView(result);
        EditText editor = new EditText(this);
        editor.setHint("ORB_HOST_IME");
        editor.setContentDescription("ORB_HOST_IME");
        layout.addView(editor);
        layout.setFocusableInTouchMode(true);
        layout.requestFocus();
        setContentView(layout);
    }
}

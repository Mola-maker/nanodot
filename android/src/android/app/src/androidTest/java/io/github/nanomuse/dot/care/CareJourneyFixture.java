package io.github.nanomuse.dot.care;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Public, disposable two-step host in the test APK; framework-only Java.
 * No accounts, network, persistent data, provider or Care controller access.
 * Page text is observable evidence, not an automatic Care verification override. */
public final class CareJourneyFixture extends Activity {
    private static final String STATE_PAGE = "care_journey_page";
    private int page;
    private LinearLayout content;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page = state == null ? 0 : Math.max(0, Math.min(2, state.getInt(STATE_PAGE, 0)));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.setFitsSystemWindows(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(24), dp(24), dp(24));
        content.setFocusableInTouchMode(true);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        showPage();
        content.requestFocus();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt(STATE_PAGE, page);
        super.onSaveInstanceState(state);
    }

    private void showPage() {
        content.removeAllViews();
        addText("公开两步流程 / Public two-step journey", 22);
        if (page == 0) {
            addText("CARE_JOURNEY_PAGE_ONE", 18);
            addText("第 1 页：点击下一页继续。 / Page 1: choose Next page.", 18);
            addButton("下一页 / Next page", 0);
        } else if (page == 1) {
            addText("CARE_JOURNEY_PAGE_TWO", 18);
            addText("第 2 页：点击完成结束。 / Page 2: choose Finish.", 18);
            addButton("完成 / Finish", 1);
        } else {
            addText("CARE_JOURNEY_COMPLETE", 18);
            addText("流程已完成，无需其他操作。 / Journey complete. No further action.", 18);
        }
    }

    private void addText(String text, int sizeSp) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(sizeSp);
        label.setTextColor(Color.BLACK);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(16);
        content.addView(label, params);
    }

    private void addButton(String label, final int expectedPage) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setContentDescription(label);
        button.setMinHeight(dp(56));
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (page != expectedPage) return;
                view.setEnabled(false);
                page++;
                showPage();
            }
        });
        content.addView(button, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

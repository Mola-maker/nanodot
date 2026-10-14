package io.github.nanomuse.dot.overlay;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;

/** Public raster evidence. Only the unrelated inspection control has accessibility semantics. */
public final class DotVisionHostFixture extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        View card = new RasterCard();
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(card, new FrameLayout.LayoutParams(-1, -1));
        Button inspect = new Button(this);
        inspect.setAllCaps(false);
        inspect.setText("Inspect card");
        inspect.setOnClickListener(view -> { /* The Care journey does not execute this step. */ });
        FrameLayout.LayoutParams control = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        control.leftMargin = dp(16);
        control.topMargin = dp(12);
        root.addView(inspect, control);
        setContentView(root);
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private final class RasterCard extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        RasterCard() { super(DotVisionHostFixture.this); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight(), r = w * .065f, y = h * .43f;
            canvas.drawColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(30, 95, 225));
            Path triangle = new Path();
            triangle.moveTo(w * .28f, y - r);
            triangle.lineTo(w * .28f + r, y + r);
            triangle.lineTo(w * .28f - r, y + r);
            triangle.close();
            canvas.drawPath(triangle, paint);
            paint.setColor(Color.rgb(245, 145, 25));
            canvas.drawCircle(w * .50f, y, r, paint);
            paint.setColor(Color.rgb(35, 165, 75));
            canvas.drawRect(w * .73f - r, y - r, w * .73f + r, y + r, paint);

            // A colored distractor outside all three intended regions.
            paint.setColor(Color.MAGENTA);
            Path diamond = new Path();
            diamond.moveTo(w * .84f, h * .13f - r);
            diamond.lineTo(w * .84f + r, h * .13f);
            diamond.lineTo(w * .84f, h * .13f + r);
            diamond.lineTo(w * .84f - r, h * .13f);
            diamond.close();
            canvas.drawPath(diamond, paint);

            float left = w * .20f, right = w * .80f, top = h * .72f, bottom = h * .88f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, w / 240f));
            paint.setColor(Color.DKGRAY);
            canvas.drawRect(left, top, right, bottom, paint);
            canvas.drawLine(w * .58f, top, w * .58f, bottom, paint);
            canvas.drawLine(left, (top + bottom) / 2, right, (top + bottom) / 2, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.BLACK);
            paint.setTextSize(w * .049f);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
            canvas.drawText("ALPHA", left + w * .035f, top + h * .051f, paint);
            canvas.drawText("17", w * .64f, top + h * .051f, paint);
            canvas.drawText("BETA", left + w * .035f, top + h * .131f, paint);
            canvas.drawText("42", w * .64f, top + h * .131f, paint);
        }
    }
}

package io.github.nanomuse.dot.care

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Test APK only; no provider, credentials, production care owner or automatic operator. */
class CareEvidenceHostFixture : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = EditText(this).apply { contentDescription = "CARE_TARGET"; hint = "Target query" }
        val wrong = EditText(this).apply { contentDescription = "CARE_WRONG"; hint = "Unrelated field" }
        val ad = TextView(this).apply { contentDescription = "CARE_AD"; text = "Original advertisement" }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            isFocusableInTouchMode = true
            addView(target); addView(wrong); addView(ad)
            addView(Button(context).apply { text = "CARE_WRONG_BUTTON"; setOnClickListener { wrong.requestFocus() } })
            addView(Button(context).apply { text = "CARE_AD_REFRESH"; setOnClickListener { ad.text = "Delivery" } })
            addView(Button(context).apply { text = "CARE_RIGHT_BUTTON"; setOnClickListener { target.requestFocus() } })
            addView(Button(context).apply { text = "CARE_CLOSE"; setOnClickListener { finish() } })
        }
        setContentView(layout)
        layout.requestFocus()
    }
}

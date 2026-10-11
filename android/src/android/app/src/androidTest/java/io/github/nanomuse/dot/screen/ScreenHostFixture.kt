package io.github.nanomuse.dot.screen

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** External test-APK package: never shipped in the product. */
class ScreenHostFixture : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("secure", false)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        layout.addView(TextView(this).apply {
            text = intent.getStringExtra("marker") ?: "HOST_ORIGIN_T3_91E7"; textSize = 24f; setTextColor(Color.BLACK)
            setBackgroundColor(if (intent.getBooleanExtra("green",false)) Color.GREEN else Color.MAGENTA); minHeight = 180
        }, LinearLayout.LayoutParams(-1, 180))
        layout.addView(EditText(this).apply { hint = "HOST_IME_T3"; contentDescription = "HOST_IME_T3"; textSize = 20f })
        layout.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText("T3_PRIVATE_PASSWORD"); contentDescription = "private password"
        })
        setContentView(layout)
    }
}

package io.github.nanomuse.dot.screen

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.content.res.ColorStateList
import android.content.res.Configuration
import androidx.compose.ui.graphics.toArgb
import io.github.nanomuse.dot.DotColors
import com.openminis.app.R

/** Separate user-initiated legacy pixel permission entry. No accessibility auto-grant. */
class LegacyProjectionPermissionActivity : Activity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) { finish(); return }
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val background = if (dark) DotColors.DarkBackground else DotColors.LightBackground
        val textColor = if (dark) DotColors.DarkText else DotColors.LightText
        val primary = if (dark) DotColors.DarkPrimary else DotColors.LightPrimary
        val onPrimary = if (dark) DotColors.DarkOnPrimary else DotColors.LightOnPrimary
        val spacing = (24 * resources.displayMetrics.density).toInt()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(background.toArgb()); setPadding(spacing,spacing,spacing,spacing)
            addView(TextView(this@LegacyProjectionPermissionActivity).apply {
                text = getString(R.string.nm_dot_capture_explain); textSize = 16f; setTextColor(textColor.toArgb())
            })
            addView(Button(this@LegacyProjectionPermissionActivity).apply {
                text = getString(R.string.nm_dot_capture_allow); textSize = 16f; minHeight = (48 * resources.displayMetrics.density).toInt()
                backgroundTintList = ColorStateList.valueOf(primary.toArgb()); setTextColor(onPrimary.toArgb())
                setOnClickListener {
                    startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),7313)
                }
            })
        })
    }
    @Deprecated("Legacy projection result is supported only on API26–29")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode == 7313) {
            if (resultCode == RESULT_OK && data != null) {
                startForegroundService(Intent(this,LegacyProjectionService::class.java).putExtra("result",resultCode).putExtra("grant",data))
            }
            finish()
        }
    }
}

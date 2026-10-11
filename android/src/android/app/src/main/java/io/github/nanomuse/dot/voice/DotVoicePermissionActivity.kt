package io.github.nanomuse.dot.voice

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.openminis.app.R
import com.openminis.app.ui.theme.MinisTheme
import io.github.nanomuse.dot.ui.DotTokens
import kotlinx.coroutines.launch

/** Visible user permission surface, retained until microphone foreground promotion acknowledges admission. */
class DotVoicePermissionActivity : ComponentActivity() {
    private var entry: DotVoiceRuntime.Entry? = null
    private var resumed = false
    private var asked = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val current = entry ?: return@registerForActivityResult
        if (result.values.any { !it } || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) { current.controller.denied(current.request); finishAndRemoveTask() }
        else tryStart()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        entry = DotVoiceRuntime.find(intent?.getStringExtra(DotVoiceRuntime.EXTRA_REQUEST))
        val current = entry
        if (current == null || savedInstanceState != null) {
            current?.controller?.cancel(current.request); finishAndRemoveTask(); return
        }
        setContent {
            MinisTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(DotTokens.Space.S24), verticalArrangement = Arrangement.Center) {
                        Text(stringResource(R.string.nm_dot_voice_permission), fontSize = DotTokens.Type.Body)
                        TextButton(onClick = { current.controller.cancel(current.request); finishAndRemoveTask() },
                            modifier = Modifier.heightIn(min = DotTokens.TouchTarget)) {
                            Text(stringResource(R.string.nm_dot_voice_keyboard), fontSize = DotTokens.Type.Body)
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            current.controller.ui.collect {
                if (current.request.admitted || DotVoiceRuntime.find(current.request.id) == null) finishAndRemoveTask()
            }
        }
    }
    override fun onResume() { super.onResume(); resumed = true; tryStart() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val current = entry ?: return
        DotVoiceRuntime.gate.visible(current.request, hasFocus && resumed)
        if (!hasFocus && current.request.starting && !current.request.admitted) current.controller.cancel(current.request)
        if (hasFocus) tryStart()
    }
    private fun tryStart() {
        val current = entry ?: return
        val r = current.request
        val visible = resumed && hasWindowFocus() && !isFinishing && !getSystemService(KeyguardManager::class.java).isKeyguardLocked
        DotVoiceRuntime.gate.visible(r, visible)
        if (!visible || r.starting || r.admitted || DotVoiceRuntime.find(r.id) !== current) return
        val required = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            if (!asked) { asked = true; permission.launch(missing.toTypedArray()) }
            return
        }
        if (!DotVoiceRuntime.gate.begin(r, permitted = true)) return
        try {
            startForegroundService(Intent(this, DotMicrophoneService::class.java).putExtra(DotVoiceRuntime.EXTRA_REQUEST, r.id))
        } catch (_: Exception) { current.controller.failed(r); finishAndRemoveTask() }
    }
    override fun onPause() {
        resumed = false
        entry?.let {
            DotVoiceRuntime.gate.visible(it.request, false)
            if (it.request.starting && !it.request.admitted) it.controller.cancel(it.request)
        }
        super.onPause()
    }
    override fun onStop() {
        entry?.let { if (!it.request.admitted) it.controller.cancel(it.request) }
        super.onStop()
    }
    override fun onDestroy() {
        entry?.let { if (!it.request.admitted) it.controller.cancel(it.request) }
        super.onDestroy()
    }
}

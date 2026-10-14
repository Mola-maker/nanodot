package io.github.nanomuse.dot.voice

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import com.openminis.app.R

/** Private, one-shot FGS. All stop paths keep the manager ticket until real resource cleanup. */
class DotMicrophoneService : Service() {
    private var entry: DotVoiceRuntime.Entry? = null
    private val main = Handler(Looper.getMainLooper())
    private var observing = false
    private var destroyed = false
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { cancelOwned() }
    }
    private val permissions = AppOpsManager.OnOpChangedListener { op, packageName ->
        if (op == AppOpsManager.OPSTR_RECORD_AUDIO && packageName == this.packageName) {
            main.post { if (!permitted()) cancelOwned() }
        }
    }
    private val checkPermission = object : Runnable {
        override fun run() {
            if (!permitted() || locked() || !notificationVisible()) cancelOwned()
            if (entry != null) main.postDelayed(this, 500)
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(DotVoiceRuntime.EXTRA_REQUEST)
        if (intent?.action == STOP) {
            if (entry?.request?.id == id) cancelOwned()
            if (entry == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        // A duplicate/forged command never tears down the current service generation.
        if (entry != null) return START_NOT_STICKY
        val next = DotVoiceRuntime.find(id)
        if (next == null || locked() || DotVoiceRuntime.gate.admit(id, permitted()) !== next.request) {
            next?.controller?.failed(next.request); stopSelf(startId); return START_NOT_STICKY
        }
        entry = next
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.nm_dot_voice_notification), NotificationManager.IMPORTANCE_LOW))
            check(notificationVisible())
            val stop = PendingIntent.getService(this, 0,
                Intent(this, DotMicrophoneService::class.java).setAction(STOP)
                    .setData(Uri.parse("nanodot-voice://stop/${next.request.id}"))
                    .putExtra(DotVoiceRuntime.EXTRA_REQUEST, next.request.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(getString(R.string.nm_dot_voice_notification))
                .setContentText(getString(R.string.nm_dot_voice_stop_hint))
                .setOngoing(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(android.R.drawable.ic_media_pause, getString(R.string.nm_dot_voice_cancel), stop).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION, notification)
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, filter, RECEIVER_NOT_EXPORTED)
            else registerReceiver(screen, filter)
            observing = true
            getSystemService(AppOpsManager::class.java).startWatchingMode(AppOpsManager.OPSTR_RECORD_AUDIO, packageName, permissions)
            main.post(checkPermission)
            if (!next.controller.foreground(next.request) { finishOwned(next) }) finishOwned(next)
        } catch (_: Exception) {
            next.controller.failed(next.request)
            // If capture started, its controller owns asynchronous completion and calls finishOwned.
            if (!next.controller.hasCapture(next.request)) finishOwned(next)
        }
        return START_NOT_STICKY
    }
    @Suppress("DEPRECATION")
    private fun permitted(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val ops = getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(), packageName)
            else ops.checkOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED || (Build.VERSION.SDK_INT >= 29 && mode == AppOpsManager.MODE_FOREGROUND)
    }
    private fun notificationVisible(): Boolean {
        val notifications = getSystemService(NotificationManager::class.java)
        return notifications.areNotificationsEnabled() && notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked || !getSystemService(PowerManager::class.java).isInteractive
    private fun cancelOwned() { entry?.let { it.controller.cancel(it.request) } }
    private fun finishOwned(expected: DotVoiceRuntime.Entry) {
        if (entry !== expected) return
        entry = null
        main.removeCallbacks(checkPermission)
        var failure: Exception? = null
        try { clearObservers() } catch (e: Exception) { failure = e }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) { failure = e }
        try { if (!destroyed) stopSelf() } catch (e: Exception) { failure = e }
        failure?.let { throw it }
    }
    private fun clearObservers() {
        if (!observing) return
        observing = false
        try { unregisterReceiver(screen) }
        finally { getSystemService(AppOpsManager::class.java).stopWatchingMode(permissions) }
    }
    override fun onDestroy() {
        destroyed = true
        cancelOwned()
        main.removeCallbacksAndMessages(null)
        try { clearObservers() }
        catch (failure: Exception) { android.util.Log.e("DotMicrophone", "Observer cleanup failed", failure) }
        // Completion lives in the controller/manager scope; destruction cannot release its lease early.
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "dot_microphone"
        private const val NOTIFICATION = 7251
        private const val STOP = "io.github.nanomuse.dot.voice.STOP"
    }
}

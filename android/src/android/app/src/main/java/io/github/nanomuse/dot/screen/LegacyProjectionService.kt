package io.github.nanomuse.dot.screen

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.openminis.app.R

/** Visible foreground lifetime for the explicitly granted legacy projection; never auto-restarts. */
class LegacyProjectionService : Service() {
    companion object {
        @Volatile var capture: LegacyProjectionCapture? = null
            private set
        private const val STOP = "io.github.nanomuse.dot.screen.STOP"
        fun stop(context: Context) { context.stopService(Intent(context,LegacyProjectionService::class.java)) }
    }
    private var projection: MediaProjection? = null
    private val stopped = object : MediaProjection.Callback() {
        override fun onStop() { capture?.close(); capture = null; stopSelf() }
    }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { stopSelf() }
    }
    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenOff,IntentFilter(Intent.ACTION_SCREEN_OFF))
    }
    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || Build.VERSION.SDK_INT >= 30 || intent?.getIntExtra("result",0) != Activity.RESULT_OK) {
            stopSelf(); return START_NOT_STICKY
        }
        val grant = intent.getParcelableExtra<Intent>("grant") ?: run { stopSelf(); return START_NOT_STICKY }
        projection?.unregisterCallback(stopped)
        capture?.close(); capture = null; projection = null
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("nm_legacy_capture",getString(R.string.nm_dot_capture_title),NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this,0,Intent(this,LegacyProjectionService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this,"nm_legacy_capture")
            .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle(getString(R.string.nm_dot_capture_title))
            .setContentText(getString(R.string.nm_dot_capture_active)).setOngoing(true)
            .addAction(Notification.Action.Builder(null,getString(R.string.nm_dot_capture_stop),stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7313,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(7313,notification)
        try {
            val current = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK,grant)
                ?: throw SecurityException("projection refused")
            projection = current
            current.registerCallback(stopped,Handler(Looper.getMainLooper()))
            capture = LegacyProjectionCapture(current,resources.configuration.densityDpi)
        } catch (_: RuntimeException) { stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        projection?.unregisterCallback(stopped)
        capture?.close(); capture = null; projection = null
        unregisterReceiver(screenOff)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

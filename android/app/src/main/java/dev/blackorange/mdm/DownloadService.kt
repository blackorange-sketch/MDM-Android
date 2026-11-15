package dev.blackorange.mdm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

/** Keeps the process (and so the downloads) alive while something is running. */
class DownloadService : Service() {
    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(
            ID,
            notification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (!running) {
            running = true
            thread(name = "mdm-service") {
                while (running) {
                    val items = Native.list().orEmpty()
                    val active = items.filter { it.active }
                    if (active.isEmpty()) break
                    val speed = active.sumOf { it.speed }
                    manager.notify(
                        ID,
                        notification("${active.size} active · ${formatSize(speed)}/s"),
                    )
                    Thread.sleep(2000)
                }
                running = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("MDM")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DownloadService::class.java))
        }
    }
}
